#!/usr/bin/env sh
# Removes only Boba Straw's explicitly named benchmark containers.
set -eu

for container_name in \
    boba-straw-benchmark-redis \
    boba-straw-benchmark-valkey
do
    if docker container inspect "$container_name" >/dev/null 2>&1; then
        docker rm --force "$container_name"
    fi
done
