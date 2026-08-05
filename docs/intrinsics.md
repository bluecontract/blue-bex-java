# Intrinsics

`$intrinsic` is BEX's only host extension point. It binds a statically declared
Blue type identity to one deterministic processor and an exact named gas
catalog. Portable operators themselves are not dynamically pluggable.

## Register by BlueId

Prefer the explicit string identity:

```java
BexIntrinsicRegistry registry = BexIntrinsicRegistry.builder()
        .register(
                operationBlueId,
                operationRegistryIdentity,
                Collections.singletonMap("signatureVerification", 500L),
                invocation -> {
                    invocation.charge(
                            "signatureVerification", 1L, "verify-signature");
                    return BexValues.scalar(verify(invocation));
                })
        .build();

try (BexEngine engine = BexEngine.builder().intrinsics(registry).build()) {
    // Compile and execute programs for this host lifecycle.
}
```

A class convenience is valid only when an explicit `BexTypeBlueIdResolver` or
the supported annotated-type mapping boundary resolves that class. A Java class
name is never a BlueId, and reflective fallback identity is forbidden.

The registry is immutable. Its identity includes sorted BlueIds, registry
identities, namespaces, counter names, and weights, so compilation caching cannot
confuse engines with different intrinsic semantics.

## Program shape

The exact `$intrinsic` source shape is defined in the BEX specification. The
operation type/BlueId is static: compilation records it in the program's
required-intrinsic set and fails when the engine does not support it. Payload
fields are ordinary compiled operands and evaluate in their normative order.

## Invocation capability

`BexIntrinsicInvocation` exposes:

- the exact operation BlueId and type value;
- an immutable evaluated field map and undefined-for-missing lookup;
- the registration's physical gas namespace and named weights;
- `charge(name, quantity, reason)` for declared work only;
- `exactField(name)` when the operation explicitly requires admitted exact Blue
  input;
- a diagnostic current ledger total.

`exactField` uses the same strict output/identity boundary as other Blue output.
Do not serialize and reparse the value or calculate a second identity.

## Processor contract

An intrinsic processor must be deterministic for its declared inputs and host
capability. It must charge before performing each declared unit of work and may
not access undeclared global authority through BEX. Return a `BexValue`; a Java
`null` result is treated as undefined only where the API explicitly documents
that behavior.

Exceptions fail the run. They are not converted into a successful false/null
result. Hosted adapters preserve recognized evidence, processor, portable-limit,
and gas classifications. Buffered BEX effects remain uncommitted.

## Checklist for a new intrinsic

1. Define and publish the operation's ordinary Blue type/BlueId.
2. Pin the registry identity that defines that operation.
3. Define a closed, collision-free namespace and counter/weight catalog.
4. Implement deterministic validation and charge-before-work processing.
5. Admit only fields whose semantics require exact Blue content.
6. Add compile, success, failure, gas-order, exhaustion, and hosted tests.
7. Document the authority and data exposed to the processor.
8. Include the registration in reproducibility/dependency evidence where it is
   part of a released integration.

Adding an intrinsic does not change the 86 portable BEX operators.
