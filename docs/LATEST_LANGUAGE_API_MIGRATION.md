# Latest Blue Language API migration ledger

This ledger describes the complete production binary-API delta caused by the
move from the removed monolithic Language facade to the modular Language API.
It is the human-readable companion to
[`latest-language-api-migration.json`](latest-language-api-migration.json).

## Audited source state

| Input | Exact state |
|---|---|
| BEX baseline | `395c484111f8c4e9e0e98d2db7f1c5b0777bd5a8` |
| Working compatibility checkpoint | `169e589` |
| BEX migration | Modernization delta rooted at the working checkpoint; the commit containing this ledger is the final target revision |
| Language target | `c3d58561220e6de6be6e302cb16799c1a1b5159f` |
| Language verified implementation | `c3d58561220e6de6be6e302cb16799c1a1b5159f` |
| Language target delta | None; the target and verified implementation commits are identical |
| Previous API manifest SHA-256 | `830caa187023079ba53fa76d2932e6e12cb8c93be3f90ac887ad374d6642b315` |
| Working-checkpoint API manifest SHA-256 | `43aea6ae9de6f39729f93c146ff5453887da9a2303f6f4f33573ba47f37d5be0` |
| Final modular API manifest SHA-256 | `5acb4712e3e03c5ba9a58d87b4a40bed4e1dcca76ed58ef355a77f0efc9d3c92` |

The migration target cannot truthfully name its eventual BEX commit while that
commit is being assembled. The Git commit containing this ledger is the target
revision; the baseline commit above is the exact revision against which every
entry was audited.

## Exact descriptor changes

The subsequent modular modernization contains 254 removed and 484 added exact
owner-qualified descriptors relative to commit `169e589`. Both compared manifests, both
classifications, and the complete sorted addition/removal sets are
source-controlled under `gradle/verification/api/`. The `binaryApiCheck` task
recomputes the set difference, compares every line, authenticates every file
hash recorded in the JSON ledger, and compares the final classification with
same-run generation. The tables below are reviewed highlights; they are not
presented as the exhaustive machine delta.

