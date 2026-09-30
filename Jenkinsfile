#!groovy
@Library('iot-invent-shared') _

// IoT Invent build of the vertx-mqtt fork. Only the iot branch carries this file, so neither the
// upstream tracking branches nor pull request branches are built or deployed from here.
//
// Versions: the pom holds <base>-iot-SNAPSHOT, e.g. 5.2.0-iot-SNAPSHOT, where <base> is the Vert.x
// release the fork is based on. A release is <base>-iot.<n>, counted from the v<base>-iot.<n> tags.
// getNextRelease() does not fit: it yields plain X.Y.Z versions, which are the upstream releases.
pipeline {

    // The test suite starts some hundred client threads (paho) and hits the task ceiling of the
    // built-in node ("unable to create native thread"). it-runner has its own limits and a single
    // executor, so the fixed ports 1883 and 8883 of the tests cannot collide with another build.
    agent { label 'it-runner' }
    // jdk21 and M3 are resolved from the node's Tool Locations on it-runner
    tools {
        jdk 'jdk21'
        maven 'M3'
    }
    options {
        timeout(time: 60, unit: 'MINUTES')
        timestamps()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
        skipStagesAfterUnstable()
    }
    parameters {
        booleanParam(name: "RELEASE",
                description: "Release instead of deploying a SNAPSHOT: builds the next free version <base>-iot.<n> (counted from the tags), deploys it and pushes the tag. Only on the iot branch.",
                defaultValue: false)
        booleanParam(name: "INTEGRATION_TESTS",
                description: "Also run the tests that start a Mosquitto broker via Testcontainers (MosquittoTest and all *IT). Needs Docker on the agent, which it-runner does not have yet.",
                defaultValue: false)
        booleanParam(name: "CLEANUP",
                description: "Cleanup Current Workspace.",
                defaultValue: false)
        string(name: "MVN_PARAMS", defaultValue: "", description: "Additional Maven Parameters for: mvn clean deploy")
    }
    environment {
        // Two values of the shared settings file only hold on the controller:
        // - the local repository /var/jenkins_home/.m2/repository does not exist on it-runner, and
        //   Maven aborts instead of falling back. Single-quoted so Groovy leaves $HOME to the shell.
        // - repo.releases/repo.snapshots point to http://nexus:8081, a name only the controller's
        //   Docker network resolves. The same repositories are reachable under the public host,
        //   the credentials of server id nexus stay with the settings. -D beats the settings profile.
        MVN_AGENT = '-Dmaven.repo.local=$HOME/.m2/repository -Drepo.releases=https://repository.iot-invent.com/repository/iot-releases/ -Drepo.snapshots=https://repository.iot-invent.com/repository/iot-snapshots/'
        // Without Docker MosquittoTest fails instead of skipping, the *IT classes are not picked up by default.
        // An exclusion alone is no selection: surefire then treats every class as a test, module-info included.
        TEST_SELECTION = "${params.INTEGRATION_TESTS ? "-Dtest='*Test,*IT'" : "-Dtest='*Test,!MosquittoTest'"} -Dsurefire.failIfNoSpecifiedTests=false"
    }

    stages {
        stage("Cleanup") {
            when { expression { params.CLEANUP } }
            steps { cleanWs(); checkout scm }
        }
        stage('Build & Deploy') {
            when { expression { !params.RELEASE } }
            steps {
                withMaven(maven: 'M3', mavenSettingsConfig: 'iot_maven') {
                    sh "mvn ${env.MVN_AGENT} ${params.MVN_PARAMS} -e -B clean deploy ${env.TEST_SELECTION}"
                }
            }
        }
        stage("Release") {
            when { expression { params.RELEASE } }
            steps {
                script {
                    if (env.BRANCH_NAME != 'iot') {
                        error "RELEASE is only allowed on the iot branch, not on ${env.BRANCH_NAME}"
                    }
                    cleanWs()
                    checkout scm
                    env.RELEASE_VERSION = nextIotRelease()
                    echo "Releasing ${env.RELEASE_VERSION}"
                }
                withMaven(maven: 'M3', mavenSettingsConfig: 'iot_maven') {
                    // the version is set in the workspace only, the branch keeps its -SNAPSHOT
                    sh "mvn ${env.MVN_AGENT} -B versions:set -DnewVersion=${env.RELEASE_VERSION} -DgenerateBackupPoms=false"
                    sh "mvn ${env.MVN_AGENT} -B clean deploy ${env.TEST_SELECTION}"
                }
                withCredentials([gitUsernamePassword(credentialsId: 'iot-invent-bot', gitToolName: 'Default')]) {
                    sh """
                    git config --global user.email support@iot-invent.com
                    git config --global user.name iot-invent-bot
                    """
                    sh "git tag v${env.RELEASE_VERSION}"
                    sh "git push origin v${env.RELEASE_VERSION}"
                }
            }
        }
        stage('Upstream Advisories') {
            // Behind Build & Deploy and Release on purpose: skipStagesAfterUnstable() is set, so an
            // unstable result placed earlier would skip the deployment.
            //
            // Scanners compare versions: dependency-check parses 5.2.0-iot.1 as 5.2.0.1, so an advisory
            // "up to and including 5.2.0" does not match the fork in the consumers' scans. The advisories
            // of the Vert.x release the fork is based on are checked here instead.
            steps {
                script {
                    def base = readMavenPom(file: 'pom.xml').properties['vertx.dependencies.version']
                    def vulns = osvVulnerabilities('io.vertx:vertx-mqtt', base)
                    if (vulns == null) {
                        unstable "OSV could not be queried for io.vertx:vertx-mqtt:${base}, upstream advisories are unchecked"
                    } else if (vulns) {
                        unstable "OSV reports advisories for io.vertx:vertx-mqtt:${base}, the base of this fork:\n" + vulns.join('\n')
                    } else {
                        echo "OSV reports no advisories for io.vertx:vertx-mqtt:${base}"
                    }
                }
            }
        }
    }

    post { always { sendNotifications currentBuild.result } }
}

