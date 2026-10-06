#!/bin/sh
# Dedicated disposable TLS fixtures. No changes to the existing plaintext matrix.
set -eu
umask 077
script_directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
certs=${1:?Usage: tls-test-up.sh ABSOLUTE_CERT_DIRECTORY}
case "$certs" in /*) ;; *) echo 'Certificate directory must be absolute' >&2; exit 1 ;; esac
if [ -e "$certs" ]; then
    echo 'Use a new certificate directory; existing certificate material is not overwritten' >&2
    exit 1
fi
docker info >/dev/null
for name in boba-straw-tls-62 boba-straw-tls-74 boba-straw-tls-valkey boba-straw-tls-topology; do
    if docker inspect "$name" >/dev/null 2>&1; then
        echo "Fixture $name already exists; inspect it or explicitly run tls-test-down.sh" >&2
        exit 1
    fi
done
mkdir -p "$certs"
openssl req -x509 -newkey rsa:2048 -nodes -days 7 -sha256 \
    -subj /CN=Boba-C7-Test-CA -keyout "$certs/ca.key" -out "$certs/ca.crt"
openssl req -newkey rsa:2048 -nodes -subj /CN=localhost \
    -keyout "$certs/server.key" -out "$certs/server.csr"
printf '%s\n' 'subjectAltName=DNS:localhost,IP:127.0.0.1' \
    'extendedKeyUsage=serverAuth,clientAuth' 'basicConstraints=CA:FALSE' >"$certs/extensions.cnf"
openssl x509 -req -in "$certs/server.csr" -CA "$certs/ca.crt" -CAkey "$certs/ca.key" \
    -CAcreateserial -days 7 -sha256 -extfile "$certs/extensions.cnf" -out "$certs/server.crt"
# Legacy PKCS12 algorithms keep the generated identity readable by the Java 8 baseline.
openssl pkcs12 -export -inkey "$certs/server.key" -in "$certs/server.crt" \
    -certfile "$certs/ca.crt" -name identity -passout pass:test-only \
    -keypbe PBE-SHA1-3DES -certpbe PBE-SHA1-3DES -macalg sha1 -out "$certs/client.p12"

start_server() {
    name=$1 port=$2 image=$3 binary=$4
    docker run -d --name "$name" --user 0:0 \
        --label io.github.susongyan.boba-test=tls \
        --publish "127.0.0.1:$port:6379" \
        --mount "type=bind,source=$certs,target=/certs,readonly" \
        "$image" "$binary" --port 0 --tls-port 6379 \
        --tls-cert-file /certs/server.crt --tls-key-file /certs/server.key \
        --tls-ca-cert-file /certs/ca.crt --tls-auth-clients yes \
        --requirepass boba-tls-test --save '' --appendonly no
}
start_server boba-straw-tls-62 17679 redis:6.2.14 redis-server
start_server boba-straw-tls-74 17680 redis:7.4.2 redis-server
start_server boba-straw-tls-valkey 17681 valkey/valkey:8.1.3 valkey-server
docker run -d --name boba-straw-tls-topology --user 0:0 \
    --label io.github.susongyan.boba-test=tls \
    --publish 127.0.0.1:17601-17603:17601-17603 \
    --publish 127.0.0.1:17701-17702:17701-17702 \
    --publish 127.0.0.1:27701-27703:27701-27703 \
    --mount "type=bind,source=$certs,target=/certs,readonly" \
    --mount "type=bind,source=$script_directory/tls-test-entrypoint.sh,target=/tls-test.sh,readonly" \
    redis:7.4.2 sh /tls-test.sh

for name in boba-straw-tls-62 boba-straw-tls-74 boba-straw-tls-valkey; do
    attempts=0
    until docker exec -e REDISCLI_AUTH=boba-tls-test "$name" redis-cli --tls \
        --cacert /certs/ca.crt --cert /certs/server.crt --key /certs/server.key PING 2>/dev/null | grep -q PONG; do
        attempts=$((attempts + 1))
        [ "$attempts" -lt 30 ] || { echo "TLS readiness failed: $name" >&2; exit 1; }
        sleep 1
    done
done
attempts=0
until docker exec boba-straw-tls-topology test -f /tmp/tls-ready; do
    attempts=$((attempts + 1))
    [ "$attempts" -lt 45 ] || { echo 'TLS topology readiness failed' >&2; exit 1; }
    sleep 1
done
echo "TLS fixtures ready. Export BOBA_TLS_CERT_DIR=$certs for compatibility tests."
