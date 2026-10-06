#!/bin/sh
# Removes only the disposable containers owned by the C7 fixture. Certificates are retained.
set -eu
for name in boba-straw-tls-62 boba-straw-tls-74 boba-straw-tls-valkey boba-straw-tls-topology; do
    if docker inspect "$name" >/dev/null 2>&1; then
        owner=$(docker inspect --format '{{index .Config.Labels "io.github.susongyan.boba-test"}}' "$name")
        [ "$owner" = tls ] || { echo "Refusing unowned container: $name" >&2; exit 1; }
        docker rm -f "$name"
    fi
done
