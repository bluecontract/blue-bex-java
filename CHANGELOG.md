# Changelog

## Unreleased

- Made `BexValues.node(Node)` deprecated and snapshot-safe.
- Added explicit `nodeSnapshot` and `nodeCursorTrustedImmutable` documentation
  and tests.
- Derived execution scope from `BexDocumentView`.
- Hardened compiled-program cache keys with stable node identity and content
  fingerprints.
- Added BEX source path diagnostics through `BexSourcePath` and enriched
  `BexException`.
- Added `BexFrozenNodeFactory` and default `NodeRoundTripFrozenNodeFactory`.
- Added focused conformance, diagnostics, cache, frozen writer, dependency, and
  local benchmark tests.
- Added v0.4 spec, conformance docs, examples, CI workflow, and release notes.
