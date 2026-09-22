#!/bin/sh
# Six Redis processes share one container so announced loopback endpoints work on macOS/Colima.
set -eu
script_directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
container_name=boba-straw-cluster-test
if docker container inspect "$container_name" >/dev/null 2>&1; then
    owner=$(docker inspect --format '{{index .Config.Labels "io.github.susongyan.boba-test"}}' "$container_name")
    if [ "$owner" != "cluster" ]; then
        echo "Refusing to use an existing container without the cluster-test ownership label" >&2
        exit 1
    fi
    if [ "$(docker inspect --format '{{.State.Running}}' "$container_name")" != true ]; then
        echo "Stopped test container exists; inspect/remove it explicitly before recreating" >&2
        exit 1
    fi
else
    docker run --detach --name "$container_name" \
        --label io.github.susongyan.boba-test=cluster \
        --publish 127.0.0.1:17401-17406:17401-17406 \
        --mount "type=bind,source=$script_directory/cluster-test-entrypoint.sh,target=/cluster-test.sh,readonly" \
        redis:7.4.2 sh /cluster-test.sh
fi
attempt=0
until docker exec "$container_name" redis-cli -p 17401 cluster info | tr -d '\r' | grep -q '^cluster_state:ok$'; do
    attempt=$((attempt + 1))
    if [ "$attempt" -ge 30 ]; then
        echo "Cluster did not become ready; inspect docker logs $container_name" >&2
        exit 1
    fi
    sleep 1
done
echo 'Boba Straw test cluster ready on 127.0.0.1:17401-17406'
