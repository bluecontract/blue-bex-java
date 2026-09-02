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
sha256:23d282ec1c0bb016263922b1b49c369fdd537efdcf23e005eceeb888d7763fe1

gas manifest
sha256:41247c820d91a12fdfc17fd9e787a5d8d668d8acc5954fdcb131715bf9e6147d

fixture package
sha256:bb78e22d4cf9f76e88423a34917d1b09a286015432c0ba2ac07192b7f30ec35c
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
./gradlew --no-daemon clean bexWorkingVerification \
  -PblueLanguageCompositePath=/absolute/path/to/blue-language-java
```

The gate includes all ordinary tests, all fixture/vector/gas/operator coverage,
local composite compile/runtime smoke, hosted adapters, Java 8 bytecode, public
API descriptors, artifacts, BEX-owned archive determinism, and a clean
machine-readable report.

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
