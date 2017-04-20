#!/usr/bin/env groovy

/* BEGIN: Things defined in src/jenkins/build.groovy that have to be duplicated because we don't have the file available at the time they are needed. */
// Install job-level dependencies that aren't specific to the build and
// can be required as part of checkout and should be applied before knowing
// the revision's information. e.g. JQ is required to post to phabricator.
// This should generally be fixed in the AMI, eventually.
// MARATHON-7026
def install_dependencies() {
  sh "chmod 0600 ~/.arcrc"
  // JQ is broken in the image
  sh "curl -L https://github.com/stedolan/jq/releases/download/jq-1.5/jq-linux64 > /tmp/jq && sudo mv /tmp/jq /usr/bin/jq && sudo chmod +x /usr/bin/jq"
  // install ammonite (scala shell)
  sh """mkdir -p ~/.ammonite && curl -L -o ~/.ammonite/predef.sc https://git.io/v6r5y && mkdir -p ~/.ammonite && curl -L -o ~/.ammonite/predef.sc https://git.io/v6r5y"""
  sh """sudo curl -L -o /usr/local/bin/amm https://github.com/lihaoyi/Ammonite/releases/download/0.8.2/2.12-0.8.2 && sudo chmod +x /usr/local/bin/amm"""
  return this
}


def m

ansiColor('gnome-terminal') {
  node('JenkinsMarathonCI-Debian8-2017-03-21') {
    m = load("src/jenkins/build.groovy")
    stage("Install Dependencies") {
      m.install_dependencies()
    }
    stage("Checkout") {
      m.checkout_marathon()
      m = load("src/jenkins/build.groovy")
    }
    m.build_marathon()
  }
}
