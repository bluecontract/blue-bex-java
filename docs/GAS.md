# BEX Gas Model

BEX gas is deterministic execution accounting. It is intended to make one
implementation's runtime behavior portable enough for conformance tests, not to
model every CPU or memory cost.

Compilation does not consume gas. Every execution starts at `0`. Each runtime
charge adds to the total. If `gasLimit >= 0` and the total becomes greater than
the limit, execution throws:

```text
BEX gas exhausted at <used> gas units
```

## Default Schedule

| Field | Default |
| --- | ---: |
| `expressionBase` | 1 |
| `statementBase` | 1 |
| `documentRead` | 2 |
| `eventRead` | 1 |
| `stepsRead` | 1 |
| `currentContractRead` | 1 |
| `varRead` | 1 |
| `resultValueRead` | 2 |
| `pointerGetBase` | 1 |
| `pointerSetBase` | 3 |
| `objectSetBase` | 2 |
| `appendChangeBase` | 5 |
| `appendEventBase` | 5 |
| `forEachItem` | 1 |
| `functionCall` | 2 |

Every function invocation charges `functionCall`. This includes the root program
function, so a trivial root expression costs at least `2 + expressionBase`.

Every evaluated expression charges `expressionBase`. Every executed statement
charges `statementBase`. Source-path wrappers and other diagnostics wrappers do
not charge gas.

## Reads

Read operators charge their read cost in addition to `expressionBase`:

| Operator | Cost |
| --- | --- |
| `$document` | `expressionBase + documentRead` |
| `$event` | `expressionBase + eventRead` |
| `$currentContract` | `expressionBase + currentContractRead` |
| `$steps` | `expressionBase + stepsRead` |
| `$binding` | `expressionBase + varRead` |
| `$var` | `expressionBase + varRead` |
| `$resultValue` | `expressionBase + resultValueRead` |

Canonical and resolved document reads currently cost the same.

Path-aware `$var` and `$const` object forms do not add a separate read charge
for static paths. If the `path` operand is dynamic, the path expression consumes
its normal expression gas before the value-local pointer read.

`$kind` and `$isKind` charge their normal expression tree costs only:

```text
$kind = expressionBase + gas for value expression
$isKind = expressionBase + gas for val expression
```

`$isKind.kind` is static authored data and does not consume expression gas.

## Pointer And Object Updates

`$pointerGet` charges:

```text
expressionBase
+ gas for object expression
+ pointerGetBase
+ numberOfPathSegments
+ gas for default expression only if default is used
```

`$pointerSet` charges:

```text
expressionBase
+ gas for val expression, unless op is remove
+ gas for object expression
+ pointerSetBase
+ numberOfPathSegments
+ estimatedSize(val)
```

For `remove`, `val` is not evaluated and `estimatedSize(undefined) = 0`.

`$objectSet` charges:

```text
expressionBase
+ gas for val expression
+ objectSetBase
+ estimatedSize(val)
+ gas for object expression
```

Static keys and paths do not consume expression gas. Dynamic keys and paths do,
because their expressions are evaluated.

## Append And Output

`$appendChange` charges:

```text
statementBase
+ gas for val expression if op is add or replace
+ appendChangeBase
+ estimatedSize(val)
```

For `remove`, `val` is not evaluated and size is `0`.

`$appendChanges` charges:

```text
statementBase
+ gas for list expression
+ for each patch: appendChangeBase + estimatedSize(val)
```

`$appendEvent` charges:

```text
statementBase
+ gas for event expression
+ appendEventBase
+ estimatedSize(event)
```

`$appendEvents` charges:

```text
statementBase
+ gas for list expression
+ for each event: appendEventBase + estimatedSize(event)
```

## Control Flow

`$if` charges `statementBase`, then the condition expression, then only the
selected branch.

`$forEach` charges `statementBase`, the input expression, `forEachItem` for each
iterated item, then the body statements for each iteration.

