#! /bin/bash

# Parse a support-core plugin -style txt file as specification for jenkins plugins to be installed
# in the reference directory, so user can define a derived Docker image with just :
#
# FROM jenkins
# COPY plugins.txt /plugins.txt
# RUN /usr/local/bin/plugins.sh /plugins.txt
#


set -e

JENKINS_UC_DOWNLOAD='http://mirror.yandex.ru/mirrors/jenkins'
REF=/var/lib/jenkins/plugins
mkdir -p $REF

while read spec || [ -n "$spec" ]; do
    plugin=(${spec//:/ });
    [[ ${plugin[0]} =~ ^# ]] && continue
    [[ ${plugin[0]} =~ ^\s*$ ]] && continue
    [[ -z ${plugin[1]} ]] && plugin[1]="latest"
    echo "Installing ${plugin[0]}:${plugin[1]}"

    if [[ ! -f "$REF/${plugin[0]}.jpi.pinned" ]]; then
        curl -sSL -f ${JENKINS_UC_DOWNLOAD}/plugins/${plugin[0]}/${plugin[1]}/${plugin[0]}.hpi -o $REF/${plugin[0]}.jpi
        unzip -qqt $REF/${plugin[0]}.jpi
        touch $REF/${plugin[0]}.jpi.pinned
    fi
done  < $1
