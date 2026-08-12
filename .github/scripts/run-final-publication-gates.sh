#!/usr/bin/env bash
set -euo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly BEX_REPOSITORY="$(cd "$SCRIPT_DIR/../.." && pwd)"
readonly INSPECTION_FILE="$BEX_REPOSITORY/src/test/resources/hosted-release/published-api-inspection.properties"
readonly LANGUAGE_REPOSITORY_URL="${BLUE_LANGUAGE_REPOSITORY_URL:-https://github.com/bluecontract/blue-language-java.git}"
readonly RELEASE_ROOT="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/blue-bex-publication.XXXXXX")"
readonly LANGUAGE_CHECKOUT="$RELEASE_ROOT/blue-language-java"
readonly RECEIPT_ROOT="$RELEASE_ROOT/receipts"
readonly INDEPENDENT_REPORT="$RECEIPT_ROOT/independent-clean-builds.json"
readonly DIFFERENTIAL_REPORT="$RECEIPT_ROOT/local-published-differential.json"
readonly RETAINED_INPUT_ROOT="$BEX_REPOSITORY/build/reports/bex-release/inputs"
readonly RETAINED_ARTIFACT_ROOT="$RETAINED_INPUT_ROOT/published-artifacts"
readonly BEX_COMMIT="$(git -C "$BEX_REPOSITORY" rev-parse HEAD)"
readonly SOURCE_COMMIT_EPOCH="$(git -C "$BEX_REPOSITORY" show -s --format=%ct "$BEX_COMMIT")"
release_succeeded=false

