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

The runtime is intentionally small: no time, random, network, filesystem,
JavaScript, or WASM in the core language. Host capabilities are available only
through explicitly registered `$intrinsic` processors keyed by BlueId.

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

Programs that use host capabilities register intrinsic processors on the
engine. The BEX program names the operation with a normal Blue type/BlueId; the
processor registry decides whether that operation is supported:

```java
BexEngine engine = BexEngine.builder()
        .intrinsic(CommonCryptoEd25519Verify.class, invocation -> {
            invocation.chargeGas(500);
            // Read invocation.field("publicKey"), invocation.field("message"),
            // and invocation.field("signature"), then return a BexValue boolean.
            return BexValues.scalar(verifySignature(invocation));
        })
        .build();
```

## Constants

`$const` must reference a declared program constant. Unknown constants fail at
compile time instead of evaluating to undefined:

```yaml
constants:
  amount: 400
expr:
  $const: amount
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
`args`, and `$call.args`, do not use Blue language keys or invalid reserved keys
as names. This includes `value`, `items`, `blueId`, `type`, `schema`, `name`,
`description`,
`itemType`, `keyType`, `valueType`, `mergePolicy`, `constraints`, `contracts`,
`properties`, `$previous`, and `$pos`.

For operator bodies, payload/reference/control keys such as `value`, `items`,
`blueId`, `contracts`, `properties`, `$previous`, and `$pos` cannot be used as ordinary
multi-field operands. Use BEX operand names such as `node`, `list`, `input`,
`pattern`, `object`, `key`, `path`, `val`, `cond`, `then`, and `else`.

Metadata keys such as `name`, `description`, `type`, `schema`, `itemType`,
`keyType`, `valueType`, and `contracts` are legal Blue language fields, but they are not
ordinary object properties. `constraints` is invalid in Blue Language 1.0. An
operator may use one of the legal metadata fields only when the BEX compiler
explicitly supports that field.

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

## Pointer Kinds

BEX has two pointer contexts.

Document pointers are resolved relative to the current document scope:

- `$document`;
- `$resultValue`;
- `$appendChange.path`;
- `$appendChanges` entry `path`.

Value pointers are resolved inside the selected value and are not affected by
the document scope:

- `$event`;
- `$currentContract`;
- `$steps.path`;
- `$binding.path`;
- `$pointerGet.path`;
- `$pointerSet.path`.

Static omitted/default paths may intentionally read a root/default location.
Dynamic pointer expressions that evaluate to `null` or undefined fail instead
of silently becoming the current document scope or root value.

Dynamic text operands also reject `null` and undefined. This applies to
operator fields such as `$get.key`, `$objectSet.key`, `$hasKey.key`,
`$binding.name`, `$steps.step`, `$appendChange.op`, and `$pointerSet.op`. A
static empty string is still allowed when it is explicitly authored.

Use `$pointerJoin` when building document paths from dynamic path segments. Each
item is treated as one JSON Pointer segment and escaped safely:

```yaml
$pointerJoin:
  - orders
  - $var: orderId
  - status
```

If `orderId` is `abc/def~ghi`, the result is
`/orders/abc~1def~0ghi/status`.

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

`$var` and `$const` support object-form value paths. The `name` is static, so
variables and constants still compile to fixed slots/lookups, while `path` is a
value-local JSON Pointer inside the selected value:

```yaml
$var:
  name: request
  path: /summary
```

```yaml
$const:
  name: Policy/minimum
  path: /amount
```

The path may be static text or an expression that evaluates to pointer text.
Missing path targets return `undefined`, matching `$pointerGet`/value reads.
There is intentionally no `$var: request/summary` shorthand; existing variable
and constant names may contain `/`. Dynamic variable or constant names are not
supported; use `$get` or `$pointerGet` for dynamic object lookup.

### Type Helpers

| Operator | Purpose |
|---|---|
| `$unwrap` | Unwrap Blue scalar wrapper values. |
| `$is` | Return whether a value matches a Blue pattern. |
| `$kind`, `$isKind` | Inspect the visible BEX runtime value kind. |
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

`$kind` returns one of `undefined`, `null`, `text`, `integer`, `double`,
`boolean`, `object`, or `list`. This is BEX runtime shape, not Blue type
conformance; use Blue schema/type validation or `$is` for Blue type semantics.

```yaml
$isKind:
  val:
    $event: /message/request/amount
  kind: [integer, double]
