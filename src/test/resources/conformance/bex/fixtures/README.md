# Blue BEX 2.0 conformance fixtures

This directory is the machine-readable conformance package for Blue BEX 2.0. It contains one behavioral fixture for every prose vector and one exact gas microfixture for every named BEX counter. The fixture manifest is bound to `../gas-manifest.yaml`; the prose table, machine-readable schedule, and all microfixture weights must agree exactly.

Read `HARNESS.md` before implementing a runner. Unknown operators, context bindings, assertion projections, fixture fields, or intrinsic types are errors and MUST NOT be skipped.
