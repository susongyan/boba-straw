#!/usr/bin/env sh
#
# Usage: ./scripts/run-benchmarks.sh [smoke|full] [target] [output-dir]
# Targets: redis, redis-binary-large, redis-observe, valkey, valkey-binary-large,
#          valkey-observe, codec, all
#
set -eu

. ./scripts/benchmark-run-lock.sh

profile=${1:-smoke}
target=${2:-all}
run_id=$(date -u +%Y%m%dT%H%M%SZ)
output_dir=${3:-benchmark-results/$run_id}
benchmark_jar=boba-straw-benchmarks/target/benchmarks.jar
redis_image="redis:7.4.2@sha256:fbdbaea47b9ae4ecc2082ecdb4e1cea81e32176ffb1dcf643d422ad07427e5d9"
valkey_image="valkey/valkey:8.1.3@sha256:fea8b3e67b15729d4bb70589eb03367bab9ad1ee89c876f54327fc7c6e618571"
benchmark_host_preflight=not_run

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
    redis|redis-binary-large|redis-observe|valkey|valkey-binary-large|valkey-observe|codec|all)
        ;;
    *)
        echo "Unknown target: $target" >&2
        echo "Expected redis, redis-binary-large, redis-observe, valkey," >&2
        echo "valkey-binary-large, valkey-observe, codec, or all." >&2
        exit 2
        ;;
esac

acquire_benchmark_run_lock
trap release_benchmark_run_lock EXIT HUP INT TERM

require_full_container() {
    container_name=$1
    host_port=$2
    image=$3

    if ! command -v docker >/dev/null 2>&1 || ! docker info >/dev/null 2>&1; then
        echo "Full network benchmarks require the pinned Docker environment." >&2
        echo "Start it with ./scripts/benchmark-up.sh." >&2
        exit 2
    fi
    if ! docker container inspect "$container_name" >/dev/null 2>&1; then
        echo "Required benchmark container is missing: $container_name" >&2
        echo "Start it with ./scripts/benchmark-up.sh." >&2
        exit 2
    fi

    expected_image_id=$(docker image inspect --format '{{.Id}}' "$image" 2>/dev/null || true)
    actual_image_id=$(docker container inspect --format '{{.Image}}' "$container_name")
    actual_port=$(docker port "$container_name" 6379/tcp)
    actual_state=$(docker container inspect --format '{{.State.Running}}/{{.State.Health.Status}}' "$container_name")
    actual_cpus=$(docker container inspect --format '{{.HostConfig.NanoCpus}}' "$container_name")
    actual_memory=$(docker container inspect --format '{{.HostConfig.Memory}}' "$container_name")
    if [ -z "$expected_image_id" ] \
        || [ "$actual_image_id" != "$expected_image_id" ] \
        || [ "$actual_port" != "127.0.0.1:$host_port" ] \
        || [ "$actual_state" != "true/healthy" ] \
        || [ "$actual_cpus" != "2000000000" ] \
        || [ "$actual_memory" != "2147483648" ]; then
        echo "Unexpected configuration for $container_name." >&2
        echo "Run ./scripts/benchmark-down.sh followed by ./scripts/benchmark-up.sh." >&2
        exit 2
    fi
}

initial_git_status=$(git status --short)
if [ "$profile" = "full" ] && [ -n "$initial_git_status" ] \
    && [ "${BOBA_BENCHMARK_ALLOW_DIRTY:-0}" != "1" ]; then
    echo "Full benchmarks require a clean Git worktree." >&2
    echo "Commit or stash changes, or explicitly set BOBA_BENCHMARK_ALLOW_DIRTY=1." >&2
    exit 2
fi

if [ "$profile" = "full" ]; then
    case "$target" in
        redis|redis-binary-large|redis-observe)
            benchmark_host_preflight=$(./scripts/check-benchmark-host.sh)
            printf '%s\n' "$benchmark_host_preflight"
            require_full_container boba-straw-benchmark-redis 17379 "$redis_image"
            ;;
        valkey|valkey-binary-large|valkey-observe)
            benchmark_host_preflight=$(./scripts/check-benchmark-host.sh)
            printf '%s\n' "$benchmark_host_preflight"
            require_full_container boba-straw-benchmark-valkey 17380 "$valkey_image"
            ;;
        all)
            benchmark_host_preflight=$(./scripts/check-benchmark-host.sh)
            printf '%s\n' "$benchmark_host_preflight"
            require_full_container boba-straw-benchmark-redis 17379 "$redis_image"
            require_full_container boba-straw-benchmark-valkey 17380 "$valkey_image"
            ;;
    esac
fi

if [ -e "$output_dir" ]; then
    echo "Benchmark output already exists: $output_dir" >&2
    echo "Choose a new run directory so raw results cannot be overwritten." >&2
    exit 2
