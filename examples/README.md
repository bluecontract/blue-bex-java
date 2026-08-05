# BEX examples

`StandaloneBexExample` has a `main` method and asserts both its result and the
presence of a canonical gas trace.

`HostedBexExample` is the runnable integration method used from an active
Contracts processor invocation. The caller supplies the selected immutable BEX
expression and a deterministic runtime namespace; the adapter binds document,
event, contract, gas, semantic-output, evidence, and failure boundaries.

The examples intentionally construct a small immutable `Node` tree directly.
Production applications may parse authored YAML through the supported Blue
Language source boundary before selecting/finalizing a `FrozenNode`; BEX itself
does not preprocess Source documents.