cleanup() {
  if [[ "$release_succeeded" != true || -z "${GITHUB_ENV:-}" ]]; then
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

resolve_reviewed_aggregate() {
  local repository="$1"
  local coordinate="$2"
  local output="$3"
  local group artifact version remainder group_path url
  IFS=: read -r group artifact version remainder <<< "$coordinate"
  if [[ -z "$group" || -z "$artifact" || -z "$version" || -n "${remainder:-}" ]]; then
    echo "Reviewed Language coordinate is not group:artifact:version: $coordinate" >&2
    exit 1
  fi
  group_path="${group//./\/}"
  url="${repository%/}/$group_path/$artifact/$version/$artifact-$version.jar"
  mkdir -p "$(dirname "$output")"
  curl --fail --silent --show-error --location --retry 3 \
    --output "$output" "$url"
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
  local mode="$3"
  local arguments=(
    --no-daemon
    -p "$checkout"
    clean
    assemble
    bexConformance
    sourceReleaseArchive
  )
  if [[ "$mode" == "local-composite" ]]; then
    arguments+=("-PblueLanguageCompositePath=$LANGUAGE_CHECKOUT")
  fi
  GRADLE_USER_HOME="$gradle_home" \
    "$checkout/gradlew" "${arguments[@]}"
}

readonly LANGUAGE_COMMIT="$(property_value source.commit)"
readonly LANGUAGE_TAG="$(property_value source.tag)"
readonly LANGUAGE_ARTIFACT_REPOSITORY="$(property_value repository)"
readonly LANGUAGE_COORDINATE="$(property_value coordinate)"
readonly LANGUAGE_ARTIFACT_SHA256="$(property_value artifact.sha256)"
readonly LANGUAGE_API_STATUS="$(property_value status)"
readonly EXPECTED_RELEASE_TAG="v$(sed -n 's/^version = "\([^"]*\)"/\1/p' "$BEX_REPOSITORY/.cz.toml" | head -n 1)"

if [[ "$LANGUAGE_API_STATUS" != "compatible-with-final-hosted-adapter" ]]; then
  echo "Published Language inspection is not compatible: $LANGUAGE_API_STATUS" >&2
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

git clone --quiet --filter=blob:none --no-checkout \
  "$LANGUAGE_REPOSITORY_URL" "$LANGUAGE_CHECKOUT"
git -C "$LANGUAGE_CHECKOUT" fetch --quiet --depth=1 origin \
  "refs/tags/$LANGUAGE_TAG:refs/tags/$LANGUAGE_TAG"
git -C "$LANGUAGE_CHECKOUT" checkout --quiet --detach "refs/tags/$LANGUAGE_TAG"
if [[ "$(git -C "$LANGUAGE_CHECKOUT" rev-parse HEAD)" != "$LANGUAGE_COMMIT" ]]; then
  echo "Published Language tag does not resolve to the reviewed commit." >&2
  exit 1
fi
if [[ -n "$(git -C "$LANGUAGE_CHECKOUT" status --porcelain --untracked-files=all)" ]]; then
  echo "Language release checkout is dirty." >&2
  exit 1
fi

export CI=true
export SOURCE_DATE_EPOCH="$SOURCE_COMMIT_EPOCH"
mkdir -p "$RECEIPT_ROOT"

cd "$BEX_REPOSITORY"
./gradlew --no-daemon clean bexWorkingVerification \
  "-PblueLanguageCompositePath=$LANGUAGE_CHECKOUT"
./gradlew --no-daemon bexModernizationVerification \
  "-PblueLanguageCompositePath=$LANGUAGE_CHECKOUT"

declare -a checkouts=(
  "$RELEASE_ROOT/standalone-one"
  "$RELEASE_ROOT/standalone-two"
  "$RELEASE_ROOT/local-one"
  "$RELEASE_ROOT/local-two"
)
for checkout in "${checkouts[@]}"; do
  clone_bex "$checkout"
done

run_isolated_build "${checkouts[0]}" "$RELEASE_ROOT/gradle-standalone-one" standalone-published
run_isolated_build "${checkouts[1]}" "$RELEASE_ROOT/gradle-standalone-two" standalone-published
run_isolated_build "${checkouts[2]}" "$RELEASE_ROOT/gradle-local-one" local-composite
run_isolated_build "${checkouts[3]}" "$RELEASE_ROOT/gradle-local-two" local-composite

readonly STANDALONE_ONE_MANIFEST="$RECEIPT_ROOT/standalone-one.sha256"
readonly STANDALONE_TWO_MANIFEST="$RECEIPT_ROOT/standalone-two.sha256"
readonly LOCAL_ONE_MANIFEST="$RECEIPT_ROOT/local-one.sha256"
readonly LOCAL_TWO_MANIFEST="$RECEIPT_ROOT/local-two.sha256"
artifact_manifest "${checkouts[0]}" "$STANDALONE_ONE_MANIFEST"
artifact_manifest "${checkouts[1]}" "$STANDALONE_TWO_MANIFEST"
artifact_manifest "${checkouts[2]}" "$LOCAL_ONE_MANIFEST"
artifact_manifest "${checkouts[3]}" "$LOCAL_TWO_MANIFEST"
if ! node "$SCRIPT_DIR/compare-independent-builds.mjs" \
    "$STANDALONE_ONE_MANIFEST" "$STANDALONE_TWO_MANIFEST" \
    "$LOCAL_ONE_MANIFEST" "$LOCAL_TWO_MANIFEST" \
    "${checkouts[0]}" "${checkouts[1]}" \
    "${checkouts[2]}" "${checkouts[3]}" \
    "$RELEASE_ROOT/gradle-standalone-one" \
    "$RELEASE_ROOT/gradle-standalone-two" \
    "$RELEASE_ROOT/gradle-local-one" \
    "$RELEASE_ROOT/gradle-local-two" \
    "$BEX_COMMIT" "$INDEPENDENT_REPORT"; then
  mkdir -p "$RETAINED_INPUT_ROOT"
  cp "$INDEPENDENT_REPORT" \
    "$RETAINED_INPUT_ROOT/independent-clean-builds.json"
  echo "Retained failed independent-build report under build/reports." >&2
  exit 1
fi

node "$SCRIPT_DIR/compare-local-published-evidence.mjs" \
  "${checkouts[2]}/blue-bex-conformance/build/reports/bex-conformance/report.json" \
  "${checkouts[0]}/blue-bex-conformance/build/reports/bex-conformance/report.json" \
  "$BEX_COMMIT" "$DIFFERENTIAL_REPORT"

readonly REVIEWED_AGGREGATE_ARTIFACT="$RECEIPT_ROOT/published-language-aggregate.jar"
resolve_reviewed_aggregate \
  "$LANGUAGE_ARTIFACT_REPOSITORY" \
  "$LANGUAGE_COORDINATE" \
  "$REVIEWED_AGGREGATE_ARTIFACT"
if [[ "$(sha256_file "$REVIEWED_AGGREGATE_ARTIFACT")" != "$LANGUAGE_ARTIFACT_SHA256" ]]; then
  echo "Resolved aggregate Language artifact does not match the reviewed SHA-256." >&2
  exit 1
fi

published_artifacts=("$REVIEWED_AGGREGATE_ARTIFACT")
while IFS= read -r -d '' artifact; do
  published_artifacts+=("$artifact")
done < <(find \
  "$RELEASE_ROOT/gradle-standalone-one/caches/modules-2/files-2.1/blue.language" \
  -type f -name '*.jar' -print0)
if [[ "${#published_artifacts[@]}" -eq 1 ]]; then
  echo "No focused published Language module artifacts were resolved in the isolated cache." >&2
  exit 1
fi
artifact_match=false
for artifact in "${published_artifacts[@]}"; do
  if [[ "$(sha256_file "$artifact")" == "$LANGUAGE_ARTIFACT_SHA256" ]]; then
    artifact_match=true
  fi
done
if [[ "$artifact_match" != true ]]; then
  echo "No resolved Language artifact matches the reviewed aggregate SHA-256." >&2
  exit 1
fi
mkdir -p "$RETAINED_ARTIFACT_ROOT"
cp "$INDEPENDENT_REPORT" \
  "$RETAINED_INPUT_ROOT/independent-clean-builds.json"
cp "$DIFFERENTIAL_REPORT" \
  "$RETAINED_INPUT_ROOT/local-published-differential.json"
for artifact in "${published_artifacts[@]}"; do
  cp "$artifact" "$RETAINED_ARTIFACT_ROOT/$(basename "$artifact")"
done
retained_artifacts=()
while IFS= read -r -d '' artifact; do
  retained_artifacts+=("$artifact")
done < <(find "$RETAINED_ARTIFACT_ROOT" -type f -name '*.jar' -print0)
readonly RETAINED_ARTIFACT_PATHS="$(IFS=:; echo "${retained_artifacts[*]}")"

# Re-run the root conformance surface in an exact-version empty cache so its
# detailed mode matrix observes both local-composite and published execution.
GRADLE_USER_HOME="$RELEASE_ROOT/gradle-root-standalone" \
  ./gradlew --no-daemon bexConformance
./gradlew --no-daemon generateBexModernizationReport

./gradlew --no-daemon bexReleaseVerify \
  "-PblueLanguageCompositePath=$LANGUAGE_CHECKOUT" \
  "-PbexPublishedLanguageCoordinate=$LANGUAGE_COORDINATE" \
  "-PbexPublishedLanguageSha256=$LANGUAGE_ARTIFACT_SHA256" \
  "-PbexPublishedLanguageArtifacts=$RETAINED_ARTIFACT_PATHS" \
  "-PbexLocalPublishedDifferential=$RETAINED_INPUT_ROOT/local-published-differential.json" \
  "-PbexIndependentCleanBuildReport=$RETAINED_INPUT_ROOT/independent-clean-builds.json"

jq -e '.releaseReady == true' \
  "$BEX_REPOSITORY/build/reports/bex-release/final.json" >/dev/null

if [[ -n "${GITHUB_ENV:-}" ]]; then
  printf 'BLUE_LANGUAGE_COMPOSITE_PATH=%s\n' "$LANGUAGE_CHECKOUT" >> "$GITHUB_ENV"
fi
release_succeeded=true
