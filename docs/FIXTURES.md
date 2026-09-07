# Blue BEX 2.0 Fixture Format

The normative fixtures use the closed `blue-bex-fixture/2.0` schema and live
under:

```text
src/test/resources/conformance/bex/fixtures/
```

The authoritative files are:

```text
fixture-schema.yaml
HARNESS.md
manifest.yaml
operator-coverage.yaml
projection-catalog.yaml
vector-coverage.yaml
```

Unknown fixture fields, context bindings, operators, assertion projections, or
intrinsic types fail closed. No normative fixture may be skipped.

## Root Shape

Every fixture requires:

```yaml
schema: blue-bex-fixture/2.0
id: example-id
vectors:
- BEX-E-01
category: e
program:
  expr: 1
context:
  rootDocument: {}
  event: {}
  processingEvent: {}
  currentContract: {}
  steps: {}
  bindings: {}
  documentScope: /
expected:
  result: 1
```

The closed categories are `c`, `e`, `g`, `gas`, `h`, `operator`, `r`, and `s`.
`vectors` binds the fixture to one or more normative specification vectors.

## Context

The context may supply exact values for:

```text
rootDocument
event
processingEvent
currentContract
steps
bindings
```

It may also supply:

- `documentScope`, the current Contracts scope;
- `provider`, verified exact BlueId entries;
- `parentRemainingGas`, the live host budget;
- `gasLimit`, a BEX-local limit that may only reduce that budget;
- `directCounterFixture`, used only by exact named-counter microfixtures.

Context values can be inline or reference-backed. Representation, cache state,
and provider segmentation must not change result or gas.

## Expected Results

The closed expected-result fields include:

```text
compileStatus
result
changes
events
errorClass
gasTrace
totalGas
assertions
variants
cases
additionalCase
reason
```

`expected.cases` contains complete executable subcases. Metadata such as
`additionalCase` is not executable.

Assertions use a path from `projection-catalog.yaml` and one of:

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

A missing or unknown projection is a harness error unless the fixture
explicitly asserts `absent`.

## Representation Variants

Each variant is an explicit independent transformation. Supported axes include:

- root form: inline, reference, eager, lazy, or materialized;
- cache: warm or cold;
- provider batching: batched or unbatched;
- exact raw root JSON;
- internal delivery classification.

`sameAcrossVariants` compares the semantic result and requested canonical trace
projections. Variant labels alone do not imply behavior.

## Exact Named Gas

Gas expectations use the ordered named ledger, never an opaque aggregate
accepted from the implementation:

```yaml
expected:
  gasTrace:
  - sequence: 0
    counter: expressionEvaluated
    quantity: 3
    weight: 1
    gas: 3
  totalGas: 3
```

A gas microfixture supplies one `directCounterFixture`:

```yaml
context:
  directCounterFixture:
    counter: expressionEvaluated
    quantity: 3
```

The harness verifies namespace, counter name, sequence, quantity, manifest
weight, subtotal, reason, and the trace-derived total. The 30 microfixtures
cover every counter in the exact BEX 2.0 gas manifest.

For exhaustion fixtures, `parentRemainingGas` is the host budget and
`gasLimit` is an optional lowering sub-limit. The failed charge must be absent,
the admitted trace prefix must remain exact, and buffered effects must not
commit.

## Output and Error Phases

The harness distinguishes compile, runtime, gas, representation, and Blue
boundary failures. Compile errors occur before runtime counters or effects.
Output admission validates Blue Language 1.0 before identity calculation,
including numeric-kind preservation and rejection of undefined list slots.

## Package Integrity

`manifest.yaml` inventories every fixture and support file with its normalized
byte length and SHA-256 digest. It binds the exact BEX runtime registry, gas
manifest, vector map, and operator map.

The implementation-baseline inventory and identities are:

```text
normative vectors: 75
behavior fixtures: 120
gas microfixtures: 30
normative operators: 86
runtime registry: sha256:2ccbfc9d1a1c4425cdcaf37c924274cc4398f82ac72769a8c2cf1dd8ba2fd04b
gas manifest: sha256:41247c820d91a12fdfc17fd9e787a5d8d668d8acc5954fdcb131715bf9e6147d
fixture package: sha256:fdb896bf467c0ab8f3b2c9af3b609c9e7d0dc368dd3061f4b2909d0ee1fa89c3
```

Run the complete package and generate machine-readable evidence with:

```text
./gradlew bexConformanceReport \
  -PblueLanguageCompositePath=../blue-language-java
```
