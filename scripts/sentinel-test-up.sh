#!/bin/sh
set -eu
script_directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
container_name=boba-straw-sentinel-test
if docker container inspect "$container_name" >/dev/null 2>&1; then
    owner=$(docker inspect --format '{{index .Config.Labels "io.github.susongyan.boba-test"}}' "$container_name")
    if [ "$owner" != sentinel ]; then
        echo 'Refusing an existing container without the sentinel-test ownership label' >&2
        exit 1
    fi
    if [ "$(docker inspect --format '{{.State.Running}}' "$container_name")" != true ]; then
        echo 'Stopped fixture exists; inspect/remove it explicitly before recreating' >&2
        exit 1
    fi
else
    docker run --detach --name "$container_name" \
        --label io.github.susongyan.boba-test=sentinel \
        --publish 127.0.0.1:17501-17502:17501-17502 \
        --publish 127.0.0.1:27501-27503:27501-27503 \
        --mount "type=bind,source=$script_directory/sentinel-test-entrypoint.sh,target=/sentinel-test.sh,readonly" \
        redis:7.4.2 sh /sentinel-test.sh
fi
attempt=0
until docker exec -e REDISCLI_AUTH=boba-test-sentinel "$container_name" \
    redis-cli -p 27501 SENTINEL CKQUORUM tea | grep -q '^OK'; do
    attempt=$((attempt + 1))
    if [ "$attempt" -ge 30 ]; then
        echo "Sentinel quorum not ready; inspect docker logs $container_name" >&2
        exit 1
    fi
    sleep 1
done
echo 'Boba Straw Sentinel fixture ready: Redis 17501-17502; Sentinel 27501-27503'
