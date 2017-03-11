// This file will have everything for environments.bitesize processing
import com.pearson.deployment.config.bitesize.*

import jenkins.model.*
import hudson.model.*
import hudson.Launcher

appBitesize = ApplicationBitesize.readConfigFromString(readFileFromWorkspace('application.bitesize'))
envBitesize = EnvironmentsBitesize.readConfigFromString(readFileFromWorkspace('environments.bitesize'))

build = Thread.currentThread().executable
workspace = build.workspace.toString()
listener = new StreamBuildListener(System.out)
launcher = new Launcher.LocalLauncher(listener)

workflowJob('environment-pipeline') {

  parameters {
    appBitesize.applications.each{ pp ->
      stringParam("${pp.normalizedName()}_VERSION", "", "")
    }
  }

  groovyscript = """
import com.pearson.deployment.*
import com.pearson.deployment.config.*
import com.pearson.deployment.config.bitesize.*
import hudson.model.*

node('generic') {

  stage "Copying bitesize files"
  step([\$class: 'CopyArtifact', filter: 'application.bitesize', projectName:'seed-job', selector: [\$class: 'WorkspaceSelector']])
  step([\$class: 'CopyArtifact', filter: 'environments.bitesize', projectName:'seed-job', selector: [\$class: 'WorkspaceSelector']])

  def str
  str = readFile file: 'application.bitesize', charset : 'utf-8' 
  appBitesize = ApplicationBitesize.readConfigFromString(str)
  str = readFile file: 'environments.bitesize', charset : 'utf-8' 
  envBitesize = EnvironmentsBitesize.readConfigFromString(str)

  environments = envBitesize.environments

  for (def i = 0; i < environments.size(); i++ ) {
    environment = environments[i]

    if (environment.isManualDeployment() ) {
      break
    }

    stage name:"\${environment.name} deploy", concurrency: 1
    build job: "\${environment.name}-deploy", parameters: collectDeployVersions(environment.services)

    stage "\${environment.name} test"
    tests = environment.tests
    if (tests != null && tests.size() > 0) {
      for (def a = 0; a < tests.size(); a++) {
        tst = tests[a]
        build job: "\${environment.name}-\${tst.name}", propagate: false, wait: false
      }
    }
  }
}

def collectDeployVersions(services) {
  def deployVersions = []
  for (def i = 0; i < services.size(); i++) {
    def svc = services[i]
    def appname = svc.application ?: svc.name
    def appname_normalized = appname.replaceAll("-","_")
    def svcname_normalized = svc.name.replaceAll("-", "_")
    if ( svc.type == null) {
      def application = appBitesize.getApplication(appname)
      def appv = getProperty("\${application.normalizedName()}_VERSION")

      if (application == null) {
        throw new Exception("Application \${appname} not found in application.bitesize")
      }

      version = new StringParameterValue("\${svcname_normalized}_VERSION", appv)
      deployVersions << version
    }
  }
  return deployVersions
}
  """

  def scriptApproval = Jenkins.instance.getExtensionList('org.jenkinsci.plugins.scriptsecurity.scripts.ScriptApproval')[0]
  scriptApproval.approveScript(scriptApproval.hash(groovyscript, 'groovy'))

  definition {
    cps {
      script groovyscript
    }
  }
}

envBitesize.environments?.each { env ->
  job("${env.name}-deploy") {
    customWorkspace(workspace)
    // label('generic')

    parameters {
      env.services?.each { svc ->
        if (svc.type == null) {
          appname_normalized = svc.name.replaceAll("-","_")
          stringParam("${appname_normalized}_VERSION", "", "Version to deploy. Empty to keep the same")
        }
      }
    }

    def groovyscript = """
    import com.pearson.deployment.job.*

    def job = new DeployEnvironment(build, launcher, listener, 'environments.bitesize', '${env.name}')
    job.deploy()
    """

    steps {
      systemGroovyCommand groovyscript
    }
  }

  // This is ugly. Could be
  //  env.tests.each { test ->
  //    job(..) {
  //        shellCommands(test.commands)
  //    }
  // }

  env.tests?.each { tst ->
    job("${env.name }-${tst.name}") {
      label('generic')
      wrappers {
          colorizeOutput()
      }
      scm {
        git {
          remote {
            url(tst.repository)
            branch(tst.branch)
            credentials('seed-job-key')
          }
        }
      }
      steps {
        tst.commands.each { cmd ->
          keys = cmd.keySet() as String[]
          type = keys[0]
          if (type == "shell" ) {
            shell cmd[type]
          }
        }
      }
    }
  }
}