#!groovy
@Library('iot-invent-shared') _

// IoT Invent build of the vertx-mqtt fork. Only the iot branch carries this file, so neither the
// upstream tracking branches nor pull request branches are built or deployed from here.
//
// Versions: the pom holds <base>-iot-SNAPSHOT, e.g. 5.2.0-iot-SNAPSHOT, where <base> is the Vert.x
// release the fork is based on. A release is <base>-iot.<n>, counted from the v<base>-iot.<n> tags.
// getNextRelease() does not fit: it yields plain X.Y.Z versions, which are the upstream releases.
pipeline {

    agent any
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
                description: "Also run the tests that start a Mosquitto broker via Testcontainers (MosquittoTest and all *IT). Needs Docker on the agent.",
                defaultValue: false)
        booleanParam(name: "CLEANUP",
                description: "Cleanup Current Workspace.",
                defaultValue: false)
        string(name: "MVN_PARAMS", defaultValue: "", description: "Additional Maven Parameters for: mvn clean deploy")
    }
    environment {
        // Without Docker MosquittoTest fails instead of skipping, the *IT classes are not picked up by default
        TEST_SELECTION = "${params.INTEGRATION_TESTS ? "-Dtest='*Test,*IT'" : "-Dtest='!MosquittoTest'"} -Dsurefire.failIfNoSpecifiedTests=false"
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
                    sh "mvn ${params.MVN_PARAMS} -e -B clean deploy ${env.TEST_SELECTION}"
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
                    sh "mvn -B versions:set -DnewVersion=${env.RELEASE_VERSION} -DgenerateBackupPoms=false"
                    sh "mvn -B clean deploy ${env.TEST_SELECTION}"
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
