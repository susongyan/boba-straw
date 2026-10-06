#!/bin/sh
# Usage: run-starter-matrix.sh unit|full-tls JAVA8_HOME JAVA17_OR_21_HOME
# Isolated builds prevent a Boot 3 compilation from contaminating the Boot 2 / Java 8 result.
set -eu
mode=${1:?Specify unit or full-tls}
case "$mode" in unit|full-tls) ;; *) exit 2 ;; esac
java8=${2:?Specify Java 8 home}
modern=${3:?Specify Java 17 or 21 home}
repo=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
evidence=$(mktemp -d "${TMPDIR:-/tmp}/boba-straw-starter-XXXXXX")
maven=$(command -v mvn)
echo "Evidence directory: $evidence"
mkdir "$evidence/source"
rsync -a --exclude=.git --exclude=target --exclude=.idea --exclude=.vscode "$repo/" "$evidence/source/"
printf 'boot\tstatus\tjava_home\n' >"$evidence/results.tsv"
failed=0
for version in 2.7.18 3.5.6; do
    java_home=$java8
    [ "$version" = 2.7.18 ] || java_home=$modern
    test -x "$java_home/bin/java"
    mkdir "$evidence/$version"
    rsync -a "$evidence/source/" "$evidence/$version/"
    status=0
    (
        export JAVA_HOME="$java_home"
        export PATH="$JAVA_HOME/bin:$PATH"
        cd "$evidence/$version"
        java -version
        set -- -Dspring-boot.version="$version"
        if [ "$mode" = full-tls ]; then
            test -f "${BOBA_TLS_CERT_DIR:?Set BOBA_TLS_CERT_DIR}/client.p12"
            set -- "$@" -Dboba.straw.runCompatibility=true -Dboba.straw.runCluster=true \
                -Dboba.straw.runSentinel=true -Dboba.straw.runTls=true
        fi
        "$maven" --batch-mode -pl boba-straw-spring-boot-autoconfigure -am clean verify "$@"
    ) >"$evidence/$version.log" 2>&1 || status=$?
    printf '%s\t%s\t%s\n' "$version" "$status" "$java_home" >>"$evidence/results.tsv"
    if [ "$status" -ne 0 ]; then
        failed=1
        tail -50 "$evidence/$version.log"
    fi
done
echo "Finished; source, logs and reports retained at $evidence"
exit "$failed"
