#!/usr/bin/env groovy

def m

ansiColor('gnome-terminal') {
  node('JenkinsMarathonCI-Debian8-2017-03-21') {
    // fetch the file directly from SCM so we can use it to checkout the rest of the pipeline.
    // TODO: Change to master once we this is submitted.
    checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: '*/pipelines/jason/omg-jenkins-ci-ci']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'SparseCheckoutPaths', sparseCheckoutPaths: [[path: 'build.groovy']]]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'mesosphere-ci-github', url: 'git@github.com:mesosphere/marathon.git']]]
    m = load("build.groovy")
    stage("Checkout") {
      m.checkout_marathon()
      m = load("build.groovy")
    }
    m.build_marathon()
  }
}
