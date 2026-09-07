# Conformance

The normative BEX 2.0 behavior is defined by the specification plus the
source-controlled machine-readable packages. Prose summaries do not override a
fixture manifest.

## Exact package baseline

```text
normative vectors       75
behavior fixtures      120
gas microfixtures       30
normative operators     86

runtime registry
sha256:2ccbfc9d1a1c4425cdcaf37c924274cc4398f82ac72769a8c2cf1dd8ba2fd04b

gas manifest
sha256:41247c820d91a12fdfc17fd9e787a5d8d668d8acc5954fdcb131715bf9e6147d

fixture package
sha256:fdb896bf467c0ab8f3b2c9af3b609c9e7d0dc368dd3061f4b2909d0ee1fa89c3
```

Documentation-only corrections do not change these identities. If
identity-bearing registry, manifest, or fixture bytes change in a future
version, the identity must change and be reviewed explicitly.

## What conformance covers

The package covers compiler validation; expression and statement semantics;
functions, constants, pointers, overlays, patches, and events; exact/transient
output; direct identity; intrinsic dispatch; representation invariance; provider
evidence; recursion/termination; all operator coverage; and exact named gas.

The strengthened representation matrix requires equivalent exact values to be
indistinguishable across inline/reference, eager/lazy, warm/cold, and provider
segmentation for kind, existence, keys, entries, size, truthiness, equality,
matching, iteration, pointer access, explicit identity, and portable gas.

Each gas counter has a microfixture. Exhaustion evidence checks the admitted
prefix, absent rejected charge, zero later work, discarded buffered effects,
local/parent limits, hosted shared budget, and exactly-once child merge.

No new BEX fixture is required merely because Contracts supports
`collectionPaths`: activation of embedded scopes is outside BEX semantics.

## Run the working suite

```bash
./gradlew --no-daemon clean bexSdkStageVerify \
  -PblueLanguageRepository=/absolute/path/to/immutable-language-repository \
  -PblueLanguageVersion=3.1.0-dev.<language-source-commit> \
  -PbexLocalStageVersion=1.1.0-dev.<bex-source-commit> \
  -PbexSdkStagingRepository=/absolute/path/to/fresh-mutable-bex-stage
```

The gate includes all ordinary tests, all fixture/vector/gas/operator coverage,
immutable-repository compile/runtime smoke, hosted adapters, Java 8 bytecode,
public API descriptors, artifacts, BEX-owned archive determinism, and a clean
machine-readable report. The repository must use the
`blue-development-maven-repository/1.0` schema and contain the exact six-module
Language runtime/POM closure; included-build substitution is not accepted by
this lane.

A green working result requires:

```text
failed       = 0
skipped      = 0
unclassified = 0
workingReady = true
```

Generated evidence lives under `build/reports`. Counts are derived from actual
JUnit/fixture mappings, not hardcoded as passing because an inventory test ran.
Missing, stale, failed, skipped, and unexecuted evidence stays visible.

## Reports are evidence, not declarations

Archive determinism from repackaging one compiled input is not the same claim as
two independent clean builds. Local-composite success is not published-artifact
success. A source inventory is not execution. Reports bind their claims to the
source commit/dirty state, dependency mode, artifact hashes, and exact test
results.

See [`BEX_CONFORMANCE.md`](BEX_CONFORMANCE.md) for lower-level report fields and
[Release](release.md) for the stricter public gate.