```

`$isKind.kind` may be a single kind or a list of kinds.

### Strings

| Operator | Purpose |
|---|---|
| `$concat` | Concatenate text. |
| `$pointerJoin` | Safely build a JSON Pointer from path segments. |
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
| `$truthy`, `$empty`, `$isEmpty` | Truthiness checks. `$isEmpty` is an alias that avoids Blue list-placeholder syntax in list operands. |
| `$exists` | Return false only for undefined values. |
| `$coalesce`, `$default` | First non-empty value. `$default` is an alias. |

`$exists` is useful for optional-field validation because it distinguishes a
missing value from present falsy values. It returns `true` for `null`, `false`,
`0`, empty text, and empty list/object values:

```yaml
$or:
  - $not:
      $exists:
        $event: /message/request/note
  - $is:
      node:
        $event: /message/request/note
      pattern:
        type: Text
```

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
| `$map`, `$filter`, `$flatMap`, `$reduce` | Deterministic collection projection, selection, fanout, and aggregation. |
| `$some`, `$find`, `$findEntry` | Short-circuit collection queries. |
| `$includes` | List membership using BEX equality. |
| `$hasKey` | Object key membership. |
| `$objectFromEntries` | Build an object from `{ key, val }` entries. |

Collection expressions accept lists or objects. Lists iterate in item order.
Objects iterate in sorted key order, matching `$keys`, `$entries`, and
`$forEach` object behavior. `item`, optional `key`, and optional `index`
bindings are local to the query expression and are restored afterwards, so they
do not overwrite outer variables with the same names.

```yaml
$map:
  in:
    $event: /message/request/changeset
  item: patch
  expr:
    op:
      $var:
        name: patch
        path: /op
    path:
      $var:
        name: patch
        path: /path
```

Collection operator shapes and results:

| Operator | Shape | Result |
|---|---|---|
| `$map` | `in`, `item`, optional `key`/`index`, `expr` | List of `expr` results. Object input still returns a list in sorted-key order. |
| `$filter` | `in`, `item`, optional `key`/`index`, `where` | Filtered list for list input; filtered object for object input. |
| `$flatMap` | `in`, `item`, optional `key`/`index`, `expr` | Concatenated list. Each `expr` result must be a list. |
| `$reduce` | `in`, `acc`, `init`, `item`, optional `key`/`index`, `expr` | Final accumulator. `init` is evaluated once before iteration. |
| `$some` | `in`, `item`, optional `key`/`index`, `where` | Boolean, short-circuiting at the first truthy `where`. |
| `$find` | `in`, `item`, optional `key`/`index`, `where` | First matching item/value, or `undefined`. |
| `$findEntry` | `in`, `item`, optional `key`/`index`, `where` | First matching entry object, or `undefined`. |

For list input, `$findEntry` returns:

```yaml
val: <item>
index: <zero-based-index>
```

For object input, `$findEntry` returns:

```yaml
key: <object-key>
val: <field-value>
index: <zero-based-sorted-key-index>
```

`$includes` has shape `{ list, val }`, requires `list` to evaluate to a list,
uses BEX equality, and short-circuits on the first equal item. `$hasKey` has
shape `{ object, key }`; it returns false for non-object inputs and true when
the object has a non-undefined value for the key.

`$objectFromEntries` expects a list of objects with `key` and `val` fields:

```yaml
$objectFromEntries:
  $map:
    in:
      $entries:
        b: 2
        a: 1
    item: entry
    expr:
      key:
        $var:
          name: entry
          path: /key
      val:
        $multiply:
          - $var:
              name: entry
              path: /val
          - 10
