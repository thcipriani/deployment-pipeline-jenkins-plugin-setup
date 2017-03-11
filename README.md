# Pearson Deployment Pipeline Build Setup
## A fresh Jenkins install on Debian Jessie

I adapted this from what I found in Pearson's [Jenkins Deployment
Pipeline](https://github.com/pearsontechnology/deployment-pipeline-jenkins-plugin#buildbitesize)
repo.

This repo is all he code that's hidden in the docker image that is at the core
of that repository.

I wanted to try to setup that repo manually on a VM instance running Jenkins
rather that spin up a new Jenkins instance of their base image deployed to a
running k8s.

Getting this plugin to work on a fresh Jenkins install on Jessie rather than
just using the [Pearson Docker
image](https://hub.docker.com/r/pearsontechnology/deployment-pipeline-jenkins-plugin/)
meant that I spent a long time looking at `docker history` for that image.

## Initialization
### How this works, maybe, for Pearson

In my mind's eye I see a close up of a marble rolling down a track, queue
[Powerhouse](https://youtu.be/qaC0vNLdLvY?t=1m25s), the marble drops through a
hole in the track and there is a sound something like a clang of a pan. There
is a boot on a stick that is released, kicking a bowling ball down a track...

A new Jenkins is built via `kubectl create`. K8s is passed a file that contains
a bunch of environment vars for the underlying image: The git repo for the
project, an SSH private key to access the repo, k8s creds for deployment info,
etc.

Inside the docker image, `jenkins.sh` is run. It runs through `plugins.txt`
line-by-line grabbing the versions of plugins specified and pinning them:

    curl -sSL [yandex-link] /var/lib/jenkins/plugins/[plugin].jpi
    touch /var/lib/jenkins/plugins/[plugin].jpi.pinned

Then Jenkins is launched.

Some `init.groovy.d` scripts spring into action. The `init.groovy.d` scripts
create a "seed" job (if one doesn't exist). This job runs everything in the
`jobsdsl` folder on the new jenkins install.

For reference I exhumed the jobdsl files using:

    docker cp <container-id>:/usr/share/jenkins/[whatever] [whatever]

The `jobdsl` files are groovy scripts that contain large HEREDOC'd groovy
scripts that themselves create new jobs.

# Now

I've got this to the point I can spin up a fresh Jenkins install and run
`./install.sh` from the root of this directory.
