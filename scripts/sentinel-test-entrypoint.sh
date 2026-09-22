#!/bin/sh
# Disposable loopback-only integration fixture. These are public TEST credentials, not secrets.
set -eu
for port in 17501 17502; do
    mkdir -p "/data/redis-$port"
    if [ "$port" = 17502 ]; then
        redis-server --port "$port" --bind 0.0.0.0 --protected-mode no \
            --requirepass boba-test-data --masterauth boba-test-data --replicaof 127.0.0.1 17501 \
            --replica-announce-ip 127.0.0.1 --replica-announce-port "$port" \
            --save '' --appendonly no --dir "/data/redis-$port" --daemonize yes
    else
        redis-server --port "$port" --bind 0.0.0.0 --protected-mode no \
            --requirepass boba-test-data --masterauth boba-test-data \
            --replica-announce-ip 127.0.0.1 --replica-announce-port "$port" \
            --save '' --appendonly no --dir "/data/redis-$port" --daemonize yes
    fi
done
for port in 27501 27502 27503; do
    mkdir -p "/data/sentinel-$port"
    cat > "/data/sentinel-$port/sentinel.conf" <<EOF
port $port
bind 0.0.0.0
protected-mode no
requirepass boba-test-sentinel
dir /data/sentinel-$port
daemonize yes
sentinel announce-ip 127.0.0.1
sentinel announce-port $port
sentinel monitor tea 127.0.0.1 17501 2
sentinel auth-pass tea boba-test-data
sentinel down-after-milliseconds tea 2000
sentinel failover-timeout tea 10000
sentinel parallel-syncs tea 1
EOF
    redis-server "/data/sentinel-$port/sentinel.conf" --sentinel
done
exec tail -f /dev/null
