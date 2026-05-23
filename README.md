# blue-bex-java

`blue-bex-java` is a compiled Java engine for **Blue Expression Objects**
(**BEX**): a deterministic scripting model written as Blue data.

BEX is for logic that should live inside Blue documents instead of host code.
A BEX program can read a Blue document view, host-provided bindings, constants,
variables, and prior results, then compute structured Blue-compatible data.
Because BEX programs are Blue data, they can participate in the same content
identity and BlueId model as the documents that carry them.

BEX is not JavaScript, WASM, a network runtime, an LLM extension point, or a
contract processor. It does not mutate documents or perform external actions.
It computes a deterministic `BexExecutionResult`; the host decides how to use
that result.

## What BEX Is

BEX programs are Blue object trees. An object with exactly one key beginning
with `$` is an operator. Objects with normal keys are literal Blue-compatible
objects. Lists and scalar values are literal values unless nested inside an
operator body.

BEX is useful when a host wants deterministic, document-owned logic for policy
checks, projections, validation rules, event payload construction, patch
construction, fixture generation, or other Blue-native computation.

## Why BEX Exists

BEX keeps logic close to the data it describes. That matters when logic should
be versioned, hashed, inspected, signed, transported, or reproduced as Blue
data instead of hidden in host application code.

The runtime is intentionally small and deterministic: no time, random, network,
filesystem, JavaScript, WASM, or arbitrary host callbacks.

## Basic Example

```yaml
do:
  - $let:
      name: status
      expr:
        $document: /status
  - $let:
      name: limit
      expr:
        $integer:
          $binding:
            name: policy
            path: /maxAmount
  - $return:
      approved:
        $and:
          - $eq:
              - $var: status
              - active
          - $gte:
              - $var: limit
              - 1000
      message:
        $concat:
          - "Status is "
          - $var: status
```

With `/status = active` and a `policy` binding containing
`/maxAmount = 1000`, the result value is:

```yaml
approved: true
message: Status is active
```

## Java Usage

```java
FrozenNode programNode = FrozenNode.fromResolvedNode(program);
FrozenNode documentNode = FrozenNode.fromResolvedNode(document);

Map<String, Object> policyMap = new LinkedHashMap<>();
policyMap.put("maxAmount", 1000);

BexExecutionContext context = BexExecutionContext.builder()
        .document(new FrozenBexDocumentView(documentNode))
        .binding("policy", BexValues.fromSimple(policyMap))
        .binding("event", BexValues.nodeSnapshot(eventNode))
        .gasLimit(100_000)
        .build();

BexExecutionResult result = BexEngine.builder()
        .build()
        .compileAndExecute(BexProgramSource.inline(programNode), context);
```

Programs that use shared functions/constants can use a definition node:

```java
BexProgramSource source = BexProgramSource.withDefinition(
        programNode,
        definitionNode,
        "entryFunction"
);
```

## Function Arguments And Blue Patterns

BEX function arguments may declare Blue type or shape patterns. BEX does not
have a separate type enum or type system; declared argument patterns are Blue
nodes and runtime values are checked with Blue's node/type matcher.

```yaml
functions:
  capture:
    args:
      amount:
        type: Integer
      hotelOrder:
        blueId: HotelOrderBlueId
      request:
        customerName:
          type: Text
          schema:
            required: true
        nights:
          type: Integer
          schema:
            required: true
    expr:
      amount:
        $var: amount
      order:
        $var: hotelOrder
```

All declared function arguments are required by the function call ABI for now.
Unknown functions, extra argument names, and missing declared arguments fail at
compile time. Typed arguments are checked after their call expressions are
evaluated; a runtime mismatch throws `BexException`.

Text `"400"` does not match the Blue `Integer` pattern. Use an explicit
conversion when conversion is intended:

```yaml
$call:
  function: capture
  args:
    amount:
      $integer:
        $event: /message/request/amount
```

Untyped required arguments remain supported by declaring an empty pattern:

```yaml
args:
  input: {}
```