/**
 * The next release version <base>-iot.<n> of the pom's <base>-iot-SNAPSHOT, counted from the tags.
 */
def nextIotRelease() {
    def version = readMavenPom(file: 'pom.xml').version
    def m = version =~ /^(.+-iot)-SNAPSHOT$/
    if (!m.matches()) {
        error "pom version ${version} is not of the form <base>-iot-SNAPSHOT"
    }
    def line = m.group(1)
    m = null
    withCredentials([gitUsernamePassword(credentialsId: 'iot-invent-bot', gitToolName: 'Default')]) {
        sh "git fetch --tags --force"
    }
    def listing = sh(script: "git tag --list 'v${line}.*'", returnStdout: true)
    return "${line}.${highestIotNumber(listing, line) + 1}"
}

/**
 * The highest <n> of the v<line>.<n> tags in a `git tag --list` output, 0 if there is none.
 * Compared as integers, as a string comparison would put .9 above .10.
 */
@NonCPS
private int highestIotNumber(String listing, String line) {
    int best = 0
    def prefix = "v${line}."
    for (String tag in listing.readLines()) {
        tag = tag.trim()
        if (tag.startsWith(prefix) && tag.substring(prefix.length()) ==~ /\d+/) {
            best = Math.max(best, tag.substring(prefix.length()) as int)
        }
    }
    return best
}

/**
 * The OSV advisories of a Maven artifact version as "<id> (<aliases>): <summary>" lines,
 * an empty list if there are none, null if OSV could not be queried.
 */
def osvVulnerabilities(String coordinates, String version) {
    def query = groovy.json.JsonOutput.toJson([package: [name: coordinates, ecosystem: 'Maven'], version: version])
    writeFile file: 'osv-query.json', text: query
    def status = sh(script: "curl -sS --fail --max-time 60 -X POST -H 'Content-Type: application/json' --data @osv-query.json -o osv-result.json https://api.osv.dev/v1/query", returnStatus: true)
    if (status != 0) {
        return null
    }
    def result = readJSON file: 'osv-result.json'
    def lines = []
    for (def v in (result.vulns ?: [])) {
        lines << "${v.id} (${(v.aliases ?: []).join(', ')}): ${v.summary ?: ''}".toString()
    }
    return lines
}
