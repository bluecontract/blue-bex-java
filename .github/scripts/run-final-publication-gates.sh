#!/usr/bin/env bash
set -euo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly BEX_REPOSITORY="$(cd "$SCRIPT_DIR/../.." && pwd)"
readonly INSPECTION_FILE="$BEX_REPOSITORY/src/test/resources/hosted-release/published-api-inspection.properties"
readonly RELEASE_ROOT="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/blue-bex-publication.XXXXXX")"
readonly RECEIPT_ROOT="$RELEASE_ROOT/receipts"
readonly INDEPENDENT_REPORT="$RECEIPT_ROOT/independent-clean-builds.json"
readonly REPEATABILITY_REPORT="$RECEIPT_ROOT/published-repeatability.json"
readonly RETAINED_INPUT_ROOT="$BEX_REPOSITORY/build/reports/bex-release/inputs"
readonly RETAINED_ARTIFACT_ROOT="$RETAINED_INPUT_ROOT/published-artifacts"
readonly BEX_COMMIT="$(git -C "$BEX_REPOSITORY" rev-parse HEAD)"
readonly SOURCE_COMMIT_EPOCH="$(git -C "$BEX_REPOSITORY" show -s --format=%ct "$BEX_COMMIT")"
retain_release_root=false

cleanup() {
  if [[ "$retain_release_root" != true ]]; then
    rm -rf "$RELEASE_ROOT"
  fi
}
trap cleanup EXIT

property_value() {
  local key="$1"
  awk -F= -v requested="$key" '$1 == requested {
    sub(/^[^=]*=/, "")
    print
    exit
  }' "$INSPECTION_FILE"
}

sha256_file() {
  local path="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$path" | awk '{print $1}'
  else
    shasum -a 256 "$path" | awk '{print $1}'
  fi
}

artifact_manifest() {
  local checkout="$1"
  local output="$2"
  local file
  : > "$output"
  while IFS= read -r file; do
    printf '%s  %s\n' \
      "$(sha256_file "$file")" \
      "${file#"$checkout"/}" >> "$output"
  done < <(find \
    "$checkout/blue-bex-core/build/libs" \
    "$checkout/blue-bex-contracts/build/libs" \
    "$checkout/blue-bex-java/build/libs" \
    "$checkout/build/distributions" \
    -type f \( -name '*.jar' -o -name '*.zip' \) -print | LC_ALL=C sort)
  if [[ ! -s "$output" ]]; then
    echo "No release artifacts were produced by $checkout" >&2
    exit 1
  fi
}

clone_bex() {
  local destination="$1"
  git clone --quiet --no-hardlinks "$BEX_REPOSITORY" "$destination"
  git -C "$destination" checkout --quiet --detach "$BEX_COMMIT"
}

run_isolated_build() {
  local checkout="$1"
  local gradle_home="$2"
  local arguments=(
    --no-daemon
    -p "$checkout"
    clean
    assemble
    bexConformance
    sourceReleaseArchive
  )
  GRADLE_USER_HOME="$gradle_home" \
    "$checkout/gradlew" "${arguments[@]}"
}

