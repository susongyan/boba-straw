#!/usr/bin/env sh
#
# Runs the same thin JMH harness against baseline and candidate Core JARs in ABBA order.
# Usage: run-ab-benchmarks.sh <smoke|full> <redis|redis-critical|valkey|codec|all> <result-dir> [baseline-ref] [candidate-ref] [harness-ref]
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
    redis|redis-critical|valkey|codec|all)
        ;;
    *)
        echo "Unknown target: $target (expected redis, redis-critical, valkey, codec, or all)" >&2
        exit 2
        ;;
esac

if [ -e "$result_dir" ]; then
    echo "A/B result directory already exists: $result_dir" >&2
    exit 2
fi

case "$target" in
    redis|redis-critical|valkey|all)
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
    echo "timestamp_utc=$run_id"
    echo "profile=$profile"
    echo "target=$target"
    echo "jmh_common_options=$common_options"
    echo "jmh_profiler_options=$profiler_options"
    uname -a
    if command -v colima >/dev/null 2>&1; then
        echo "colima_status_begin"
        colima status || true
        echo "colima_status_end"
    fi
    if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
        echo "docker_version_begin"
        docker version
        echo "docker_version_end"
        for container_name in \
            boba-straw-benchmark-redis \
            boba-straw-benchmark-valkey
        do
            if docker container inspect "$container_name" >/dev/null 2>&1; then
                echo "container_begin=$container_name"
                docker container inspect --format \
                    'image={{.Config.Image}} image_id={{.Image}} cpus={{.HostConfig.NanoCpus}} memory={{.HostConfig.Memory}}' \
                    "$container_name"
                case "$container_name" in
                    boba-straw-benchmark-redis)
                        docker exec "$container_name" redis-cli INFO server
                        ;;
                    boba-straw-benchmark-valkey)
                        docker exec "$container_name" valkey-cli INFO server
                        ;;
                esac
                echo "container_end=$container_name"
            fi
        done
    fi
} >"$result_dir/environment.txt" 2>&1

{
    echo "01=baseline"
    echo "02=candidate"
    echo "03=candidate"
    echo "04=baseline"
} >"$result_dir/run-order.txt"

run_codec() {
    core_jar=$1
    destination=$2
    log_file=$destination/codec-throughput.log
    echo "Running $destination codec throughput"
    if ! java -cp "$core_jar:$harness_jar" org.openjdk.jmh.Main \
        '.*RespCodecBenchmark.*' \
        $common_options $profiler_options \
        -bm thrpt -tu s -foe true \
        -rf json -rff "$destination/codec-throughput.json" \
        >"$log_file" 2>&1; then
        tail -100 "$log_file" >&2
        return 1
    fi
}

run_network() {
    core_jar=$1
    destination=$2
    label=$3
    endpoint=$4

    throughput_log=$destination/$label-throughput.log
    echo "Running $destination $label throughput"
    if ! java -cp "$core_jar:$harness_jar" org.openjdk.jmh.Main \
        '.*(Redis(Command|Batch|LargeValue)Benchmark|AsyncWindowBenchmark).*' \
        -p endpoint="$endpoint" -p protocol=AUTO \
        $common_options $profiler_options \
        -bm thrpt -tu s -foe true \
        -rf json -rff "$destination/$label-throughput.json" \
        >"$throughput_log" 2>&1; then
        tail -100 "$throughput_log" >&2
        return 1
    fi

    latency_log=$destination/$label-latency.log
    echo "Running $destination $label sample-time"
    if ! java -cp "$core_jar:$harness_jar" org.openjdk.jmh.Main \
        '.*(Redis(Command|Batch|LargeValue)Benchmark|AsyncWindowBenchmark|SharedEventLoopFairnessBenchmark|SlowCallbackIsolationBenchmark).*' \
        -p endpoint="$endpoint" -p protocol=AUTO \
        $common_options $profiler_options \
        -bm sample -tu us -foe true \
        -rf json -rff "$destination/$label-latency.json" \
        >"$latency_log" 2>&1; then
        tail -100 "$latency_log" >&2
        return 1
    fi
}

run_critical_network() {
    core_jar=$1
    destination=$2
    endpoint=redis://127.0.0.1:17379

    throughput_log=$destination/redis-critical-throughput.log
    echo "Running $destination Redis critical throughput"
    if ! java -cp "$core_jar:$harness_jar" org.openjdk.jmh.Main \
        '.*(AsyncWindowBenchmark.asyncGetWindow1024|RedisBatchBenchmark.pipeline128).*' \
        -p endpoint="$endpoint" -p protocol=AUTO \
        $common_options $profiler_options \
        -bm thrpt -tu s -foe true \
        -rf json -rff "$destination/redis-critical-throughput.json" \
        >"$throughput_log" 2>&1; then
        tail -100 "$throughput_log" >&2
        return 1
    fi

    latency_log=$destination/redis-critical-latency.log
    echo "Running $destination Redis critical sample-time"
    if ! java -cp "$core_jar:$harness_jar" org.openjdk.jmh.Main \
        '.*(RedisBatchBenchmark.pipeline128|RedisCommandBenchmark.syncGet|SharedEventLoopFairnessBenchmark|SlowCallbackIsolationBenchmark).*' \
        -p endpoint="$endpoint" -p protocol=AUTO \
        $common_options $profiler_options \
        -bm sample -tu us -foe true \
        -rf json -rff "$destination/redis-critical-latency.json" \
        >"$latency_log" 2>&1; then
        tail -100 "$latency_log" >&2
        return 1
    fi
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
        redis-critical)
            run_critical_network "$core_jar" "$destination"
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
