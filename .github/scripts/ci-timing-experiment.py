#!/usr/bin/env python3
"""Run only the allowlisted, non-publishing BEX validation experiment."""
import json
import math
import os
from pathlib import Path
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

VERIFY = ['clean', 'bexCheck', 'bexConformance', 'bexCompatibilityCheck',
          'bexReproducibilityCheck'] + [f':{module}:writeLanguageDependencyEvidence'
          for module in ['blue-bex-core', 'blue-bex-contracts', 'blue-bex-conformance',
                         'blue-bex-java', 'examples']]
BENCHMARK = [':blue-bex-conformance:jmh']
GROUPS = {'baseline': [VERIFY, BENCHMARK], 'verification': [VERIFY],
          'benchmarks': [BENCHMARK], 'four-forks': [VERIFY, BENCHMARK]}


def inventory(root):
    tests = []
    for path in root.glob('**/build/test-results/**/TEST-*.xml'):
        for case in ET.parse(path).getroot().iter('testcase'):
            state = next((tag for tag in ['failure', 'error', 'skipped']
                          if case.find(tag) is not None), 'passed')
            tests.append([str(path.relative_to(root)), case.get('classname'),
                          case.get('name'), state])
    return sorted(tests)


def benchmark_inventory(root):
    path = root / 'blue-bex-conformance/build/reports/jmh/results.json'
    if not path.exists():
        return []
    return sorted(json.dumps([row['benchmark'], row.get('params', {}), row['mode']],
                             sort_keys=True) for row in json.loads(path.read_text()))


def validate(rows, identity):
    assert len(rows) == 4 and {row['group'] for row in rows} == set(GROUPS), 'Missing/duplicate groups'
    result = {row['group']: row for row in rows}
    for row in rows:
        assert row['identity'] == identity, 'Wrong commit/run/attempt'
        assert row['exit_code'] == 0, f"Failed group: {row['group']}"
        assert row['commands'] == GROUPS[row['group']], 'Unexpected commands'
        assert all(isinstance(row[key], (int, float)) and math.isfinite(row[key])
                   and row[key] > 0 for key in ['started_at', 'finished_at', 'elapsed_s']), 'Invalid timing'
        assert math.isclose(row['finished_at'] - row['started_at'], row['elapsed_s'],
                            rel_tol=0, abs_tol=0.001), 'Inconsistent timing'
    baseline = result['baseline']
    assert baseline['tests'] and baseline['benchmarks'], 'Empty baseline coverage'
    assert all(case[-1] not in ['error', 'failure'] for case in baseline['tests']), 'Failed tests'
    for group in ['verification', 'four-forks']:
        assert result[group]['tests'] == baseline['tests'], 'Test coverage/outcomes differ'
    for group in ['benchmarks', 'four-forks']:
        assert result[group]['benchmarks'] == baseline['benchmarks'], 'JMH coverage differs'
    return result


def main():
    root = Path.cwd()
    mode = sys.argv[1]
    identity = {key: os.environ[key] for key in ['GITHUB_SHA', 'GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT']}
    if mode == 'summary':
        rows = [json.loads(path.read_text()) for path in Path(sys.argv[2]).glob('*/timing.json')]
        result = validate(rows, identity)
        base = result['baseline']['elapsed_s']
        parallel = max(result[g]['finished_at'] for g in ['verification', 'benchmarks']) - min(
            result[g]['started_at'] for g in ['verification', 'benchmarks'])
        text = ('# BEX validation timing (no publication)\n\n'
                f'Baseline commands: {base:.1f} s\n\n'
                f'Parallel command envelope: {parallel:.1f} s ({(1-parallel/base)*100:.1f}% reduction)\n\n'
                f"Four-fork baseline: {result['four-forks']['elapsed_s']:.1f} s\n\n"
                'All groups succeeded; JUnit inventory/outcomes and JMH case coverage match. '
                'The parallel envelope includes gaps between measured commands, but excludes '
                'checkout/tool setup and final artifact collection. Use Actions run duration for end-to-end time. '
                'This checks Build and validate, not the full release gate.\n')
        print(text)
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as output:
            output.write(text)
        return
    if mode not in GROUPS:
        raise ValueError('Unknown experiment group')
    actual_head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
    if actual_head != identity['GITHUB_SHA']:
        raise ValueError('Checkout HEAD differs from GITHUB_SHA')
    output = Path(os.environ['RUNNER_TEMP']) / 'bex-ci-timing-output'
    output.mkdir(exist_ok=True)
    started = time.time()
    code = 0
    with (output / 'build.log').open('w') as log:
        for tasks in GROUPS[mode]:
            command = ['./gradlew', '--no-daemon', '--no-build-cache', '--max-workers=4']
            if mode == 'four-forks':
                command += ['--init-script', '.github/scripts/ci-four-forks.init.gradle']
            command += tasks
            print('Running:', ' '.join(command), flush=True)
            process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                       text=True)
            for line in process.stdout:
                print(line, end='', flush=True)
                log.write(line)
            code = process.wait()
            if code:
                break
    finished = time.time()
    row = dict(group=mode, identity=identity, commands=GROUPS[mode], exit_code=code,
               started_at=started, finished_at=finished, elapsed_s=finished-started,
               tests=inventory(root), benchmarks=benchmark_inventory(root))
    (output / 'timing.json').write_text(json.dumps(row, indent=2))
    sys.exit(code)


if __name__ == '__main__':
    main()