```

Entry keys cannot be `undefined` or `null`; those fail. Entry values may be
`undefined`, which omits/removes that key from the result. Duplicate keys use
the last non-undefined value, unless a later undefined value removes the key.

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
| `$intrinsic` | Invoke a registered host intrinsic by the BlueId of its static `type`. |
| `$literal` | Return payload without compiling nested operators. |
| `$null`, `$emptyObject`, `$emptyList` | Emit explicit null, empty object, or empty list values after Blue source normalization. |

`$literal` prevents normal expression compilation, but BEX still rejects
BEX-looking operators inside Blue type-definition fields such as `type`,
`itemType`, `keyType`, `valueType`, `blue`, and `schema`.

### Intrinsics

`$intrinsic` is the only host-extension expression. It does not require a
special `Blue/BEX Intrinsic` supertype. The `type` may be any static Blue type
or Blue value whose BlueId can be resolved. BEX takes that BlueId and looks up
a registered processor.

```yaml
$intrinsic:
  type:
    blueId: CTkdsd4MNjiFA13MFeAx34jnnBLfGzn7HfP6fx1dV43s
  publicKey:
    $const: trustedSignerPublicKey
  message:
    $event: /message/canonicalBytes
  signature:
    $event: /message/signature
```

Rules:

- `type` is static authored Blue data. BEX expressions inside `type` are
  rejected, because the compiler must know the intrinsic BlueId before
  execution.
- Hosts can register processors directly by BlueId or by a Java class with a
  resolvable `@TypeBlueId`.
- The payload is the normal fields of the typed operation object. There is no
  `args` or `params` wrapper.
- The `type` field itself is not passed as a payload field. Processors receive
  `type` separately as `invocation.type()`.
- Payload field expressions are evaluated normally. Fields that evaluate to
  `undefined` are omitted, matching object literal behavior.
- Compilation fails if the active engine has no intrinsic processor registered
  for the resolved BlueId. Execution checks the same support boundary again so
  a shared compiled-program cache cannot bypass it.
- The processor returns a `BexValue`. A `null` Java return is normalized to BEX
  `undefined`.
- The processor is responsible for its own gas accounting by calling
  `invocation.chargeGas(...)`.

The Blue type definition and its description/spec text define what the
operation means. For standard intrinsics, keep conformance vectors beside the
spec text so independent processors can prove they implemented the same
behavior. For Ed25519, the definition must be explicit about what bytes are
signed; do not describe verification over a generic object without also naming
the canonical byte representation.

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
| `$returnIf` | Return early when a condition is truthy. |
| `$fail` | Fail deterministically. |
| `$failIf` | Fail deterministically when a condition is truthy. |

`$returnIf` returns from the current function/root when `cond` is truthy:

```yaml
$returnIf:
  cond:
    $empty:
      $event: /message/request/summary
  expr:
    changeset: []
    events:
      - type: Conversation/Proposed Change Invalid
        reason: summary is missing
```

`expr` is optional. When omitted, `$returnIf` returns the default result value,
the same as bare `$return`. The payload field is named `expr`; `value` is not
accepted because `value` is a Blue scalar-wrapper field and cannot safely carry
an object payload in authored Blue YAML.

`$failIf` fails deterministically when `cond` is truthy:

```yaml
$failIf:
  cond:
    $not:
      $exists:
        $event: /message/request/id
  message: request id is required
```

The `expr` and `$failIf.message` operands are lazy; they are evaluated only
when the guard condition is truthy.

`$let` also supports a multi-bind form. Without `order`, the bindings are
parallel: all expressions read the frame as it existed before the `$let`, then
all variables are assigned. Unordered bindings are sorted only to make execution
deterministic; they do not create dependencies by name.

With `order`, bindings are sequential and later bindings may read earlier ones.
`order` must list every key in `vars` exactly once:

```yaml
$let:
  order: [request, summary]
  vars:
    request:
      $event: /message/request
    summary:
      $var:
        name: request
        path: /summary
```

In unordered form, a binding cannot read another new binding from the same
`vars` block unless that variable already existed before the block. Use `order`
when one binding depends on another.

`$forEach` can bind list indexes and object keys when those are needed for
patch paths:

```yaml
$forEach:
  in:
    $event: /message/request/orders
  item: order
  index: i
  do:
    - $appendChange:
        op: replace
        path:
          $pointerJoin:
            - orders
            - $var: i
            - status
        val: received
