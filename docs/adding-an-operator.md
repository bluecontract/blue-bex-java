# Adding a portable operator

Portable operators are part of the BEX language, not application plugins. Add
one only as an intentional versioned language change. For application-specific
host work, use an [intrinsic](intrinsics.md).

This repository's current modernization preserves the existing 86 operators and
does not authorize new semantics.

## Required change set

A future operator change is incomplete unless one review covers all of these:

1. **Specification** — define source shape, operands, evaluation order,
   laziness, value kinds, failures, pointer behavior, output behavior, and worked
   examples in the BEX specification.
2. **Catalog** — add one machine-readable/operator-descriptor entry with its
   canonical name, expression-or-statement kind, family, and documentation link.
3. **Compiler and IR** — validate static operands, compile dynamic operands,
   retain source diagnostics, and represent the instruction immutably.
4. **Runtime** — implement deterministic semantics without importing compiler
   implementation details back into lower layers.
5. **Gas** — use the existing closed counter vocabulary when it exactly models
   the work. If it cannot, treat a vocabulary/weight change as a versioned gas
   manifest identity change; never hide work in estimates or `gasConsumed`.
6. **Normative fixtures** — add success, edge, lazy/evaluation-order, failure,
   identity/representation, and exact gas cases as appropriate.
7. **Coverage** — update operator and vector coverage so no catalog entry is
   unclassified or unexecuted.
8. **Documentation** — update the relevant concept guide and operator reference.
9. **API review** — classify any new public/protected signature and update the
   exact migration ledger/baseline intentionally.
10. **Evidence** — run focused tests, then the complete ordinary/conformance,
    Java 8, archive, API, dependency, and reproducibility gates required for the
    release mode.

## Semantic review questions

- Which operands are static, eager, lazy, or conditionally evaluated?
- What is the exact left-to-right/canonical traversal order?
- Which reads/constructions/comparisons occur, and which counters precede them?
- What happens for undefined, null, exact references, transient values, and
  provider evidence failure?
- Does the operator preserve representation blindness?
- Does it cross the strict Blue output boundary, and exactly once?
- Can exhaustion happen before each unit of work with the rejected charge absent?
- Are patch/event order and failure atomicity preserved?
- Does the operator accidentally add host authority or Contracts semantics?

## Things not to add as BEX operators

Timeline, Mandate, Coordination, `Process Embedded`, feeder, persistence,
collection activation, and Contracts `collectionPaths` are orchestration or host
semantics. BEX may compute an ordinary value or patch that a host later uses;
that does not move those decisions into the expression language.

## Commit discipline

Keep the catalog/specification/implementation/fixture/gas changes reviewable as
one semantic unit. Do not make fixtures pass by changing expected behavior after
the implementation. Exact result and gas-trace parity must remain visible in the
machine-readable report, and skipped/unclassified cases fail closed.
