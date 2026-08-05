# Runtime and execution context

One BEX execution combines a shareable engine and compiled program with a
run-owned execution context. The runtime evaluates deterministic instructions,
buffers result effects, accounts named gas, and admits Blue output.

## Engine and run ownership

`BexEngine` owns immutable configuration, intrinsic registry, gas schedule,
metrics sink, pointer cache, and compiled-program cache. It is designed for
reuse, including concurrent independent compile/execute calls. The engine is
`AutoCloseable`: it closes only a default `BlueLanguage` runtime it created;
an explicitly supplied runtime remains caller-owned.

`BexExecutionContext` belongs to one run. Its document view, bindings, lazy
binding slots, parent/local gas configuration, host gas capability, and semantic
identity boundary must not be used as cross-run mutable state. Compiled frames,
variables, accumulator, result overlay, gas session, and metrics are likewise
run-local.

## Document views

`BexDocumentView` exposes canonical and resolved reads plus the current scope
path. `$document` uses the canonical view unless the operand explicitly asks for
the resolved view. Scope-relative pointers are resolved by the view; callers
should not maintain a second competing scope value.

Standalone integrations usually use `FrozenBexDocumentView`. Hosted integrations
must use the Contracts adapter so canonical/resolved/evidence behavior comes from
the active processor invocation.

## Bindings

Hosts provide named `BexValue` bindings. Standard bindings include event,
processing event, current contract, and prior step results. A missing binding
reads as defined by the operator/context contract; invalid standard-binding
configuration fails during context construction.

Lazy bindings are evaluated at most once per built context and only on demand.
Their value or failure is memoized. Cyclic lazy reads fail deterministically.
Materializing `bindings()` deliberately resolves all slots in declaration order.
Suppliers must not rely on being called, because lazy control flow may skip them.

Inputs are non-null unless the API explicitly maps `null` to undefined or a
documented default. Prefer explicit `BexValues.undefined()` when absence is part
of the program model.

## Frames and control flow

The root invocation and each function call have isolated frames. Variable
initialization, update, and collection iteration follow compiled lexical rules.
Control values for return/failure never escape as ordinary BEX values. Operand,
statement, patch, and event order are normative.

## Buffered effects and overlay

`$appendChange` and `$appendEvent` append to run-local ordered buffers.
`$resultValue` reads a lazy overlay of the original document plus current
patches. The engine does not apply those patches and does not dispatch events.
On runtime failure or exhaustion, no successful result is returned for the host
to commit.

## Results and metrics

`BexExecutionResult` contains the root value, ordered changeset/events, canonical
gas ledger/trace, admitted output metadata when applicable, and defensive metric
snapshots. `gasUsed()` is derived from the trace rather than accepted as an
opaque host number.

Wall-clock and cache metrics are diagnostic. Internal mutation is confined to a
run-owned recorder; results and `BexMetricsSink` expose immutable
`BexMetricsSnapshot` values. A throwing sink is isolated and cannot change a
successful compilation, cache update, execution, or gas-ledger completion into
an observable failure. A metrics sink should still be thread-safe if its engine
is shared.

## Failures

Runtime failures stop later work. The charge for already admitted work remains;
the rejected gas charge and all later work are absent. Portable failures remain
BEX-owned, while the Contracts adapter preserves host evidence, processor, and
gas classifications. See [Gas and exhaustion](gas-and-exhaustion.md) and
[Contracts hosting](contracts-hosting.md).
