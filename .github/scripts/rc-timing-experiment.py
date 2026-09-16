#!/usr/bin/env python3
"""Full RC verification experiment. Never creates tags or invokes publication."""
import importlib.util
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import time

sys.dont_write_bytecode = True

spec = importlib.util.spec_from_file_location('build_timing', Path(__file__).with_name('ci-timing-experiment.py'))
build_timing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build_timing)
INDEPENDENT = ['clean', 'assemble', 'bexConformance', 'sourceReleaseArchive']
FINAL_TASK = 'generateBexReleaseReport'


def require(condition, message):
    if not condition:
        raise ValueError(message)


def identity():
    result = {k: os.environ[k] for k in ['GITHUB_SHA', 'GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT']}
    require(subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
            == result['GITHUB_SHA'], 'Checkout HEAD mismatch')
    return result


def validate_final(report, exit_code, log, commit, expected_tag):
    require(exit_code == 1, 'Expected exactly Gradle verification failure')
    require(report.get('schema') == 'blue-bex-strict-release/2.0'
            and report.get('dependencyPolicy') == 'published-only'
            and report.get('bexCommit') == commit, 'Wrong strict report identity')
    require(report.get('releaseReady') is False, 'Experiment must never authorize release')
    require(report.get('blockers') == ['HEAD is not tagged exactly ' + expected_tag],
            'Unexpected or missing release blocker')
    state = report.get('sourceState', {})
    require(state.get('clean') is True and state.get('exactReleaseTag') is False
            and state.get('expectedTag') == expected_tag and state.get('tagsAtHead') == [],
            'Source must be clean and untagged')
    for field in ['modernizationStatus', 'conformanceReleaseStatus', 'publishedLanguageStatus',
                  'independentCleanBuildStatus', 'publishedRepeatabilityStatus']:
        require(report.get(field) == 'passed', 'Unverified gate: ' + field)
    failures = re.findall(r'^> Task (\S+) FAILED$', log, re.MULTILINE)
    require(failures == [':' + FINAL_TASK], 'Unexpected failed Gradle task')
    require(re.search(r"^Execution failed for task ':generateBexReleaseReport'"
                      r"(?: \(registered by plugin 'blue\.bex\.root-orchestration'\))?\.$",
                      log, re.MULTILINE)
            and 'BEX public release remains fail-closed; see ' in log
            and 'BUILD FAILED' in log, 'Missing expected final failure evidence')


def validate_receipt(row, expected_identity, group):
    require(row['identity'] == expected_identity and row['group'] == group, 'Stale/wrong receipt')
    require(row['success'] is True, 'Failed receipt')
    for field in ['started_at', 'finished_at', 'elapsed_s']:
        require(isinstance(row[field], (float, int)) and math.isfinite(row[field])
                and row[field] > 0, 'Invalid timing')
    require(math.isclose(row['finished_at'] - row['started_at'], row['elapsed_s'],
                        rel_tol=0, abs_tol=.001), 'Inconsistent timing')


def run(command, cwd, env, log, allow_failure=False):
    print('Running:', ' '.join(map(str, command)), flush=True)
    with log.open('a') as output:
        process = subprocess.Popen(command, cwd=cwd, env=env, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, text=True)
        for line in process.stdout:
            print(line, end='', flush=True)
            output.write(line)
        code = process.wait()
    if not allow_failure:
        require(code == 0, 'Command failed: ' + str(command))
    return code


def gradle(source, home, tasks, log, allow_failure=False):
    env = dict(os.environ, GRADLE_USER_HOME=str(home))
    return run([str(source / 'gradlew'), '--no-daemon', '--console=plain',
                '--no-build-cache', '--max-workers=4', *tasks],
               source, env, log, allow_failure)


def manifest(checkout, path):
    import hashlib
    paths = []
    for prefix in ['blue-bex-core/build/libs', 'blue-bex-contracts/build/libs',
                   'blue-bex-java/build/libs', 'build/distributions']:
        paths += [p for p in (checkout / prefix).glob('*') if p.suffix in ['.jar', '.zip']]
    require(paths, 'No independent build artifacts')
    path.write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest() + '  '
                           + str(p.relative_to(checkout)) + '\n' for p in sorted(paths)))


