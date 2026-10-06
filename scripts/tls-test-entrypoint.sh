#!/bin/sh
set -eu
tls='--port 0 --tls-cert-file /certs/server.crt --tls-key-file /certs/server.key --tls-ca-cert-file /certs/ca.crt --tls-auth-clients yes --tls-replication yes'
for port in 17601 17602 17603; do
    mkdir -p "/tmp/node-$port"
    # Word splitting is intentional for this fixed, repository-owned argument list.
    redis-server $tls --tls-port "$port" --bind 0.0.0.0 --protected-mode no \
        --cluster-enabled yes --tls-cluster yes --cluster-config-file nodes.conf \
        --cluster-announce-ip 127.0.0.1 --cluster-announce-tls-port "$port" \
        --dir "/tmp/node-$port" --daemonize yes --save '' --appendonly no
done
redis-cli --tls --cacert /certs/ca.crt --cert /certs/server.crt --key /certs/server.key \
    --cluster create 127.0.0.1:17601 127.0.0.1:17602 127.0.0.1:17603 --cluster-yes
for port in 17701 17702; do
    mkdir -p "/tmp/data-$port"
    redis-server $tls --tls-port "$port" --bind 0.0.0.0 --protected-mode no \
        --dir "/tmp/data-$port" --daemonize yes --save '' --appendonly no
done
for port in 17701 17702; do
    attempts=0
    until redis-cli --tls --cacert /certs/ca.crt --cert /certs/server.crt --key /certs/server.key \
        -p "$port" PING 2>/dev/null | grep -q PONG; do
        attempts=$((attempts + 1))
        [ "$attempts" -lt 30 ] || exit 1
        sleep 1
    done
done
redis-cli --tls --cacert /certs/ca.crt --cert /certs/server.crt --key /certs/server.key \
    -p 17702 REPLICAOF 127.0.0.1 17701
for port in 27701 27702 27703; do
    config="/tmp/sentinel-$port.conf"
    printf '%s\n' 'port 0' "tls-port $port" 'bind 0.0.0.0' 'protected-mode no' \
        'tls-cert-file /certs/server.crt' 'tls-key-file /certs/server.key' \
        'tls-ca-cert-file /certs/ca.crt' 'tls-auth-clients yes' 'tls-replication yes' \
        'sentinel monitor tea 127.0.0.1 17701 2' 'sentinel down-after-milliseconds tea 1000' \
        'sentinel failover-timeout tea 10000' 'daemonize yes' >"$config"
    redis-server "$config" --sentinel
done
attempts=0
until redis-cli --tls --cacert /certs/ca.crt --cert /certs/server.crt --key /certs/server.key \
    -p 17601 CLUSTER INFO | tr -d '\r' | grep -q '^cluster_state:ok$'; do
    attempts=$((attempts + 1))
    [ "$attempts" -lt 30 ] || exit 1
    sleep 1
done
attempts=0
until redis-cli --tls --cacert /certs/ca.crt --cert /certs/server.crt --key /certs/server.key \
    -p 27701 SENTINEL CKQUORUM tea | grep -q '^OK'; do
    attempts=$((attempts + 1))
    [ "$attempts" -lt 30 ] || exit 1
    sleep 1
done
touch /tmp/tls-ready
exec tail -f /dev/null
