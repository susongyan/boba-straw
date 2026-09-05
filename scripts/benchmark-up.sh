#!/usr/bin/env sh
#
# Starts isolated, resource-bounded Redis and Valkey containers for local benchmarks.
# A running Docker daemon is required; on macOS use `colima start` first.
#
set -eu

redis_image="redis:7.4.2@sha256:fbdbaea47b9ae4ecc2082ecdb4e1cea81e32176ffb1dcf643d422ad07427e5d9"
valkey_image="valkey/valkey:8.1.3@sha256:fea8b3e67b15729d4bb70589eb03367bab9ad1ee89c876f54327fc7c6e618571"

require_expected_container() {
    container_name=$1
    host_port=$2
    expected_image_id=$3
    expected_command=$4

    actual_image_id=$(docker container inspect --format '{{.Image}}' "$container_name")
    actual_port=$(docker port "$container_name" 6379/tcp)
    actual_cpus=$(docker container inspect --format '{{.HostConfig.NanoCpus}}' "$container_name")
    actual_memory=$(docker container inspect --format '{{.HostConfig.Memory}}' "$container_name")
    actual_command=$(docker container inspect --format '{{json .Config.Cmd}}' "$container_name")

    if [ "$actual_image_id" != "$expected_image_id" ] \
        || [ "$actual_port" != "127.0.0.1:$host_port" ] \
        || [ "$actual_cpus" != "2000000000" ] \
        || [ "$actual_memory" != "2147483648" ] \
        || [ "$actual_command" != "$expected_command" ]; then
        echo "$container_name exists with a different benchmark configuration." >&2
        echo "Run ./scripts/benchmark-down.sh, then start the environment again." >&2
        echo "actual: image=$actual_image_id port=$actual_port cpus=$actual_cpus memory=$actual_memory command=$actual_command" >&2
        exit 2
    fi
}

start_container() {
    container_name=$1
    host_port=$2
    image=$3
    server_command=$4
    cli_command=$5

    if ! docker image inspect "$image" >/dev/null 2>&1; then
        docker pull "$image" >/dev/null
    fi
    expected_image_id=$(docker image inspect --format '{{.Id}}' "$image")
    expected_command="[\"$server_command\",\"--save\",\"\",\"--appendonly\",\"no\"]"

    if docker container inspect "$container_name" >/dev/null 2>&1; then
        require_expected_container \
            "$container_name" "$host_port" "$expected_image_id" "$expected_command"
        running=$(docker container inspect --format '{{.State.Running}}' "$container_name")
        if [ "$running" != "true" ]; then
            docker start "$container_name" >/dev/null
        fi
    else
        docker run --detach \
            --name "$container_name" \
            --publish "127.0.0.1:$host_port:6379" \
            --cpus 2 \
            --memory 2g \
            --health-cmd "$cli_command ping | grep PONG" \
            --health-interval 1s \
            --health-timeout 1s \
            --health-retries 30 \
            "$image" \
            "$server_command" --save "" --appendonly no >/dev/null
    fi

    attempts=0
    while [ "$attempts" -lt 60 ]; do
        if [ "$(docker exec "$container_name" "$cli_command" ping 2>/dev/null || true)" = "PONG" ]; then
            require_expected_container \
                "$container_name" "$host_port" "$expected_image_id" "$expected_command"
            digest=$(docker image inspect --format '{{index .RepoDigests 0}}' "$expected_image_id")
            echo "$container_name ready at redis://127.0.0.1:$host_port ($digest)"
            return
        fi
        attempts=$((attempts + 1))
        sleep 1
    done

    echo "$container_name did not become ready" >&2
    docker logs "$container_name" >&2
    exit 1
}

docker info >/dev/null
start_container boba-straw-benchmark-redis 17379 "$redis_image" redis-server redis-cli
start_container boba-straw-benchmark-valkey 17380 "$valkey_image" valkey-server valkey-cli