def independent(root, index, source, ident, logs):
    start = time.time()
    directory = root / str(index)
    directory.mkdir(parents=True)
    checkout, home = directory / 'checkout', directory / 'gradle-home'
    log = logs / ('independent-' + str(index) + '.log')
    run(['git', 'clone', '--quiet', '--no-hardlinks', str(source), str(checkout)],
        source, os.environ, log)
    run(['git', 'checkout', '--quiet', '--detach', ident['GITHUB_SHA']], checkout, os.environ, log)
    gradle(checkout, home, INDEPENDENT, log)
    manifest(checkout, directory / 'manifest.sha256')
    row = dict(group='independent-' + str(index), identity=ident, success=True,
               started_at=start, finished_at=time.time(), commands=INDEPENDENT,
               tests=build_timing.inventory(checkout),
               manifest=(directory / 'manifest.sha256').read_text())
    row['elapsed_s'] = row['finished_at'] - start
    require(row['tests'], 'Independent build has no JUnit results')
    (directory / 'receipt.json').write_text(json.dumps(row, indent=2))
    return row


def package(directory, destination):
    # Transport actual required evidence, not an entire user home/cache. Git
    # metadata and source files are from a fresh local clone with no credentials.
    checkout = directory / 'checkout'
    with tarfile.open(destination, 'w:gz', compresslevel=1) as archive:
        tracked = subprocess.check_output(['git', 'ls-files', '-z'], cwd=checkout).decode().split('\0')
        for relative in filter(None, tracked):
            archive.add(checkout / relative, arcname='checkout/' + relative)
        archive.add(checkout / '.git', arcname='checkout/.git')
        for pattern in ['*/build/libs', 'build/distributions',
                        'blue-bex-conformance/build/reports/bex-conformance',
                        '**/build/test-results']:
            for path in checkout.glob(pattern):
                archive.add(path, arcname='checkout/' + str(path.relative_to(checkout)))
        cache = directory / 'gradle-home/caches/modules-2/files-2.1/blue.language'
        require(cache.is_dir(), 'Resolved Language cache is absent')
        archive.add(cache, arcname='gradle-home/caches/modules-2/files-2.1/blue.language')
        for name in ['manifest.sha256', 'receipt.json']:
            archive.add(directory / name, arcname=name)


def restore(downloads, root, ident):
    for index in range(1, 5):
        path = downloads / ('bex-rc-input-' + str(index)) / 'inputs.tar.gz'
        destination = root / str(index)
        destination.mkdir(parents=True)
        with tarfile.open(path) as archive:
            archive.extractall(destination, filter='data')
        receipt = json.loads((destination / 'receipt.json').read_text())
        validate_receipt(receipt, ident, 'independent-' + str(index))
        require(receipt['commands'] == INDEPENDENT and receipt['tests'], 'Wrong independent coverage')


def properties(path):
    return dict(line.split('=', 1) for line in path.read_text().splitlines()
                if line and not line.startswith('#') and '=' in line)


def verification_phases(mode):
    require(mode in ['baseline', 'parallel'], 'Invalid verification mode')
    return (['readiness', 'publish-prerequisites', 'jreleaser-prerequisites']
            if mode == 'baseline' else ['readiness'])


