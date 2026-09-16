# Java 25 CI and shared release verification

## Goal and scope

Build and validate, Release RC and Release use Java 25 for build logic,
compilation, tests and Javadoc. Published library bytecode remains Java 8 via
`--release 8`; the existing bytecode check remains enabled. Existing published
Blue Language coordinates, hashes and build provenance are unchanged.

The release topology prepares one source, executes four independent clean builds
on four runners, compares their real archives and semantic/gas evidence, then
runs `bexReleaseVerify publish jreleaserFullRelease` in one Gradle invocation.
The graph contains shared verification tasks once. There are no test exclusions,
synthetic passing receipts or publication bypasses. JReleaser explicitly runs
after all three local Maven publication tasks.

Build and validate runs the original verification and JMH groups on two runners.
Their coverage is unchanged. Each result has a separate artifact name.

## Source and publication safety

The producer supplies commit/tree outputs and a Git bundle with metadata bound
to workflow SHA, run ID, attempt and mode. Every consumer compares those outputs
before restoring the bundle. RC must be one child of the workflow SHA changing
only `.cz.toml`; stable and verify sources must equal the workflow SHA.

Production RC preserves preparation of its version commit and exact local tag,
then pushes them only after a successful release. Stable requires an existing
exact version tag on main. Production modes require their exact branches.

The isolated `BEX release topology on Java 25 (no publication)` workflow calls
the same topology in `verify` mode, with no passed secrets. That mode never
creates a tag or selects publication tasks. The unchanged final strict report
must have every verification status passed, clean source, exactly the missing
version-tag blocker and `releaseReady=false`. The expected final Gradle failure
is accepted only under those precise conditions, including the failed task and
exception. Any other failure remains a failed job.

## Measurements

The completed all-Java25 paired experiment is run
[35116423386](https://github.com/bluecontract/blue-bex-java/actions/runs/35116423386),
commit `304063131d2aceac510f282e44337232465d29fd`:

| Computation | Before | After |
| --- | ---: | ---: |
| Four builds plus release verification, including repeated publish/JReleaser prerequisites | 34:47.53 | 12:36.02 |

Reduction: **63.78% / 22:11.52**. Both variants have identical 968 JUnit cases,
42 JMH cases, four independent inventories of 965 JUnit cases and ten identical
release artifacts per independent build. Candidate time includes transfer/join.
All compilation, test, Javadoc and build-logic toolchains use Java25.

The actual shared production topology also passed without publication in PR12,
run [35116490725](https://github.com/bluecontract/blue-bex-java/actions/runs/35116490725):
11:09.24 measured fanout-to-final, 11:44 including source preparation/setup/upload.
Its PR merge source was `c3b64b7b955120bb40c16d67075560dbf58786e0`.
Do not mix this separate runner result with the paired baseline when computing
percentage improvement. Actual Build and validate run
[35116490662](https://github.com/bluecontract/blue-bex-java/actions/runs/35116490662)
also passed: verification 1:43, serious JMH 7:49, total workflow about8:10.

The later documentation/manual-trigger cleanup changes none of the measured
build commands, toolchains or verification code. Historical timing experiments
are manually triggered; normal PR checks run the production verification paths.
CPU utilization/PSS were not sampled by these BEX experiments.

The original public RC took 46:05. About 10:40 was actual JReleaser signing,
upload, remote processing and release work. That external portion is unmeasured
in the no-publication experiment. Do not label 12:22 as a complete public release.

## Verification

RED command before implementation:
`PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s .github/scripts -p test_release_ci.py`
failed because the release driver implementing the required mode/identity
contract did not exist. Tests now cover exact command selection, forbidden
production branches, every identity binding and a real untagged bundle
round-trip with stale attempt rejection.

The mechanical extraction of unchanged evidence helpers and Java25 configuration
use a TDD exemption: existing receipt/strict-report tests plus actual Gradle
configuration, Javadoc, bytecode and task-graph checks provide direct evidence.

| Check | Command | Tier |
| --- | --- | --- |
| Driver and measurement contracts | `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s .github/scripts -p 'test*.py'` | Local fast |
| Workflow structure | `actionlint .github/workflows/*.yml` | Local fast |
| Production graph, without running publication | `CI=true ./gradlew --no-daemon --max-workers=2 bexReleaseVerify publish jreleaserFullRelease --dry-run` | Local fast |
| Java25 Javadoc + Java8 bytecode | `CI=true ./gradlew --no-daemon --max-workers=2 :blue-bex-contracts:javadoc :blue-bex-contracts:java8BytecodeCheck` | Local fast |
| Full production topology without publication | `BEX release topology on Java 25 (no publication)` | CI_PENDING |
| Full build and JMH groups | `Build and validate` pull request check | CI_PENDING |

Local Gradle commands use an explicitly selected Java25 JAVA_HOME. No local
full suite, tag creation, package publication or release is part of this gate.