fi

mvn -pl boba-straw-benchmarks -am -DskipTests clean package

set -- boba-straw-core/target/boba-straw-core-*.jar
if [ "$#" -ne 1 ] || [ ! -f "$1" ]; then
    echo "Expected exactly one Boba Straw core JAR after a clean build." >&2
    exit 2
fi
core_jar=$1

record_sha256() {
    file=$1
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$file"
    else
        sha256sum "$file"
    fi
}

output_parent=$(dirname "$output_dir")
mkdir -p "$output_parent"
mkdir "$output_dir"

{
    echo "timestamp_utc=$run_id"
    echo "profile=$profile"
    echo "target=$target"
    echo "git_commit=$(git rev-parse HEAD)"
    echo "git_status_begin"
    if [ -n "$initial_git_status" ]; then
        echo "$initial_git_status"
    fi
    echo "git_status_end"
    uname -a
    java -version
    mvn -version
    echo "jmh_version=1.37"
    echo "jmh_common_options=$common_options"
    echo "jmh_profiler_options=$profiler_options"
    echo "benchmark_host_preflight_begin"
    printf '%s\n' "$benchmark_host_preflight"
    echo "benchmark_host_preflight_end"
} >"$output_dir/environment.txt" 2>&1

{
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
    else
        echo "docker_environment=unavailable"
    fi
} >>"$output_dir/environment.txt" 2>&1

{
    echo "artifact_sha256_begin"
    record_sha256 "$core_jar"
    record_sha256 "$benchmark_jar"
    echo "artifact_sha256_end"
} >>"$output_dir/environment.txt" 2>&1

run_codec() {
    log_file=$output_dir/codec-throughput.log
    echo "Running codec throughput benchmark"
    if ! java -jar "$benchmark_jar" \
        '.*RespCodecBenchmark.*' \
        $common_options $profiler_options \
        -bm thrpt -tu s -foe true \
        -rf json -rff "$output_dir/codec-throughput.json" \
        >"$log_file" 2>&1; then
        tail -100 "$log_file" >&2
        return 1
    fi
}

run_network() {
    label=$1
    endpoint=$2

    throughput_log=$output_dir/$label-throughput.log
    echo "Running $label throughput benchmarks"
    if ! java -jar "$benchmark_jar" \
        '.*(Redis(Command|Batch|LargeValue|BinaryLargeValue)Benchmark|AsyncWindowBenchmark).*' \
        -p endpoint="$endpoint" -p protocol=AUTO \
        $common_options $profiler_options \
        -bm thrpt -tu s -foe true \
        -rf json -rff "$output_dir/$label-throughput.json" \
        >"$throughput_log" 2>&1; then
        tail -100 "$throughput_log" >&2
        return 1
    fi

    latency_log=$output_dir/$label-latency.log
    echo "Running $label sample-time benchmarks"
    if ! java -jar "$benchmark_jar" \
        '.*(Redis(Command|Batch|LargeValue|BinaryLargeValue)Benchmark|AsyncWindowBenchmark|SharedEventLoopFairnessBenchmark|SlowCallbackIsolationBenchmark).*' \
        -p endpoint="$endpoint" -p protocol=AUTO \
        $common_options $profiler_options \
        -bm sample -tu us -foe true \
        -rf json -rff "$output_dir/$label-latency.json" \
        >"$latency_log" 2>&1; then
        tail -100 "$latency_log" >&2
        return 1
    fi
}

run_binary_large() {
    label=$1
    endpoint=$2

    throughput_log=$output_dir/$label-throughput.log
    echo "Running $label throughput benchmarks"
    if ! java -jar "$benchmark_jar" \
        '.*RedisBinaryLargeValueBenchmark.*' \
        -p endpoint="$endpoint" -p protocol=AUTO \
        $common_options $profiler_options \
        -bm thrpt -tu s -foe true \
        -rf json -rff "$output_dir/$label-throughput.json" \
        >"$throughput_log" 2>&1; then
        tail -100 "$throughput_log" >&2
        return 1
    fi

    latency_log=$output_dir/$label-latency.log
    echo "Running $label sample-time benchmarks"
    if ! java -jar "$benchmark_jar" \
        '.*RedisBinaryLargeValueBenchmark.*' \
        -p endpoint="$endpoint" -p protocol=AUTO \
        $common_options $profiler_options \
        -bm sample -tu us -foe true \
        -rf json -rff "$output_dir/$label-latency.json" \
        >"$latency_log" 2>&1; then
        tail -100 "$latency_log" >&2
        return 1
    fi
}