def finalize(root, source, ident, logs, mode):
    import hashlib
    receipt_root = root / 'comparison'
    receipt_root.mkdir()
    roots = [root / str(i) / 'checkout' for i in range(1, 5)]
    homes = [root / str(i) / 'gradle-home' for i in range(1, 5)]
    manifests = [root / str(i) / 'manifest.sha256' for i in range(1, 5)]
    independent_report = receipt_root / 'independent-clean-builds.json'
    repeatability = receipt_root / 'published-repeatability.json'
    run(['node', str(source / '.github/scripts/compare-independent-builds.mjs'),
         *map(str, manifests + roots + homes), ident['GITHUB_SHA'], str(independent_report)],
        source, os.environ, logs / 'compare.log')
    run(['node', str(source / '.github/scripts/compare-published-conformance-evidence.mjs'),
         *[str(p / 'blue-bex-conformance/build/reports/bex-conformance/report.json') for p in roots],
         ident['GITHUB_SHA'], str(repeatability)], source, os.environ, logs / 'compare.log')
    inspection = properties(source / 'src/test/resources/hosted-release/published-api-inspection.properties')
    require(inspection['status'] == 'compatible-with-final-hosted-adapter', 'Incompatible Language')
    coordinate = inspection['coordinate']
    require(re.fullmatch(r'blue\.language:blue-language-java:[0-9A-Za-z][0-9A-Za-z._-]*', coordinate),
            'Invalid Language coordinate')
    version = coordinate.split(':')[-1]
    modules = ['blue-language-java', *inspection['release.resolvedRuntimeArtifacts'].split(',')]
    require(len(set(modules)) == len(modules) and all(modules), 'Invalid Language artifact list')
    artifacts = []
    for module in modules:
        found = list((homes[0] / 'caches/modules-2/files-2.1/blue.language' / module / version)
                     .glob('*/' + module + '-' + version + '.jar'))
        require(len(found) == 1, 'Missing/duplicate Language artifact ' + module)
        key = 'artifact.sha256' if module == 'blue-language-java' else 'artifact.' + module + '.sha256'
        require(hashlib.sha256(found[0].read_bytes()).hexdigest() == inspection[key],
                'Language artifact hash mismatch')
        artifacts.append(found[0])
    home = root / 'gradle-root-release'
    gradle(source, home, ['clean'], logs / 'root-clean.log')
    retained = source / 'build/reports/bex-release/inputs'
    published = retained / 'published-artifacts'
    published.mkdir(parents=True)
    for path in [independent_report, repeatability]:
        shutil.copy2(path, retained / path.name)
    for path in artifacts:
        shutil.copy2(path, published / path.name)
    args = ['bexReleaseVerify', '-PbexPublishedLanguageCoordinate=' + coordinate,
            '-PbexPublishedLanguageSha256=' + inspection['artifact.sha256'],
            '-PbexPublishedLanguageArtifacts=' + ':'.join(str(published / p.name) for p in artifacts),
            '-PbexPublishedRepeatability=' + str(retained / repeatability.name),
            '-PbexIndependentCleanBuildReport=' + str(retained / independent_report.name)]
    phases = []
    expected_tag = 'v' + re.search(r'^version = "([^"]+)"', (source / '.cz.toml').read_text(), re.M)[1]
    for phase in verification_phases(mode):
        # Production publish and jreleaserFullRelease both depend on this exact
        # gate and each uses another fresh Gradle home. Measure their repeated
        # compute without ever selecting either publication task.
        phase_home = home if phase == 'readiness' else root / ('gradle-' + phase)
        log = logs / (phase + '.log')
        start = time.time()
        code = gradle(source, phase_home, args, log, allow_failure=True)
        report = json.loads((source / 'build/reports/bex-release/final.json').read_text())
        validate_final(report, code, log.read_text(), ident['GITHUB_SHA'], expected_tag)
        phases.append(dict(phase=phase, elapsed_s=time.time()-start, strict_report=report,
                           tests=build_timing.inventory(source),
                           benchmarks=build_timing.benchmark_inventory(source)))
    return phases


