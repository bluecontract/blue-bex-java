#!/usr/bin/env node

import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  readFileSync,
  realpathSync,
  statSync,
  writeFileSync
} from 'node:fs';
import { isAbsolute, relative, resolve, sep } from 'node:path';

const [standaloneOneManifest, standaloneTwoManifest,
  localOneManifest, localTwoManifest,
  standaloneOneRoot, standaloneTwoRoot, localOneRoot, localTwoRoot,
  standaloneOneGradle, standaloneTwoGradle, localOneGradle, localTwoGradle,
  bexCommit, outputPath] = process.argv.slice(2);
if (!standaloneOneManifest || !standaloneTwoManifest ||
    !localOneManifest || !localTwoManifest ||
    !standaloneOneRoot || !standaloneTwoRoot ||
    !localOneRoot || !localTwoRoot ||
    !standaloneOneGradle || !standaloneTwoGradle ||
    !localOneGradle || !localTwoGradle || !bexCommit || !outputPath) {
  throw new Error(
    'usage: compare-independent-builds.mjs S1 S2 L1 L2 ' +
      'S1_ROOT S2_ROOT L1_ROOT L2_ROOT S1_GRADLE S2_GRADLE ' +
      'L1_GRADLE L2_GRADLE COMMIT OUTPUT'
  );
}

const SHA_256 = /^[0-9a-f]{64}$/;
const BEX_COMMIT = /^[0-9a-f]{40}$/;
const ARTIFACT_PATH =
  /^(?:blue-bex-(?:core|contracts|java)\/build\/libs\/[^/]+\.jar|build\/distributions\/[^/]+-source-release\.zip)$/;

function digest(value) {
  return createHash('sha256').update(value).digest('hex');
}

function requireAbsoluteDirectory(path, label) {
  if (!isAbsolute(path)) {
    throw new Error(`${label} must be absolute: ${path}`);
  }
  const real = realpathSync(path);
  if (!statSync(real).isDirectory()) {
    throw new Error(`${label} must be a directory: ${path}`);
  }
  return real;
}

function requireAbsoluteFile(path, label) {
  if (!isAbsolute(path)) {
    throw new Error(`${label} must be absolute: ${path}`);
  }
  const real = realpathSync(path);
  if (!statSync(real).isFile()) {
    throw new Error(`${label} must be a file: ${path}`);
  }
  return real;
}

function contained(root, child) {
  const value = relative(root, child);
  return value !== '' && value !== '..' &&
    !value.startsWith(`..${sep}`) && !isAbsolute(value);
}

function safeArtifactPath(path) {
  return !path.startsWith('/') && !path.includes('\\') &&
    path !== '..' && !path.startsWith('../') &&
    !path.includes('/../') && !path.endsWith('/..');
}

function loadManifest(path, checkoutRoot) {
  const manifestPath = requireAbsoluteFile(path, 'artifact manifest');
  const bytes = readFileSync(manifestPath);
  const text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  if (!text || !text.endsWith('\n') || text.includes('\r')) {
    throw new Error(
      `artifact manifest must be non-empty LF text: ${manifestPath}`
    );
  }
  const lines = text.slice(0, -1).split('\n');
  const artifacts = [];
  const paths = new Set();
  for (const line of lines) {
    const match = /^([0-9a-f]{64})  (\S(?:.*\S)?)$/.exec(line);
    if (!match || !SHA_256.test(match[1]) ||
        !safeArtifactPath(match[2]) || !ARTIFACT_PATH.test(match[2])) {
      throw new Error(
        `invalid artifact manifest line in ${manifestPath}: ${line}`
      );
    }
    if (paths.has(match[2])) {
      throw new Error(
        `duplicate artifact path in ${manifestPath}: ${match[2]}`
      );
    }
    paths.add(match[2]);
    const artifactPath = realpathSync(resolve(checkoutRoot, match[2]));
    if (!contained(checkoutRoot, artifactPath) ||
        !statSync(artifactPath).isFile()) {
      throw new Error(`artifact escapes checkout: ${match[2]}`);
    }
    const artifactBytes = readFileSync(artifactPath);
    const actualHash = digest(artifactBytes);
    if (actualHash !== match[1]) {
      throw new Error(`artifact hash is stale: ${match[2]}`);
    }
    artifacts.push({
      path: match[2],
      bytes: artifactBytes.length,
      sha256: actualHash
    });
  }
  artifacts.sort((left, right) => left.path < right.path
    ? -1 : left.path > right.path ? 1 : 0);
  const artifactSet = artifacts.map(
    (artifact) => `${artifact.sha256}  ${artifact.path}\n`
  ).join('');
  if (!bytes.equals(Buffer.from(artifactSet, 'utf8'))) {
    throw new Error(`artifact manifest is not bytewise canonical: ${path}`);
  }
  return {
    bytes,
    artifacts,
    evidence: {
      path: manifestPath,
      bytes: bytes.length,
      sha256: digest(bytes),
      artifactCount: artifacts.length,
      artifactSetSha256: digest(artifactSet)
    }
  };
}

