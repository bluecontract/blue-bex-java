# Blue BEX 2.0 fixture harness

## 1. Purpose

The harness executes `blue-bex-fixture/2.0`. Fixtures are executable BEX programs, not descriptions of hypothetical cases. Unknown fields, operators, variants, cases, assertions, or output projections fail closed.

The runner MUST use:

```text
fixture-schema.yaml
operator-coverage.yaml
projection-catalog.yaml
```

## 2. Compilation and program selection

Compilation follows the exact BEX 2.0 rules. `entry`, `expr`, and `do` are selected in the specified precedence order. An unselected `do` block cannot declare a local used by a selected root `expr`. Compile-time failures occur before runtime counters or effects.

`program`, `constants`, `functions`, function arguments, static patterns, and static intrinsic types are interpreted exactly as specified. Fixture-only metadata never creates a BEX variable or capability.

## 3. Context

The context supplies exact values for:

```text
$document
$event
$processingEvent
$currentContract
$steps
$binding
```

`documentScope` is the current Contracts scope. Provider entries map exact BlueIds to verified Blue values. Context values may be inline or pure references, but portable BEX cannot observe representation, cache state, or provider segmentation.

## 4. Exact and transient values

Existing exact Blue values retain their Node BlueId across reads, variables, constants, functions, patch/event appenders, and output.

Transient objects, lists, Text, Integer, and decimal values are BEX values until they cross a Blue output or `$nodeBlueId` boundary. At that boundary:

- BEX Integer becomes Blue Integer;
- every BEX decimal, including `1.0`, becomes Blue Double using binary64 round-to-nearest, ties-to-even;
- a decimal is never changed to Blue Integer merely because its mathematical value is integral;
- invalid Blue output shapes fail deterministically.

## 5. Variants

A variant is an object with an explicit transformation. Bare labels are invalid.

- `rootForm: inline` materializes the referenced target directly in `rootDocument`.
- `rootForm: reference` retains the pure reference and provider entry.
- `rootForm: eager` verifies and materializes the target before execution.
- `rootForm: lazy` retains the reference until the first semantic demand.
- `rootForm: materialized` is the already verified materialized form used by kind tests.
- `cache` and `batching` alter physical provider preparation only.
- `rawRootDocumentJson` replaces `rootDocument` by parsing exactly that JSON source, preserving numeric token class before Blue inference.
- `deliveryKind` changes only the current internal-delivery classification. The supplied `$event` and `$processingEvent` remain exact and must retain their documented bindings.

Each variant runs from an independent copy of the base context. `sameAcrossVariants` compares both the semantic result and every requested canonical trace projection.

## 6. Cases

`expected.cases` is an explicit list of complete subcases. Each case replaces `program`, optionally replaces `context`, and asserts the named `errorClass` and optional reason. A case name alone has no semantics.

## 7. Output admission

Values crossing a Blue boundary are validated under Blue Language 1.0 before identity calculation. The runner MUST reject:

- root or list `undefined`;
- mixed `blueId` forms;
- mixed scalar/list/object payloads;
- reserved-invalid fields;
- invalid schema vocabulary;
- computed `blue` directives;
- unconsumed list controls;
- invalid numeric conversion.

The BlueId helper used for package generation MUST validate these rules before hashing. Hashability alone is not validity.

## 8. Gas and child ledger

`parentRemainingGas` is the exact budget offered by the Contracts host. `gasLimit` is an optional BEX-local sub-limit. The effective runtime budget is the smaller of the two when both are present.

The runner maintains a live child budget bounded by parent remaining gas and the optional local sub-limit. Every counter increment is admitted before work. The failing charge is absent. Short-circuited expressions and unexecuted statements charge nothing.

The child ledger contains named counters and quantities only. It is merged into the Contracts host exactly once. Opaque runtime gas integers, recursive value-size counters, UTF-16 length counters, cache discounts, and representation-dependent charges are nonconforming.

## 9. Operator coverage

`operator-coverage.yaml` is generated from the normative operator tables and fixture programs. Every required operator MUST have at least one direct executable behavior fixture. Grouped prose vectors do not substitute for direct operator occurrence.

Compiler-error, lazy-evaluation, output-boundary, and representation variants supplement direct success coverage where relevant.

## 10. Assertions and projections

`projection-catalog.yaml` is the closed list of legal assertion paths. Supported operators are:

```text
equals
notEquals
absent
present
contains
notContains
lessThan
greaterThan
sameAcrossVariants
all
none
```

A missing projection is a runner failure unless `absent` is asserted.

## 11. Gas microfixtures

A microfixture supplies `context.directCounterFixture`. The runner emits exactly one named trace entry with the bound namespace, counter, quantity, weight, subtotal, and total. It cannot replace that trace with an opaque total.

## 12. Package integrity

`manifest.yaml` is the authoritative inventory for this fixture package. It lists every behavior fixture, direct operator fixture, gas microfixture, and support file with its relative path, role, LF-normalized byte length, and SHA-256 digest. It binds the exact BEX runtime-registry package identity, gas-manifest package identity and file digest, vector-coverage map, and direct operator-coverage map.

The fixture-package identity is calculated as:

```text
sha256(
  UTF-8 canonical JSON of manifest.yaml
  with packageIdentity set to null
  and object keys sorted lexicographically
)
```

A fixture, support file, gas schedule, registry dependency, vector map, or operator map change requires a new fixture-package identity. Disagreement among prose, registry, gas manifest, fixtures, or package identities is a release failure. The registry manifest's reverse fixture binding is excluded from the registry package identity to avoid an identity cycle.