```

For object iteration, use `key` and `item` to bind the object key and value
separately. The older form with only `item` still binds `{ key, val }`.

## Results And Accumulators

`BexExecutionResult` contains:

- `value`, the primary return value;
- `changeset`, the standard patch accumulator;
- `events`, the standard event/data accumulator;
- `gasUsed`;
- `metrics`.

BEX computes these values only. The host decides whether patches are applied,
events are emitted, or accumulators are treated as ordinary data.

`$resultValue` reads the document value after applying accumulated patches in
order. Parent reads reflect descendant object patches, so reading
`/hotelOrder` after replacing `/hotelOrder/status` returns the original
`hotelOrder` object with the updated status. Current materialization supports
object paths, list index replacement, and non-shifting list index removal.
Removing a list index creates a sparse overlay slot: the removed index reads as
`undefined`, later indexes keep their positions, and converting the whole sparse
overlay list to Blue output fails under the strict host-boundary profile.

`$appendChanges` validates each patch entry the same way as `$appendChange`.
Supported patch operations are `add`, `replace`, and `remove`. `add` and
`replace` require a non-undefined `val`; `remove` does not include a value.

`$appendEvents` validates each item the same way as `$appendEvent`. Undefined
event values are rejected. BEX core does not require events to be objects; hosts
decide what event shape they accept.

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
- `$resultValue` materializes accumulated patch overlays for reads;
- output conversion to `Node`, `FrozenNode`, or simple Java values is explicit.

Every result includes `BexMetrics`:

```java
BexMetrics metrics = result.metrics();
metrics.compiledExecutions();
metrics.compileCacheHits();
metrics.frozenDocumentReads();
metrics.nodeMaterializations();
```

The deterministic gas rules are specified in [docs/GAS.md](docs/GAS.md). The
portable fixture format is documented in [docs/FIXTURES.md](docs/FIXTURES.md).
The rich fixture suite lives under `src/test/resources/rich-fixtures/` and
currently has 159 cases: 53 current behavior fixtures, 3 parse-error fixtures,
and 103 gas fixtures. The local fixture package is kept aligned with the
canonical BEX spec fixtures under `blue-spec/specifications/bex/1.0/fixtures/`.

The translated corpus strategy is documented in
[docs/TRANSLATED_CORPUS.md](docs/TRANSLATED_CORPUS.md). It contains 80
representative Kyverno, JMESPath/JSONata, and JSON Patch-style cases translated
into BEX to test whether the small query/operator core is sufficient for common
policy, transform, and patch-emission workflows: 30 Kyverno validate-style
cases, 20 Kyverno mutate/generate-style cases, 20 JMESPath/JSONata-style
query/transform cases, and 10 JSON Patch emission edge cases.

## Release Setup

The project version is stored in `.cz.toml`. Local builds append `-SNAPSHOT`;
CI builds publish the plain version, for example `1.0.0`.

Stable releases are triggered manually from `main` with the `Release` workflow:

```bash
./gradlew clean build
./gradlew publish
./gradlew jreleaserFullRelease
```

Release candidates are published from the long-lived `next` branch. Pushes to
`next` that affect `.cz.toml`, Gradle files, the Gradle wrapper, or `src/**`
run the `Release RC` workflow, which prepares the next `X.Y.Z-rc.N` version,
publishes it, and pushes the release commit and tag back to `next`.

The release workflows expect these repository secrets:

- `WORKFLOW_PAT`
- `MAVENCENTRAL_USERNAME` - the Central Portal user-token username
- `MAVENCENTRAL_PASSWORD` - the Central Portal user-token password/passcode
- `GPG_PUBLIC_KEY`
- `GPG_SECRET_KEY`
- `GPG_PASSPHRASE`

## Tests

```bash
./gradlew test --tests '*BexRichFixtureTest'
./gradlew test --tests '*BexTranslatedCorpusTest'
./gradlew test
./gradlew build
```

## License

MIT. See [LICENSE](LICENSE).
