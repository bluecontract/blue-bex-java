# BEX 2.0 Gas Model

BEX 2.0 meters deterministic logical work. It does not meter serialized bytes,
recursive value size, cache state, or physical Blue representation.

The normative sources are:

```text
specifications/blue-bex-specification-2.0.md
src/test/resources/conformance/bex/gas-manifest.yaml
```

The schedule identifier is `blue-bex/gas/2.0`. The implementation-baseline
manifest identity is:

```text
sha256:41247c820d91a12fdfc17fd9e787a5d8d668d8acc5954fdcb131715bf9e6147d
```

## Closed Counter Vocabulary

The portable BEX child ledger contains exactly these 30 counters:

| Counter | Weight | Logical work |
| --- | ---: | --- |
| `expressionEvaluated` | 1 | One executed expression. |
| `statementExecuted` | 1 | One executed statement. |
| `functionCalled` | 2 | The root invocation or one user-function invocation. |
| `intrinsicCalled` | 5 | One registry-bound intrinsic invocation. |
| `documentRead` | 2 | One document read operation. |
| `eventRead` | 1 | One event read operation. |
| `processingEventRead` | 1 | One processing-event read operation. |
| `currentContractRead` | 1 | One current-contract read operation. |
| `stepsRead` | 1 | One steps read operation. |
| `bindingRead` | 1 | One host-binding read operation. |
| `variableRead` | 1 | One local-variable read operation. |
| `constantRead` | 1 | One program-constant read operation. |
| `resultValueRead` | 2 | One accumulated-result-overlay read. |
| `pointerSegmentRead` | 1 | One examined pointer segment. |
| `pointerSegmentWritten` | 1 | One traversed or created write segment. |
| `objectMemberRead` | 1 | One direct object-member read. |
| `listItemRead` | 1 | One direct list-position read. |
| `collectionItemVisited` | 1 | One input item evaluated by collection work. |
| `collectionItemProduced` | 1 | One result item produced by collection work. |
| `textBlockExamined` | 1 | One examined block of up to 64 Unicode code points. |
| `textBlockConstructed` | 1 | One constructed block of up to 64 Unicode code points. |
| `integerLimbOperation` | 1 | One canonical base-`2^32` limb-work unit. |
| `comparisonNodeVisited` | 1 | One semantic node occurrence compared or matched. |
| `sortComparison` | 1 | One canonical stable-merge-sort comparator call. |
| `patchAppended` | 5 | One validated patch appended. |
| `eventAppended` | 5 | One non-undefined event appended. |
| `transientObjectMemberProduced` | 1 | One retained transient object member. |
| `transientListItemProduced` | 1 | One transient list item. |
| `blueOutputBoundary` | 5 | One value admitted at a Blue output boundary. |
| `nodeIdentityRequested` | 5 | One explicit `$nodeBlueId` request. |

`BexGasCounter` is the closed Java vocabulary. `BexGasSchedule` exposes the
manifest weights and supports deterministic overrides by these same names.

## Live Parent-Bounded Ledger

Before execution, the host supplies a child ledger bounded by its exact
remaining budget. Every charge is admitted before the associated work. A
charge that would exceed the effective budget is absent from the trace and the
work does not begin.

A BEX-local `gasLimit` may only lower the available parent budget:

```text
effectiveBudget = min(parentRemainingGas, localGasLimit)
```

The child ledger is merged into the parent exactly once and in trace order.
Already admitted charges remain observable on deterministic failure. Buffered
patches, events, and output are discarded when execution fails or exhausts gas.
Transient provider unavailability suspends outside completed execution and
does not commit a child ledger.

Portable evidence is the ordered named trace. `gasUsed()` and `totalGas()` are
convenience projections derived from that trace; no API accepts an opaque
aggregate gas integer as portable evidence.

## Evaluation and Lazy Work

Every executed expression charges `expressionEvaluated`. Every executed
statement charges `statementExecuted`. The root program and each called user
function charge `functionCalled`.

Skipped work charges nothing. This includes:

- unselected branches;
- operands skipped by lazy boolean/coalescing operators;
- collection items after a short circuit;
- `val` for a `remove` patch;
- unused lazy expressions.

A statically compiled constant is not reconstructed on each read. Its read
still charges `constantRead`, in addition to the expression charge.

