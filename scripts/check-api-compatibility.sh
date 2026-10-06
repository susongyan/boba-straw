#!/bin/sh
# Build an explicit trusted Git baseline locally; never infer an unpublished Central version.
set -eu
baseline=${1:?Usage: check-api-compatibility.sh BASELINE_COMMIT}
repo=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
commit=$(git -C "$repo" rev-parse --verify "$baseline^{commit}")
evidence=$(mktemp -d "${TMPDIR:-/tmp}/boba-straw-api-XXXXXX")
echo "Evidence directory: $evidence (baseline $commit)"
mkdir "$evidence/baseline"
git -C "$repo" archive "$commit" | tar -x -C "$evidence/baseline"
(
    cd "$evidence/baseline"
    mvn --batch-mode -pl boba-straw-core -am package -DskipTests
) >"$evidence/baseline.log" 2>&1
set -- "$evidence/baseline/boba-straw-core/target/"boba-straw-core-*.jar
[ "$#" -eq 1 ] || { echo 'Expected exactly one baseline core jar' >&2; exit 1; }
jar=$1
cd "$repo"
mvn --batch-mode -pl boba-straw-core -am -Papi-check -Dboba.api.baseline="$jar" verify -DskipTests \
    >"$evidence/comparison.log" 2>&1 || { tail -50 "$evidence/comparison.log"; exit 1; }
echo "Public core API compatibility passed; reports at $evidence"
