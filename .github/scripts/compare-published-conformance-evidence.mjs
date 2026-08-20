#!/usr/bin/env node

import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';

const [firstPath, secondPath, thirdPath, fourthPath,
  bexCommit, outputPath] = process.argv.slice(2);
if (!firstPath || !secondPath || !thirdPath || !fourthPath ||
    !bexCommit || !outputPath) {
  throw new Error(
    'usage: compare-published-conformance-evidence.mjs ' +
      'REPORT1 REPORT2 REPORT3 REPORT4 COMMIT OUTPUT'
  );
}

const reports = [firstPath, secondPath, thirdPath, fourthPath]
  .map((path) => JSON.parse(readFileSync(path, 'utf8')));
if (!/^[0-9a-f]{40}$/.test(bexCommit)) {
  throw new Error(`invalid BEX commit: ${bexCommit}`);
}
const semanticKeys = [
  'identities',
  'finalTotals',
  'normativeVectorCoverage',
  'operatorCoverage',
  'representationMatrix',
  'representationMatrixResult',
  'cacheMatrix',
  'recursionEvidence',
  'finiteLoopEvidence',
  'semanticBoundaryInvocationEvidence',
  'cyclicProofEvidence',
  'cyclicProofUnavailabilityCapability',
  'intrinsicEvidence',
  'referenceEvidenceClassificationEvidence',
  'hostedLocalLimitCapability',
  'ledgerLifecycleEvidence'
];
const gasKeys = [
  'identities',
  'finalTotals',
  'counterCoverage',
  'gasExhaustionEvidence',
  'gasExhaustionTraceExamples',
  'maximumObservedOrderedTraceEntries'
];

function selected(report, keys) {
  return Object.fromEntries(keys.map((key) => [key, report[key] ?? null]));
}

function canonical(value) {
  if (Array.isArray(value)) {
    return `[${value.map(canonical).join(',')}]`;
  }
  if (value && typeof value === 'object') {
    return `{${Object.keys(value).sort().map(
      (key) => `${JSON.stringify(key)}:${canonical(value[key])}`
    ).join(',')}}`;
  }
  return JSON.stringify(value);
}

function digest(value) {
  return createHash('sha256').update(canonical(value)).digest('hex');
}

function repeated(values) {
  return values.length === 4 && values.every((value) => value === values[0]);
}

const modes = reports.map((report) => report.dependency?.mode ?? 'missing');
const coordinates = reports.map(
  (report) => report.dependency?.declaredCoordinate ?? 'missing'
);
const artifactHashes = reports.map(
  (report) => report.dependency?.resolution?.artifact?.sha256 ?? 'missing'
);
const semanticHashes = reports.map(
  (report) => digest(selected(report, semanticKeys))
);
const gasHashes = reports.map(
  (report) => digest(selected(report, gasKeys))
);
const allowedIncompleteGate =
  'independent-clean-build-reproducibility-gate-not-passing';
const sourceBound = reports.every((report) => report.commit === bexCommit);
const publishedModes = modes.every((mode) => mode === 'standalone-published');
const dependencyResolutionPassed = reports.every((report) =>
  report.dependency?.resolution?.status === 'passed' &&
    report.languageReleaseIdentity
      ?.currentDependencyExactFinalArtifactProven === true
);
const noUnexpectedFailures = reports.every((report) =>
  Array.isArray(report.currentModeFailures) &&
    report.currentModeFailures.every(
      (failure) => failure === allowedIncompleteGate
    )
);
const dependencyIdentityRepeated = repeated(coordinates) &&
  repeated(artifactHashes) && /^[0-9a-f]{64}$/.test(artifactHashes[0]);
const semanticAndGasRepeatability = repeated(semanticHashes);
const exactGasTraceRepeatability = repeated(gasHashes);
const passed = sourceBound && publishedModes && dependencyResolutionPassed &&
  noUnexpectedFailures && dependencyIdentityRepeated &&
  semanticAndGasRepeatability && exactGasTraceRepeatability;

const report = {
  schema: 'blue-bex-published-conformance-repeatability/1.0',
  status: passed ? 'passed' : 'failed',
  dependencyPolicy: 'published-only',
  bexCommit,
  runCount: reports.length,
  modes,
  coordinates,
  sourceBound,
  dependencyResolutionPassed,
  noUnexpectedFailures,
  dependencyIdentityRepeated,
  semanticAndGasRepeatability: semanticAndGasRepeatability
    ? 'passed' : 'failed',
  exactGasTraceRepeatability: exactGasTraceRepeatability
    ? 'passed' : 'failed',
  semanticEvidenceSha256: semanticHashes,
  gasEvidenceSha256: gasHashes,
  dependencyArtifactSha256: artifactHashes
};
writeFileSync(outputPath, `${JSON.stringify(report, null, 2)}\n`);
if (!passed) {
  console.error(
    `Published conformance repeatability failed: ${outputPath}`
  );
  process.exitCode = 1;
}
