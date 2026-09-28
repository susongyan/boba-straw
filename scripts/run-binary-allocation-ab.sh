#!/usr/bin/env sh
# Focused allocation diagnostic, not a replacement for the full network benchmark matrix.
# Usage: sh scripts/run-binary-allocation-ab.sh baseline.jar candidate.jar thin-harness.jar result-dir
set -eu

. ./scripts/benchmark-run-lock.sh
if [ "$#" -ne 4 ]; then
    echo "Expected baseline Core, candidate Core, thin harness and a new result directory" >&2
    exit 2
fi
baseline_core=$1
candidate_core=$2
harness_jar=$3
result_dir=$4
for artifact in "$baseline_core" "$candidate_core" "$harness_jar"; do
    test -f "$artifact"
done
if [ -e "$result_dir" ]; then
    echo "Result directory already exists: $result_dir" >&2
    exit 2
fi
acquire_benchmark_run_lock
trap release_benchmark_run_lock EXIT HUP INT TERM
mkdir -p "$result_dir"
{
    date -u
    uname -a
    java -version
    sh scripts/check-benchmark-host.sh
    shasum -a 256 "$baseline_core" "$candidate_core" "$harness_jar"
} >"$result_dir/environment.txt" 2>&1

# Uses only the existing dedicated benchmark Redis, never application/test topology endpoints.
for leg in 01-A 02-B 03-B 04-A; do
    case "$leg" in
        *-A) core=$baseline_core ;;
        *-B) core=$candidate_core ;;
    esac
    echo "Running binary allocation diagnostic $leg"
    if ! java -cp "$core:$harness_jar" org.openjdk.jmh.Main \
        '.*RedisBinaryLargeValueBenchmark.set' \
        -p endpoint=redis://127.0.0.1:17379 -p protocol=AUTO \
        -p valueBytes=1024,65536,1048576 \
        -wi 3 -w 1s -i 5 -r 1s -f 1 -t 1 -prof gc -bm thrpt -tu s -foe true \
        -rf json -rff "$result_dir/$leg.json" >"$result_dir/$leg.log" 2>&1; then
        tail -60 "$result_dir/$leg.log" >&2
        exit 1
    fi
done
echo "Binary allocation evidence: $result_dir"
