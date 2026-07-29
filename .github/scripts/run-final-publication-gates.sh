#!/usr/bin/env bash
set -euo pipefail

readonly SCRIPT_DIR="$(
  cd "$(dirname "${BASH_SOURCE[0]}")"
  pwd
)"
readonly BEX_REPOSITORY="$(
  cd "$SCRIPT_DIR/../.."
  pwd
)"
readonly INSPECTION_FILE="$BEX_REPOSITORY/src/test/resources/hosted-release/published-api-inspection.properties"
readonly LANGUAGE_REPOSITORY_URL="${BLUE_LANGUAGE_REPOSITORY_URL:-https://github.com/bluecontract/blue-language-java.git}"
readonly BEX_RELEASE_TEMP_ROOT="$(
  mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/blue-bex-publication.XXXXXX"
)"
readonly LANGUAGE_CHECKOUT="$BEX_RELEASE_TEMP_ROOT/blue-language-java"
readonly FIRST_BEX_CHECKOUT="$BEX_RELEASE_TEMP_ROOT/blue-bex-clean-one"
readonly SECOND_BEX_CHECKOUT="$BEX_RELEASE_TEMP_ROOT/blue-bex-clean-two"
readonly RECEIPT_ROOT="$BEX_RELEASE_TEMP_ROOT/receipts"
readonly STANDALONE_FIRST_RECEIPT="$RECEIPT_ROOT/standalone-first.properties"
readonly STANDALONE_SECOND_RECEIPT="$RECEIPT_ROOT/standalone-second.properties"
readonly LOCAL_FIRST_RECEIPT="$RECEIPT_ROOT/local-composite-first.properties"
readonly LOCAL_SECOND_RECEIPT="$RECEIPT_ROOT/local-composite-second.properties"

property_value() {
  local key="$1"
  sed -n "s/^${key}=//p" "$INSPECTION_FILE" | head -n 1
}

readonly LANGUAGE_COMMIT="$(property_value "source.commit")"
readonly LANGUAGE_TAG="$(property_value "source.tag")"
readonly LANGUAGE_API_STATUS="$(property_value "status")"
readonly BEX_COMMIT="$(git -C "$BEX_REPOSITORY" rev-parse HEAD)"
readonly SOURCE_COMMIT_EPOCH="$(
  git -C "$BEX_REPOSITORY" show -s --format=%ct "$BEX_COMMIT"
)"

if [[ ! "$LANGUAGE_COMMIT" =~ ^[0-9a-fA-F]{40}$ ]]; then
  echo "Recorded final Blue Language commit is unavailable." >&2
  exit 1
fi
if [[ ! "$LANGUAGE_TAG" =~ ^[A-Za-z0-9][A-Za-z0-9._/-]*$ ||
      "$LANGUAGE_TAG" == *..* ]]; then
  echo "Recorded final Blue Language tag is unavailable or invalid." >&2
  exit 1
fi
if [[ "$LANGUAGE_API_STATUS" != "compatible-with-final-hosted-adapter" ]]; then
  echo "Recorded Blue Language artifact is not final-host compatible: $LANGUAGE_API_STATUS" >&2
  exit 1
fi
if [[ -n "$(git -C "$BEX_REPOSITORY" status --porcelain --untracked-files=all)" ]]; then
  echo "Publication requires a completely clean BEX checkout." >&2
  exit 1
fi

mkdir -p "$LANGUAGE_CHECKOUT"
git -C "$LANGUAGE_CHECKOUT" init
git -C "$LANGUAGE_CHECKOUT" remote add origin "$LANGUAGE_REPOSITORY_URL"
git -C "$LANGUAGE_CHECKOUT" fetch \
  --depth=1 \
  origin \
  "refs/tags/$LANGUAGE_TAG:refs/tags/$LANGUAGE_TAG"
git -C "$LANGUAGE_CHECKOUT" checkout --detach "refs/tags/$LANGUAGE_TAG"

if [[ "$(git -C "$LANGUAGE_CHECKOUT" rev-parse HEAD)" != "$LANGUAGE_COMMIT" ]]; then
  echo "Blue Language checkout does not match the recorded commit." >&2
  exit 1
fi
if [[ -n "$(git -C "$LANGUAGE_CHECKOUT" status --porcelain --untracked-files=all)" ]]; then
  echo "Publication requires a clean Blue Language composite checkout." >&2
  exit 1
fi

export CI=true
export SOURCE_DATE_EPOCH="$SOURCE_COMMIT_EPOCH"
if [[ -n "${GITHUB_ENV:-}" ]]; then
  printf 'SOURCE_DATE_EPOCH=%s\n' "$SOURCE_COMMIT_EPOCH" >> "$GITHUB_ENV"
fi

cd "$BEX_REPOSITORY"

# Assemble the publication artifacts twice from separate clean checkouts of
# this exact BEX commit, using the standalone published dependency in both.
git clone --no-hardlinks "$BEX_REPOSITORY" "$FIRST_BEX_CHECKOUT"
git clone --no-hardlinks "$BEX_REPOSITORY" "$SECOND_BEX_CHECKOUT"
git -C "$FIRST_BEX_CHECKOUT" checkout --detach "$BEX_COMMIT"
git -C "$SECOND_BEX_CHECKOUT" checkout --detach "$BEX_COMMIT"
mkdir -p "$RECEIPT_ROOT"

