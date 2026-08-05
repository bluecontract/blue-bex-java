#!/usr/bin/env node

import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';

const [localPath, publishedPath, bexCommit, outputPath] = process.argv.slice(2);
if (!localPath || !publishedPath || !bexCommit || !outputPath) {
  throw new Error(
    'usage: compare-local-published-evidence.mjs LOCAL PUBLISHED COMMIT OUTPUT'
  );
}

const local = JSON.parse(readFileSync(localPath, 'utf8'));
const published = JSON.parse(readFileSync(publishedPath, 'utf8'));

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

const localSemantic = selected(local, semanticKeys);
const publishedSemantic = selected(published, semanticKeys);
const localGas = selected(local, gasKeys);
const publishedGas = selected(published, gasKeys);
const semanticAndGasParity =
  canonical(localSemantic) === canonical(publishedSemantic);
const exactGasTraceParity = canonical(localGas) === canonical(publishedGas);
const sourceBound = local.commit === bexCommit && published.commit === bexCommit;
const dependenciesDistinct =
  local.dependency?.mode === 'local-composite' &&
  published.dependency?.mode === 'standalone-published';
const passed = semanticAndGasParity && exactGasTraceParity && sourceBound &&
  dependenciesDistinct;

const report = {
  schema: 'blue-bex-local-published-differential/1.0',
  status: passed ? 'passed' : 'failed',
  bexCommit,
  localMode: local.dependency?.mode ?? 'missing',
  publishedMode: published.dependency?.mode ?? 'missing',
  sourceBound,
  dependenciesDistinct,
  semanticAndGasParity: semanticAndGasParity ? 'passed' : 'failed',
  exactGasTraceParity: exactGasTraceParity ? 'passed' : 'failed',
  semanticEvidenceSha256: {
    local: digest(localSemantic),
    published: digest(publishedSemantic)
  },
  gasEvidenceSha256: {
    local: digest(localGas),
    published: digest(publishedGas)
  }
};
writeFileSync(outputPath, `${JSON.stringify(report, null, 2)}\n`);
if (!passed) {
  process.exitCode = 1;
}
