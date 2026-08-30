#!/usr/bin/env sh
#
# Starts the Redis/Valkey compatibility matrix used by Boba Straw integration tests.
# Requires a running Colima Docker daemon.
#
set -eu

start_container() {
    container_name=$1
    host_port=$2
    image=$3

    if docker container inspect "$container_name" >/dev/null 2>&1; then
        echo "$container_name already exists on localhost:$host_port"
        return
    fi

    docker run --detach \
        --name "$container_name" \
        --publish "127.0.0.1:$host_port:6379" \
        "$image" \
        redis-server --save "" --appendonly no

    echo "Started $container_name on redis://127.0.0.1:$host_port"
}

docker info >/dev/null

start_container boba-straw-redis-5 16379 redis:5.0.14
start_container boba-straw-redis-6 16380 redis:6.2.14
start_container boba-straw-redis-7 16381 redis:7.4.2
start_container boba-straw-valkey 16382 valkey/valkey:8.1.3

echo "All compatibility containers are starting."
echo "Use: docker ps --filter name=boba-straw"