`$returnIf` charges `statementBase`, then the condition expression. Its `expr`
operand is evaluated only when the condition is truthy.

`$failIf` charges `statementBase`, then the condition expression. Its `message`
operand is evaluated only when the condition is truthy.

`$let.vars` charges `statementBase`. In unordered form, all binding expressions
are evaluated before any slot is assigned. In ordered form, each binding
expression is evaluated and assigned in the explicit order.

Collection query bindings are restored after `$map`, `$filter`, `$flatMap`,
`$some`, `$find`, `$findEntry`, and `$reduce` finish. Capturing and restoring
slots is deterministic bookkeeping and does not add gas beyond the expression
and item charges below.

`$and`, `$or`, `$coalesce`, `$some`, `$find`, `$findEntry`, and `$includes`
short-circuit. Unevaluated operands or collection items consume no gas.

Collection expressions `$map`, `$filter`, `$flatMap`, `$reduce`, `$some`,
`$find`, and `$findEntry` charge:

```text
expressionBase
+ gas for input expression
+ forEachItem for each evaluated item
+ gas for the query/body expression for each evaluated item
```

`$reduce` also charges the initializer expression once after the input
expression.

`$objectFromEntries` charges:

```text
expressionBase
+ gas for entries expression
+ forEachItem for each input entry
```

`$includes` charges:

```text
expressionBase
+ gas for list expression
+ gas for val expression
+ forEachItem for each compared list item
```

`$hasKey` charges:

```text
expressionBase
+ gas for object expression
+ gas for key expression only when key is dynamic
```

`$intrinsic` charges:

```text
expressionBase
+ gas for each evaluated payload field expression
+ gas charged explicitly by the registered intrinsic processor
```

`$intrinsic.type` is static authored Blue data and does not consume expression
gas. Unsupported intrinsic BlueIds fail during compilation before execution
starts. Payload fields that evaluate to `undefined` are omitted after their
normal expression gas has been charged.

Function calls charge the caller expression or statement normally, then the
called function invocation charges `functionCall`. Argument expressions are
charged before entering the callee.

## Size Estimator

`estimatedSize(value)` is:

| Value | Size |
| --- | ---: |
| `undefined` | 0 |
| `null` | 0 |
| scalar | `max(1, length(value.asText()))` |
| list | `list.size + sum(estimatedSize(item))` |
| object | `numberOfKeys + sum(length(key)) + sum(estimatedSize(valueForKey))` |

Examples:

```text
estimatedSize("x") = 1
estimatedSize("") = 1
estimatedSize(null) = 0
estimatedSize("hello") = 5
estimatedSize(12345) = 5
estimatedSize(true) = 4
estimatedSize(["a", "bb"]) = 2 + 1 + 2 = 5
estimatedSize({ a: "x", bb: "yy" }) = 2 + 1 + 1 + 2 + 2 = 8
```

Frozen values may be cached by BlueId and runtime values may be cached by
identity, but caching does not change gas used. It only affects metrics and
performance.

## Known Limit

Large values returned by pure expressions are not directly size-charged unless
they are later appended or inserted through charged output/update operators.
For example, `$concat`, `$join`, `$split`, `$keys`, `$entries`, `$merge`,
`$listConcat`, object literals, and list literals pay expression and operand gas
but not `estimatedSize(result)`.

This is the current specified model: simple execution and output accounting.

## Conformance Fixtures

Exact gas conformance fixtures live under:

```text
src/test/resources/rich-fixtures/gas/
```

Success fixtures may assert:

```yaml
expectation:
  outcome: success
  gasUsed: 10
```

Fixtures may set execution limits:

```yaml
context:
  gasLimit: 2
expectation:
  outcome: runtime-error
  errorContains: BEX gas exhausted
```

Fixtures may override individual schedule fields:

```yaml
gasSchedule:
  expressionBase: 10
```
