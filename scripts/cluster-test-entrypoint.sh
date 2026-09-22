#!/bin/sh
# Only used INSIDE the isolated Boba Straw cluster-test container.
set -eu
for port in 17401 17402 17403 17404 17405 17406; do
    mkdir -p "/data/node-$port"
    redis-server --port "$port" --bind 0.0.0.0 --protected-mode no \
        --cluster-enabled yes --cluster-config-file nodes.conf --cluster-node-timeout 1000 \
        --cluster-announce-ip 127.0.0.1 --appendonly no --save '' \
        --dir "/data/node-$port" --daemonize yes --pidfile "/data/node-$port/redis.pid"
done
redis-cli --cluster create \
    127.0.0.1:17401 127.0.0.1:17402 127.0.0.1:17403 \
    127.0.0.1:17404 127.0.0.1:17405 127.0.0.1:17406 \
    --cluster-replicas 1 --cluster-yes
exec tail -f /dev/null
