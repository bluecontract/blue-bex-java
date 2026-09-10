# Rooted checkpoint Language binding

## Published dependency

BEX selects Language `3.1.0-rc.25` from Maven Central. The release tag
`v3.1.0-rc.25` points to `806536457fd2ff284159fe65439973aa0f02ca4f`
(tag object `01bb740a3a4697041345aef501a3cfed391f5004`). Its source differs
from the local candidate below only in `.cz.toml` release version metadata.

The published JAR SHA-256 values and required hosted API inspection are pinned
in `src/test/resources/hosted-release/published-api-inspection.properties`.
The BEX specification matches the copy packaged in the published Language
conformance JAR and is bound to RC25 in `published-specification.properties`.

The hosted event regression requires RC25's admitted canonical event cursor:
with RC24, `hostedCanonicalEventChildWithListRetainsItsExactIdentity` fails
because the admitted event is not strict canonical. BEX's runtime adapter
continues to wrap the cursor supplied by Language without reconstructing it.

Merging into `next` starts the existing BEX RC release workflow, which assigns
the next BEX RC version and runs the strict publication gates before publishing.
PR checks and local candidate acceptance do not establish public release readiness.

## Historical local candidate

Selected Language source: `1c4aaa029dcbd59458b36b6ff5bc0fc215240059`.
Selected version: `3.1.0-rc.33`.
Artifact manifest: `sha256:a7030f7599fc1d4a8d18733f9e85dea29444f82e78ff743736f78936f5096666`.
Contracts specification: `sha256:e91381c970859a6bafecdd99e46f5115ba033bf0534be84bbd5582531e9e347f`.
Contracts release: `sha256:156b58c6a19ab94cd3d1759dbbd115963c6d345c69511113798824cb5c749ced`.

The local `rc.33` number belongs to the pre-publication candidate lane; it is
not the Maven Central version. Its recorded manifest identifies those local
bytes and is not used as published artifact evidence.
