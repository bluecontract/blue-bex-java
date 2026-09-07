# Release

BEX has separate working and public-release gates. Keeping them separate
distinguishes same-checkout verification from the isolated repeatability proof
required for publication.

## Working published gate

```bash
./gradlew --no-daemon clean bexWorkingVerification
```

This mandatory gate resolves the exact reviewed modular Language release from
Maven Central and requires
all ordinary/conformance/hosted tests, 75 vectors, 120 behavior fixtures, 30 gas
microfixtures, 86 operator checks, Java 8 bytecode, API reports, runtime smoke,
artifact construction, BEX-owned archive determinism, and current dependency and
source evidence.

Success requires zero failed, skipped, or unclassified evidence and
`workingReady = true`. An included-build Language checkout is not a supported
input to this gate.

## Commit-bound development candidate stage

Cross-repository development uses immutable, commit-bound versions rather than
inventing an RC number or resolving from Maven Local. Language is supplied as
`3.1.0-dev.<40-character-source-commit>` from its sealed repository. BEX is
built as `1.1.0-dev.<40-character-source-commit>` and seals its own downstream
repository with:

```bash
./gradlew assembleImmutableDevelopmentRepository \
  -PblueLanguageRepository=/absolute/path/to/immutable-language-repository \
  -PblueLanguageVersion=3.1.0-dev.<language-source-commit> \
  -PbexLocalStageVersion=1.1.0-dev.<bex-source-commit> \
  -PbexSdkStagingRepository=/absolute/path/to/fresh-mutable-bex-stage \
  -PbexDevelopmentRepository=/absolute/path/to/immutable-bex-repository
```

The staged Language repository is authoritative for the `blue.language` group;
Maven Central is excluded for that group, so missing candidate modules fail
closed. The BEX target is never overwritten. An existing destination is
accepted only when its complete byte tree is identical. Its
`artifact-manifest.json` and checksum bind the BEX source commit and tree,
record `stagePurpose: DEVELOPMENT`, `releaseReadinessClaimed: false`,
`builtWithJava: 17`, and `sourceDirty: false`, and bind the exact Language
version/source/manifest, BEX specification identity, fixture/registry/gas
package identities, and every runtime/POM/source/Javadoc artifact hash.

This lane is development evidence only. Public release gates continue to
consume authenticated Maven Central artifacts.

## Local RC candidate stage

The same immutable export supports the existing `1.1.0-rc.N` line when its
Language input uses `3.1.0-rc.N`. For this closeout the selected coordinates
are BEX `1.1.0-rc.5` and Language `3.1.0-rc.24`. Use
`exportDevelopmentRepository` with `-PbexLocalStageVersion=1.1.0-rc.5` and
the exact Language version and sealed repository properties above.

RC export requires clean exact source commits, Java 17, and the complete
seven-module Language publication set, including conformance, sources and
Javadoc. Language uses `blue-local-rc-maven-repository/1.0`; BEX emits
`blue-bex-local-rc-repository/1.0`. Both record `stagePurpose: LOCAL_RC` and
`releaseReadinessClaimed: false`. Existing destination bytes remain immutable.

Run `bexSdkStageVerify` against those exact inputs with
`-PbexSdkStageBaseline=/absolute/path/to/reviewed-sdk-stage-baseline.json`.
The baseline uses the existing `blue-bex-sdk-stage-baseline/1.0` schema and
binds the Language version and source commit plus the final BEX source commit
and specification digest. An external source lock avoids embedding a commit's
own hash in its tracked contents. The RC gate verifies clean BEX source and
the complete Language repository file set and checksums. This local gate
does not authorize publication; final integrated acceptance must be retained
separately against the exported bytes.

## Strict public gate

```bash
bash .github/scripts/run-final-publication-gates.sh
```

The strict gate additionally requires:

- exact compatible published Language module coordinates from controlled
  repositories (never an ambiguous `mavenLocal` substitute);
- published artifact API and SHA-256 inspection;
- same-run published conformance receipts from authenticated artifacts;
- four independent clean standalone-published builds with isolated Gradle homes
  and matching artifact hashes and conformance receipts;
- a clean tagged BEX source state and commit-bound evidence;
- `releaseReady = true` in the final report.

The script is the supported publication entry point. It runs the published
working and modernization gates, executes four clean standalone-published BEX
builds as two independently isolated build pairs, compares their artifact
manifests and conformance receipts, authenticates the resolved Language JARs,
and finally invokes `bexReleaseVerify`.

On success, the script retains its four isolated checkouts, manifests, and
Gradle homes under the ephemeral runner temporary directory. The later
`publish` and JReleaser invocations re-open that live evidence rather than
trusting copied JSON alone, and each receives its own empty Gradle home. Failed
gates remove the temporary evidence immediately; hosted runners discard it at
the end of the job.

If matching published modules or any independent evidence is absent, the gate is
truthfully red or `not-executed`. It must not be described as passing and must
not be weakened to unblock local work.

## Artifacts and evidence

The release surface includes the aggregate JAR, intended module JARs, sources
JAR, Javadoc JAR, source archive, API descriptors and migration ledger,
dependency lock/evidence, working report, and strict release report. All
BEX-owned archives must be reproducible.

Reports record source commit and dirty state, exact published Language source
and artifact identities, dependency graph and JAR hashes,
registry/gas/fixture identities, test/fixture/operator totals, semantic and gas
parity, hosted boundaries, Java 8 verification, artifact hashes, and the exact
standalone-published mode and repeatability status.
The strict decision is written to `build/reports/bex-release/final.json` and
`final.md` even when the Gradle task fails closed.

Do not publish from a dirty tree, rewrite `.cz.toml` merely to select a
dependency version, embed a local checkout path in published metadata, or stage
downloaded archives. Version automation and tags remain the repository's
existing release process.

## Benchmark reporting

JMH sources belong to the benchmark suite, but a benchmark claim is valid only
when a report records JVM/CPU details, forks, warmups, measurements, allocation
data, confidence intervals, and result/gas identity checks. Merely compiling a
benchmark is not a performance result. Long benchmark campaigns do not block the
first working compatibility checkpoint, but release/modernization reports must
label unexecuted campaigns honestly. `bexModernizationVerification` consumes
the serious `results.json` plus the recorded JVM/OS/CPU/campaign environment;
the bounded `jmhSmoke` result belongs only to the working compatibility gate.

## Reading current state

This guide defines gates; it does not assert their latest outcome. Inspect the
current generated JSON/Markdown under `build/reports` and require the relevant
ready flag. Working success and public-release eligibility are distinct fields.