GRADLE_USER_HOME="$BEX_RELEASE_TEMP_ROOT/gradle-clean-one" \
  "$FIRST_BEX_CHECKOUT/gradlew" \
  --no-daemon \
  -p "$FIRST_BEX_CHECKOUT" \
  clean test writeCleanBuildArtifactHashes
GRADLE_USER_HOME="$BEX_RELEASE_TEMP_ROOT/gradle-clean-two" \
  "$SECOND_BEX_CHECKOUT/gradlew" \
  --no-daemon \
  -p "$SECOND_BEX_CHECKOUT" \
  clean test writeCleanBuildArtifactHashes

cp \
  "$FIRST_BEX_CHECKOUT/build/reports/bex-release/clean-build-artifacts.properties" \
  "$STANDALONE_FIRST_RECEIPT"
cp \
  "$SECOND_BEX_CHECKOUT/build/reports/bex-release/clean-build-artifacts.properties" \
  "$STANDALONE_SECOND_RECEIPT"

GRADLE_USER_HOME="$BEX_RELEASE_TEMP_ROOT/gradle-evidence-verifier" \
  ./gradlew --no-daemon verifyIndependentCleanBuildReproducibility \
  -PcleanBuildEvidenceOne="$STANDALONE_FIRST_RECEIPT" \
  -PcleanBuildEvidenceTwo="$STANDALONE_SECOND_RECEIPT"

# Record each supported dependency mode separately after the independent
# archive evidence exists. Fresh Gradle homes make standalone dependency-cache
# provenance explicit and prevent one mode from borrowing resolution state
# from the other.
GRADLE_USER_HOME="$BEX_RELEASE_TEMP_ROOT/gradle-standalone-mode" \
  ./gradlew --no-daemon clean test

GRADLE_USER_HOME="$BEX_RELEASE_TEMP_ROOT/gradle-local-clean-one" \
  "$FIRST_BEX_CHECKOUT/gradlew" \
  --no-daemon \
  -p "$FIRST_BEX_CHECKOUT" \
  clean test writeCleanBuildArtifactHashes \
  -PblueLanguageCompositePath="$LANGUAGE_CHECKOUT"
GRADLE_USER_HOME="$BEX_RELEASE_TEMP_ROOT/gradle-local-clean-two" \
  "$SECOND_BEX_CHECKOUT/gradlew" \
  --no-daemon \
  -p "$SECOND_BEX_CHECKOUT" \
  clean test writeCleanBuildArtifactHashes \
  -PblueLanguageCompositePath="$LANGUAGE_CHECKOUT"

cp \
  "$FIRST_BEX_CHECKOUT/build/reports/bex-release/clean-build-artifacts.properties" \
  "$LOCAL_FIRST_RECEIPT"
cp \
  "$SECOND_BEX_CHECKOUT/build/reports/bex-release/clean-build-artifacts.properties" \
  "$LOCAL_SECOND_RECEIPT"

GRADLE_USER_HOME="$BEX_RELEASE_TEMP_ROOT/gradle-local-evidence-verifier" \
  ./gradlew --no-daemon verifyIndependentCleanBuildReproducibility \
  -PblueLanguageCompositePath="$LANGUAGE_CHECKOUT" \
  -PcleanBuildEvidenceOne="$LOCAL_FIRST_RECEIPT" \
  -PcleanBuildEvidenceTwo="$LOCAL_SECOND_RECEIPT"

GRADLE_USER_HOME="$BEX_RELEASE_TEMP_ROOT/gradle-local-mode" \
  ./gradlew --no-daemon clean test \
  -PblueLanguageCompositePath="$LANGUAGE_CHECKOUT"

# Rebuild the exact standalone publication outputs from a fresh dependency
# cache and make the single fail-closed readiness decision consumed below by
# the publication workflows.
GRADLE_USER_HOME="$BEX_RELEASE_TEMP_ROOT/gradle-final-standalone" \
  ./gradlew --no-daemon clean bexReleaseEvidence

readonly CLEAN_BUILD_EVIDENCE_ARCHIVE="$BEX_REPOSITORY/build/reports/bex-release/independent-clean-builds"
mkdir -p "$CLEAN_BUILD_EVIDENCE_ARCHIVE"
cp "$STANDALONE_FIRST_RECEIPT" \
  "$CLEAN_BUILD_EVIDENCE_ARCHIVE/standalone-first.properties"
cp "$STANDALONE_SECOND_RECEIPT" \
  "$CLEAN_BUILD_EVIDENCE_ARCHIVE/standalone-second.properties"
cp "$LOCAL_FIRST_RECEIPT" \
  "$CLEAN_BUILD_EVIDENCE_ARCHIVE/local-composite-first.properties"
cp "$LOCAL_SECOND_RECEIPT" \
  "$CLEAN_BUILD_EVIDENCE_ARCHIVE/local-composite-second.properties"
