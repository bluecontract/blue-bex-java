#!/usr/bin/env node

import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';

const [standaloneOnePath, standaloneTwoPath, localOnePath, localTwoPath,
  bexCommit, outputPath] = process.argv.slice(2);
if (!standaloneOnePath || !standaloneTwoPath || !localOnePath ||
    !localTwoPath || !bexCommit || !outputPath) {
  throw new Error(
    'usage: compare-independent-builds.mjs S1 S2 L1 L2 COMMIT OUTPUT'
  );
}

function load(path) {
  const text = readFileSync(path, 'utf8');
  if (!text.trim()) {
    throw new Error(`empty artifact manifest: ${path}`);
  }
  return text;
}

function digest(text) {
  return createHash('sha256').update(text).digest('hex');
}

function pair(firstPath, secondPath) {
  const first = load(firstPath);
  const second = load(secondPath);
  const passed = first === second;
  return {
    status: passed ? 'passed' : 'failed',
    firstManifestSha256: digest(first),
    secondManifestSha256: digest(second),
    exactArtifactBytesMatch: passed,
    artifactCount: first.trim().split(/\r?\n/).length
  };
}

const standalonePublished = pair(standaloneOnePath, standaloneTwoPath);
const localComposite = pair(localOnePath, localTwoPath);
const passed = standalonePublished.status === 'passed' &&
  localComposite.status === 'passed';
const report = {
  schema: 'blue-bex-independent-clean-builds/1.0',
  status: passed ? 'passed' : 'failed',
  bexCommit,
  isolatedGradleHomes: 4,
  standalonePublished,
  localComposite
};
writeFileSync(outputPath, `${JSON.stringify(report, null, 2)}\n`);
if (!passed) {
  process.exitCode = 1;
}
