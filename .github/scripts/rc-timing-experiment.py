#!/usr/bin/env python3
"""Full RC timing experiment; publication is never selected."""
import importlib.util
import json
import math
import os
from pathlib import Path
import re
import sys
import time

spec = importlib.util.spec_from_file_location('release_verification', Path(__file__).with_name('release-verification.py'))
verification = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verification)
# Keep the experiment's public helpers available to its focused regression tests.
for name in ['build_timing', 'require', 'identity', 'validate_final', 'validate_receipt',
             'run', 'gradle', 'manifest', 'independent', 'package', 'restore', 'properties',
             'INDEPENDENT', 'FINAL_TASK', 'subprocess', 'tarfile']:
    globals()[name] = getattr(verification, name)


def verification_phases(mode):
    require(mode in ['baseline', 'parallel'], 'Invalid verification mode')
    return (['readiness', 'publish-prerequisites', 'jreleaser-prerequisites']
            if mode == 'baseline' else ['readiness'])


def finalize(root, source, ident, logs, mode):
    home, args = verification.prepare_release_inputs(root, source, ident, logs)
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
