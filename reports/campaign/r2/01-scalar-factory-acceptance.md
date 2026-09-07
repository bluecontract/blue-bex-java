# E2: actual BEX scalar factory acceptance

BEX base: `e0597120d9a57015c6631a6acd3fadac8376e073`.
Verified real project: `/Users/piotr/data/blue-bex-java`.
Isolated worktree: `/private/tmp/codex-r2-contracts-bex`, branch `codex/r2-contracts-bex`.
Language base: `26fd05dbd16fa4a59a470aa9d3caf6b64b9fd658`.
Language implementation and test commit:
`6b972a2d6d7958309ed1e8e5a1906dbe70fe9384`.

Only acceptance tests and this receipt change in BEX. The proven defect belongs
to Contracts output canonicalization and inherited runtime-field resolution;
there is no BEX production change, private application factory, or declaration
semantics change.

`BexScalarDocumentFactoryTest` runs ordinary BEX functions with scalar arguments
through `BexEngine`, `BexContractsExecutionContext`, admitted exact patch/event
capabilities, full snapshot-backed Contracts execution, and managed admission.
It covers Order and Observation types; inline, referenced, imported and
current-document type bindings; two children; wrong kind, explicit empty object,
null and missing values; stable receipts on exact closed-input replay; distinct
receipts for legitimate fresh lineage reservations; an already-initialized
command rejection; and complete rollback on the second child's failure.
The independent Contracts fixture provides the manually authored document
control and owns the production regression.

Validation (successful, 8 seconds):

```sh
./gradlew :blue-bex-conformance:test --tests '*BexScalarDocumentFactoryTest' --tests '*BexContractsNullBoundaryAcceptanceTest' -PblueLanguageCompositePath=/private/tmp/codex-r2-contracts --offline --console=plain
```

Six JUnit tests, zero failures/errors/skips. The scalar test methods contain
bounded matrices; the existing two null-boundary tests distinguish explicit
empty objects, missing values and null across actual hosted output boundaries.

Exact replay preserves receipt identity; durable publication deduplication is
host-owned. New commands use fresh durable lineage reservations, even when
content is equal. F2's birth/replay/retained-source contract is documented in
Language's `docs/guides/managed-document-composition.md`.

Expected BEX semantic identity impact: none (test-only). The Language fix allows
valid factories that previously failed to initialize and commit. No package
regeneration, final sealing, remote publication, or edits to other worktrees.
The containing commit identifies this BEX slice; the Language final handoff
records that exact commit for A2/F2.
