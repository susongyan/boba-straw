#!/usr/bin/env sh
#
# Builds one JMH harness against the baseline public API and two replaceable Core JARs.
# Usage: prepare-ab-benchmarks.sh <baseline-ref> <candidate-ref> <harness-ref> <artifact-dir>
#
set -eu

baseline_ref=${1:-ca078f4}
candidate_ref=${2:-HEAD}
harness_ref=${3:-HEAD}
run_id=$(date -u +%Y%m%dT%H%M%SZ)
artifact_dir=${4:-benchmark-results/ab-artifacts/$run_id}

baseline_sha=$(git rev-parse --verify "$baseline_ref^{commit}")
candidate_sha=$(git rev-parse --verify "$candidate_ref^{commit}")
harness_sha=$(git rev-parse --verify "$harness_ref^{commit}")

if [ -e "$artifact_dir" ]; then
    echo "A/B artifact directory already exists: $artifact_dir" >&2
    exit 2
fi

artifact_parent=$(dirname "$artifact_dir")
mkdir -p "$artifact_parent"
mkdir "$artifact_dir"

temporary_root=$(mktemp -d "${TMPDIR:-/tmp}/boba-straw-ab.XXXXXX")
cleanup() {
    case "$temporary_root" in
        */boba-straw-ab.*)
            rm -rf -- "$temporary_root"
            ;;
        *)
            echo "Refusing to clean unexpected temporary path: $temporary_root" >&2
            ;;
    esac
}
trap cleanup EXIT HUP INT TERM

baseline_tree=$temporary_root/baseline
candidate_tree=$temporary_root/candidate
harness_tree=$temporary_root/harness
mkdir -p "$baseline_tree" "$candidate_tree" "$harness_tree" "$temporary_root/m2"

export_tree() {
    commit=$1
    destination=$2
    archive=$3
    git archive --format=tar --output="$archive" "$commit"
    tar -xf "$archive" -C "$destination"
}

export_tree "$baseline_sha" "$baseline_tree" "$temporary_root/baseline.tar"
export_tree "$candidate_sha" "$candidate_tree" "$temporary_root/candidate.tar"
export_tree "$harness_sha" "$harness_tree" "$temporary_root/harness.tar"

cp -R "$harness_tree/boba-straw-benchmarks/." \
    "$baseline_tree/boba-straw-benchmarks/"

if ! (
    cd "$baseline_tree"
    mvn -Dmaven.repo.local="$temporary_root/m2" \
        -Pthin-harness \
        -pl boba-straw-benchmarks -am \
        -Dmaven.test.skip=true clean package
) >"$artifact_dir/baseline-build.log" 2>&1; then
    tail -100 "$artifact_dir/baseline-build.log" >&2
    exit 1
fi

if ! (
    cd "$candidate_tree"
    mvn -Dmaven.repo.local="$temporary_root/m2" \
        -pl boba-straw-core -am \
        -Dmaven.test.skip=true clean package
) >"$artifact_dir/candidate-build.log" 2>&1; then
    tail -100 "$artifact_dir/candidate-build.log" >&2
    exit 1
fi

set -- "$baseline_tree"/boba-straw-core/target/boba-straw-core-*.jar
if [ "$#" -ne 1 ] || [ ! -f "$1" ]; then
    echo "Expected exactly one baseline Core JAR." >&2
    exit 2
fi
baseline_core=$1

set -- "$candidate_tree"/boba-straw-core/target/boba-straw-core-*.jar
if [ "$#" -ne 1 ] || [ ! -f "$1" ]; then
    echo "Expected exactly one candidate Core JAR." >&2
    exit 2
fi
candidate_core=$1

harness_jar=$baseline_tree/boba-straw-benchmarks/target/benchmarks-harness.jar
if [ ! -f "$harness_jar" ]; then
    echo "Thin benchmark harness was not produced." >&2
    exit 2
fi

jar tf "$harness_jar" >"$temporary_root/harness-contents.txt"
if grep -q 'io/github/susongyan/bobastraw/BobaStrawClient.class' \
    "$temporary_root/harness-contents.txt"; then
    echo "Thin harness unexpectedly contains Boba Straw Core classes." >&2
    exit 2
fi
if ! grep -q 'org/openjdk/jmh/Main.class' "$temporary_root/harness-contents.txt"; then
    echo "Thin harness does not contain the JMH runtime." >&2
    exit 2
fi

cp "$baseline_core" "$artifact_dir/baseline-core.jar"
cp "$candidate_core" "$artifact_dir/candidate-core.jar"
cp "$harness_jar" "$artifact_dir/benchmarks-harness.jar"

java -cp "$artifact_dir/baseline-core.jar:$artifact_dir/benchmarks-harness.jar" \
    org.openjdk.jmh.Main -l >"$artifact_dir/baseline-benchmarks.txt"
java -cp "$artifact_dir/candidate-core.jar:$artifact_dir/benchmarks-harness.jar" \
    org.openjdk.jmh.Main -l >"$artifact_dir/candidate-benchmarks.txt"
cmp "$artifact_dir/baseline-benchmarks.txt" "$artifact_dir/candidate-benchmarks.txt"

record_sha256() {
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$@"
    else
        sha256sum "$@"
    fi
}

{
    echo "timestamp_utc=$run_id"
    echo "baseline_ref=$baseline_ref"
    echo "baseline_sha=$baseline_sha"
    echo "candidate_ref=$candidate_ref"
    echo "candidate_sha=$candidate_sha"
    echo "harness_ref=$harness_ref"
    echo "harness_sha=$harness_sha"
    java -version
    mvn -version
    echo "artifact_sha256_begin"
    record_sha256 \
        "$artifact_dir/baseline-core.jar" \
        "$artifact_dir/candidate-core.jar" \
        "$artifact_dir/benchmarks-harness.jar"
    echo "artifact_sha256_end"
} >"$artifact_dir/build-manifest.txt" 2>&1

echo "A/B benchmark artifacts: $artifact_dir"
