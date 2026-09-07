#!/usr/bin/env sh

benchmark_run_lock_dir=${TMPDIR:-/tmp}/boba-straw-benchmark-run.lock

acquire_benchmark_run_lock() {
    if ! mkdir "$benchmark_run_lock_dir" 2>/dev/null; then
        echo "Another Boba Straw benchmark run is active." >&2
        echo "Lock: $benchmark_run_lock_dir" >&2
        echo "If no benchmark process exists, remove only this stale lock directory." >&2
        exit 2
    fi
}

release_benchmark_run_lock() {
    rmdir "$benchmark_run_lock_dir" 2>/dev/null || true
}
