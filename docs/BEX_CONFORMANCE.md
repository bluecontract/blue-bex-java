# Blue BEX 2.0 conformance evidence

The executable BEX 2.0 package remains source controlled under
`src/test/resources/conformance/bex`. The conformance module consumes it but
does not package fixture implementation into the minimal runtime JAR.

## Normative inventory

`BexConformancePackageIntegrityTest` verifies the closed package inventory,
LF-normalized byte lengths and SHA-256 values, reverse vector coverage, direct
coverage for every operator, and all gas counters. The executable totals are:

```text
60 normative vectors
105 behavior fixtures
30 gas microfixtures
86 operators
```

`BexConformanceFixtureTest` executes every manifest-declared fixture and case.
`BexGasMicrofixtureTest` checks namespace, counter, sequence, quantity, weight,
subtotal, reason, and trace-derived total. There is no assumption, disabled, or
skip path in the harness.

The report also derives representation, provider/cyclic evidence, semantic
identity admission, hosted ledger lifecycle, intrinsic dispatch, and gas
exhaustion from exact executed JUnit selectors. A passing inventory check is
never converted into an invented execution count.

## Run locally

Use the explicit verified Language composite:

```bash
./gradlew --no-daemon clean bexWorkingVerification \
  -PblueLanguageCompositePath=/absolute/path/to/blue-language-java
```

The working gate creates:

```text
blue-bex-conformance/build/reports/bex-conformance/report.json
blue-bex-conformance/build/reports/bex-conformance/report.md
blue-bex-conformance/build/reports/bex-release/public-api.txt
blue-bex-conformance/build/reports/bex-release/public-api-classification.json
build/reports/latest-language-migration/baseline.json
build/reports/latest-language-migration/final.json
build/reports/bex-modernization/architecture.json
```

The final working report requires zero failed, skipped, and unclassified tests;
exact 60/105/30/86 execution totals; all critical semantic sections; hosted
boundary selectors; Java 8 classfiles; exact reviewed API descriptors; all
module and source artifacts; and byte-identical BEX-owned archive replicas.

Run the longer modernization gate separately:

```bash
./gradlew --no-daemon bexModernizationVerification \
  -PblueLanguageCompositePath=/absolute/path/to/blue-language-java
```

It adds architecture/source metrics, concurrency and property tests, fourteen
developer guides, and the serious two-fork JMH campaign with GC allocation
profiling. Its reports are:

```text
build/reports/bex-modernization/final.json
build/reports/bex-modernization/final.md
blue-bex-conformance/build/reports/jmh/results.json
blue-bex-conformance/build/reports/jmh/environment.json
```

## Reproducibility claims

`verifyReproducibleArchives` and
`verifySourceReleaseArchiveReproducibility` compare independently packaged
module/source/Javadoc/source-release archives from the same compiled inputs.
That is the BEX-owned working claim; it is not mislabeled as two clean builds.

Public release additionally uses four isolated BEX checkouts and four isolated
Gradle homes: two standalone-published builds and two local-composite builds.
`.github/scripts/run-final-publication-gates.sh` records exact artifact
manifests for each pair and compares local versus published conformance fields
for semantic and exact-gas equality.

## Fail-closed publication

`bexPublishedLanguageVerification` authenticates resolved artifact bytes
against `published-api-inspection.properties`, requires every reviewed API
claim and a same-run local/published differential, and cannot pass from a CLI
coordinate/hash alone. `bexReleaseVerify` then requires modernization, both
independent clean-build pairs, clean exact-tagged BEX source, and writes:

```text
build/reports/bex-release/final.json
build/reports/bex-release/final.md
```

Unavailable or incompatible published Language modules remain visibly
`not-executed` or `incompatible`; they never make local-composite evidence red
and never become a public-release pass.
