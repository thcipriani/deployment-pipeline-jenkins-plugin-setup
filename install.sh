#!/usr/bin/env bash

set -eu

JENKINS_HOME=/var/lib/jenkins
JENKINS_USER=jenkins

sudo -k

echo 'Setup initial groovy scripts'
sudo cp ./init.groovy.d/*.groovy "$JENKINS_HOME"/init.groovy.d/
sudo chown -R $JENKINS_USER "$JENKINS_HOME"
sudo chmod a+x "$JENKINS_HOME"/init.groovy.d/*.groovy

[ ! -d "/usr/share/jenkins/jobdsl" ] && {
    echo 'Setup initial jobdsl'
    sudo cp -R jobdsl /usr/share/jenkins/jobdsl
}

[ ! -d "/usr/share/jenkins/lib" ] && {
    echo 'Setup initial job lib'
    sudo cp -R lib /usr/share/jenkins/lib
}

sudo chmod -R a+r /usr/share/jenkins/{jobdsl,lib}

echo 'Installing and pinning plugins'
sudo "$(pwd)"/bin/plugins.sh "$(pwd)/plugins.txt"

echo 'Add some manual plugins'
for file in "$(pwd)"/build/*.hpi; do

    if [ ! -f "${JENKINS_HOME}/plugins/$(basename "$file")" ]; then
        sudo cp "$file" "$JENKINS_HOME/plugins"
    fi
done

sudo chown -R $JENKINS_USER "$JENKINS_HOME"

echo "SUCCESS! Now navigate to http(s)://[JENKINS_URL]/safeRestart to trigger the groovy scripts"