def summary(downloads, ident):
    rows = {}
    for mode in ['baseline', 'parallel']:
        row = json.loads((downloads / ('bex-rc-result-' + mode) / 'receipt.json').read_text())
        validate_receipt(row, ident, mode)
        require(row['coverage'] == ['four-independent-builds', 'full-rc-compute'], 'Incomplete graph')
        require([phase['phase'] for phase in row['verification_phases']] == verification_phases(mode),
                'Missing or unexpected full RC compute phase')
        for phase in row['verification_phases']:
            require(phase['elapsed_s'] > 0 and math.isfinite(phase['elapsed_s']), 'Invalid phase timing')
            for field in ['tests', 'benchmarks', 'strict_report']:
                require(phase[field] == row[field], 'Verification phase changed coverage: ' + field)
        require(row['tests'] and row['benchmarks'], 'Missing final test/benchmark inventory')
        require(len(row['independent']) == 4, 'Missing independent build')
        for index, receipt in enumerate(row['independent'], 1):
            validate_receipt(receipt, ident, 'independent-' + str(index))
            require(receipt['commands'] == INDEPENDENT and receipt['tests'], 'Wrong independent work')
        rows[mode] = row
    for key in ['tests', 'benchmarks', 'strict_report', 'repeatability']:
        require(rows['baseline'][key] == rows['parallel'][key], 'Different root results: ' + key)
    for left, right in zip(rows['baseline']['independent'], rows['parallel']['independent']):
        require(left['tests'] == right['tests'], 'Different independent test coverage')
        require(left['manifest'] == right['manifest'], 'Different baseline/parallel artifact bytes')
    base = rows['baseline']['elapsed_s']
    candidate = rows['parallel']['finished_at'] - min(r['started_at'] for r in rows['parallel']['independent'])
    text = (f'# BEX full RC compute (no publication)\n\n'
            f'Baseline: {base:.2f} s. Parallel build fanout + transfer/join + final verification: '
            f'{candidate:.2f} s. Reduction: {(1-candidate/base)*100:.2f}%.\n\n'
            'All prepublication checks and identical test/benchmark inventories passed. '
            'Baseline includes the three release-verification graphs (readiness, publish prerequisites, '
            'JReleaser prerequisites); candidate executes their shared verification once. '
            'All unchanged strict release reports remain releaseReady=false with exactly '
            'the missing-tag blocker. No tag, package publication or Maven wait was executed. '
            'Root setup/transfer gaps are included in the candidate envelope; initial runner '
            'setup and final result upload/summary are excluded.\n')
    print(text)
    with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as output:
        output.write(text)


def main():
    source = Path.cwd()
    ident = identity()
    require(os.environ['GITHUB_REF'] == 'refs/heads/codex/ci/bex-parallel-experiment', 'Experiment branch only')
    mode = sys.argv[1]
    if mode == 'summary':
        summary(Path(sys.argv[2]), ident)
        return
    require(mode in ['baseline', 'parallel', 'independent'], 'Unknown experiment mode')
    require(not subprocess.check_output(['git', 'status', '--porcelain'], text=True).strip(), 'Dirty source')
    require(not subprocess.check_output(['git', 'tag', '--points-at', 'HEAD'], text=True).strip(), 'Tagged source prohibited')
    root = Path(os.environ['RUNNER_TEMP']) / 'bex-rc-work'
    root.mkdir()
    output = Path(os.environ['RUNNER_TEMP']) / 'bex-rc-output'
    output.mkdir()
    started = time.time()
    if mode == 'independent':
        index = int(sys.argv[2])
        require(index in range(1, 5), 'Invalid independent index')
        independent(root, index, source, ident, output)
        package(root / str(index), output / 'inputs.tar.gz')
        return
    if mode == 'baseline':
        for index in range(1, 5):
            independent(root, index, source, ident, output)
    else:
        restore(Path(sys.argv[2]), root, ident)
    phases = finalize(root, source, ident, output, mode)
    finished = time.time()
    row = dict(group=mode, identity=ident, success=True, started_at=started,
               finished_at=finished, elapsed_s=finished-started,
               coverage=['four-independent-builds', 'full-rc-compute'],
               strict_report=phases[-1]['strict_report'], verification_phases=phases,
               repeatability=json.loads((root / 'comparison/published-repeatability.json').read_text()),
               tests=build_timing.inventory(source), benchmarks=build_timing.benchmark_inventory(source),
               independent=[json.loads((root / str(i) / 'receipt.json').read_text()) for i in range(1, 5)])
    (output / 'receipt.json').write_text(json.dumps(row, indent=2))


if __name__ == '__main__':
    main()
