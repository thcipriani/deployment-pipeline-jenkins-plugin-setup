import jenkins.model.*
import hudson.model.*
import hudson.model.Cause.*
import hudson.security.*
import org.jenkinsci.plugins.*

import com.cloudbees.plugins.credentials.*
import com.cloudbees.plugins.credentials.domains.*
import com.cloudbees.plugins.credentials.common.*
import com.cloudbees.jenkins.plugins.sshcredentials.impl.*

import hudson.plugins.git.GitSCM
import hudson.plugins.git.UserRemoteConfig
import hudson.plugins.git.BranchSpec
import hudson.plugins.git.SubmoduleConfig
import hudson.plugins.git.extensions.impl.PathRestriction

import hudson.triggers.*
import hudson.tasks.Shell
import hudson.plugins.gradle.Gradle

import javaposse.jobdsl.plugin.ExecuteDslScripts
import javaposse.jobdsl.plugin.RemovedJobAction
import javaposse.jobdsl.plugin.RemovedViewAction

import hudson.plugins.groovy.*

seed_jobs_repo = 'ssh://analytics.tylercipriani.com:test-jenkins/scap'
git_key = """\
--- BEGIN SOME PRIVATE KEY ---
sajdfl
--- END SOME PRIVATE KEY ---
""".stripIndent()

global_domain = Domain.global()
credentials_store =
Jenkins.instance.getExtensionList('com.cloudbees.plugins.credentials.SystemCredentialsProvider')[0].getStore()

credentials = new BasicSSHUserPrivateKey(
    CredentialsScope.GLOBAL,
    "seed-job-key",
    "seed-job-key",
    new BasicSSHUserPrivateKey.DirectEntryPrivateKeySource(git_key),
    null,
    "Private key for accessing git repos"
)

username_matcher = CredentialsMatchers.withUsername("git")
available_credentials =
    CredentialsProvider.lookupCredentials(
        StandardUsernameCredentials.class,
        Jenkins.getInstance(),
        hudson.security.ACL.SYSTEM,
        new SchemeRequirement("ssh")
    )

existing_credentials =
    CredentialsMatchers.firstOrNull(
        available_credentials,
        username_matcher
    )

if(existing_credentials != null) {
    credentials_store.updateCredentials(
        global_domain,
        existing_credentials,
        credentials
    )
} else {
    credentials_store.addCredentials(global_domain, credentials)
}


if (!Jenkins.instance.getItemMap().containsKey("seed-job")) {
    def seedJob = Jenkins.instance.createProject(FreeStyleProject.class, "seed-job")

    username_matcher = CredentialsMatchers.withUsername("seed-job-key")
    available_credentials =
        CredentialsProvider.lookupCredentials(
            StandardUsernameCredentials.class,
            Jenkins.getInstance(),
            hudson.security.ACL.SYSTEM,
            new SchemeRequirement("ssh")
        )

    existing_credentials =
        CredentialsMatchers.firstOrNull(
            available_credentials,
            username_matcher
        )

    def userRemoteConfig = new UserRemoteConfig(seed_jobs_repo, null, null, existing_credentials.id)

    def scm = new GitSCM(
        Collections.singletonList(userRemoteConfig),
        Collections.singletonList(new BranchSpec("master")),
        false,
        Collections.<SubmoduleConfig>emptyList(),
        null,
        null,
        null)

    scm.getExtensions().add(new PathRestriction("application.bitesize\nbuild.bitesize",""))
    seedJob.scm = scm

    def trigger = new SCMTrigger("* * * * *")
    trigger.job = seedJob

    seedJob.addTrigger(trigger)

    // def customDSL = new File("/usr/share/jenkins/jobdsl/custom_dsl.groovy").text
    // def systemSetupScript = new File("/usr/share/jenkins/jobdsl/system_setup.groovy").text
    // dsl = "${systemSetupScript}\n${customDSL}\n"
    // def customScript = new ExecuteDslScripts.ScriptLocation("true", "", dsl)

    def scripts = new ExecuteDslScripts.ScriptLocation(
        "false",
        "lib/*.groovy",
        ""
    )

    cmd = """\
        mkdir -p \$WORKSPACE/lib
        cp /usr/share/jenkins/lib/*.jar \$WORKSPACE/lib
        cp /usr/share/jenkins/jobdsl/*.groovy \$WORKSPACE/lib
    """.stripIndent()

    seedJob.buildersList.add(new Shell(cmd))

    seedJob.buildersList.add(
        new ExecuteDslScripts(
            scriptLocation=scripts,
            false,
            RemovedJobAction.DELETE,
            RemovedViewAction.DELETE,
            null,
            "lib/*.jar"
        ))
    // seedJob.buildersList.add(new SystemGroovy(groovySource, null, null))

    seedJob.save()
    seedJob.scheduleBuild(new Cause.UserIdCause())
}
