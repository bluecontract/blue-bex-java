# Gas and exhaustion

BEX 2.0 accounts deterministic logical work with a canonical named ledger. It
does not estimate serialized payload size or use machine-dependent time/memory
as portable gas.

## Normative schedule

```text
schedule:  blue-bex/gas/2.0
counters:  30
identity:  sha256:41247c820d91a12fdfc17fd9e787a5d8d668d8acc5954fdcb131715bf9e6147d
```

The closed vocabulary covers expression/statement/function/intrinsic work;
document and binding reads; pointer/object/list work; collection production;
text/numeric/comparison/sort work; patch/event append; transient construction;
Blue output admission; and identity requests. The exact names and weights live
in `src/main/resources/blue/bex/gas/blue-bex-gas-2.0.yaml` and are explained in
[`GAS.md`](GAS.md).

Changing a counter name, order, or weight is a semantic versioned change. This
modernization does not do so.

## Charge before work

Every logical operation asks the active ledger to admit its named charge before
performing that work. If the charge would exceed the effective budget:

1. the attempted charge is absent from the trace;
2. the associated work is not performed;
3. no later operand, callback, output admission, or intrinsic runs;
4. buffered patches and events do not commit.

The trace contains ordered sequence, counter, quantity, weight, subtotal, and
available source/operator/reason metadata. `gasUsed` is derived from admitted
trace entries; an opaque externally supplied total is never authoritative.

## Laziness and exact work

Short-circuited or unselected work has no charge. Lazy bindings charge only when
read. A warm cache cannot earn gas credit. Equivalent inline/reference,
eager/lazy, warm/cold, and provider-segmented representations have identical
portable gas traces for the same logical work.

Text work uses deterministic Unicode scalar-value blocks, not UTF-16 length.
Integer/decimal gas follows exact numeric work. Collections charge actual visits,
comparisons, and produced transient members. BEX never charges recursive
`estimatedSize` or serialized bytes.

Existing exact values cross by identity and are not recursively sized or
rebuilt. Transient values pay for actual construction, traversal, conversion,
and identity establishment.

## Standalone budgets

Standalone execution has an effective parent remaining budget and an optional
BEX-local limit. `NO_LOCAL_LIMIT` means the local layer does not further reduce
the parent. A local limit may reduce the effective budget but can never replenish
or exceed the parent budget.

`BexGasLimitExceededException` exposes the rejected counter and budget evidence
for portable standalone exhaustion. Callers must not resume the failed runtime.

## Contracts-hosted budgets

The Contracts adapter opens a live child capability against the invocation's
shared parent budget. All BEX work and registered intrinsic namespaces consume
that same live parent capacity. If one processor invocation runs several BEX
programs, each uses a distinct deterministic physical namespace while sharing
the parent.

The child trace merges into Contracts exactly once. BEX must not independently
replay entries, return a magic `gasConsumed`, or let a local limit replenish the
parent. Host exhaustion/failure classification remains owned by Contracts.

## Intrinsic gas

Each intrinsic registration declares an immutable namespace-local counter
vocabulary and weights. An invocation may charge only those names. Intrinsic
work cannot be hidden in a portable integer or charged under the BEX core
namespace. See [Intrinsics](intrinsics.md).

## Evidence

The 30 gas microfixtures cover every closed BEX counter. Additional tests cover
exact charge order, lazy branches, representation parity, parent/shared limits,
rejected-charge omission, no work after rejection, and hosted merge behavior.
Passing claims come only from current generated reports; this guide does not
claim that an unexecuted release gate has passed.
