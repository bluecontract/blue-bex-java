# Blue output boundary

BEX values are not automatically valid Blue nodes. Every value that leaves a
runtime position requiring Blue content passes through one strict, atomic output
admission boundary.

## Exact values

An existing exact value already owns an ordinary BlueId and verified semantic
cursor. Admission preserves that identity. It does not recursively clone,
serialize, size, expand, or rehash the value or its exact descendants.

A pure reference has exactly one `blueId` and no sibling payload. Its identity is
enough for pass-through; semantic materialization is demanded only by an
operation that actually inspects content.

## Transient values

A transient value is converted recursively to runtime Blue content:

- undefined root fails;
- undefined object members are omitted;
- an undefined list member fails;
- null becomes the Blue null/empty-node form;
- scalar kinds use deterministic Blue scalar rules;
- objects and lists traverse in the order required by BEX;
- exact descendants remain exact rather than being reconstructed;
- the final node is validated before direct identity establishment.

The host's `BexSemanticIdentityBoundary` establishes the ordinary BlueId exactly
once. The admitted result retains both exact identity and the run-local semantic
cursor, so a later `$resultValue`, variable, event, or changeset read does not
repeat conversion or hashing.

## Runtime content, not Source content

Output is runtime Blue content. It is not a Source document waiting for
preprocessing. A root or nested `blue` directive is invalid, and admission never
runs Source preprocessing, complete resolution, canonicalization, or
minimization.

Other fail-closed rules include:

- scalar `value`, list `items`, and object payload cannot be mixed;
- `blueId` with sibling payload is invalid;
- computed `type`, `itemType`, `keyType`, `valueType`, `schema`, `mergePolicy`,
  and `contracts` retain Blue Language meaning and must validate;
- unsupported schema/compatibility keys are rejected;
- `$previous`, `$pos`, and `$replace` list-control source forms are rejected;
- `$empty: true` is accepted only in the exact valid Blue placeholder shape;
- a transient cyclic-set member identity cannot be established without the
  host's complete cyclic-set proof.

## `$nodeBlueId`

`$nodeBlueId` first evaluates its operand and charges the identity request.
For exact input it returns the already established ordinary BlueId. For transient
input it invokes this same output boundary and direct identity establishment.
There is no alternate BEX identity algorithm and no semantic-ID shortcut.

## Gas and atomicity

Admission charges `blueOutputBoundary` before conversion/validation. Transient
construction and direct identity work are charged only when performed. Exact
pass-through does not incur recursive member-production gas.

If conversion, validation, identity evidence, or gas admission fails, the value
is not admitted. No later work occurs and buffered BEX patches/events do not
commit. Hosted execution preserves the host failure classification and merges a
successful child identity/gas effect exactly once.

See [Values and identity](values-and-identity.md) and
[Gas and exhaustion](gas-and-exhaustion.md).