When BEX converts computed values back to Blue nodes for `$is`, function
argument checks, or output conversion, Blue language keys keep their Blue
meaning. For example, this computed value is a node with a `type` field and a
`status` property, not an object with an ordinary property named `type`:

```yaml
type:
  blueId: HotelOrderType
status: confirmed
```

A bare `blueId` object is a Blue reference pattern:

```yaml
blueId: HotelOrderType
```

Do not combine `blueId` with sibling fields to describe a typed instance. Use
`type: { blueId: ... }` for typed values.

BEX programs are Blue documents, so BEX syntax must use valid Blue authoring
forms. For user-defined name containers such as `functions`, `constants`,
`args`, and `$call.args`, do not use Blue language keys as names. This includes
`value`, `items`, `blueId`, `type`, `schema`, `name`, `description`,
`itemType`, `keyType`, `valueType`, `mergePolicy`, `constraints`, `contracts`,
`properties`, `$previous`, and `$pos`.

For operator bodies, payload/reference/control keys such as `value`, `items`,
`blueId`, `properties`, `$previous`, and `$pos` cannot be used as ordinary
multi-field operands. Use BEX operand names such as `node`, `list`, `input`,
`pattern`, `object`, `key`, `path`, `val`, `cond`, `then`, and `else`.

Metadata keys such as `name`, `description`, `type`, `schema`, `itemType`,
`keyType`, and `valueType` are legal Blue language fields, but they are not
ordinary object properties. An operator may use one of them only when the BEX
compiler explicitly supports that field.

Function argument patterns and `$is.pattern` are static Blue patterns. BEX does
not evaluate expressions inside those patterns, and it does not emulate Blue
authoring sugar such as inline `type: Integer` preprocessing for computed type
fields.

## Document Views

`BexDocumentView` owns document pointer resolution, canonical reads, resolved
reads, and the current scope path.

```java
FrozenBexDocumentView view = new FrozenBexDocumentView(
        canonicalRoot,
        resolvedRoot,
        "/orders/123"
);
```

`$document` reads the canonical view by default:

```yaml
$document: /status
```

Resolved view reads are explicit:

```yaml
$document:
  path: /status
  view: resolved
```

## Runtime Bindings

Hosts can provide arbitrary named bindings:

```java
BexExecutionContext.builder()
        .document(view)
        .binding("policy", policyValue)
        .binding("actor", actorValue)
        .binding("event", eventValue)
        .build();
```

Use `$binding` for arbitrary host bindings:

```yaml
$binding:
  name: policy
  path: /maxAmount
```

Short form is supported:

```yaml
$binding: actor/name
```

Hosts often provide common bindings such as `event`, `steps`, and
`currentContract`. BEX includes shortcuts for those common names:

- `$event`;
- `$steps`;
- `$currentContract`.

For every other host binding, use `$binding`.

## Expression Operators

BEX operators are Blue objects whose single key starts with `$`.

### Reading Data

| Operator | Purpose |
|---|---|
| `$document` | Read from the Blue document view. |
| `$binding` | Read a host-provided runtime binding. |
| `$event` | Shortcut for the common `event` binding. |
| `$steps` | Shortcut for prior step/result bindings when a host provides them. |
| `$currentContract` | Shortcut for a host-provided current contract binding. |
| `$var` | Read a local variable. |
| `$const` | Read a program constant. |
| `$get` | Read an object field by key. |

### Type Helpers

| Operator | Purpose |
|---|---|
| `$unwrap` | Unwrap Blue scalar wrapper values. |
| `$is` | Return whether a value matches a Blue pattern. |
| `$text` | Convert to text. |
| `$integer` | Convert to exact integer. |
| `$number` | Convert to exact decimal/number. |
| `$boolean` | Convert to boolean. |
| `$object` | Require an object, or default undefined to an empty object. |
| `$list` | Require a list, or default undefined to an empty list. |

`$is.pattern` is static Blue pattern data, not a BEX expression:

```yaml
$is:
  node:
    $event: /message/request/amount
  pattern:
    type: Integer
```

### Strings

| Operator | Purpose |
|---|---|
| `$concat` | Concatenate text. |
| `$join` | Join list items with a separator. |
| `$split` | Split text. |
| `$startsWith` | Check a prefix. |
| `$sliceAfter` | Return text after a prefix. |

