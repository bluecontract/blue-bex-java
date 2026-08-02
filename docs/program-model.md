# Program model

A BEX program is a deterministic Blue object tree selected by its host. BEX does
not search a document for executable content and does not execute arbitrary
objects merely because a key begins with `$`.

## Source forms

`BexProgramSource.expression(node)` compiles one expression.
`BexProgramSource.inline(node)` compiles a full program. A definition-backed
source combines a selected program node, shared definition node, and entry
function.

A full program may contain:

```text
constants    statically named literal/expression-independent values
functions    statically named functions with declared argument patterns
do           ordered statement list
expr         root expression
```

Name containers are Blue object properties, so Blue-reserved language keys are
not valid user names. Functions have a fixed argument ABI: missing and extra
arguments are compile errors. Declared Blue patterns are static and are checked
after call operands evaluate.

## Operators and literals

An operator is an object with exactly one recognized operator key in an
operator position:

```yaml
$concat:
  - "order-"
  - $document: /orderNumber
```

Normal objects, lists, scalars, null, and undefined form the value model.
`$literal` prevents an operator-looking object from being compiled. Static Blue
fields such as `type` and `schema` retain Blue meaning; BEX expressions are not
evaluated inside static patterns.

The normative list of 86 operators and each operand shape is in the
[BEX 2.0 specification](../specifications/blue-bex-specification-2.0.md).
The machine-readable operator coverage catalog prevents documentation or
compiler recognition from silently drifting.

## Evaluation order and laziness

Operand evaluation order is defined by each operator, not by Java map iteration.
Where multiple operands are eager, they evaluate in normative order. Lazy
operators evaluate only required operands:

- `$and` and `$or` short-circuit;
- `$coalesce` stops at its selected value;
- `$if`/`$choose` execute only the selected branch;
- collection operators execute their body in source collection order;
- `$return`, `$returnIf`, failure, and gas exhaustion stop later work.

Skipped work performs no reads, construction, intrinsic calls, output admission,
or gas charges. Object keys use deterministic Unicode ordering where the
specification requires canonical traversal/sorting.

## Variables, constants, functions, and scopes

`$const` names a declared constant and is resolved at compile time. `$var` reads
a run-local variable or function argument. Names are atomic: a slash in a name
is not a pointer. `$let` initializes a name once in its scope; `$set` updates an
existing initialized variable under the language rules.

Function call graphs are compiled before execution. Recursive cycles are
rejected. Each call has an isolated frame; collection bodies add their specified
item/key/index bindings without leaking them after the iteration.

## Reads and effects

Document, event, processing-event, current-contract, steps, binding, variable,
constant, and result-overlay reads are distinct operations with distinct gas.
JSON Pointers have explicit canonical/resolved/scope-relative meanings.

Changes and events are append-only run-local data. Patch order, event order, and
duplicates are significant. `$resultValue` reads a lazy overlay of the original
document and accumulated patches; reading it does not commit the patch.

## Compile errors versus runtime errors

Compilation rejects defects knowable from the selected source: unknown or
malformed operators, invalid declaration names, missing constants/functions,
call ABI mismatches, invalid static operands/patterns, recursive call graphs,
and multiple operators where one is required.

Runtime errors depend on actual data or selected control flow: missing bindings,
dynamic pointer/key/type failures, exact arithmetic failures, division by zero,
explicit failure, invalid patch/event payloads, failed intrinsics, output
admission, provider evidence, and exhaustion.

Diagnostics should preserve error class, source path, operator, function/frame,
and safe pointer details when known. Exact message text is normative only where a
fixture says so.

## Deliberate non-features

BEX does not define Timeline, Mandate, Coordination, `Process Embedded`, feeder,
persistence, or contract lifecycle semantics. Contracts `collectionPaths` and
collection activation decide whether an ordinary object-producing patch creates
an embedded scope. That decision does not add a BEX operator or change BEX gas.
