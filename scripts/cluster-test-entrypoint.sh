#!/bin/sh
# Only used INSIDE the isolated Boba Straw cluster-test container.
set -eu
existing_nodes=0
for port in 17401 17402 17403 17404 17405 17406; do
    if [ -s "/data/node-$port/nodes.conf" ]; then
        existing_nodes=$((existing_nodes + 1))
    fi
done
if [ "$existing_nodes" -ne 0 ] && [ "$existing_nodes" -ne 6 ]; then
    echo 'Partial fixture topology found; refusing to overwrite existing node state' >&2
    exit 1
fi
for port in 17401 17402 17403 17404 17405 17406; do
    mkdir -p "/data/node-$port"
    redis-server --port "$port" --bind 0.0.0.0 --protected-mode no \
        --cluster-enabled yes --cluster-config-file nodes.conf --cluster-node-timeout 1000 \
        --cluster-announce-ip 127.0.0.1 --appendonly no --save '' \
        --dir "/data/node-$port" --daemonize yes --pidfile "/data/node-$port/redis.pid"
done
if [ "$existing_nodes" -eq 0 ]; then
    redis-cli --cluster create \
        127.0.0.1:17401 127.0.0.1:17402 127.0.0.1:17403 \
        127.0.0.1:17404 127.0.0.1:17405 127.0.0.1:17406 \
        --cluster-replicas 1 --cluster-yes
fi
exec tail -f /dev/null
