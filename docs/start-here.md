# Start here

BEX is a small deterministic language whose programs are Blue data. The Java
library compiles a selected program tree into immutable executable form and runs
it against one immutable host context. The result is data for the host to
inspect; the engine does not mutate the host document or perform external work.

## The four objects to know

1. `BexProgramSource` identifies either a full program, a single expression, or
   a program combined with a shared definition and entry function.
2. `BexExecutionContext` supplies the document view, bindings, scope, gas, and
   host boundaries for one run.
3. `BexEngine` compiles, caches, and executes. Reuse it across independent runs.
4. `BexExecutionResult` contains the returned value, ordered changeset, ordered
   events, canonical gas ledger, admitted output metadata, and diagnostic
   metrics.

## Run a standalone expression

```java
FrozenNode expression = FrozenNode.fromResolvedNode(
        new Node().properties("$add", new Node().items(
                new Node().value(40L),
                new Node().value(2L))));
FrozenNode emptyDocument = FrozenNode.fromResolvedNode(new Node());

BexExecutionContext context = BexExecutionContext.builder()
        .document(new FrozenBexDocumentView(emptyDocument))
        .binding("policy", BexValues.fromSimple(
                Collections.<String, Object>singletonMap("limit", 100L)))
        .gasLimit(10_000L)
        .build();

try (BexEngine engine = BexEngine.builder().build()) {
    BexExecutionResult result = engine.compileAndExecute(
            BexProgramSource.expression(expression), context);
}
```

The complete runnable source is in the `examples` module.

## A full program

A full program can define constants, functions, a statement body, and/or a root
expression:

```yaml
constants:
  threshold: 400
expr:
  $gte:
    - $document: /amount
    - $const: threshold
```

An object with exactly one recognized `$...` key is an operator. Ordinary
objects and lists are literal values unless they occur in an operator-defined
operand position. `$literal` is the explicit escape when a value would otherwise
look executable.

BEX source is still Blue source. Parse and resolve it with the supported Blue
Language authoring boundary before wrapping the selected immutable program in
`BexProgramSource`; do not ask the BEX runtime to preprocess source documents.

## Choose the right value boundary

- Use `BexValues.frozen(...)` for an existing immutable exact Blue value.
- Use `BexValues.exact(...)` when a host already owns the canonical identity and
  resolved semantic cursor.
- Use `BexValues.fromSimple(...)` for transient maps, lists, and scalar data.
- Use a lazy context binding only when constructing the value is expensive and
  the program may not read it.

Never convert an exact value through a Java map simply to give it to BEX. That
would discard identity/provenance and add work.

## Understand failures

Compilation rejects unknown operators, malformed operands, unknown constants or
functions, recursive call graphs, invalid declarations, and other static
defects. Runtime failures cover dynamic pointer/type errors, arithmetic errors,
explicit `$fail`, unavailable/invalid host evidence, output admission, intrinsic
failure, and gas exhaustion.

Execution is fail-closed. A failed run does not commit its buffered changes or
events. The host owns the larger transaction.

## Pick an integration mode

Standalone mode uses a `FrozenBexDocumentView`, a local/parent gas budget, and
direct ordinary Blue identity establishment. Contracts-hosted mode uses the
`blue.bex.contracts` adapter so the invocation's document views, evidence,
shared budget, semantic identity boundary, and failure classification remain
host-owned.

Continue with [Program model](program-model.md), [Values and identity](values-and-identity.md),
and [Gas and exhaustion](gas-and-exhaustion.md). Integrators should also read
[Blue output boundary](blue-output-boundary.md) and
[Contracts hosting](contracts-hosting.md).
