#!/usr/bin/env sh
#
# Usage: ./scripts/run-benchmarks.sh [smoke|full] [redis|valkey|codec|all] [output-dir]
#
set -eu

profile=${1:-smoke}
target=${2:-all}
run_id=$(date -u +%Y%m%dT%H%M%SZ)
output_dir=${3:-benchmark-results/$run_id}
benchmark_jar=boba-straw-benchmarks/target/benchmarks.jar
redis_image="redis:7.4.2@sha256:fbdbaea47b9ae4ecc2082ecdb4e1cea81e32176ffb1dcf643d422ad07427e5d9"
valkey_image="valkey/valkey:8.1.3@sha256:fea8b3e67b15729d4bb70589eb03367bab9ad1ee89c876f54327fc7c6e618571"

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
        redis)
            require_full_container boba-straw-benchmark-redis 17379 "$redis_image"
            ;;
        valkey)
            require_full_container boba-straw-benchmark-valkey 17380 "$valkey_image"
            ;;
        all)
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
    java -jar "$benchmark_jar" \
        '.*RespCodecBenchmark.*' \
        $common_options $profiler_options \
        -bm thrpt -tu s -foe true \
        -rf json -rff "$output_dir/codec-throughput.json"
}

run_network() {
    label=$1
    endpoint=$2

    java -jar "$benchmark_jar" \
        '.*(Redis(Command|Batch|LargeValue)Benchmark|AsyncWindowBenchmark).*' \
        -p endpoint="$endpoint" -p protocol=AUTO \
        $common_options $profiler_options \
        -bm thrpt -tu s -foe true \
        -rf json -rff "$output_dir/$label-throughput.json"

    java -jar "$benchmark_jar" \
        '.*(Redis(Command|Batch|LargeValue)Benchmark|AsyncWindowBenchmark|SharedEventLoopFairnessBenchmark|SlowCallbackIsolationBenchmark).*' \
        -p endpoint="$endpoint" -p protocol=AUTO \
        $common_options $profiler_options \
        -bm sample -tu us -foe true \
        -rf json -rff "$output_dir/$label-latency.json"
}

case "$target" in
    codec)
        run_codec
        ;;
    redis)
        run_network redis redis://127.0.0.1:17379
        ;;
    valkey)
        run_network valkey redis://127.0.0.1:17380
        ;;
    all)
        run_codec
        run_network redis redis://127.0.0.1:17379
        run_network valkey redis://127.0.0.1:17380
        ;;
esac

echo "Benchmark artifacts: $output_dir"
