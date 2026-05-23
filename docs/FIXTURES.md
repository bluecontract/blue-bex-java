# BEX Rich Fixture Format

Rich fixtures are portable YAML test cases for BEX implementations. They live
under:

```text
src/test/resources/rich-fixtures/
```

The Java runner rejects unknown fixture fields so typos do not silently weaken a
conformance test.

## Root Fields

Allowed root fields:

| Field | Required | Meaning |
| --- | --- | --- |
| `fixtureId` | yes | Stable fixture identifier. |
| `title` | yes | Human-readable fixture title. |
| `targetStatus` | no | Informational status used while migrating fixtures. |
| `tags` | no | List of grouping tags. |
| `context` | no | Execution context data. |
| `blueDefinitions` | no | Fixture-local BlueId provider definitions. |
| `gasSchedule` | no | Per-fixture gas schedule overrides. |
| `programSource` | yes | Blue YAML source for the BEX program. |
| `expectation` | yes | Expected outcome and assertions. |

Example:

```yaml
fixtureId: BEX-EXAMPLE-001
title: Const returns declared value
tags:
  - constants
programSource: |
  type: Blue/BEX Program
  constants:
    amount: 400
  expr:
    $const: amount
expectation:
  outcome: success
  resultSimple: 400
```

`tags` must be a list of non-empty text values. `targetStatus`, when present,
must be one of:

```text
current-pass
current-compile-error
current-runtime-error
current-output-conversion-error
current-parse-error
current-parse-error-or-output-conversion-error
current-gas-property
```

## Context

Allowed `context` fields:

| Field | Meaning |
| --- | --- |
| `documentScope` | Current document scope path. Defaults to `/`. |
| `rootDocumentSource` | Blue YAML for the canonical/resolved root document. Defaults to `{}`. |
| `eventSource` | Blue YAML for the event binding. Defaults to `{}`. |
| `currentContractSource` | Blue YAML for the current contract binding. Defaults to `{}`. |
| `stepsBinding` | Map of step names to simple step-result values. |
| `gasLimit` | Execution gas limit. Defaults to `1000000`. |
| `bindings` | Additional host bindings as simple YAML values. |

Document pointers are resolved using `documentScope`. Value-local pointers such
as `$event`, `$currentContract`, `$steps`, `$binding`, `$pointerGet`, and
`$pointerSet` are resolved inside the selected value.

## Blue Definitions

Use `blueDefinitions` when a fixture references custom BlueIds:

```yaml
blueDefinitions:
  HotelOrderType: |
    status:
      type: Text
```

The fixture runner exposes each key as a Blue provider entry. This keeps
fixtures portable and avoids Java-only hardcoded provider behavior.

## Gas Schedule

Allowed `gasSchedule` override fields:

```text
expressionBase
statementBase
documentRead
eventRead
stepsRead
currentContractRead
varRead
resultValueRead
pointerGetBase
pointerSetBase
objectSetBase
appendChangeBase
appendEventBase
forEachItem
functionCall
```

Any omitted field uses the default schedule documented in
[GAS.md](GAS.md).

## Outcomes

Allowed `expectation.outcome` values:

| Outcome | Meaning |
| --- | --- |
| `success` | Program compiles and executes successfully. |
| `compile-error` | Program parses but BEX compilation fails. |
| `runtime-error` | Program compiles but execution fails. |
| `parse-error` | Blue YAML parsing fails. |
| `output-conversion-error` | Execution succeeds, but converting the output to a Blue node/frozen node fails. |
| `parse-error-or-output-conversion-error` | Either parse or output conversion failure is acceptable for strict Blue authoring edge cases. |
| `gas-property` | Fixture asserts a named gas property rather than exact output. |

For `success`, allowed expectation fields are:

```text
outcome
resultSimple
changeset
events
gasUsed
```

For `compile-error`, `runtime-error`, `parse-error`,
`output-conversion-error`, and `parse-error-or-output-conversion-error`, allowed
fields are:

```text
outcome
errorContains
```

For `gas-property`, allowed fields are:

```text
outcome
property
```

## Exact Gas

Success fixtures may assert exact gas:

```yaml
expectation:
  outcome: success
  gasUsed: 10
```

Runtime gas exhaustion fixtures usually set a low context limit:

```yaml
context:
  gasLimit: 2
expectation:
  outcome: runtime-error
  errorContains: BEX gas exhausted
```

## Manifest

The fixture suite has a machine-readable manifest at:

```text
src/test/resources/rich-fixtures/manifest.yaml
```

The manifest records the suite name, version, fixture root, required
directories, gas-model document, fixture-format document, and fixture counts.
It is not itself executed as a fixture.
