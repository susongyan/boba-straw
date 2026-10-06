#!/usr/bin/env sh
# Usage: sh scripts/run-stability-soak.sh [seconds=1800]
# Existing isolated fixtures only; no container creation/restart or business retries.
set -eu
seconds=${1:-1800}
case "$seconds" in ''|*[!0-9]*) echo 'Duration must be an integer' >&2; exit 2 ;; esac
if [ "$seconds" -lt 10 ] || [ "$seconds" -gt 86400 ]; then
    echo 'Duration must be 10..86400 seconds' >&2
    exit 2
fi
repo=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
lock="${TMPDIR:-/tmp}/boba-straw-stability-run.lock"
if ! mkdir "$lock" 2>/dev/null; then
    echo "A stability run owns $lock; inspect it before starting another run" >&2
    exit 2
fi
trap 'rmdir "$lock"' EXIT
validate_fixture() {
    name=$1
    expected_image=$2
    expected_port=$3
    test "$(docker inspect --format '{{.State.Running}}' "$name")" = true
    test "$(docker inspect --format '{{.Config.Image}}' "$name")" = "$expected_image"
    test "$(docker port "$name" 6379/tcp)" = "127.0.0.1:$expected_port"
}
validate_fixture boba-straw-redis-5 redis:5.0.14 16379
validate_fixture boba-straw-redis-6 redis:6.2.14 16380
validate_fixture boba-straw-redis-7 redis:7.4.2 16381
validate_fixture boba-straw-valkey valkey/valkey:8.1.3 16382
output=$(mktemp -d "${TMPDIR:-/tmp}/boba-straw-soak-XXXXXX")
echo "Soak evidence: $output"
mkdir "$output/source"
rsync -a --exclude=.git --exclude=target --exclude=benchmark-results --exclude=.idea \
    --exclude=.vscode "$repo/" "$output/source/"
(
    cd "$output/source"
    find . -type f -exec shasum -a 256 {} +
) >"$output/source-sha256.txt"
{
    date -u
    uname -a
    java -version
    mvn -version
    git -C "$repo" rev-parse HEAD
    git -C "$repo" status --short
    echo "seconds=$seconds"
    echo '8 persistent clients, 4 servers x RESP2/AUTO; 100ms pause/cycle/worker'
    docker inspect --format '{{.Name}} {{.Config.Image}} {{.Image}}' \
        boba-straw-redis-5 boba-straw-redis-6 boba-straw-redis-7 boba-straw-valkey
} >"$output/environment.txt" 2>&1
status=0
(
    cd "$output/source"
    mvn --batch-mode -pl boba-straw-core -am test -Dtest=StabilitySoakTest \
        -Dsurefire.failIfNoSpecifiedTests=false -Dboba.straw.runSoak=true \
        -Dboba.straw.soakSeconds="$seconds" -Dboba.straw.soakSamples="$output/samples.tsv" \
        '-DargLine=-Xms128m -Xmx256m'
) >"$output/maven.log" 2>&1 || status=$?
printf '%s\n' "$status" >"$output/exit-code.txt"
if [ -d "$output/source/boba-straw-core/target/surefire-reports" ]; then
    cp -R "$output/source/boba-straw-core/target/surefire-reports" "$output/reports"
fi
echo "Soak exited $status; evidence retained at $output"
if [ "$status" -ne 0 ]; then
    tail -80 "$output/maven.log" >&2
fi
exit "$status"