| Change | Classification | Exact signature | Replacement or purpose | Compatibility impact |
|---|---|---|---|---|
| Removed | stable API | `method public blue(blue.language.Blue):blue.bex.api.BexEngine$Builder` | Replaced by `language(BlueLanguage)` because the monolithic facade was removed. | Binary and source breaking for callers of `blue`. |
| Added | stable API | `method public language(blue.language.runtime.BlueLanguage):blue.bex.api.BexEngine$Builder` | Supported modular runtime entry point. | Additive alone; migration target for the removed method. |
| Added | intrinsic SPI | `method public intrinsic(java.lang.Class,blue.bex.api.BexTypeBlueIdResolver,java.lang.String,java.util.Map,blue.bex.api.BexIntrinsicProcessor):blue.bex.api.BexEngine$Builder` | Explicit application-owned class-to-BlueId policy. | Binary and source compatible addition. |
| Added | intrinsic SPI | `method public with(java.lang.Class,blue.bex.api.BexTypeBlueIdResolver,java.lang.String,java.util.Map,blue.bex.api.BexIntrinsicProcessor):blue.bex.api.BexIntrinsicRegistry` | Explicit resolver variant of immutable registration. | Binary and source compatible addition. |
| Added | intrinsic SPI | `method public register(java.lang.Class,blue.bex.api.BexTypeBlueIdResolver,java.lang.String,java.util.Map,blue.bex.api.BexIntrinsicProcessor):blue.bex.api.BexIntrinsicRegistry$Builder` | Explicit resolver variant of builder registration. | Binary and source compatible addition. |
| Added | intrinsic SPI | `class public abstract interface blue.bex.api.BexTypeBlueIdResolver` | BEX-owned replacement boundary for the removed Language resolver utility. | Binary and source compatible addition. |
| Added | intrinsic SPI | `method public abstract resolve(java.lang.Class):java.lang.String` | Exact mapping operation; class names are never treated as identities. | Compatible member of a new functional SPI. |
| Removed | internal implementation | `blue.bex.result.BexResultOverlay::<init>(blue.bex.api.BexDocumentView,blue.bex.result.BexMetrics,blue.language.Blue)` | Replaced by the same constructor with `BlueLanguage`. | Binary and source breaking for direct users of the public-but-internal type. |
| Added | internal implementation | `blue.bex.result.BexResultOverlay::<init>(blue.bex.api.BexDocumentView,blue.bex.result.BexMetrics,blue.language.runtime.BlueLanguage)` | Modular runtime replacement. | Additive alone; migration target for the removed constructor. |
| Removed | internal implementation | `blue.bex.runtime.BexRuntime::<init>(blue.bex.compile.BexCompiledProgram,blue.bex.api.BexExecutionContext,blue.language.Blue,blue.bex.gas.BexGasSchedule,blue.bex.result.BexMetrics,blue.bex.pointer.BexPointerCache)` | Replaced by the same constructor with `BlueLanguage`. | Binary and source breaking for direct users of the public-but-internal type. |
| Added | internal implementation | `blue.bex.runtime.BexRuntime::<init>(blue.bex.compile.BexCompiledProgram,blue.bex.api.BexExecutionContext,blue.language.runtime.BlueLanguage,blue.bex.gas.BexGasSchedule,blue.bex.result.BexMetrics,blue.bex.pointer.BexPointerCache)` | Modular runtime replacement. | Additive alone; migration target for the removed constructor. |
| Removed | internal implementation | `blue.bex.runtime.BexRuntime::<init>(blue.bex.compile.BexCompiledProgram,blue.bex.api.BexExecutionContext,blue.language.Blue,blue.bex.gas.BexGasSchedule,blue.bex.result.BexMetrics,blue.bex.pointer.BexPointerCache,blue.bex.api.BexIntrinsicRegistry)` | Replaced by the same constructor with `BlueLanguage`. | Binary and source breaking for direct users of the public-but-internal type. |
| Added | internal implementation | `blue.bex.runtime.BexRuntime::<init>(blue.bex.compile.BexCompiledProgram,blue.bex.api.BexExecutionContext,blue.language.runtime.BlueLanguage,blue.bex.gas.BexGasSchedule,blue.bex.result.BexMetrics,blue.bex.pointer.BexPointerCache,blue.bex.api.BexIntrinsicRegistry)` | Modular runtime replacement. | Additive alone; migration target for the removed constructor. |
| Removed | internal implementation | `blue.bex.type.BexBlueTypeMatcher::<init>(blue.language.Blue)` | Replaced by the constructor accepting `BlueLanguage`. | Binary and source breaking for direct users of the public-but-internal type. |
| Added | internal implementation | `blue.bex.type.BexBlueTypeMatcher::<init>(blue.language.runtime.BlueLanguage)` | Supported modular matcher/runtime boundary. | Additive alone; migration target for the removed constructor. |
| Removed | host SPI | `method public static referenceBacked(blue.bex.value.BexValue,blue.language.Blue):blue.bex.value.BexValue` | Replaced by the overload using the modular graph-capable runtime. | Binary and source breaking for direct host callers. |
| Added | host SPI | `method public static referenceBacked(blue.bex.value.BexValue,blue.language.runtime.BlueLanguage):blue.bex.value.BexValue` | Verified, demand-driven reference materialization through `BlueLanguage`. | Additive alone; migration target for host integrations. |

The compiler-package acyclicity pass contributes these reviewed descriptors:

| Change | Classification | Exact signature | Replacement or purpose | Compatibility impact |
|---|---|---|---|---|
| Removed | internal implementation | `class public final blue.bex.compile.BexCompiler` and its three public members | Compilation is now reached only through `BexEngine`; the package-private compiler and internal bridge bind the resulting opaque program to the complete compile environment. | Intentional pre-release removal of an implementation type that allowed callers to construct unbound executable IR. |
| Removed | stable API | `constructor public <init>(blue.bex.api.BexProgramSource$Kind,java.lang.String,java.lang.String,java.lang.String)` on `blue.bex.compile.BexCompiledProgramKey` | Replaced by the compile-owned kind. | Binary and direct-constructor source breaking. |
| Added | stable API | `constructor public <init>(blue.bex.compile.BexCompilationInput$Kind,java.lang.String,java.lang.String,java.lang.String)` on `blue.bex.compile.BexCompiledProgramKey` | Compile-owned cache-key kind. | Migration target for direct constructor callers. |
| Removed | stable API | `constructor public <init>(blue.bex.api.BexProgramSource$Kind,java.lang.String,java.lang.String,java.lang.String,java.lang.String)` on `blue.bex.compile.BexCompiledProgramKey` | Replaced by the compile-owned kind. | Binary and direct-constructor source breaking. |
| Added | stable API | `constructor public <init>(blue.bex.compile.BexCompilationInput$Kind,java.lang.String,java.lang.String,java.lang.String,java.lang.String)` on `blue.bex.compile.BexCompiledProgramKey` | Compile-owned environment-aware cache-key kind. | Migration target for direct constructor callers. |
| Removed | stable API | `method public kind():blue.bex.api.BexProgramSource$Kind` on `blue.bex.compile.BexCompiledProgramKey` | Replaced by the compile-owned kind result. | Binary and typed-read source breaking. |
| Added | stable API | `method public kind():blue.bex.compile.BexCompilationInput$Kind` on `blue.bex.compile.BexCompiledProgramKey` | Compile-owned kind result. | Use `BexCompilationInput.Kind` for typed reads. |
| Removed | stable API | `method public static from(blue.bex.api.BexProgramSource):blue.bex.compile.BexCompiledProgramKey` | Replaced by the compile-owned input factory. | Binary breaking; source compatible on recompilation. |
| Added | stable API | `method public static from(blue.bex.compile.BexCompilationInput):blue.bex.compile.BexCompiledProgramKey` | Compile-owned cache-key input. | Existing `BexProgramSource` calls remain source compatible. |
| Removed | stable API | `method public static from(blue.bex.api.BexProgramSource,java.lang.String):blue.bex.compile.BexCompiledProgramKey` | Replaced by the compile-owned input factory. | Binary breaking; source compatible on recompilation. |
| Added | stable API | `method public static from(blue.bex.compile.BexCompilationInput,java.lang.String):blue.bex.compile.BexCompiledProgramKey` | Compile-owned environment-aware cache-key input. | Existing `BexProgramSource` calls remain source compatible. |
| Added | stable API | `class public abstract interface blue.bex.compile.BexCompilationInput` | Compile-owned immutable source view. | Compatible additive type. |
| Added | stable API | `method public abstract isExpression():boolean` on `blue.bex.compile.BexCompilationInput` | Source-shape discriminator. | Member of a new type; already implemented by `BexProgramSource`. |
| Added | stable API | `method public abstract programNode():blue.language.snapshot.FrozenNode` on `blue.bex.compile.BexCompilationInput` | Selected frozen program root. | Member of a new type; already implemented by `BexProgramSource`. |
| Added | stable API | `method public abstract definitionNode():java.util.Optional` on `blue.bex.compile.BexCompilationInput` | Optional frozen definition root. | Member of a new type; already implemented by `BexProgramSource`. |
| Added | stable API | `method public abstract entry():java.util.Optional` on `blue.bex.compile.BexCompilationInput` | Optional selected entry. | Member of a new type; already implemented by `BexProgramSource`. |
| Added | stable API | `class public static final blue.bex.compile.BexCompilationInput$Kind extends java.lang.Enum` | Compile-owned cache source kind. | Compatible additive type. |
| Added | stable API | `field public static final FULL_PROGRAM:blue.bex.compile.BexCompilationInput$Kind` | Full-program kind. | Member of a new type. |
| Added | stable API | `field public static final EXPRESSION:blue.bex.compile.BexCompilationInput$Kind` | Expression kind. | Member of a new type. |
| Added | stable API | `method public static valueOf(java.lang.String):blue.bex.compile.BexCompilationInput$Kind` | Compiler-generated enum lookup. | Member of a new type. |
| Added | stable API | `method public static values():blue.bex.compile.BexCompilationInput$Kind[]` | Compiler-generated enum values. | Member of a new type. |
| Added | intrinsic SPI | `class public abstract interface blue.bex.compile.BexIntrinsicCatalog` | Compile-time intrinsic-membership view. | Compatible additive type. |
| Added | intrinsic SPI | `method public abstract supports(java.lang.String):boolean` on `blue.bex.compile.BexIntrinsicCatalog` | Exact BlueId membership. | Member of a new type; already implemented by `BexIntrinsicRegistry`. |
| Removed | stable API | `class public final blue.bex.api.BexProgramSource` | Declaration now records the compile-input role. | Compatible interface addition. |
| Added | stable API | `class public final blue.bex.api.BexProgramSource implements blue.bex.compile.BexCompilationInput` | Immutable host-to-compiler adapter. | Binary and source compatible interface addition. |
| Removed | intrinsic SPI | `class public final blue.bex.api.BexIntrinsicRegistry` | Declaration now records the compile-catalog role. | Compatible interface addition. |
| Added | intrinsic SPI | `class public final blue.bex.api.BexIntrinsicRegistry implements blue.bex.compile.BexIntrinsicCatalog` | Immutable runtime registry adapting to compilation. | Binary and source compatible interface addition. |