```yaml
$join:
  list:
    - a
    - b
  separator: ":"
```

### Logic And Comparison

| Operator | Purpose |
|---|---|
| `$eq`, `$ne` | Equality and inequality. |
| `$gt`, `$gte`, `$lt`, `$lte` | Numeric comparisons. |
| `$and`, `$or`, `$not` | Boolean logic with short-circuiting. |
| `$truthy`, `$empty` | Truthiness checks. |
| `$coalesce` | First non-empty value. |

### Numeric

| Operator | Purpose |
|---|---|
| `$add` | Add exact integers. |
| `$subtract` | Subtract exact integers. |
| `$multiply` | Multiply exact integers. |
| `$divide` | Divide exact integers; non-exact division fails. |

### Objects And Lists

| Operator | Purpose |
|---|---|
| `$keys` | Sorted object keys. |
| `$entries` | Sorted object entries as `{ key, val }`. |
| `$size` | Size of a list, object, or scalar value. |
| `$listGet` | Read a list item. |
| `$listConcat` | Concatenate lists. |
| `$merge` | Shallow object merge. |
| `$objectSet` | Set a dynamic object key without mutating the input. |
| `$pointerGet` | Read by JSON Pointer from any value. |
| `$pointerSet` | Return a value with a JSON Pointer update. |

### Result Helpers

| Operator | Purpose |
|---|---|
| `$changeset` | Return accumulated patch entries. |
| `$events` | Return accumulated event/data entries. |
| `$resultValue` | Read the document value implied by accumulated changes. |

### Other Expressions

| Operator | Purpose |
|---|---|
| `$choose` | Conditional expression. |
| `$call` | Call a local function. |
| `$literal` | Return payload without compiling nested operators. |

`$literal` prevents normal expression compilation, but BEX still rejects
BEX-looking operators inside Blue type-definition fields such as `type`,
`itemType`, `keyType`, `valueType`, `blue`, and `schema`.

## Statement Operators

| Statement | Purpose |
|---|---|
| `$let` | Define or initialize a local variable. |
| `$set` | Update an existing local variable. |
| `$if` | Conditional branch. |
| `$forEach` | Iterate list items or object entries. |
| `$appendChange` | Append a patch entry to the result changeset. |
| `$appendChanges` | Append many patch entries. |
| `$appendEvent` | Append an event/data value. |
| `$appendEvents` | Append many event/data values. |
| `$call` | Call a local function for side effects and return handling. |
| `$return` | Return the result value. |
| `$fail` | Fail deterministically. |

## Results And Accumulators

`BexExecutionResult` contains:

- `value`, the primary return value;
- `changeset`, the standard patch accumulator;
- `events`, the standard event/data accumulator;
- `gasUsed`;
- `metrics`.

BEX computes these values only. The host decides whether patches are applied,
events are emitted, or accumulators are treated as ordinary data.

## Determinism

BEX execution is deterministic for a fixed program, context, document view,
bindings, gas schedule, and immutable host boundary values.

Use `BexValues.nodeSnapshot(node)` for untrusted mutable `Node` values. Use
`BexValues.nodeCursorTrustedImmutable(node)` only when the host can guarantee
the node will not be mutated during execution.

## Performance Model

The engine is compiled-first:

- selected programs compile lazily;
- compiled programs are cacheable by stable Blue node identity and entry name;
- variables use slot frames;
- static pointers are parsed at compile time;
- document and binding reads use cursor-backed values where possible;
- `$objectSet` and `$pointerSet` use overlay values;
- `$resultValue` uses an indexed overlay;
- output conversion to `Node`, `FrozenNode`, or simple Java values is explicit.

Every result includes `BexMetrics`:

```java
BexMetrics metrics = result.metrics();
metrics.compiledExecutions();
metrics.compileCacheHits();
metrics.frozenDocumentReads();
metrics.nodeMaterializations();
```

## Tests

```bash
./gradlew test
./gradlew build
```

## License

MIT. See [LICENSE](LICENSE).
