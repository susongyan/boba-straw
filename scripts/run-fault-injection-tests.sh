#!/usr/bin/env sh
#
# Usage: ./scripts/run-fault-injection-tests.sh [output-dir]
#
# Runs the deterministic, JDK-only socket fault suite. When output-dir is supplied,
# the environment, Maven log and Surefire XML reports are retained for review.

set -eu

output_dir=${1:-}
report_dir=boba-straw-core/target/surefire-reports

if [ -n "$output_dir" ]; then
    if [ -e "$output_dir" ]; then
        echo "Fault-injection output already exists: $output_dir" >&2
        exit 2
    fi
    mkdir -p "$output_dir"
    {
        echo "timestamp_utc=$(date -u +%Y%m%dT%H%M%SZ)"
        echo "git_commit=$(git rev-parse HEAD)"
        echo "git_status_begin"
        git status --short
        echo "git_status_end"
        uname -a
        java -version
        mvn -version
        echo "junit_tag=fault-injection"
    } >"$output_dir/environment.txt" 2>&1
    log_file=$output_dir/maven.log
else
    log_file=/dev/stdout
fi

set +e
mvn -pl boba-straw-core -am clean test -Dgroups=fault-injection >"$log_file" 2>&1
test_status=$?
set -e

if [ -n "$output_dir" ] && [ -d "$report_dir" ]; then
    for report in "$report_dir"/TEST-*.xml; do
        if [ -f "$report" ]; then
            cp "$report" "$output_dir/"
        fi
    done
fi

if [ "$test_status" -ne 0 ]; then
    if [ -n "$output_dir" ]; then
        tail -100 "$log_file" >&2
    fi
    exit "$test_status"
fi

if [ -n "$output_dir" ]; then
    echo "Fault-injection reports written to $output_dir"
else
    echo "Fault-injection suite passed"
fi
