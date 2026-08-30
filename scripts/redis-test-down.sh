#!/usr/bin/env sh
#
# Removes only the explicitly named Boba Straw local compatibility containers.
#
set -eu

for container_name in \
    boba-straw-redis-5 \
    boba-straw-redis-6 \
    boba-straw-redis-7 \
    boba-straw-valkey
do
    if docker container inspect "$container_name" >/dev/null 2>&1; then
        docker rm --force "$container_name"
    fi
done
