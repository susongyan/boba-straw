#!/usr/bin/env sh
# Usage: sh scripts/run-compatibility-matrix.sh unit|full JAVA_HOME [JAVA_HOME ...]
# full requires the standalone, Cluster and Sentinel test fixtures already running.
set -eu

mode=${1:-}
case "$mode" in
    unit|full) shift ;;
    *) echo "Usage: $0 unit|full JAVA_HOME [JAVA_HOME ...]" >&2; exit 2 ;;
esac
if [ "$#" -eq 0 ]; then
    echo "At least one explicit JAVA_HOME is required" >&2
    exit 2
fi
for java_home in "$@"; do
    case "$java_home" in
        /*) ;;
        *) echo "JAVA_HOME must be absolute: $java_home" >&2; exit 2 ;;
    esac
    if [ ! -x "$java_home/bin/java" ] || [ ! -x "$java_home/bin/javac" ]; then
        echo "Not a JDK: $java_home" >&2
        exit 2
    fi
done
command -v rsync >/dev/null
maven=$(command -v mvn)
repo=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
output=$(mktemp -d "${TMPDIR:-/tmp}/boba-straw-compatibility-XXXXXX")
echo "Evidence directory: $output"
mkdir "$output/source"
rsync -a --exclude=.git --exclude=target --exclude=.idea --exclude=.vscode \
    "$repo/" "$output/source/"
{
    date -u
    uname -a
    echo "mode=$mode"
    git -C "$repo" rev-parse HEAD
    git -C "$repo" status --short
} >"$output/environment.txt" 2>&1

index=0
failed=0
printf 'run\texit_code\tjava_home\n' >"$output/results.tsv"
for java_home in "$@"; do
    index=$((index + 1))
    evidence="$output/run-$index"
    mkdir "$evidence"
    mkdir "$evidence/build"
    rsync -a "$output/source/" "$evidence/build/"
    echo "Running $mode matrix entry $index: $java_home"
    (
        export JAVA_HOME="$java_home"
        export PATH="$JAVA_HOME/bin:$PATH"
        java -version
        "$maven" -version
    ) >"$evidence/environment.txt" 2>&1
    status=0
    (
        cd "$evidence/build"
        export JAVA_HOME="$java_home"
        export PATH="$JAVA_HOME/bin:$PATH"
        if [ "$mode" = full ]; then
            "$maven" --batch-mode clean test -Dboba.straw.runCompatibility=true \
                -Dboba.straw.runCluster=true -Dboba.straw.runSentinel=true
        else
            "$maven" --batch-mode clean test
        fi
    ) >"$evidence/maven.log" 2>&1 || status=$?
    for module in "$evidence/build"/boba-straw-*; do
        if [ -d "$module/target/surefire-reports" ]; then
            mkdir "$evidence/$(basename "$module")"
            cp -R "$module/target/surefire-reports" "$evidence/$(basename "$module")/"
        fi
    done
    printf '%s\t%s\t%s\n' "$index" "$status" "$java_home" >>"$output/results.tsv"
    if [ "$status" -ne 0 ]; then
        failed=1
        tail -60 "$evidence/maven.log" >&2
    fi
done
echo "Finished; logs, reports and source snapshot retained at $output"
exit "$failed"
