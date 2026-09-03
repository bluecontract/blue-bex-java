# BEX empty-object identity migration

The BEX behavioral identity closure is complete. Final module and repository
artifact identities are intentionally not claimed yet because they must bind a
final clean Language development repository and the final clean BEX commit.
The machine-readable source of truth is
`reports/migration/empty-object-identity-impact.json`.

## Exact cascade

The upstream Language `List` node changed because list elements now preserve
ordinary `{}` distinctly from the `$empty: true` null placeholder. BEX's
conformance-only sort intrinsic names `List` as its `values` type, so the exact
dependency chain is:

```text
Language List
  -> BEX SortFixtureIntrinsic
  -> BEX runtime registry package
  -> bex-g-09 plus the BEX fixture package
  -> the current BEX specification digest and SDK specification lock
```

| Artifact | Previous exact identity | Current exact identity |
| --- | --- | --- |
| Exact `{}` | `5ajuwjHoLj33yG5t5UFsJtUb3vnRaJQEMPqSLz6VyoHK` | unchanged |
| Language `List` | `8DSFoWG9MqRSUhStqoPLrwVQiYByRh18NWbDEarN8MKF` | `85ip88snCGrgUNdi1rUFqqAxcxwVGKV2g4LjsKoyKmXK` |
| BEX sort intrinsic | `2R1WaEk8LVwFRMEGnsZ8HTj15QTz3tQEj9LDYYjGFJJG` | `3x6byASNDdnEf9o2EgzAVqewP1zuqyNmzccAmyiYfQsw` |
| Runtime registry | `sha256:23d282ec1c0bb016263922b1b49c369fdd537efdcf23e005eceeb888d7763fe1` | `sha256:2ccbfc9d1a1c4425cdcaf37c924274cc4398f82ac72769a8c2cf1dd8ba2fd04b` |
| Fixture package after boundary work | `sha256:dc2a1a999d8af0cf67ec96c8deb5c0242c9b9ec5a6a290574b8365c4e5545ec2` | `sha256:fdb896bf467c0ab8f3b2c9af3b609c9e7d0dc368dd3061f4b2909d0ee1fa89c3` |
| BEX specification bytes | `sha256:3c9b5950fc0dbd476eec9ea1bdc3449f0d647d51855e7e53d25fac4ac0a88898` | `sha256:560c18300bbaba312ad1b8a9bb53ea0655627a2228360d5074a923fb7de539de` |

`Compute2`, `FixtureIntrinsic`, and the BEX gas package remain unchanged. The
older fixture package `sha256:a1b7bb2b...` and specification
`sha256:1725878b...` remain only as immutable pre-stable release evidence.
They are not aliases for the current content.

## Historical and current specification gates

The hosted-release baseline still authenticates its historical specification
bytes. A standalone published-release report must match that baseline. An SDK
candidate instead reports the current specification and is accepted only when
the current file exists; `verifyBexSdkStageReport` independently hashes that
file and compares it with both the report and
`bex.currentSpecificationSha256` in the candidate lock.

This separation permits an intentional staged specification revision without
weakening either gate: history stays immutable, while current candidate bytes
are exact and fail closed if the file, lock, or report differs.

## No aliases

No old-to-new BlueId alias, provider redirect, or compatibility registry entry
was added. Active runtime, fixture, documentation, and specification references
use only the current identities. The historical baseline files remain
unchanged and are explicitly inventoried.

## Remaining release closure

The following values are unresolved by design:

1. the final Language candidate version, source commit, manifest hash, and
   module hashes;
2. final BEX JAR, POM, module metadata, sources, and Javadoc hashes;
3. the immutable commit-bound BEX repository manifest;
4. Coordination's BEX version, manifest hash, and dependency locks.

After the final Language repository is available, run the regeneration order
recorded in the JSON inventory. Do not seal BEX from a dirty checkout or copy
historical release identities into the candidate receipt.
