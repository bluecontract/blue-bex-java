# Migrating to modular Blue Language

BEX no longer compiles against the removed monolithic `blue.language.Blue`
facade or the old `blue.language.utils` surface. It consumes focused modules and
the public modular runtime APIs.

## Dependency shape

Portable BEX uses the minimum applicable modules from:

```text
blue.language:blue-language-model
blue.language:blue-language-core
blue.language:blue-language-mapping   only for supported Java type mapping
```

Contracts adapters additionally use:

```text
blue.language:blue-contracts-core
```

`blue.language:blue-language-java` is the aggregate compatibility artifact. It
must not appear on focused production compile/runtime classpaths. The aggregate
is an explicit conformance-only runtime dependency so clean local and published
runs resolve and hash the compatibility artifact; the strict release gate also
downloads and authenticates it directly in isolation.

## Local composite mode

Use the explicit checkout path:

```bash
./gradlew --no-daemon clean bexWorkingVerification \
  -PblueLanguageCompositePath=/absolute/path/to/blue-language-java
```

Composite substitution maps every coordinate to its matching Language
subproject:

```text
blue-language-model   -> :blue-language-model
blue-language-core    -> :blue-language-core
blue-language-mapping -> :blue-language-mapping
blue-contracts-core   -> :blue-contracts-core
blue-language-java    -> :blue-language-java (isolated compatibility only)
```

Mapping the aggregate coordinate to `project(":")` is wrong: the Language root
is an orchestration project and has no library classes.

The working gate records the exact Language HEAD, code-equivalent implementation
commit when different, dirty state, module graph, project origins, resolved JAR
hashes, registry/Contracts identities, and BEX source state. The Language
checkout is read-only; never edit it to manufacture evidence.

## Source migration map

Important replacements include:

```text
blue.language.Blue                         -> blue.language.runtime.BlueLanguage
blue.language.utils.JsonPointer            -> blue.language.model.wire.JsonPointer
blue.language.snapshot.ResolvedSnapshot    -> blue.language.merge.ResolvedSnapshot
root NodeProvider                          -> focused provider package
root BlueOperation* outcomes               -> focused public API packages
old Properties core IDs                    -> BlueCoreTypeRegistry
old BlueId calculators/resolvers           -> public direct identity and explicit
                                                BEX-owned type resolver boundaries
```

Do not depend on `blue-language-mapping` merely to reach an internal resolver.
For intrinsic classes, prefer an explicit ordinary BlueId or
`BexTypeBlueIdResolver`. The exact public descriptor delta is recorded in
[`latest-language-api-migration.json`](latest-language-api-migration.json), with
both manifests, both classifications, and the complete additions/removals under
`gradle/verification/api/`.

## Published mode

Published mode resolves exact module coordinates from the controlled public
repository configuration. `mavenLocal()` or an uncontrolled same-GAV repository
must not masquerade as release evidence. The strict gate inspects coordinates,
origin, hashes, API, and four isolated published-mode repeatability runs.

If matching modular Language artifacts are unavailable, optional local-composite
developer work can still proceed, but it is never accepted by
`bexReleaseVerify`, which must remain red or `not-executed`. See
[Release](release.md).

## Consumer migration

Replace engine builder `.blue(oldFacade)` calls with `.language(BlueLanguage)`.
Move processor-specific setup to the `blue.bex.contracts` adapter rather than
passing `ProcessorExecutionContext` into core APIs. Preserve exact values with
the focused immutable snapshot/identity APIs instead of round-tripping through
maps or YAML.

The Contracts boundary moves are intentional pre-release source and binary API
changes; there is no compatibility shim in core:

| Previous API | Current API |
|---|---|
| `blue.bex.api.ProcessorExecutionContextBexDocumentView` | `blue.bex.contracts.ProcessorExecutionContextBexDocumentView` |
| `blue.bex.api.ProcessorExecutionContextBexGasLedgerHost` | `blue.bex.contracts.ProcessorExecutionContextBexGasLedgerHost` |
| `blue.bex.output.ProcessorExecutionContextBexSemanticIdentityBoundary` | `blue.bex.contracts.ProcessorExecutionContextBexSemanticIdentityBoundary` |
| `BexExecutionContext.Builder.processorExecutionContext(context)` | `BexContractsExecutionContext.builder(context)` or `configure(builder, context)` |
| `BexExecutionContext.Builder.processorExecutionContext(context, namespace)` | `BexContractsExecutionContext.builder(context, namespace)` or `configure(builder, context, namespace)` |

`BexGasLedgerHost` is now host-neutral: its signatures use BEX-owned
`BexGasLedgerCapability`, `BexSharedGasBudget`, `BexGasChargeContext`, and
`BexHostGasExhaustion` instead of Contracts `GasMeter.ChildGasLedger`,
`RuntimeWorkBudget`, `GasChargeContext`, and `GasLimitExceededException` types.
Core failure lifecycle decisions go through the BEX-owned `BexFailureBoundary`;
`BexContractsFailureBoundary` performs the Contracts classification/translation
at the adapter edge.

After migration, run an import scan for the removed packages, the full ordinary
and conformance suites, Java 8 bytecode verification, API comparison, and both
focused classpath and isolated aggregate checks.
