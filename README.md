# blue-bex-java

`blue-bex-java` compiles and executes Blue Expression Objects (BEX):
deterministic programs represented as Blue object trees. A BEX program reads a
host-supplied document view and bindings, computes Blue-compatible values, and
returns a `BexExecutionResult`. It never applies patches, emits workflow events,
or performs I/O by itself.

BEX is deliberately not JavaScript, a contract processor, or a general plugin
runtime. Portable programs have no clock, randomness, network, filesystem, or
implicit host authority. The one controlled extension point is `$intrinsic`,
whose processor, BlueId, counter vocabulary, and weights are registered by the
host.

## Five-minute example

This expression returns `42`:

```yaml
$add:
  - 40
  - 2
```

The standalone API has three inputs: a selected `BexProgramSource`, an immutable
`BexExecutionContext`, and a reusable `BexEngine`.

```java
FrozenNode expression = FrozenNode.fromResolvedNode(
        new Node().properties("$add", new Node().items(
                new Node().value(40L),
                new Node().value(2L))));
FrozenNode document = FrozenNode.fromResolvedNode(new Node());

try (BexEngine engine = BexEngine.builder().build()) {
    BexExecutionResult result = engine.compileAndExecute(
            BexProgramSource.expression(expression),
            BexExecutionContext.builder()
                    .document(new FrozenBexDocumentView(document))
                    .gasLimit(10_000L)
                    .build());

    System.out.println(result.value().toSimple()); // 42
    System.out.println(result.gasLedger().trace());
}
```

A runnable version lives in
[`StandaloneBexExample.java`](examples/src/main/java/blue/bex/examples/StandaloneBexExample.java).

## Standalone use

The aggregate coordinate is `blue.bex:blue-bex-java`; select an actual version
from release metadata rather than copying a snapshot version from this checkout.
Standalone execution supplies `FrozenBexDocumentView`, ordinary bindings, and a
local gas limit. One engine can be reused; each execution context belongs to one
run.

Existing exact Blue values should enter through `BexValues.frozen(...)` or an
equivalent exact-value boundary. Transient Java maps/lists can enter through
`BexValues.fromSimple(...)`. They have different construction costs but the same
portable observable behavior once they represent the same exact value.

## Contracts-hosted use

The `blue.bex.contracts` adapter is the only layer that should know
`ProcessorExecutionContext`. It binds the canonical/resolved document views,
standard event and contract bindings, the live shared gas budget, failure
translation, and the host-owned semantic-output boundary. See
[`HostedBexExample.java`](examples/src/main/java/blue/bex/examples/HostedBexExample.java)
and [Contracts hosting](docs/contracts-hosting.md).

Hosted execution does not give BEX contract-processing authority. BEX returns
data; Contracts decides whether and how patches, events, scopes, and processor
effects are committed.

## Semantics at a glance

- There is one ordinary BlueId algorithm. `$nodeBlueId` returns an established
  exact identity or directly establishes identity for a strictly admitted
  transient value. It never preprocesses, resolves, canonicalizes, or minimizes
  a Source document.
- Exact values cross BEX boundaries by identity. Portable operators cannot
  distinguish inline/reference, eager/lazy, warm/cold, or provider segmentation.
- Gas is named logical work. A charge is admitted before its work; a rejected
  charge is absent, and no later work or buffered effect commits.
- `collectionPaths`, Timeline, Mandate, Coordination, `Process Embedded`,
  persistence, and collection activation are host/Contracts concerns, not BEX
  semantics.

## Read next

- [Start here](docs/start-here.md) — mental model and first integration
- [Program model](docs/program-model.md) — source trees, functions, errors, and
  evaluation order
- [Values and identity](docs/values-and-identity.md) — exact/transient values and
  representation blindness
- [Gas and exhaustion](docs/gas-and-exhaustion.md) — the canonical named ledger
- [Architecture](docs/architecture.md) — modules and ownership boundaries
- [Conformance](docs/conformance.md) — normative counts and identities
- [Release](docs/release.md) — local working gate versus strict publication gate
- [BEX 2.0 specification](specifications/blue-bex-specification-2.0.md) — normative
  language definition

## Current release state

Two fail-closed gates intentionally answer different questions:

```text
bexWorkingVerification  exact local modular Blue Language checkout
bexReleaseVerify        independently reproducible published dependencies
```

The working gate may pass before compatible Language modules are published. A
public release is eligible only when `bexReleaseVerify` records
`releaseReady = true`; missing published artifacts or unexecuted differential
evidence must remain red or `not-executed`. This README does not claim that an
unexecuted gate, benchmark, or release has passed. Consult the current generated
reports under `build/reports`.

The normative package currently contains 60 vectors, 105 behavior fixtures, 30
gas microfixtures, and coverage for 86 operators. Exact identities are recorded
in [Conformance](docs/conformance.md).

## License

MIT License. See [LICENSE](LICENSE).
