# Latest Blue Language API migration ledger

This ledger describes the complete production binary-API delta caused by the
move from the removed monolithic Language facade to the modular Language API.
It is the human-readable companion to
[`latest-language-api-migration.json`](latest-language-api-migration.json).

## Audited source state

| Input | Exact state |
|---|---|
| BEX baseline | `395c484111f8c4e9e0e98d2db7f1c5b0777bd5a8` |
| BEX migration | Uncommitted working-tree delta rooted at the exact baseline above |
| Language target | `9a607e584ff5dd973684d35d71eb4022d946b760` |
| Language verified implementation | `63a9ed6a1a66d47119a80d16ed2ab0beda0d2453` |
| Language target delta | `LICENSE`, one migration report, and one modernization report only |
| Previous API manifest SHA-256 | `830caa187023079ba53fa76d2932e6e12cb8c93be3f90ac887ad374d6642b315` |
| Migrated API manifest SHA-256 | `43aea6ae9de6f39729f93c146ff5453887da9a2303f6f4f33573ba47f37d5be0` |

The migration target cannot truthfully name its eventual BEX commit while that
commit is being assembled. The Git commit containing this ledger is the target
revision; the baseline commit above is the exact revision against which every
entry was audited.

## Exact descriptor changes

There are six removals and eleven additions. No other generated production API
descriptor changed.

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

The exact string-BlueId intrinsic methods remain authoritative. The retained
class convenience does not infer identity from a class name: it uses either an
explicit `BexTypeBlueIdResolver` or the focused annotated-type mapping boundary.

## Public API classification and deterministic inventory

[`public-api-classification.json`](public-api-classification.json) classifies
all 72 public production types as stable API, host SPI, intrinsic SPI, internal
implementation, or conformance-only. The exact 798 class/member descriptors are
source-controlled in
`src/test/resources/hosted-release/required-public-api.txt`; that file is the
machine-comparable inventory, while the JSON file supplies intent metadata.

At this audited state the required inventory is byte-for-byte identical to
`build/reports/bex-release/public-api.txt`. Build wiring should continue to
generate the latter from compiled classes and fail on any diff from the former.
