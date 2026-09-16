# Full BEX RC verification timing, without publication

This document describes the historical isolated experiment. The subsequent
production workflow integration and Java25 migration are documented in
[CI_RELEASE_OPTIMIZATION.md](CI_RELEASE_OPTIMIZATION.md).

## Task brief

Goal: measure Release RC computation, including the release-verification graph
repeated by both publication commands. Compare sequential four-build preparation
and three root verifications against four independent runners and one shared
root verification.
This extends the earlier Build and validate experiment, which did not measure
full RC readiness. Production release workflows and validators remain unchanged.

Acceptance: all original artifact reproducibility, semantic/exact-gas repeatability,
published Language authentication, working, modernization, conformance, JMH and
strict final report checks execute. Both variants have identical test inventories,
JMH cases and archive bytes. The only permitted strict failure is missing version
tag: `releaseReady` remains false. No tag, package publication or release is created. Local artifact construction
is expected; no publication task is invoked.

Scope: this document, rc-timing-experiment.py, its tests and its isolated workflow.
Assumption: `next` remains ab72af14ee54c6123e6d80349956af373a887680; fetched before
implementation. Risks: artifact transfer overhead, runner variability, path-bearing
reports, missing input detection. No local heavy build is used as a shortcut.

## Actual command graph

Baseline runs the four isolated builds sequentially, each with a distinct clean
clone and empty Gradle home, using the original release script task list:

```
./gradlew --no-daemon clean assemble bexConformance sourceReleaseArchive
```

Candidate runs those same four builds on four runners. Actual tracked sources,
Git metadata, generated jars/source archive, conformance report, test XML and
resolved blue.language dependency artifacts are transferred. No entire user
home, unrelated Gradle cache, daemon credentials or mocked evidence is transferred.

Then **both** run the original `compare-independent-builds.mjs` and
`compare-published-conformance-evidence.mjs`, authenticate the reviewed Language
artifact SHA-256 values, run root `clean` with a fifth empty Gradle home and run:

```
./gradlew --no-daemon bexReleaseVerify \
  -PbexPublishedLanguageCoordinate=... \
  -PbexPublishedLanguageSha256=... \
  -PbexPublishedLanguageArtifacts=... \
  -PbexPublishedRepeatability=... \
  -PbexIndependentCleanBuildReport=...
```

All invocations additionally pin `--console=plain --no-build-cache --max-workers=4`
for equal measurements. No tests are excluded and no fork setting is changed. The baseline repeats `bexReleaseVerify` with two more fresh Gradle homes,
matching the prerequisites of `publish` and `jreleaserFullRelease`. These two
publication tasks are never selected. The candidate performs the identical
shared root verification once. Every baseline phase must have the same JUnit,
JMH and strict gate results as that candidate phase.

The unchanged Java release checker reopens the restored real checkouts,
Git directories, archive bytes and manifests; it does not merely trust JSON.
The transferred Gradle-home subset contains actual resolved Language cache bytes;
other dependency caches are not needed by the comparison and are not imported
into the fresh fifth root build.

## Scope against the original 46-minute RC

Original run `34484843999` (job `102896404928`) completed in 46:05:

| Stage | Original elapsed | What the log shows |
| --- | ---: | --- |
| Readiness | 16:50 | Four clean builds, root clean and full verification |
| Gradle publish | 9:11 | Repeated verification, then local staging at 14:13:39.140–.150 UTC |
| Gradle release | 19:42 | Repeated verification until 14:22:39; JReleaser starts 14:22:41 and ends 14:33:21 |

The `publish` repository is `build/staging-deploy` (PublicationConventionsPlugin).
Its nine minutes largely repeat JMH smoke and full JMH, also repeated during
JReleaser prerequisites. This experiment measures all three verification graphs
in baseline and one equivalent graph in candidate. It excludes tiny local
publication-file writes and approximately 10:40 of actual JReleaser signing,
upload, service processing and release actions. Those actions cannot be timed
safely while obeying the no-publication constraint. Do not report the measured
candidate as a completed 46-minute public release replacement; report the
measured compute reduction and the unchanged external tail separately.

## Exact expected failure, not release authorization

Original `run-final-publication-gates.sh` requires an existing exact release tag
before it starts. This new experiment-only driver reproduces all of its compute
commands and original comparisons while deliberately supplying untagged source.
It does not invoke or modify that production shell script.

The complete unchanged `bexReleaseVerify` therefore ends in its final
`generateBexReleaseReport` task with Gradle exit1. The experiment accepts that
outcome only when the current report has correct commit/schema/policy, clean
untagged source, every verification status passed, exactly the missing-tag
blocker and `releaseReady=false`. Logs must identify precisely that failed task
and its expected strict error. Any other failed task, additional blocker,
missing report or command failure fails the experiment.

The workflow is green when that prepublication experiment succeeds; it is **not**
a green production Release RC and does not establish publication readiness.
Tag creation, version mutation, publish, JReleaser and waiting for Maven Central
are excluded and never called. The latter is external publication latency and
cannot be safely measured without publishing.

## Measurement and completeness

The baseline covers cloning, all four builds, comparisons and all three root
verification graphs. The candidate covers those checks once after fanout.
The candidate envelope starts with the earliest independent build and finishes
with final root verification; transfer, queue and join-setup gaps are included.
Initial runner setup and final result upload/summary are outside both measurements.
No shared build cache is restored. Both use the same commit and source timestamp.

Final summary requires both variants complete, four distinct indexed receipts
matching commit/run/attempt, positive consistent timing, equal standalone/root
JUnit identities/outcomes, equal JMH case identities and exact matching standalone
artifact manifests and semantic/gas repeatability reports across variants.

Single runs on different hosts are preliminary; do not attribute all variation
to the proposed fanout. Existing sequential default and release protections are
unchanged. The earlier Build and validate experiment may also run on this branch;
its timings and scope must not be confused with this experiment.

## Local verification / remote execution

Inherited narrow tests cover valid tag-only prepublication outcomes and reject
all other failures. After resuming, the full-RC phase-plan test failed because
there was no three-phase baseline; it passes with the implemented three-phase
baseline and one-phase candidate. The implemented validator now
accepts only that expected state, with focused negative tests.

```
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s .github/scripts -p 'test*timing_experiment.py'
actionlint .github/workflows/rc-timing-experiment.yml
git diff --check
```

Heavy verification remains CI_PENDING until baseline, all four independent builds,
parallel final verification and summary pass. The historical experiment is now manual-only (`workflow_dispatch`) and remains
guarded to `codex/ci/bex-parallel-experiment`. Read-only
tokens, no secrets and nonpersisted checkout credentials are used. No production
branch, tags or existing release workflow is modified.
