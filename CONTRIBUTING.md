# Contributing

## Scope

Keep `blue-bex-java` standalone. It may depend on `blue-language-java`; it must
not depend on `blue-contract-java`, JavaScript engines, QuickJS, WASM runtimes,
or product-specific packages.

## Tests

Run:

```bash
./gradlew clean test
./gradlew build
```

Add tests under focused conformance classes when changing semantics. Avoid tests
that only inflate counts; each test should document one behavior or failure.

## API Changes

Public boundary changes should update README, spec docs, and examples in the
same change. Mutable host objects require explicit immutability or snapshot
contracts.

## Benchmarks

Use disabled local benchmarks or a dedicated benchmark source set. Do not add
fragile wall-clock thresholds to CI.