The exact string-BlueId intrinsic methods remain authoritative. The retained
class convenience does not infer identity from a class name: it uses either an
explicit `BexTypeBlueIdResolver` or the focused annotated-type mapping boundary.

## Modular host and IR package moves

The following pre-release moves are intentional and have no deprecated
compatibility aliases:

| Previous type | Final type | Reason |
|---|---|---|
| `blue.bex.api.ProcessorExecutionContextBexDocumentView` | `blue.bex.contracts.ProcessorExecutionContextBexDocumentView` | Contracts-host adapters no longer live in the pure API package. |
| `blue.bex.api.ProcessorExecutionContextBexGasLedgerHost` | `blue.bex.contracts.ProcessorExecutionContextBexGasLedgerHost` | Processor gas translation is Contracts-owned. |
| `blue.bex.output.ProcessorExecutionContextBexSemanticIdentityBoundary` | `blue.bex.contracts.ProcessorExecutionContextBexSemanticIdentityBoundary` | Hosted semantic-output admission is isolated from pure output code. |
| `blue.bex.runtime.CompileScope` | `blue.bex.compile.CompileScope` | Compiler scope moved with the immutable IR. |
| `blue.bex.runtime.CompiledExpression` | `blue.bex.compile.CompiledExpression` | Compiled expressions are compiler-owned IR. |
| `blue.bex.runtime.CompiledFrame` | `blue.bex.compile.CompiledFrame` | The frame uses the narrow `BexExecutionMachine` port. |
| `blue.bex.runtime.CompiledStatement` | `blue.bex.compile.CompiledStatement` | Compiled statements are compiler-owned IR. |
| `blue.bex.runtime.Control` | `blue.bex.compile.Control` | IR control flow moved with compiled statements. |

`BexCompiledProgram` now executes through the compile-owned
`BexExecutionMachine` interface. `BexRuntime` implements that interface and
uses `BexRuntimeContext` and `BexRuntimeIntrinsics`, which removes the final
compile/runtime package cycle without changing execution or gas semantics.

The final opacity and metrics pass removes public construction of executable
IR, binds every compiler-produced program to its exact compiler, Language,
gas-manifest, runtime-registry, and intrinsic-registry identity, and rejects a
program at execution when that identity differs. Runtime execution crosses an
internal `BexCompiledProgramRuntimeAccess` bridge; the stable program handle no
longer exposes `CompiledExpression`, `CompiledStatement`, or
`BexExecutionMachine` in its public methods.

`BexMetrics` is now an immutable compatibility view with no mutation methods.
The supported sink receives `BexMetricsSnapshot`; invocation-owned mutation is
confined to the API-classified internal `BexMetricsRecorder`. Sink failures are
isolated because diagnostics cannot alter compile/cache/execution/gas success.

## Public API classification and deterministic inventory

[`public-api-classification.json`](public-api-classification.json) classifies
all 101 public production types as stable API, host SPI, intrinsic SPI, internal
implementation, or conformance-only. The exact 1,028 class/member descriptors are
source-controlled in
`src/test/resources/hosted-release/required-public-api.txt`; that file is the
machine-comparable inventory, while the JSON file supplies intent metadata.

At this audited state the required inventory is byte-for-byte identical to
`blue-bex-conformance/build/reports/bex-release/public-api.txt`, and both have
SHA-256 `5acb4712e3e03c5ba9a58d87b4a40bed4e1dcca76ed58ef355a77f0efc9d3c92`.
Build wiring generates the latter from compiled classes and fails on any diff
from the reviewed source-controlled baseline.
