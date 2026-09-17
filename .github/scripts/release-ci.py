#!/usr/bin/env python3
"""Prepare one source, fan out real builds, and run the release graph once."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('verification', Path(__file__).with_name('release-verification.py'))
v = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v)
spec = importlib.util.spec_from_file_location('central', Path(__file__).with_name('wait-maven-central.py'))
central = importlib.util.module_from_spec(spec)
spec.loader.exec_module(central)


def release_tasks(mode):
    v.require(mode in ['verify', 'rc', 'stable'], 'Unknown release mode')
    if mode == 'verify':
        return ['bexReleaseVerify']
    return ['bexReleaseVerify', 'publish'] + (['bexReserveRcVersion'] if mode == 'rc' else []) + [
        'jreleaserDeploy', '-PbexSeparateMavenWait=true']


def validate_mode(mode, ref):
    release_tasks(mode)
    if mode != 'verify':
        v.require(ref == {'rc': 'refs/heads/next', 'stable': 'refs/heads/main'}[mode],
                  'Publication mode requires its exact production branch')


def validate_identity(actual, expected):
    v.require(actual == expected, 'Prepared source identity differs from producer outputs/run/attempt')


def git(*args):
    return subprocess.check_output(['git', *args], text=True).strip()


def expected_tag():
    return 'v' + re.search(r'^version = "([^"]+)"', Path('.cz.toml').read_text(), re.M)[1]


def prepare(mode, output):
    validate_mode(mode, os.environ['GITHUB_REF'])
    v.require(git('rev-parse', 'HEAD') == os.environ['GITHUB_SHA'], 'Unexpected preparation HEAD')
    v.require(not git('status', '--porcelain'), 'Dirty preparation source')
    if mode == 'rc':
        subprocess.run(['git', 'fetch', 'origin', 'main:refs/remotes/origin/main', '--tags'], check=True)
        subprocess.run(['node', '.github/scripts/prepare-rc-release.js'], check=True)
        subprocess.run(['git', 'add', '.cz.toml'], check=True)
        subprocess.run(['git', 'commit', '-m', 'chore: release ' + expected_tag()[1:]], check=True)
        subprocess.run(['git', 'tag', '-a', expected_tag(), '-m', 'Release ' + expected_tag()[1:]], check=True)
    elif mode == 'stable':
        v.require(expected_tag() in git('tag', '--points-at', 'HEAD').splitlines(), 'Stable source needs its exact tag')
    else:
        v.require(not git('tag', '--points-at', 'HEAD'), 'Verification source must remain untagged')
    meta = dict(commit=git('rev-parse', 'HEAD'), tree=git('rev-parse', 'HEAD^{tree}'),
                workflowCommit=os.environ['GITHUB_SHA'], run=os.environ['GITHUB_RUN_ID'],
                attempt=os.environ['GITHUB_RUN_ATTEMPT'], mode=mode)
    output.mkdir(parents=True)
    subprocess.run(['git', 'bundle', 'create', str(output / 'source.bundle'), 'HEAD', '--tags'], check=True)
    (output / 'identity.json').write_text(json.dumps(meta, indent=2))
    with open(os.environ['GITHUB_OUTPUT'], 'a') as out:
        out.write('commit=' + meta['commit'] + '\ntree=' + meta['tree'] + '\nattempt=' + meta['attempt'] + '\n')


def restore_source(mode, directory):
    validate_mode(mode, os.environ['GITHUB_REF'])
    meta = json.loads((directory / 'identity.json').read_text())
    # Partial reruns keep the successful producer's source generation.
    expected = dict(commit=os.environ['PREPARED_COMMIT'], tree=os.environ['PREPARED_TREE'],
                    workflowCommit=os.environ['GITHUB_SHA'], run=os.environ['GITHUB_RUN_ID'],
                    attempt=os.environ['PREPARED_ATTEMPT'], mode=mode)
    validate_identity(meta, expected)
    subprocess.run(['git', 'fetch', str(directory / 'source.bundle'), 'HEAD', '--tags'], check=True)
    subprocess.run(['git', 'checkout', '--detach', meta['commit']], check=True)
    v.require(git('rev-parse', 'HEAD^{tree}') == meta['tree'], 'Prepared tree mismatch')
    v.require(not git('status', '--porcelain'), 'Restored source is dirty')
    if mode == 'rc':
        v.require(git('rev-parse', 'HEAD^') == meta['workflowCommit'], 'RC is not one prepared child commit')
        v.require(git('diff', '--name-only', 'HEAD^', 'HEAD') == '.cz.toml', 'RC changed more than version')
    else:
        v.require(meta['commit'] == meta['workflowCommit'], 'Unexpected prepared source mutation')
    tags = git('tag', '--points-at', 'HEAD').splitlines()
    v.require((not tags) if mode == 'verify' else expected_tag() in tags, 'Prepared tag policy mismatch')
    os.environ['SOURCE_DATE_EPOCH'] = git('show', '-s', '--format=%ct', 'HEAD')
    return dict(GITHUB_SHA=meta['commit'], GITHUB_RUN_ID=meta['run'], GITHUB_RUN_ATTEMPT=meta['attempt'])


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verified_source(source, mode):
    validate_mode(mode, os.environ['GITHUB_REF'])
    v.require(mode in ['rc', 'stable'], 'Verification requires production mode')
    ident = dict(GITHUB_SHA=os.environ['PREPARED_COMMIT'], GITHUB_RUN_ID=os.environ['GITHUB_RUN_ID'],
                 GITHUB_RUN_ATTEMPT=os.environ['PREPARED_ATTEMPT'])
    tag = 'v' + re.search(r'^version = "([^"]+)"', (source / '.cz.toml').read_text(), re.M)[1]
    v.require(git('rev-parse', 'HEAD') == ident['GITHUB_SHA']
              and git('rev-parse', 'HEAD^{tree}') == os.environ['PREPARED_TREE']
              and not git('status', '--porcelain')
              and tag in git('tag', '--points-at', 'HEAD').splitlines(), 'Verified source changed')
    report = source / 'build/reports/bex-release/final.json'
    strict = json.loads(report.read_text())
    v.require(strict.get('releaseReady') is True and strict.get('blockers') == []
              and strict.get('bexCommit') == ident['GITHUB_SHA'], 'Missing strict release authorization')
    return ident, tag, report


def reserve_rc_version(source, mode):
    v.require(mode == 'rc', 'Only RC reserves a version')
    _, tag, _ = verified_source(source, mode)
    # One atomic, non-forced update prevents an uploaded version from being reused.
    subprocess.run(['git', 'push', '--atomic', 'origin', 'HEAD:refs/heads/next',
                    f'refs/tags/{tag}:refs/tags/{tag}'], check=True)


def deployment_proof(source, mode):
    ident, tag, report = verified_source(source, mode)
    properties = source / 'build/jreleaser/maven-central-submitted.properties'
    central.deployment_id(properties.read_text())
    staging = source / 'build/staging-deploy'
    artifacts = {str(p.relative_to(staging)): sha256(p) for p in sorted(staging.rglob('*')) if p.is_file()}
    v.require(artifacts, 'Missing staged artifacts')
    return dict(identity=ident, mode=mode, execution_attempt=os.environ['GITHUB_RUN_ATTEMPT'],
                workflow_commit=os.environ['GITHUB_SHA'], tree=os.environ['PREPARED_TREE'], tag=tag,
                report_sha256=sha256(report), properties_sha256=sha256(properties), artifacts=artifacts)


def write_deployment_proof(source, ident, mode, path):
    proof = deployment_proof(source, mode)
    v.require(proof['identity'] == ident, 'Deployment source identity mismatch')
    path.write_text(json.dumps(proof, indent=2))


def validate_metadata_proof(source, mode, proof, published):
    expected = deployment_proof(source, mode)
    v.require(json.loads(proof.read_text()) == expected, 'Deployment evidence changed')
    receipt = json.loads(published.read_text())
    deployment = central.deployment_id((source / 'build/jreleaser/maven-central-submitted.properties').read_text())
    v.require(receipt == dict(deploymentId=deployment, deploymentState='PUBLISHED',
                             propertiesSha256=expected['properties_sha256']),
              'Maven Central publication not confirmed for this deployment')


def main():
    mode, operation = sys.argv[1:3]
    validate_mode(mode, os.environ['GITHUB_REF'])
    temp = Path(os.environ['RUNNER_TEMP'])
    source = Path.cwd()
    proof = temp / 'bex-release-output/deployment-proof.json'
    published = temp / 'bex-release-output/maven-publication.json'
    if operation == 'reserve':
        reserve_rc_version(source, mode)
        return
    if operation in ['metadata', 'metadata-check']:
        validate_metadata_proof(source, mode, proof, published)
        if operation == 'metadata':
            os.environ['SOURCE_DATE_EPOCH'] = git('show', '-s', '--format=%ct', 'HEAD')
            v.gradle(source, temp / 'bex-release-work/gradle-root-release',
                     ['jreleaserFullRelease', '--exclude-deployer-name=sonatype', '-PbexReleaseMetadataOnly=true'],
                     temp / 'bex-release-output/metadata.log')
        return
    if operation == 'prepare':
        prepare(mode, temp / 'bex-release-source')
        return
    ident = restore_source(mode, temp / 'bex-release-source')
    source = Path.cwd()
    root, logs = temp / 'bex-release-work', temp / 'bex-release-output'
    root.mkdir()
    logs.mkdir()
    if operation == 'independent':
        index = int(sys.argv[3])
        v.require(index in range(1, 5), 'Invalid independent build index')
        v.independent(root, index, source, ident, logs)
        v.package(root / str(index), logs / 'inputs.tar.gz')
        return
    v.require(operation == 'join', 'Unknown release operation')
    v.restore(temp / 'bex-release-downloads', root, ident)
    home, args = v.prepare_release_inputs(root, source, ident, logs)
    # One Gradle graph shares all checks naturally. Production keeps the exact
    # strict tagged-source gate ahead of local staging and remote release.
    if mode != 'verify':
        (source / 'build/jreleaser/output.properties').unlink(missing_ok=True)
        (source / 'build/jreleaser/maven-central-submitted.properties').unlink(missing_ok=True)
        proof.unlink(missing_ok=True)
        published.unlink(missing_ok=True)
    tasks = release_tasks(mode) + args[1:]
    code = v.gradle(source, home, tasks, logs / 'release.log', allow_failure=mode == 'verify')
    report = json.loads((source / 'build/reports/bex-release/final.json').read_text())
    if mode == 'verify':
        v.validate_final(report, code, (logs / 'release.log').read_text(), ident['GITHUB_SHA'], expected_tag())
    else:
        v.require(report.get('releaseReady') is True and report.get('blockers') == [], 'Release gate not ready')
        shutil.copy2(source / 'build/jreleaser/output.properties',
                     source / 'build/jreleaser/maven-central-submitted.properties')
        write_deployment_proof(source, ident, mode, proof)
    independent = [json.loads((root / str(i) / 'receipt.json').read_text()) for i in range(1, 5)]
    receipt = dict(identity=ident, execution_attempt=os.environ['GITHUB_RUN_ATTEMPT'], mode=mode, success=True,
                   elapsed_s=time.time()-min(r['started_at'] for r in independent),
                   strict_report=report, tests=v.inventory(source),
                   benchmarks=v.benchmark_inventory(source), independent=independent)
    (logs / 'receipt.json').write_text(json.dumps(receipt, indent=2))
    with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as out:
        out.write(f"## BEX {mode}: release verification and submission\n\n"
                  f"Four-build fanout + transfer/join + verification/submission: {receipt['elapsed_s']:.2f}s. "
                  f"Elapsed time includes any rerun waiting gaps. "
                  f"JUnit cases: {len(receipt['tests'])}; JMH cases: {len(receipt['benchmarks'])}. "
                  f"Release-ready: {report['releaseReady']}.\n")


if __name__ == '__main__':
    main()
