# Blue BEX 2.0 conformance

The executable BEX 2.0 release package is copied unchanged under:

```text
src/test/resources/conformance/bex/
```

`BexConformancePackageIntegrityTest` verifies the closed fixture schema, the
complete 147-file inventory, every LF-normalized byte length and SHA-256
digest, all three package identities, the 60-vector reverse map, direct
coverage for all 86 operators, all 30 gas counters, and the exact runtime
registry files and BlueIds.

`BexConformanceFixtureTest` executes all 105 manifest-declared behavior
fixtures. Explicit variants and complete `expected.cases` run independently.
The harness has no disabled-test, assumption, or skip path. Fixture
`additionalCase` metadata is not executable, as required by `HARNESS.md`.

`BexGasMicrofixtureTest` executes all 30 named-counter microfixtures directly
against the public meter. Each test verifies the namespace, counter, sequence,
quantity, manifest weight, subtotal, reason, and trace-derived total.

The JSON and Markdown reports also publish concrete exhaustion traces from
`BexPrimitiveExhaustionEvidenceTest`. Each example includes the exact
namespace, rejected counter, quantity, weight, admitted gas, effective budget,
rejected-charge absence, and zero later work. The numeric expectations are
source-controlled in
`src/test/resources/hosted-release/gas-exhaustion-trace-examples.properties`;
an example is marked passing only when its exact dynamic JUnit selector ran
and passed.

The published baseline reconciliations are explicit:

- the manifest count of 60 vectors and 105 behavior fixtures is authoritative;
- BEX-S-07 is a runtime uninitialized-binding failure;
- BEX-C-09 rejects recursion with `recursive-call-graph` before runtime;
- BEX-E-14's `result.identityB` expected value is a projection reference;
- `$findEntry` requires the canonical `index` in addition to the fixture's
  `key`/`val` subset;
- BEX-G-09's `canonical-merge-sort` value is algorithm evidence backed by the
  exact comparison trace.

Run the complete test and evidence workflow with:

```text
./gradlew bexConformanceReport \
  -PblueLanguageCompositePath=../blue-language-java
```

The composite path is explicit. Omitting it selects
`standalone-published`, which resolves the declared Blue Language coordinate
from Maven Central only. Dependency resolution never consults `mavenLocal`;
the resolved standalone JAR must match the coordinate, repository provenance,
and SHA-256 recorded in
`src/test/resources/hosted-release/published-api-inspection.properties`.

The test task always finalizes by writing:

```text
build/reports/bex-conformance/report.json
build/reports/bex-conformance/report.md
```

The report is deterministic for a fixed source state, test result set, and
artifacts. It reports the current commit and dirty-worktree flag, Java and
Gradle versions, fixture/registry/gas identities, actual JUnit XML counts,
operator and counter matrices, cache and representation matrices, recursion
and finite-loop evidence, and SHA-256 hashes only for current-version
artifacts. Failed, skipped, or unexecuted evidence remains visibly so; a
declaration is never reported as an execution.

Normative-vector passing totals are derived from the status of every mapped
behavior or gas fixture in `vector-coverage.yaml`. The report does not turn a
passing inventory-integrity test into a hardcoded `60/60` execution claim.

`verifyDeterministicArchives` is an archive-packaging determinism gate. It
repackages the same compiled main output and source inputs and independently
regenerates Javadoc content before byte comparison. It does not claim a
second clean compilation.

`writeCleanBuildArtifactHashes` is intentionally stricter: it runs only from a
completely clean checkout and records the commit, dependency mode, version,
and main, sources, Javadoc, and source-release hashes. Run it in two clean
checkouts of the same commit with the non-snapshot CI version and one source
epoch. Every `GRADLE_USER_HOME` below must be a distinct fresh empty directory.
Then compare the two property files with:

```bash
export CI=true
export SOURCE_DATE_EPOCH="$(git show -s --format=%ct HEAD)"

(cd /first/clean/blue-bex-java && \
  GRADLE_USER_HOME=/tmp/blue-bex-gradle-one \
  ./gradlew --no-daemon clean test writeCleanBuildArtifactHashes)

(cd /second/clean/blue-bex-java && \
  GRADLE_USER_HOME=/tmp/blue-bex-gradle-two \
  ./gradlew --no-daemon clean test writeCleanBuildArtifactHashes)

./gradlew verifyIndependentCleanBuildReproducibility \
  -PcleanBuildEvidenceOne=/first/clean/blue-bex-java/build/reports/bex-release/clean-build-artifacts.properties \
  -PcleanBuildEvidenceTwo=/second/clean/blue-bex-java/build/reports/bex-release/clean-build-artifacts.properties
```

Both evidence producers must use the same dependency mode. The publication
pair uses standalone-published mode. To prove local-composite packaging
separately, run another two-clean-checkout pair in two additional BEX roots
with the same explicit
`-PblueLanguageCompositePath=/absolute/path/to/clean/blue-language-java`
argument on both builds. Keep all four roots until the final report has
re-hashed their outputs and receipt-owned Language JAR copies; never compare
one build from each mode. The local-composite receipt is accepted only when
its live source checkout is the exact published Language commit and carries
the recorded `v<coordinate-version>` tag.

The combined evidence is commit-bound and stale evidence fails closed. The
conformance report also requires its own four artifacts to match the hashes
from both clean builds. After this comparison exists, record both modes and
make the final decision in the reporting checkout:

```bash
export CI=true
export SOURCE_DATE_EPOCH="$(git show -s --format=%ct HEAD)"

GRADLE_USER_HOME=/tmp/blue-bex-standalone-mode \
  ./gradlew --no-daemon clean test \
  -PblueLanguageRequireFreshModuleCache=true
GRADLE_USER_HOME=/tmp/blue-bex-local-mode \
  ./gradlew --no-daemon clean test \
  -PblueLanguageCompositePath=/absolute/path/to/clean/blue-language-java
GRADLE_USER_HOME=/tmp/blue-bex-final-standalone \
  ./gradlew --no-daemon clean bexReleaseEvidence
```

The release and RC workflows perform that complete sequence before any
publication command and archive `build/reports`, `build/distributions`, test
results, and persistent mode evidence.

`binaryApiCheck` writes
`build/reports/bex-release/public-api.txt`, a deterministic
public/protected descriptor manifest of the packaged JAR, then fails closed
unless it exactly equals the source-controlled first-public BEX 2.0 baseline
in `src/test/resources/hosted-release/required-public-api.txt`. Missing,
changed, reordered, or unexpected public/protected signatures all fail. The
generated manifest hash is identity evidence; exact line equality is the API
compatibility claim.

A module-specific cache acceptance is reported separately for the exact
`blue.language:blue-language-java:3.1.0-rc.19` Gradle module-version path.
Standalone acceptance passes only when that exact path was absent at project
configuration and the subsequently resolved JAR matches the recorded Maven
Central hash in the dedicated run that explicitly requires fresh-cache proof.
That authenticated mode receipt is reused by later publication invocations;
they do not overwrite it or require a populated cache to become absent again.
It does not claim that the entire Gradle cache was empty or that a network
fetch was directly observed. Local-composite runs remain `not-executed` for
this acceptance.
