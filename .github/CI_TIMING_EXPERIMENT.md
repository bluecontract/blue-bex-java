# BEX validation timing experiment

This document describes the historical isolated experiment. The subsequent
production workflow integration and Java25 migration are documented in
[CI_RELEASE_OPTIMIZATION.md](CI_RELEASE_OPTIMIZATION.md).

Branch: `codex/ci/bex-parallel-experiment`, based on `next` at
`ab72af14ee54c6123e6d80349956af373a887680`.

This is a **Build and validate** experiment, not a complete release dry run.
Existing build, RC, release and publication checks remain unchanged.

## What runs

Four fresh Ubuntu 24.04 runners use Corretto 25, Corretto 8 for Javadoc,
independent empty Gradle homes and the same source timestamp. Gradle wrapper
bootstrap (with bounded download retries) occurs before the timed commands.
All commands use `--no-daemon --no-build-cache --max-workers=4`.

- **baseline**: exact verification task list from `build.yml`, then serious JMH.
- **verification**: the same complete verification list on its own.
- **benchmarks**: the same complete serious JMH task on its own; Gradle compiles
  its prerequisites on that runner. No test or benchmark selectors/exclusions.
- **four-forks**: the complete baseline commands with an experiment-only Gradle
  init script setting Test.maxParallelForks=4. No JUnit method-level concurrency.

The two middle jobs are the proposed split. The summary fails unless every job
succeeds, all receipts refer to this commit/run/attempt, JUnit test identities and
outcomes match baseline for verification/four-forks, and JMH case identities
(including parameter combinations and modes) match baseline. JMH numerical
scores are not expected to be identical across machines.

Measurement logs/receipts live under RUNNER_TEMP, outside the source checkout;
measurements and Gradle reports use separate artifacts. The runner checks actual
HEAD against GITHUB_SHA before timing, and the summary rejects nonfinite,
nonpositive or inconsistent durations.

The summary reports measured command duration and the parallel command envelope,
including launch gaps. It does not claim to measure checkout, tool setup,
artifact transfer or the final summary; consult Actions job/run times for those.
Four-fork duration includes JMH unchanged, so any improvement applies only to
JUnit portions of the full command sequence. Concurrent jobs consume more runner
minutes. One run is preliminary evidence, not a statistically robust estimate.

## Why this partition

On `next`, run 34489833557 completed Build and validate in 8m50s:
verification 2m02s, serious benchmark gate 6m34s. Splitting can overlap those
phases, but the benchmark job must compile again, so the potential gain is
bounded by roughly the two-minute phase (before overhead).

Release RC run 34484843999 took 46m05s: readiness 16m50s, publish 9m11s,
release 19m42s. This experiment does **not** optimize or execute those commands.

The release script separately runs four isolated clean builds serially, each
`clean assemble bexConformance sourceReleaseArchive`, then compares archive
hashes and conformance results before the final release graph. Those four builds
are candidates for future fanout. Its current verifier reopens live source
checkouts, caches, archives and manifests and requires an exact release tag;
transporting and verifying those inputs needs its own design. This experiment
neither invents a tag nor bypasses that verifier.

## Parallelism audit

`Java8LibraryConventionsPlugin` configures JUnit Platform but contains no explicit
maxParallelForks, heap cap or JUnit parallel property. Repository search found no
other such setting, nor an org.gradle.parallel/workers.max override. Thus the
single test fork and disabled JUnit concurrency follow framework defaults,
not an explicit BEX ban. History search found no maxParallelForks policy in
build-logic. This is distinct from Language's explicit restrictions.

JMH's two forks are separate benchmark runs, not two concurrent JUnit workers.
The serious campaign explicitly uses 2 forks, 3 warmups, 5 measurements, 250ms per
iteration and GC profiling. Modernization evidence checks that campaign shape;
it must not be shortened to manufacture a timing improvement.

## Safety and verification

Only the exact experiment branch push or guarded manual dispatch starts this
workflow. It has read-only contents permissions, no release secrets, no retained
checkout credentials and no publish/JReleaser/version/tag/push commands. Local
artifact packaging and GitHub upload-artifact report storage are expected.
No changes are made to the three existing workflows.

TDD_EXEMPTION: isolated workflow wiring has no useful pre-change behavioral RED.
Alternative verification: actionlint, focused receipt completeness/rejection
unit tests, static command/trigger audit, then all four actual CI jobs and the
summary. No expensive local Gradle operation is needed before remote testing.

Local checks:

```sh
python3 -m unittest discover -s .github/scripts -p 'test_ci_timing_experiment.py'
actionlint .github/workflows/ci-timing-experiment.yml
git diff --check
```

CI_PENDING: all four Measure jobs and final summary. A successful push alone is
not build evidence. Never interpret failed/incomplete runs as a speedup.
