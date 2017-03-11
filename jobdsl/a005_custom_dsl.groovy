import org.yaml.snakeyaml.Yaml
import jenkins.model.*
import hudson.model.*
import hudson.Launcher

import com.pearson.deployment.*
import com.pearson.deployment.config.*
import com.pearson.deployment.config.bitesize.*
import com.pearson.deployment.syspkg.*

// TODO: abstract out groovyscripts into files
// TODO: probably good idea to divide this file into
// smaller .dsl pieces too.

def gradleStep(def context, step) {
  desc     = step.description ?: ''
  wrapper  = step.use_wrapper ?: true
  sw       = step.switches ?: ''

  context.gradle {
    tasks step.tasks
    description desc
    useWrapper wrapper
    switches sw
  }
}

def dslStep(def context, step) {
  keys = step.keySet() as String[]
  type = keys[0]
  switch(type) {
    case 'shell':
      context.shell(step[type])
      break
    case 'gradle':
      gradleStep(context, step)
      break
    default:
      break
  }
}

build = Thread.currentThread().executable
workspace = build.workspace.toString()
listener = new StreamBuildListener(System.out)
launcher = new Launcher.LocalLauncher(listener)

appBitesize = ApplicationBitesize.readConfigFromString(readFileFromWorkspace('application.bitesize'))
envBitesize = EnvironmentsBitesize.readConfigFromString(readFileFromWorkspace('environments.bitesize'))
buildBitesize = BuildBitesize.readConfigFromString(readFileFromWorkspace('build.bitesize'))

buildBitesize.components?.each { component ->
  job(component.name) {
    label('generic')

    wrappers {
        colorizeOutput()
    }
    if (component.repository) {
       scm {
        git {
          remote {
            credentials component.repository.credentials ?: 'seed-job-key'
            url component.repository.git
          }
          branch component.repository.branch
        }
      }
    }

    triggers {
      scm('* * * * *')
    }

    steps {
      environmentVariables {
        component.env.each { envVar ->
          env(envVar.name, envVar.value)
        }
      }
      component.dependencies?.each { dependency ->
        bitesize_build_dependency {
          pkg            dependency.pkg
          type           dependency.type
          version        dependency.version
          location       dependency.location
          repository_key dependency.repository_key
          repository     dependency.repository
        }
      }
      component.build.each { command ->
        dslStep(delegate, command)
      }
    }

    publishers {
      component.artifacts?.each { artifact ->
        archiveArtifacts artifact.location
      }

      publishToAptly 'bitesize'
    }
  }

}

appBitesize.applications.each { app ->
  pipelineJob("${app.name}-docker-image") {
    environmentVariables {
      env("appname", app.name)
    }

    def groovyscript = """
import com.pearson.deployment.*
import com.pearson.deployment.config.bitesize.*
import hudson.model.*
import jenkins.model.*

node('dind') {
  stage 'Copying Artifacts'
  sh "rm -rf deb/*; mkdir -p deb"

  step([\$class: 'CopyArtifact', filter: 'application.bitesize', projectName:'seed-job', selector: [\$class: 'WorkspaceSelector']])
  def str = readFile file: 'application.bitesize', charset : 'utf-8' 
  def appBitesize = ApplicationBitesize.readConfigFromString(str)

  def application = appBitesize.getApplication(env.appname)

  stage 'Building Docker Image'
  
  dockerfile = "Dockerfile-\${application.name}"
  wr = new Dockerfile(application)
  writeFile file: dockerfile, text: wr.contents()

  docker_image = "\${application.getDockerImage()}:\${application.getVersion()}"
  def timeTag = wr.currentTimeTag()
  timed_image  = "\${application.getDockerImage()}:\${application.getVersion()}-\${timeTag}"

  sh "docker build -t \${docker_image} -f \${dockerfile} ."

  sh "docker tag \${docker_image} \${timed_image}"
  sh "docker push \${docker_image}"
  sh "docker push \${timed_image}"
  
  stage 'Environment Pipeline'

  def versions = []
  def string_param = new StringParameterValue(
    "\${application.normalizedName()}_VERSION",
    "\${application.version}-\${timeTag}"
  )
  versions << string_param
  build job: 'environment-pipeline', propagate: false, wait: false, parameters: versions
  
}

def getUpstreamJobs(def deps) {
  def retval = []
  for (def i = 0; i < deps.size(); i++) {
    dep = deps[i]
    if (dep.origin?.build) {
      retval << dep.origin.build
    }
  }
  retval
}

@NonCPS
def shouldBuild(def dependencies) {
  // -------------------
  // Get upstream build's cause and check if we have it in
  //  dependencies
  def cause = currentBuild.getRawBuild().getCause(Cause.UpstreamCause.class)
  if (cause == null) {
    return true
  }
  def rootCauses = cause.getUpstreamCauses() ?: []

  def shouldBuild = false
  def upstreamJobs = getUpstreamJobs(dependencies)

  for (int i = 0; i < rootCauses.size(); i++) {
    def rootCause = rootCauses[i]
    for (int a = 0; a < upstreamJobs.size(); a++) {
      def u = upstreamJobs[a]
      def j = Jenkins.instance.getItem(u)
      if (rootCause.pointsTo(j)) {
        shouldBuild = true
        break
      }
    }
  }
  return shouldBuild
}

def log (step, msg) {

echo \"\"\"************************************************************
Step: \$step
\$msg
************************************************************\"\"\"
}
    """

    def scriptApproval = Jenkins.instance.getExtensionList('org.jenkinsci.plugins.scriptsecurity.scripts.ScriptApproval')[0]
    scriptApproval.approveScript(scriptApproval.hash(groovyscript, 'groovy'))

    definition {
      cps {
        script groovyscript
      }
    }

    triggers {
      app.dependencies?.each { dep -> 
        if (dep.origin?.build) {
          upstream(dep.origin.build, 'SUCCESS')
        }
      }
    }
  }
}

job("service-manage") {
  wrappers {
      colorizeOutput()
  }

  scm {
      git {
        remote {
          url(System.getenv().SEED_JOBS_REPO)
          branch('refs/heads/master')
          credentials('seed-job-key')
        }
      }
  }

  triggers {
      cron('* * * * *')
  }

  steps {
    serviceManager 'environments.bitesize'
  }
}
