# Release

BEX has separate working and public-release gates. Keeping them separate allows
downstream local integration to proceed without pretending that unpublished
Language artifacts have public provenance.

## Working local gate

```bash
./gradlew --no-daemon clean bexWorkingVerification \
  -PblueLanguageCompositePath=/absolute/path/to/blue-language-java
```

This mandatory gate uses the exact local modular Language checkout and requires
all ordinary/conformance/hosted tests, 60 vectors, 105 behavior fixtures, 30 gas
microfixtures, 86 operator checks, Java 8 bytecode, API reports, runtime smoke,
artifact construction, BEX-owned archive determinism, and current dependency and
source evidence.

Success requires zero failed, skipped, or unclassified evidence and
`workingReady = true`. A green, reviewable working commit is a usable local
artifact checkpoint even when published Language modules do not yet exist.

## Isolated SDK candidate stage

The Coordination SDK candidate uses a local Maven repository without labeling
its contents as published. Language is staged first; BEX then verifies the
exact candidate dependency and, in a separate invocation, publishes its own
candidate artifacts:

```bash
./gradlew --no-daemon clean bexSdkStageVerify \
  -PblueLanguageRepository=/absolute/path/to/fresh-staged-repository \
  -PbexSdkStagingRepository=/absolute/path/to/fresh-staged-repository \
  -PbexLocalStageVersion=1.1.0-rc.4

./gradlew --no-daemon publish \
  -PblueLanguageRepository=/absolute/path/to/fresh-staged-repository \
  -PbexSdkStagingRepository=/absolute/path/to/fresh-staged-repository \
  -PbexLocalStageVersion=1.1.0-rc.4
```

`bexSdkStageVerify` does not publish. The publication tasks depend on that gate,
so the second command verifies again before writing BEX artifacts. The gate
uses dependency mode `staged-repository`, requires every focused Language
`3.1.0-rc.21` JAR in the explicit repository to hash-match the resolved JAR,
and writes `build/reports/bex-sdk-stage/verification.json`. Any entry in the
same-run conformance report's `currentModeFailures` makes the task fail.

The source lock is
`gradle/verification/sdk-stage-language-baseline.json`: it binds Language
`3.1.0-rc.21` to commit `e463efe3b0ae161c680f1c00cd2846f7e4aa8e8f` and
BEX `1.1.0-rc.4` to an explicit candidate-version selection. The retained
`.cz.toml` value `1.1.0-rc.3` and the retained Maven Central Language rc.20
inspection describe historical releases. They are not rewritten or compared
to the candidate repository.

## Strict public gate

```bash
bash .github/scripts/run-final-publication-gates.sh
```

The strict gate additionally requires:

- exact compatible published Language module coordinates from controlled
  repositories (never an ambiguous `mavenLocal` substitute);
- published artifact API and SHA-256 inspection;
- semantic and exact gas differential proof between authenticated local and
  published dependency modes;
- two independent clean builds with isolated Gradle homes and matching artifact
  hashes;
- a clean tagged BEX source state and commit-bound evidence;
- `releaseReady = true` in the final report.

The script is the supported publication entry point. It checks out the exact
reviewed Language tag, runs the local working and modernization gates, builds
two clean standalone and two clean local-composite BEX checkouts with isolated
Gradle homes, compares their artifact manifests, derives a local/published
semantic and exact-gas differential, authenticates the resolved Language JARs,
and finally invokes `bexReleaseVerify`.

If matching published modules or any independent evidence is absent, the gate is
truthfully red or `not-executed`. It must not be described as passing and must
not be weakened to unblock local work.

## Artifacts and evidence

The release surface includes the aggregate JAR, intended module JARs, sources
JAR, Javadoc JAR, source archive, API descriptors and migration ledger,
dependency lock/evidence, working report, and strict release report. All
BEX-owned archives must be reproducible.

Reports record source commit and dirty state, Language exact/code-equivalent
commits, dependency graph and JAR hashes, registry/gas/fixture identities,
test/fixture/operator totals, semantic and gas parity, hosted boundaries, Java 8
verification, artifact hashes, and the exact local/published mode status.
The strict decision is written to `build/reports/bex-release/final.json` and
`final.md` even when the Gradle task fails closed.

Do not publish from a dirty tree, rewrite `.cz.toml` merely to select a local
candidate, embed a local checkout path in published metadata, or stage
downloaded archives. Version automation and tags remain the repository's
existing release process.

## Benchmark reporting

JMH sources belong to the benchmark suite, but a benchmark claim is valid only
when a report records JVM/CPU details, forks, warmups, measurements, allocation
data, confidence intervals, and result/gas identity checks. Merely compiling a
benchmark is not a performance result. Long benchmark campaigns do not block the
first local compatibility checkpoint, but release/modernization reports must
label unexecuted campaigns honestly. `bexModernizationVerification` consumes
the serious `results.json` plus the recorded JVM/OS/CPU/campaign environment;
the bounded `jmhSmoke` result belongs only to the working compatibility gate.

## Reading current state

This guide defines gates; it does not assert their latest outcome. Inspect the
current generated JSON/Markdown under `build/reports` and require the relevant
ready flag. Working success and public-release eligibility are distinct fields.