sample_client_and_server() {
    launcher_pid=$1
    container_name=$2
    samples_file=$3

    client_pid=$launcher_pid
    if command -v pgrep >/dev/null 2>&1; then
        child_pid=$(pgrep -P "$launcher_pid" 2>/dev/null | head -n 1 || true)
        if [ -n "$child_pid" ]; then
            client_pid=$child_pid
        fi
    fi

    client_cpu_rss=$(ps -o pcpu= -o rss= -p "$client_pid" 2>/dev/null \
        | awk 'NR == 1 { print $1 "\t" $2 }')
    if [ "$(uname -s)" = "Darwin" ]; then
        client_threads=$(ps -M -p "$client_pid" 2>/dev/null \
            | awk 'NR > 1 { count++ } END { print count + 0 }')
    elif ps -o nlwp= -p "$client_pid" >/dev/null 2>&1; then
        client_threads=$(ps -o nlwp= -p "$client_pid" | awk 'NR == 1 { print $1 }')
    else
        client_threads=NA
    fi
    if [ -z "$client_cpu_rss" ]; then
        client_stats=$(printf 'NA\tNA\tNA')
    else
        client_stats=$(printf '%s\t%s' "$client_cpu_rss" "$client_threads")
    fi

    server_stats=$(docker stats --no-stream \
        --format '{{.CPUPerc}}\t{{.MemUsage}}\t{{.PIDs}}' \
        "$container_name" 2>/dev/null || true)
    if [ -z "$server_stats" ]; then
        server_stats=$(printf 'NA\tNA\tNA')
    fi
    server_network=$(docker exec "$container_name" cat /proc/1/net/dev 2>/dev/null \
        | awk '$1 ~ /eth0:/ { print $2 "\t" $10 }')
    if [ -z "$server_network" ]; then
        server_network=$(printf 'NA\tNA')
    fi

    printf '%s\t%s\t%s\t%s\t%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
        "$client_pid" "$client_stats" "$server_stats" "$server_network" >>"$samples_file"
}

run_observation() {
    label=$1
    endpoint=$2
    container_name=$3

    log_file=$output_dir/$label-observation.log
    result_file=$output_dir/$label-observation.json
    samples_file=$output_dir/$label-system-samples.tsv
    printf 'timestamp_utc\tclient_pid\tclient_cpu_percent\tclient_rss_kib\tclient_threads\tserver_cpu_percent\tserver_memory_usage\tserver_pids\tserver_network_rx_bytes\tserver_network_tx_bytes\n' \
        >"$samples_file"

    echo "Running $label transport and system observation"
    java -jar "$benchmark_jar" \
        '.*TransportObservationBenchmark.*' \
        -p endpoint="$endpoint" -p protocol=AUTO \
        $common_options $profiler_options \
        -bm thrpt -tu s -foe true \
        -rf json -rff "$result_file" \
        >"$log_file" 2>&1 &
    observation_pid=$!
    trap 'stop_observation; release_benchmark_run_lock' HUP INT TERM EXIT

    while kill -0 "$observation_pid" >/dev/null 2>&1; do
        sample_client_and_server "$observation_pid" "$container_name" "$samples_file"
        sleep 1
    done

    observation_status=0
    wait "$observation_pid" || observation_status=$?
    observation_pid=
    trap release_benchmark_run_lock HUP INT TERM EXIT
    if [ "$observation_status" -ne 0 ]; then
        tail -100 "$log_file" >&2
        return "$observation_status"
    fi
}

stop_observation() {
    if [ -z "${observation_pid:-}" ]; then
        return
    fi
    if command -v pgrep >/dev/null 2>&1; then
        observation_children=$(pgrep -P "$observation_pid" 2>/dev/null || true)
        for observation_child in $observation_children; do
            kill "$observation_child" >/dev/null 2>&1 || true
        done
    fi
    kill "$observation_pid" >/dev/null 2>&1 || true
}

case "$target" in
    codec)
        run_codec
        ;;
    redis)
        run_network redis redis://127.0.0.1:17379
        ;;
    redis-binary-large)
        run_binary_large redis-binary-large redis://127.0.0.1:17379
        ;;
    redis-observe)
        run_observation redis redis://127.0.0.1:17379 boba-straw-benchmark-redis
        ;;
    valkey)
        run_network valkey redis://127.0.0.1:17380
        ;;
    valkey-binary-large)
        run_binary_large valkey-binary-large redis://127.0.0.1:17380
        ;;
    valkey-observe)
        run_observation valkey redis://127.0.0.1:17380 boba-straw-benchmark-valkey
        ;;
    all)
        run_codec
        run_network redis redis://127.0.0.1:17379
        run_network valkey redis://127.0.0.1:17380
        ;;
esac

echo "Benchmark artifacts: $output_dir"