## Reads and Pointers

Context reads charge their corresponding named read counter. Each examined
pointer segment charges `pointerSegmentRead`, and the semantic member or
position examined also charges `objectMemberRead` or `listItemRead`.

`$pointerSet` charges `pointerSegmentWritten` for every traversed or created
segment. It charges transient production only for structure it actually
creates. The assigned value is never recursively sized, cloned, or rehashed.

BEX owns the access it requests. The same logical member read is not charged
again as Contracts semantic work; Blue validation and identity establishment
remain host semantic work.

## Text and Numeric Work

Text work uses Unicode code points, not UTF-16 code units. A block contains up
to 64 code points:

```text
fullScan(t) = ceil(codePointLength(t) / 64)
construction(t) = ceil(constructedCodePointLength(t) / 64)
```

Comparisons charge only the blocks actually read through the first difference
or the end of the shorter operand.

Integer work uses an unsigned base-`2^32` magnitude with a separate sign. Let
`L(x)` be at least one and otherwise the magnitude limb count:

| Operation | `integerLimbOperation` quantity |
| --- | ---: |
| equality or ordering | `L(a) + L(b)` |
| addition or subtraction | `max(L(a), L(b)) + 1` |
| multiplication | `L(a) * L(b)` |
| division or remainder | `L(a) * L(b)` |

Exact decimal operations use the same formula over unscaled Integer magnitudes
and add one operation for scale alignment.

## Collections, Equality, and Sorting

Collection operators charge `collectionItemVisited` once per evaluated input
item. Output-producing operators additionally charge
`collectionItemProduced` once per produced item. Constructing a new object or
list charges the corresponding transient production counter. A single logical
iteration is not double-counted.

Deep equality and pattern matching charge `comparisonNodeVisited` once per
semantic node occurrence. Known exact Node BlueIds may conclude equality after
one visited comparison node. Text and numeric content add their corresponding
block or limb work.

Sorting uses the canonical trace of a stable bottom-up merge sort: initial run
width one, left-to-right merges, doubled width after each pass, and left
selection on equality. Every comparator call charges `sortComparison`,
`comparisonNodeVisited`, and any scalar-content work.

## Patches, Events, Output, and Identity

`$appendChange` charges `patchAppended` once after validation and required
operand evaluation. `$appendChanges` applies the same rule per entry.
`$appendEvent` charges `eventAppended` once after evaluating a non-undefined
event, and `$appendEvents` applies it per event.

Every value crossing a Blue boundary charges `blueOutputBoundary` once.
Existing exact nodes retain their exact identity and incur no recursive
construction or size charge. Transient values pay the logical construction,
validation, and host identity work they actually require.

`$nodeBlueId` charges `nodeIdentityRequested`. For a transient operand it also
crosses the Blue boundary and performs Contracts semantic identity
establishment. That identity work is merged once.

There is deliberately no `estimatedSize` counter or replacement based on
serialized payload bytes. Passing a large exact value through a variable,
function, patch, event, or output does not scan it. Content is charged only
when it is inspected, compared, constructed, iterated, sorted, validated, or
identified.

## Intrinsics

`$intrinsic` charges `intrinsicCalled` plus normal payload-expression work.
Each intrinsic registration binds:

- an exact intrinsic registry identity;
- a disjoint namespace;
- a closed name-to-weight map;
- its deterministic processor.

The processor calls `BexIntrinsicInvocation.charge(counter, quantity, reason)`.
Unknown counter names fail. An intrinsic cannot return arbitrary aggregate gas
or hide unnamed portable work.

Hosted execution opens each statically required intrinsic namespace as its own
runtime-session child. Intrinsic counters are never flattened into `bex`, and
`/` is reserved as the physical namespace separator.

## Trace and Conformance

Each admitted `BexGasCharge` records:

```text
sequence
namespace
counter
quantity
weight
gas
sourcePath?
operator?
reason
```

Sequence starts at zero, and `gas` is exactly `quantity * weight`. The ledger
total is the sum of the ordered trace.

The exact counter microfixtures live in:

```text
src/test/resources/conformance/bex/fixtures/gas-micro/
```

Run the complete BEX 2.0 fixture, integrity, and report workflow with:

```text
./gradlew bexConformanceReport \
  -PblueLanguageCompositePath=../blue-language-java
```
