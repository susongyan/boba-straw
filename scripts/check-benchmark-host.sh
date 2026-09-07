#!/usr/bin/env sh
#
# Refuses a formal network benchmark when the host is already saturated.
# Set BOBA_BENCHMARK_MAX_LOAD_PER_CPU=0 only for an explicitly non-comparable run.

set -eu

max_load_per_cpu=${BOBA_BENCHMARK_MAX_LOAD_PER_CPU:-1.50}

case "$(uname -s)" in
    Darwin)
        logical_cpus=$(sysctl -n hw.logicalcpu)
        load_one=$(sysctl -n vm.loadavg | awk '{ print $2 }')
        ;;
    Linux)
        logical_cpus=$(getconf _NPROCESSORS_ONLN)
        load_one=$(awk '{ print $1 }' /proc/loadavg)
        ;;
    *)
        echo "Unsupported host for benchmark load preflight: $(uname -s)" >&2
        exit 2
        ;;
esac

load_per_cpu=$(awk -v load="$load_one" -v cpus="$logical_cpus" \
    'BEGIN { printf "%.3f", load / cpus }')

echo "benchmark_host_logical_cpus=$logical_cpus"
echo "benchmark_host_load_1m=$load_one"
echo "benchmark_host_load_per_cpu=$load_per_cpu"
echo "benchmark_host_max_load_per_cpu=$max_load_per_cpu"

if awk -v maximum="$max_load_per_cpu" 'BEGIN { exit !(maximum <= 0) }'; then
    echo "benchmark_host_load_check=disabled"
    exit 0
fi

if awk -v actual="$load_per_cpu" -v maximum="$max_load_per_cpu" \
    'BEGIN { exit !(actual > maximum) }'; then
    echo "Host load is too high for a comparable formal network benchmark." >&2
    echo "Wait for load/per-CPU <= $max_load_per_cpu, or use a smoke run." >&2
    exit 2
fi

echo "benchmark_host_load_check=passed"
