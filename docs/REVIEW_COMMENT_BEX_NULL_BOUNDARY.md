# Review decision: BEX `null` at the Blue boundary

**Status:** accepted and implemented.

This record incorporates the review comment supplied with the empty-object and
embedded-collection specification update. The review identified that retaining
explicit `{}` in Blue Language made the older BEX conversion of every `null` to
`{}` observably unsafe. The accepted decision is:

```text
BEX object-member null     -> omitted
BEX list-item null         -> { $empty: true }
BEX root/patch/event null  -> Blue output admission failure
BEX explicit {}            -> present exact empty object
```

The rule is recursive and applies to reserved members as well:

```text
BEX {type: null} -> Blue {}
BEX {type: {}}   -> Blue {type: {}}
```

The first form omits `type`; the second retains an explicit empty inline type.
The conformance package covers both forms.

## Why the earlier behavior was rejected

Blue Language deliberately distinguishes absence, `null`, `{}`, and `[]` at
the Source boundary:

- an omitted object member is absent;
- a null object member means no information and is omitted;
- an explicit `{}` is a present empty object;
- an explicit `[]` is a present empty list;
- a null list item is the positional placeholder `{ $empty: true }`.

Before explicit empty objects were retained, BEX could convert `null` to `{}`
without exposing much of the mismatch because Language preprocessing removed
the empty object. Once `{}` became canonical content, that conversion would
make a null object member present, turn a null list hole into an ordinary value,
and rotate identities depending on whether equivalent input arrived through
Source preprocessing or BEX output.

That mismatch has concrete consequences. A computed `request: null` could
become a present empty request and satisfy a presence check. A null produced by
missing-data handling could be injected into a scalar-typed field and fail
validation. A null list item could become an ordinary empty object rather than
preserving its position as a placeholder. Reserved `type: null` could acquire a
meaning it did not have in Source input.

## Accepted boundary model

BEX keeps `null` distinct from `undefined` while executing. At the strict Blue
output boundary, both are interpreted by structural position:

| Position | `undefined` | `null` | explicit `{}` |
| --- | --- | --- | --- |
| Object member | omit member | omit member | retain member and value |
| List item | admission failure | `{ $empty: true }` | retain ordinary item |
| Root, patch, event, or request root | admission failure | admission failure | retain exact value |

This is Source-equivalent positional normalization, not permission to execute
arbitrary Source directives. The boundary rejects `blue` transformation
directives, validates the normalized runtime content, canonicalizes
identity-bearing inline types through Blue Language, and then establishes its
ordinary BlueId. Exact admitted descendants preserve their existing identity.

## Identity, gas, and atomicity

The normalization is part of the one authoritative output-admission boundary.
It therefore applies uniformly to returned values, patches, events, intrinsic
results, and `$nodeBlueId` operands. Admission and construction are metered
deterministically. If normalization, validation, identity establishment, or gas
admission fails, no value is published and buffered patches and events do not
commit.

## Executable evidence

The canonical BEX conformance package contains `BEX-EMPTY-01` through
`BEX-EMPTY-15`. Together with focused boundary and independent identity-oracle
tests, those vectors cover:

- object-member `null`, `undefined`, and `{}`;
- list-item `null` and `{}`;
- root `null` and root `{}`;
- nested empty objects after null omission;
- typed null versus typed empty object;
- `type: null` versus `type: {}`;
- request null versus request `{}`;
- patch, event, intrinsic, and `$nodeBlueId` admission;
- warm, cold, and batched execution parity;
- deterministic gas and canonical/semantic/item identity projections.

`BEX-EMPTY-14` separates two notions that must not be conflated. Exact empty
content has an identical full portable trace across inline/reference,
warm/cold, and batched/unbatched provider forms. A transient object containing
an evaluated null member reaches the same exact `{}` identity and performs the
same single output-boundary and host-ledger merge, but it also records the
permitted transient-member construction and one semantic-identity admission.
Full exact/transient trace equality would contradict the construction-work
rule; the fixture asserts the stable work and the intentional delta explicitly.

`BEX-EMPTY-06` makes failure atomicity observable rather than inferring it from
the missing result. Every nested case first admits one valid patch and one valid
event, then fails one of the root, patch, event, intrinsic, or `$nodeBlueId`
boundaries. The case-local oracle requires empty patch and event buffers, the
original overlay, every opened child ledger finalized exactly once, and exact boundary, identity,
patch, event, and node-identity counter quantities plus the complete admitted
gas total. The intrinsic case records its additional registered child ledger.

`BEX-EMPTY-11` is the execution-time distinction matrix. It carries
`undefined`, `null`, and exact `{}` through local variables and an untyped
function argument; checks `$isKind`, `$exists`, equality, `$coalesce`, and
`$choose`; observes object keys before output admission; and separately proves
the intentional `$object(null) -> {}` and `$list(null) -> []` constructor
semantics. Its raw execution result retains the null member while its admitted
Blue boundary value omits only that member and retains exact `{}`.

This decision intentionally introduces no compatibility alias. Nothing had
been published, so the rejected `null -> {}` behavior is not retained.

## Cross-repository acceptance traceability

The Contracts/BEX integration acceptance identifiers are not BEX normative
vector identifiers: the closed fixture schema reserves `vectors` for IDs of
the form `BEX-...`. They are therefore cross-referenced explicitly here and in
JUnit test names instead of being inserted into that namespace:

| Acceptance item | Executable BEX evidence |
| --- | --- |
| `C-BEX-NULL-01` | `BEX-EMPTY-06` plus `cBexNull01FailureDiscardsPreviouslyBufferedPatchAndEvent`, which buffers one valid patch and event before a null event fails and proves both buffers are cleared |
| `C-BEX-NULL-02` | `BEX-EMPTY-01`, `BEX-EMPTY-09`, `BEX-EMPTY-10`, plus `cBexNull02RecursivelyNormalizesNullByStructuralPosition` |
| `C-BEX-NULL-03` | `BEX-EMPTY-05` plus `cBexNull03ExplicitPlaceholderConvergesWithListNullButEmptyObjectDoesNot` |
| `C-BEX-NULL-04` | `cBexNull04RetainsNestedContainerAndRejectsScalarTypedEmptyObject`, which preserves `{x:{}}` after recursive null omission and then proves ordinary Language snapshot validation rejects the retained `{}` when typed as `Text` |
