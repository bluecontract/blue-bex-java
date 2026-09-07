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

- a null or undefined root fails;
- null or undefined object members are omitted;
- a null list member becomes the exact positional placeholder
  `{ $empty: true }`;
- an undefined list member fails;
- explicit empty objects and lists remain present values;
- scalar kinds use deterministic Blue scalar rules;
- objects and lists traverse in the order required by BEX;
- exact descendants remain exact rather than being reconstructed;
- the final node is validated before direct identity establishment.

These rules apply recursively, including reserved members. In particular,
`{type: null}` admits as `{}`, while `{type: {}}` retains the explicit empty
inline type. A null patch, event, request root, or other root-valued output is
an admission failure; an explicit `{}` in the same position is valid content.

The host's `BexSemanticIdentityBoundary` establishes the ordinary BlueId exactly
once. The admitted result retains both exact identity and the run-local semantic
cursor, so a later `$resultValue`, variable, event, or changeset read does not
repeat conversion or hashing.

## Runtime admission with Source-equivalent empty semantics

Output is runtime Blue content, not an arbitrary Source document. The boundary
nevertheless applies the same position-sensitive null and empty-container rules
as Source preprocessing so the same authored shape has the same canonical
meaning on both sides of the boundary. It does not execute `blue` imports or
other Source transformation directives. After positional normalization, the
Language runtime validates the payload and canonicalizes identity-bearing
inline types before direct identity establishment.

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
[Gas and exhaustion](gas-and-exhaustion.md). The rationale and accepted
compatibility decision are recorded in
[Review decision: BEX null at the Blue boundary](REVIEW_COMMENT_BEX_NULL_BOUNDARY.md).
