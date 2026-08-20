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
all ordinary/conformance/hosted tests, 60 vectors, 105 behavior fixtures, 30 gas
microfixtures, 86 operator checks, Java 8 bytecode, API reports, runtime smoke,
artifact construction, BEX-owned archive determinism, and current dependency and
source evidence.

Success requires zero failed, skipped, or unclassified evidence and
`workingReady = true`. An included-build Language checkout is not a supported
input to this gate.

## Retired SDK candidate stage

The isolated SDK stage was used before Language `3.1.0-rc.21` was published.
Its source lock remains as historical evidence in
`gradle/verification/sdk-stage-language-baseline.json`, but its status is
`retired-after-publication` and it is not accepted by a public BEX gate.

The ledger truthfully records that the staged candidate used Language commit
`e0dfc897ea7d158895325fae2bf84e103b8c1989` while `3.1.0-rc.20` was the
published release. The final `3.1.0-rc.21` tag instead resolves to commit
`5c4e5c88fa75d6cbc52b2e8772f14f2ac5246f52`. Current release evidence is kept
only in the latest-Language baseline and published API inspection.

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