readonly LANGUAGE_COMMIT="$(property_value source.commit)"
readonly LANGUAGE_COORDINATE="$(property_value coordinate)"
readonly LANGUAGE_COORDINATE_TAIL="${LANGUAGE_COORDINATE#*:}"
readonly LANGUAGE_AGGREGATE_ARTIFACT="${LANGUAGE_COORDINATE_TAIL%%:*}"
readonly LANGUAGE_VERSION="${LANGUAGE_COORDINATE##*:}"
readonly LANGUAGE_ARTIFACT_SHA256="$(property_value artifact.sha256)"
readonly LANGUAGE_API_STATUS="$(property_value status)"
readonly EXPECTED_RELEASE_TAG="v$(sed -n 's/^version = "\([^"]*\)"/\1/p' "$BEX_REPOSITORY/.cz.toml" | head -n 1)"

if [[ "$LANGUAGE_API_STATUS" != "compatible-with-final-hosted-adapter" ]]; then
  echo "Published Language inspection is not compatible: $LANGUAGE_API_STATUS" >&2
  exit 1
fi
if [[ ! "$LANGUAGE_COORDINATE" =~ ^blue\.language:blue-language-java:[0-9A-Za-z][0-9A-Za-z._-]*$ ]]; then
  echo "Published Language aggregate coordinate is invalid: $LANGUAGE_COORDINATE" >&2
  exit 1
fi
if [[ ! "$LANGUAGE_COMMIT" =~ ^[0-9a-fA-F]{40}$ ]]; then
  echo "Published Language source commit is missing." >&2
  exit 1
fi
if [[ ! "$LANGUAGE_ARTIFACT_SHA256" =~ ^[0-9a-f]{64}$ ]]; then
  echo "Published Language artifact SHA-256 is missing." >&2
  exit 1
fi
if [[ -n "$(git -C "$BEX_REPOSITORY" status --porcelain --untracked-files=all)" ]]; then
  echo "Publication requires a completely clean BEX checkout." >&2
  exit 1
fi
if ! git -C "$BEX_REPOSITORY" tag --points-at HEAD | grep -Fxq "$EXPECTED_RELEASE_TAG"; then
  echo "Publication requires exact tag $EXPECTED_RELEASE_TAG at HEAD." >&2
  exit 1
fi

export CI=true
export SOURCE_DATE_EPOCH="$SOURCE_COMMIT_EPOCH"
mkdir -p "$RECEIPT_ROOT"

cd "$BEX_REPOSITORY"

declare -a checkouts=(
  "$RELEASE_ROOT/standalone-one"
  "$RELEASE_ROOT/standalone-two"
  "$RELEASE_ROOT/standalone-three"
  "$RELEASE_ROOT/standalone-four"
)
for checkout in "${checkouts[@]}"; do
  clone_bex "$checkout"
done

run_isolated_build "${checkouts[0]}" "$RELEASE_ROOT/gradle-standalone-one"
run_isolated_build "${checkouts[1]}" "$RELEASE_ROOT/gradle-standalone-two"
run_isolated_build "${checkouts[2]}" "$RELEASE_ROOT/gradle-standalone-three"
run_isolated_build "${checkouts[3]}" "$RELEASE_ROOT/gradle-standalone-four"

readonly STANDALONE_ONE_MANIFEST="$RECEIPT_ROOT/standalone-one.sha256"
readonly STANDALONE_TWO_MANIFEST="$RECEIPT_ROOT/standalone-two.sha256"
readonly STANDALONE_THREE_MANIFEST="$RECEIPT_ROOT/standalone-three.sha256"
readonly STANDALONE_FOUR_MANIFEST="$RECEIPT_ROOT/standalone-four.sha256"
artifact_manifest "${checkouts[0]}" "$STANDALONE_ONE_MANIFEST"
artifact_manifest "${checkouts[1]}" "$STANDALONE_TWO_MANIFEST"
artifact_manifest "${checkouts[2]}" "$STANDALONE_THREE_MANIFEST"
artifact_manifest "${checkouts[3]}" "$STANDALONE_FOUR_MANIFEST"
comparison_failed=false
if ! node "$SCRIPT_DIR/compare-independent-builds.mjs" \
    "$STANDALONE_ONE_MANIFEST" "$STANDALONE_TWO_MANIFEST" \
    "$STANDALONE_THREE_MANIFEST" "$STANDALONE_FOUR_MANIFEST" \
    "${checkouts[0]}" "${checkouts[1]}" \
    "${checkouts[2]}" "${checkouts[3]}" \
    "$RELEASE_ROOT/gradle-standalone-one" \
    "$RELEASE_ROOT/gradle-standalone-two" \
    "$RELEASE_ROOT/gradle-standalone-three" \
    "$RELEASE_ROOT/gradle-standalone-four" \
    "$BEX_COMMIT" "$INDEPENDENT_REPORT"; then
  comparison_failed=true
fi
if ! node "$SCRIPT_DIR/compare-published-conformance-evidence.mjs" \
    "${checkouts[0]}/blue-bex-conformance/build/reports/bex-conformance/report.json" \
    "${checkouts[1]}/blue-bex-conformance/build/reports/bex-conformance/report.json" \
    "${checkouts[2]}/blue-bex-conformance/build/reports/bex-conformance/report.json" \
    "${checkouts[3]}/blue-bex-conformance/build/reports/bex-conformance/report.json" \
    "$BEX_COMMIT" "$REPEATABILITY_REPORT"; then
  comparison_failed=true
fi
if [[ "$comparison_failed" == true ]]; then
  mkdir -p "$RETAINED_INPUT_ROOT"
  [[ ! -f "$INDEPENDENT_REPORT" ]] || cp "$INDEPENDENT_REPORT" \
    "$RETAINED_INPUT_ROOT/independent-clean-builds.json"
  [[ ! -f "$REPEATABILITY_REPORT" ]] || cp "$REPEATABILITY_REPORT" \
    "$RETAINED_INPUT_ROOT/published-repeatability.json"
  echo "Retained failed published-only comparison reports under build/reports." >&2
  exit 1
fi

readonly PUBLISHED_CACHE_ROOT="$RELEASE_ROOT/gradle-standalone-one/caches/modules-2/files-2.1/blue.language"
aggregate_artifacts=()
while IFS= read -r -d '' artifact; do
  aggregate_artifacts+=("$artifact")
done < <(find \
  "$PUBLISHED_CACHE_ROOT/$LANGUAGE_AGGREGATE_ARTIFACT/$LANGUAGE_VERSION" \
  -type f \
  -name "$LANGUAGE_AGGREGATE_ARTIFACT-$LANGUAGE_VERSION.jar" \
  -print0)
if [[ "${#aggregate_artifacts[@]}" -ne 1 ]]; then
  echo "Expected exactly one resolved aggregate Language JAR; found ${#aggregate_artifacts[@]}." >&2
  exit 1
fi
readonly REVIEWED_AGGREGATE_ARTIFACT="${aggregate_artifacts[0]}"
if [[ "$(sha256_file "$REVIEWED_AGGREGATE_ARTIFACT")" != "$LANGUAGE_ARTIFACT_SHA256" ]]; then
  echo "Resolved aggregate Language artifact does not match the reviewed SHA-256." >&2
  exit 1
fi

IFS=',' read -r -a FOCUSED_RUNTIME_LANGUAGE_MODULES <<< \
  "$(property_value release.resolvedRuntimeArtifacts)"
if [[ "${#FOCUSED_RUNTIME_LANGUAGE_MODULES[@]}" -eq 0 ]]; then
  echo "Reviewed runtime Language artifact list is empty." >&2
  exit 1
fi
published_artifacts=("$REVIEWED_AGGREGATE_ARTIFACT")
for module in "${FOCUSED_RUNTIME_LANGUAGE_MODULES[@]}"; do
  module_artifacts=()
  while IFS= read -r -d '' artifact; do
    module_artifacts+=("$artifact")
  done < <(find "$PUBLISHED_CACHE_ROOT/$module/$LANGUAGE_VERSION" \
    -type f -name "$module-$LANGUAGE_VERSION.jar" -print0)
  if [[ "${#module_artifacts[@]}" -ne 1 ]]; then
    echo "Expected exactly one resolved $module $LANGUAGE_VERSION JAR; found ${#module_artifacts[@]}." >&2
    exit 1
  fi
  artifact="${module_artifacts[0]}"
  expected_hash="$(property_value "artifact.$module.sha256")"
  if [[ ! "$expected_hash" =~ ^[0-9a-f]{64}$ \
      || "$(sha256_file "$artifact")" != "$expected_hash" ]]; then
    echo "Resolved $module JAR does not match its reviewed SHA-256." >&2
    exit 1
  fi
  published_artifacts+=("$artifact")
done
readonly EXPECTED_ARTIFACT_COUNT="$((${#FOCUSED_RUNTIME_LANGUAGE_MODULES[@]} + 1))"
if [[ "${#published_artifacts[@]}" -ne "$EXPECTED_ARTIFACT_COUNT" ]]; then
  echo "Published Language artifact set is incomplete." >&2
  exit 1
fi

# Clean the root checkout before retaining comparison inputs. The next single
# invocation uses this same empty-cache Gradle home for the complete working,
# modernization, and release graph.
readonly ROOT_RELEASE_GRADLE_HOME="$RELEASE_ROOT/gradle-root-release"
GRADLE_USER_HOME="$ROOT_RELEASE_GRADLE_HOME" \
  ./gradlew --no-daemon clean

mkdir -p "$RETAINED_ARTIFACT_ROOT"
cp "$INDEPENDENT_REPORT" \
  "$RETAINED_INPUT_ROOT/independent-clean-builds.json"
cp "$REPEATABILITY_REPORT" \
  "$RETAINED_INPUT_ROOT/published-repeatability.json"
for artifact in "${published_artifacts[@]}"; do
  cp "$artifact" "$RETAINED_ARTIFACT_ROOT/$(basename "$artifact")"
done
retained_artifacts=()
while IFS= read -r -d '' artifact; do
  retained_artifacts+=("$artifact")
done < <(find "$RETAINED_ARTIFACT_ROOT" -type f -name '*.jar' -print0)
readonly RETAINED_ARTIFACT_PATHS="$(IFS=:; echo "${retained_artifacts[*]}")"

# Run the complete root release surface once against one fresh cache. The task
# graph includes the published working and modernization gates.
GRADLE_USER_HOME="$ROOT_RELEASE_GRADLE_HOME" \
  ./gradlew --no-daemon bexReleaseVerify \
  "-PbexPublishedLanguageCoordinate=$LANGUAGE_COORDINATE" \
  "-PbexPublishedLanguageSha256=$LANGUAGE_ARTIFACT_SHA256" \
  "-PbexPublishedLanguageArtifacts=$RETAINED_ARTIFACT_PATHS" \
  "-PbexPublishedRepeatability=$RETAINED_INPUT_ROOT/published-repeatability.json" \
  "-PbexIndependentCleanBuildReport=$RETAINED_INPUT_ROOT/independent-clean-builds.json"

jq -e '.releaseReady == true' \
  "$BEX_REPOSITORY/build/reports/bex-release/final.json" >/dev/null

# The strict report deliberately re-opens every independent checkout and
# manifest instead of trusting JSON alone. Keep those ephemeral inputs alive
# for the subsequent publish and JReleaser Gradle invocations. RUNNER_TEMP is
# discarded with the CI job; failed gates still remove it via the EXIT trap.
retain_release_root=true
echo "Retained live publication evidence at $RELEASE_ROOT"
