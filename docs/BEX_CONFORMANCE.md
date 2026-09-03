# Blue BEX 2.0 conformance evidence

The executable BEX 2.0 package remains source controlled under
`src/test/resources/conformance/bex`. The conformance module consumes it but
does not package fixture implementation into the minimal runtime JAR.

## Normative inventory

`BexConformancePackageIntegrityTest` verifies the closed package inventory,
LF-normalized byte lengths and SHA-256 values, reverse vector coverage, direct
coverage for every operator, and all gas counters. The executable totals are:

```text
75 normative vectors
120 behavior fixtures
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

Use the exact reviewed Language release from Maven Central:

```bash
./gradlew --no-daemon clean bexWorkingVerification
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
exact 75/120/30/86 execution totals; all critical semantic sections; hosted
boundary selectors; Java 8 classfiles; exact reviewed API descriptors; all
module and source artifacts; and byte-identical BEX-owned archive replicas.

Run the longer modernization gate separately:

```bash
./gradlew --no-daemon bexModernizationVerification
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

An unpublished Language candidate is consumed only through its immutable,
commit-bound development repository:

```bash
./gradlew --no-daemon clean bexSdkStageVerify \
  -PblueLanguageRepository=/absolute/path/to/immutable-language-repository \
  -PblueLanguageVersion=3.1.0-dev.<language-source-commit> \
  -PbexLocalStageVersion=1.1.0-dev.<bex-source-commit> \
  -PbexSdkStagingRepository=/absolute/path/to/fresh-mutable-bex-stage
```

The repository must use schema `blue-development-maven-repository/1.0`, bind a
clean Language source commit and tree, and contain only the six reviewed
runtime/POM coordinates and their checksums. This development lane is not
public-release evidence and is never used by the final-publication gate.

## Reproducibility claims

`verifyReproducibleArchives` and
`verifySourceReleaseArchiveReproducibility` compare independently packaged
module/source/Javadoc/source-release archives from the same compiled inputs.
That is the BEX-owned working claim; it is not mislabeled as two clean builds.

Public release additionally uses four isolated BEX checkouts and four isolated
Gradle homes, all in `standalone-published` mode. The final-publication script
records exact artifact manifests and conformance receipts for every build,
requires byte-identical BEX artifacts, and compares the published semantic,
fixture, operator, and exact-gas evidence for repeatability.

## Fail-closed publication

`bexPublishedLanguageVerification` authenticates resolved artifact bytes
against `published-api-inspection.properties`, requires every reviewed API
claim and same-run published repeatability evidence, and cannot pass from a CLI
coordinate/hash alone. `bexReleaseVerify` then requires modernization, all four
independent standalone-published builds, clean exact-tagged BEX source, and
writes:

```text
build/reports/bex-release/final.json
build/reports/bex-release/final.md
```

Unavailable or incompatible published Language modules remain visibly
`not-executed` or `incompatible` and never become a public-release pass.
