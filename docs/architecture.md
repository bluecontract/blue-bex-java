# Architecture

The design separates the portable BEX runtime from Contracts hosting,
conformance machinery, examples, and build tooling. Dependencies point inward;
host-specific code never leaks into the portable runtime.

## Projects

```text
blue-bex-core          portable public API, compiler, IR, runtime, values,
                       pointers, results, gas, output and intrinsic SPIs
blue-bex-contracts     ProcessorExecutionContext adapters and Contracts failure,
                       evidence, exact-value, semantic-output and gas bridges
blue-bex-conformance   fixture runners, integrity checks and evidence production
blue-bex-java          one-coordinate aggregate with no duplicate implementation
examples               compile-tested standalone and hosted integrations
build-logic            typed conventions, verification and publication tasks
```

`blue-bex-core` depends only on the focused Language model/core facilities it
uses. `blue-bex-contracts` depends on core plus `blue-contracts-core`.
Conformance and examples consume public modules; neither is packaged in the
minimal runtime JAR. The aggregate re-exports the intended runtime artifacts.

There must be no project cycle, package cycle larger than one, or Java split
package. Contracts adapters therefore live only in `blue.bex.contracts`.

## Runtime flow

```text
selected FrozenNode
      |
BexProgramSource
      |
BexCompiler -- validates static shape and creates immutable compiled program
      |
BexEngine cache -- keyed by source plus compiler/runtime/gas/intrinsic identity
      |
BexRuntime -- one run-local frame/context/accumulator/gas session
      |
BexOutputAdmission -- exact pass-through or strict transient Blue admission
      |
BexExecutionResult -- value + patches + events + gas + metrics + output metadata
```

Compilation never performs host actions. Runtime reads are mediated by
`BexDocumentView` and explicit bindings. Output admission is the only route from
transient BEX data to exact Blue output. Intrinsics are statically identified by
BlueId and receive only their declared payload and named gas capability.

## Ownership and mutability

- An engine owns its immutable configuration and thread-safe compiled-program
  cache. It may be shared by concurrent callers.
- A compiled program is immutable and shareable.
- An execution context, runtime frame, accumulator, gas session, and result
  belong to one run. Do not reuse a context to communicate between runs.
- Exact host nodes are immutable snapshots/cursors. Mutable `Node` input must be
  cloned/frozen at the boundary unless the host explicitly guarantees immutable
  ownership.
- Returned collections and ledgers are immutable views or defensive copies.

Public inputs are non-null unless a method explicitly documents `null` as
`undefined`, absence, or a default. Builder defaults are intentional; passing
`null` to bypass a required boundary is not supported.

## Failure boundary

Portable compiler/runtime/output errors use BEX-owned failures. The Contracts
adapter translates evidence unavailable, invalid evidence, portable limits,
processor failures, and host gas exhaustion without erasing their host
classification. Unexpected exceptions do not become successful undefined
values.

No patch or event is committed by the engine. On failure or exhaustion, the
host receives no successful BEX result and must apply its own whole-invocation
rollback rules.

## Gas ownership

Core owns the closed BEX counter vocabulary, schedule, trace model, and the rule
that a charge precedes its work. Standalone execution owns a local ledger.
Hosted execution opens a parent-bounded child capability and merges its trace
exactly once. Intrinsic namespaces remain separate and can use only their
registered named counters.

Metrics such as wall-clock time are diagnostic only. They never affect
semantics, cache keys (except declared configuration identity), BlueId, or gas.

See [Compiler and IR](compiler-and-ir.md), [Runtime and context](runtime-and-context.md),
and [Contracts hosting](contracts-hosting.md).
