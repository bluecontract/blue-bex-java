# Values and identity

BEX has one semantic value model and one ordinary BlueId model. Storage or
provider representation is not part of program-visible semantics.

## Value kinds

Portable operations work with:

```text
undefined
null
Boolean
Text
Integer
Decimal
object
list
exact Blue value
changeset/event helper views
```

`undefined` means absence and is distinct from null. Undefined object members
are omitted during Blue output conversion; undefined list members and an
undefined root cannot cross the Blue output boundary. Integers are exact and
decimals use deterministic decimal arithmetic. Non-finite floating-point input
is rejected.

## Exact and transient values

An exact value already has ordinary Blue identity/provenance. It may be backed
by an immutable inline node, a pure reference, or a verified materialized
cursor. Exact values cross BEX boundaries by identity: the runtime must not
recursively clone, serialize, size, or hash them just because they are passed,
returned, stored, or emitted.

A transient value was computed in BEX. It pays only for work actually performed:
construction, traversal, comparison, sorting, strict Blue conversion, and direct
identity establishment when requested or emitted.

Use the most truthful host boundary:

- `BexValues.frozen` for an immutable exact node;
- `BexValues.exact` for host-established canonical/resolved identity;
- `BexValues.referenceBacked` when semantic reads may demand verified provider
  materialization;
- `BexValues.fromSimple` for transient Java scalar/list/map input;
- `BexValues.nodeSnapshot` for mutable `Node` input that must be cloned/frozen.

## Representation blindness

A verified pure reference and its verified materialization are the same exact
BEX value. Portable observations must not reveal whether equivalent content is:

```text
inline or referenced
eager or lazy
warm or cold
held in one provider segment or several
```

This applies to kind, existence, keys, entries, size, truthiness, equality,
matching, iteration, pointer access, and explicit identity. Caching may reduce
host cost or diagnostics, but it cannot alter BEX gas or results.

A semantic read of a reference may require provider evidence. Unavailable
evidence is not silently converted to `undefined`; invalid evidence is a
deterministic failure. Merely carrying or returning the exact reference does not
require materialization.

## One BlueId

There is no BEX semantic ID, canonicalized-source ID, or alternate hash format.
`$nodeBlueId` follows exactly two paths:

1. For an exact value, return its already established ordinary BlueId without
   transitive expansion.
2. For a transient value, perform strict transient-to-Blue output admission and
   then direct ordinary BlueId establishment through the configured boundary.

It never runs Source preprocessing, complete Source resolution,
canonicalization, minimization, or a second identity algorithm. A transient
cyclic-set member identity cannot be invented in isolation.

## Equality is not identity

BEX equality follows the language's exact semantic rules, including numeric and
structural behavior. It is not a comparison of serialized Java objects and is
not interchangeable with BlueId equality. Matching uses Blue's focused matcher
boundary and preserves failures from unresolved/invalid evidence.

Object ordering is deterministic Unicode code-point ordering where canonical
order is required. It does not depend on locale, UTF-16 code-unit quirks, or host
map iteration order.

## Output identity

Existing exact output crosses by identity and charges only the output boundary
work defined by the gas model. Transient output is recursively validated as
runtime Blue content, established exactly once, and retained as an admitted
exact value alongside its semantic cursor. Nested exact descendants retain their
identity; the host boundary must not reopen them for redundant hashing.

Read [Blue output boundary](blue-output-boundary.md) for validation rules and
[Gas and exhaustion](gas-and-exhaustion.md) for the exact charge boundary.