function requiredArtifactRolesPresent(artifacts) {
  return [
    'blue-bex-core/build/libs/',
    'blue-bex-contracts/build/libs/',
    'blue-bex-java/build/libs/'
  ].every((prefix) => artifacts.some(
    (artifact) => artifact.path.startsWith(prefix) &&
      artifact.path.endsWith('.jar')
  )) && artifacts.some(
    (artifact) => artifact.path.startsWith('build/distributions/') &&
      artifact.path.endsWith('-source-release.zip')
  );
}

function git(checkoutRoot, ...gitArguments) {
  return execFileSync('git', ['-C', checkoutRoot, ...gitArguments], {
    encoding: null,
    stdio: ['ignore', 'pipe', 'pipe']
  });
}

function buildEvidence(manifestPath, rootPath, gradlePath) {
  const checkoutRoot = requireAbsoluteDirectory(rootPath, 'checkout root');
  const gradleHome = requireAbsoluteDirectory(gradlePath, 'Gradle home');
  const head = git(checkoutRoot, 'rev-parse', 'HEAD')
    .toString('utf8').trim();
  const reportedGitDirectory = git(checkoutRoot, 'rev-parse', '--git-dir')
    .toString('utf8').trim();
  const gitDirectory = realpathSync(isAbsolute(reportedGitDirectory)
    ? reportedGitDirectory : resolve(checkoutRoot, reportedGitDirectory));
  const status = git(
    checkoutRoot, 'status', '--porcelain', '-z', '--untracked-files=all'
  );
  if (head !== bexCommit || status.length !== 0) {
    throw new Error(`checkout is not clean at ${bexCommit}: ${checkoutRoot}`);
  }
  const manifest = loadManifest(manifestPath, checkoutRoot);
  return {
    internalManifestBytes: manifest.bytes,
    report: {
      checkoutRoot,
      gitDirectory,
      gradleHome,
      head,
      clean: true,
      manifest: manifest.evidence,
      artifacts: manifest.artifacts
    }
  };
}

