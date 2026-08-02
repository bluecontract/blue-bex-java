# Contracts hosting

`blue-bex-core` is independently executable and has no
`ProcessorExecutionContext` dependency. All Contracts-specific composition lives
in `blue-bex-contracts`, package `blue.bex.contracts`.

## What the adapter configures

Given the active `ProcessorExecutionContext`, the adapter configures one
`BexExecutionContext.Builder` with:

- invocation-owned canonical and resolved document views and current scope;
- current event, original processing event, current contract, and optional step
  result bindings;
- a live parent-bounded BEX gas capability;
- the processor-owned semantic-output/identity boundary;
- evidence and failure translation that preserves Contracts classification.

The host still selects the BEX source and engine/intrinsic registry. BEX neither
discovers programs nor gains general access to the processor context.

```java
public BexExecutionResult run(
        ProcessorExecutionContext processor,
        FrozenNode selectedExpression) {
    BexExecutionContext.Builder context = BexExecutionContext.builder();
    BexContractsExecutionContext.configure(context, processor, "bex:policy");

    try (BexEngine engine = BexEngine.builder().build()) {
        return engine.compileAndExecute(
                BexProgramSource.expression(selectedExpression),
                context.build());
    }
}
```

See the compile-tested hosted example for the exact current adapter signatures.
Use a stable deterministic runtime namespace. When one processor work session
runs several BEX programs, give each execution a distinct namespace.

## Document and exact-value provenance

The document adapter must preserve the invocation's canonical/resolved views,
scope, reference provider evidence, and immutable ownership. Exact values enter
BEX with their established ordinary BlueId and semantic cursor. They are not
cloned through Java maps or independently rehashed.

Transient output is admitted through the processor-owned semantic-output
boundary. The host returns one established exact value/cursor capability; BEX
retains it without repeating identity work. Root and nested Source `blue`
directives remain invalid runtime output.

## Shared gas

Hosted BEX consumes a live child ledger of the current `RuntimeWorkSession`.
The effective BEX-local limit can reduce but never replenish the parent's
remaining budget. Core BEX counters remain under the execution's physical
namespace; intrinsic namespaces remain separately registered.

Charges occur before work. The rejected entry is absent. The child trace merges
into Contracts exactly once, and no later BEX work or buffered effect commits
after exhaustion. Do not create a detached meter and reconcile an opaque total
after execution.

## Failure translation

The adapter preserves the exact host categories for:

```text
execution evidence unavailable
invalid execution evidence
portable limit exceeded
processor failure
host gas limit exceeded
```

Portable compiler/runtime/output defects remain BEX failures. An unexpected
runtime exception is not swallowed as undefined. If provider evidence is
temporarily unavailable, the host may suspend/retry outside a completed BEX run;
the failed attempt does not commit a child ledger or BEX effects. Invalid
evidence is deterministic and retains the admitted trace prefix required by the
host contract.

## Result ownership

The BEX engine returns value, ordered patches, ordered events, and evidence. It
does not apply or dispatch them. Contracts validates and decides the larger
transaction's commit/rollback behavior.

`collectionPaths`, Timeline, Mandate, Coordination, `Process Embedded`, and
collection activation remain Contracts/Coordination concerns. A BEX program can
construct an ordinary object or patch that adds it to a collection; only
Contracts decides whether that object becomes an embedded scope.

## Testing a host integration

Cover canonical/resolved/scope reads, exact inline/reference provenance,
unavailable and invalid provider evidence, direct transient identity, nested
exact descendants, multiple child namespaces, parent/local exhaustion,
rejected-charge omission, exactly-once trace merge, and failure rollback. Run
the generic hosted-consumer smoke against public APIs rather than internal test
helpers.
