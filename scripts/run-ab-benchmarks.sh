#!/usr/bin/env sh
#
# Runs the same thin JMH harness against baseline and candidate Core JARs in ABBA order.
# Usage: run-ab-benchmarks.sh <smoke|full> <redis|valkey|codec|all> <result-dir> [baseline-ref] [candidate-ref] [harness-ref]
#
set -eu

profile=${1:-smoke}
target=${2:-codec}
run_id=$(date -u +%Y%m%dT%H%M%SZ)
result_dir=${3:-benchmark-results/ab-$run_id}
baseline_ref=${4:-ca078f4}
candidate_ref=${5:-HEAD}
harness_ref=${6:-HEAD}

case "$profile" in
    smoke)
        common_options="-wi 1 -w 1s -i 1 -r 1s -f 1 -t 1"
        profiler_options=""
        ;;
    full)
        common_options="-wi 5 -w 2s -i 8 -r 2s -f 3 -t 1"
        profiler_options="-prof gc"
        ;;
    *)
        echo "Unknown profile: $profile (expected smoke or full)" >&2
        exit 2
        ;;
esac

case "$target" in
    redis|valkey|codec|all)
        ;;
    *)
        echo "Unknown target: $target (expected redis, valkey, codec, or all)" >&2
        exit 2
        ;;
esac

if [ -e "$result_dir" ]; then
    echo "A/B result directory already exists: $result_dir" >&2
    exit 2
fi

case "$target" in
    redis|valkey|all)
        ./scripts/benchmark-up.sh
        ;;
esac

result_parent=$(dirname "$result_dir")
mkdir -p "$result_parent"
mkdir "$result_dir"

artifact_dir=$result_dir/artifacts
./scripts/prepare-ab-benchmarks.sh \
    "$baseline_ref" "$candidate_ref" "$harness_ref" "$artifact_dir"

harness_jar=$artifact_dir/benchmarks-harness.jar
baseline_core=$artifact_dir/baseline-core.jar
candidate_core=$artifact_dir/candidate-core.jar

{
    echo "profile=$profile"
    echo "target=$target"
    echo "jmh_common_options=$common_options"
    echo "jmh_profiler_options=$profiler_options"
    echo "01=baseline"
    echo "02=candidate"
    echo "03=candidate"
    echo "04=baseline"
} >"$result_dir/run-order.txt"

run_codec() {
    core_jar=$1
    destination=$2
    java -cp "$core_jar:$harness_jar" org.openjdk.jmh.Main \
        '.*RespCodecBenchmark.*' \
        $common_options $profiler_options \
        -bm thrpt -tu s -foe true \
        -rf json -rff "$destination/codec-throughput.json"
}

run_network() {
    core_jar=$1
    destination=$2
    label=$3
    endpoint=$4

    java -cp "$core_jar:$harness_jar" org.openjdk.jmh.Main \
        '.*(Redis(Command|Batch|LargeValue)Benchmark|AsyncWindowBenchmark).*' \
        -p endpoint="$endpoint" -p protocol=AUTO \
        $common_options $profiler_options \
        -bm thrpt -tu s -foe true \
        -rf json -rff "$destination/$label-throughput.json"

    java -cp "$core_jar:$harness_jar" org.openjdk.jmh.Main \
        '.*(Redis(Command|Batch|LargeValue)Benchmark|AsyncWindowBenchmark|SharedEventLoopFairnessBenchmark|SlowCallbackIsolationBenchmark).*' \
        -p endpoint="$endpoint" -p protocol=AUTO \
        $common_options $profiler_options \
        -bm sample -tu us -foe true \
        -rf json -rff "$destination/$label-latency.json"
}

run_variant() {
    sequence=$1
    variant=$2
    core_jar=$3
    destination=$result_dir/$sequence-$variant
    mkdir "$destination"

    case "$target" in
        codec)
            run_codec "$core_jar" "$destination"
            ;;
        redis)
            run_network "$core_jar" "$destination" redis redis://127.0.0.1:17379
            ;;
        valkey)
            run_network "$core_jar" "$destination" valkey redis://127.0.0.1:17380
            ;;
        all)
            run_codec "$core_jar" "$destination"
            run_network "$core_jar" "$destination" redis redis://127.0.0.1:17379
            run_network "$core_jar" "$destination" valkey redis://127.0.0.1:17380
            ;;
    esac
}

run_variant 01 baseline "$baseline_core"
run_variant 02 candidate "$candidate_core"
run_variant 03 candidate "$candidate_core"
run_variant 04 baseline "$baseline_core"

echo "A/B benchmark results: $result_dir"