function pair(first, second) {
  const firstArtifacts = first.report.artifacts;
  const secondArtifacts = second.report.artifacts;
  const exactManifestBytesMatch =
    first.internalManifestBytes.equals(second.internalManifestBytes);
  const firstPaths = firstArtifacts.map((artifact) => artifact.path);
  const secondPaths = secondArtifacts.map((artifact) => artifact.path);
  const artifactPathSetMatch = JSON.stringify(firstPaths) ===
    JSON.stringify(secondPaths);
  const exactArtifactBytesMatch = artifactPathSetMatch &&
    firstArtifacts.every((artifact, index) =>
      artifact.bytes === secondArtifacts[index].bytes &&
      artifact.sha256 === secondArtifacts[index].sha256
    );
  const firstByPath = new Map(firstArtifacts.map(
    (artifact) => [artifact.path, artifact]
  ));
  const secondByPath = new Map(secondArtifacts.map(
    (artifact) => [artifact.path, artifact]
  ));
  const artifactDifferences = [...new Set([
    ...firstByPath.keys(), ...secondByPath.keys()
  ])].sort().flatMap((path) => {
    const firstArtifact = firstByPath.get(path) ?? null;
    const secondArtifact = secondByPath.get(path) ?? null;
    return JSON.stringify(firstArtifact) === JSON.stringify(secondArtifact)
      ? [] : [{ path, firstArtifact, secondArtifact }];
  });
  const requiredArtifactsPresent =
    requiredArtifactRolesPresent(firstArtifacts) &&
    requiredArtifactRolesPresent(secondArtifacts);
  const passed = exactManifestBytesMatch && exactArtifactBytesMatch &&
    requiredArtifactsPresent;
  return {
    status: passed ? 'passed' : 'failed',
    exactManifestBytesMatch,
    exactArtifactBytesMatch,
    artifactPathSetMatch,
    artifactDifferences,
    requiredArtifactRolesPresent: requiredArtifactsPresent,
    artifactCount: firstArtifacts.length,
    firstBuild: first.report,
    secondBuild: second.report
  };
}

if (!BEX_COMMIT.test(bexCommit)) {
  throw new Error(`invalid BEX commit: ${bexCommit}`);
}
const builds = [
  buildEvidence(standaloneOneManifest,
    standaloneOneRoot, standaloneOneGradle),
  buildEvidence(standaloneTwoManifest,
    standaloneTwoRoot, standaloneTwoGradle),
  buildEvidence(localOneManifest, localOneRoot, localOneGradle),
  buildEvidence(localTwoManifest, localTwoRoot, localTwoGradle)
];
const checkoutRoots = builds.map((build) => build.report.checkoutRoot);
const gitDirectories = builds.map((build) => build.report.gitDirectory);
const gradleHomes = builds.map((build) => build.report.gradleHome);
const manifestPaths = builds.map((build) => build.report.manifest.path);
const distinctCheckoutRoots = new Set(checkoutRoots).size === 4;
const distinctGitDirectories = new Set(gitDirectories).size === 4;
const distinctGradleHomes = new Set(gradleHomes).size === 4;
const distinctInputManifestFiles = new Set(manifestPaths).size === 4;
const standalonePublished = pair(builds[0], builds[1]);
const localComposite = pair(builds[2], builds[3]);
const passed = distinctCheckoutRoots && distinctGitDirectories &&
  distinctGradleHomes && distinctInputManifestFiles &&
  standalonePublished.status === 'passed' &&
  localComposite.status === 'passed';
const report = {
  schema: 'blue-bex-independent-clean-builds/2.1',
  status: passed ? 'passed' : 'failed',
  bexCommit,
  checkoutCount: checkoutRoots.length,
  gitDirectoryCount: gitDirectories.length,
  gradleHomeCount: gradleHomes.length,
  inputManifestCount: manifestPaths.length,
  distinctCheckoutRoots,
  distinctGitDirectories,
  distinctGradleHomes,
  distinctInputManifestFiles,
  standalonePublished,
  localComposite
};
writeFileSync(outputPath, `${JSON.stringify(report, null, 2)}\n`);
if (!passed) {
  console.error(`Independent clean-build comparison failed: ${outputPath}`);
  for (const [label, comparison] of [
    ['standalone-published', standalonePublished],
    ['local-composite', localComposite]
  ]) {
    if (comparison.status === 'passed') {
      continue;
    }
    console.error(
      `${label}: manifestBytes=${comparison.exactManifestBytesMatch}, ` +
        `artifactBytes=${comparison.exactArtifactBytesMatch}, ` +
        `pathSet=${comparison.artifactPathSetMatch}, ` +
        `requiredRoles=${comparison.requiredArtifactRolesPresent}`
    );
    for (const difference of comparison.artifactDifferences) {
      console.error(
        `${label}: ${difference.path}: ` +
          `${difference.firstArtifact?.sha256 ?? 'missing'} != ` +
          `${difference.secondArtifact?.sha256 ?? 'missing'}`
      );
    }
  }
  process.exitCode = 1;
}
