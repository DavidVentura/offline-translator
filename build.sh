#!/bin/bash

set -e
USE_PODMAN=""

while (( $# >= 1 )); do 
    case $1 in
        --podman) USE_PODMAN="$2";;
        --podman=*) USE_PODMAN="${1#*=}";;
    *) break;
    esac;
    shift
done


if [[ "${USE_PODMAN}" -eq "0" ]];then
    USE_PODMAN=""
fi

echo "Building Docker image..."
if [[ -z "${USE_PODMAN}" ]]; then
    docker build -t translator-app:latest .
else
    podman build -t translator-app:latest .
fi
echo "Running build in container..."
if [[ -z "${USE_PODMAN}" ]]; then
    docker run --rm \
           -v "$(pwd)":/home/vagrant/build/dev.davidv.translator/ \
           --user "$(id -u):$(id -g)" \
           translator-app:latest \
           ./gradlew assembleAarch64Release
else
    podman run --rm \
           -v "$(pwd)":/home/vagrant/build/dev.davidv.translator/ \
           --userns=keep-id \
           translator-app:latest \
           ./gradlew assembleAarch64Release

fi
echo "Build completed! APK files are in app/build/outputs/apk/"
