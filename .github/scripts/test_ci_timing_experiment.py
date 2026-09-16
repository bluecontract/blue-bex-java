import copy
import importlib.util
import json
import tempfile
from unittest.mock import patch
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('timing', Path(__file__).with_name('ci-timing-experiment.py'))
timing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(timing)

class Receipts(unittest.TestCase):
    def setUp(self):
        self.identity = {'GITHUB_SHA': 'a' * 40, 'GITHUB_RUN_ID': '123', 'GITHUB_RUN_ATTEMPT': '1'}
        self.rows = [dict(group=g, identity=self.identity.copy(), commands=copy.deepcopy(commands),
                          exit_code=0, started_at=100.0, finished_at=110.0, elapsed_s=10.0, tests=[['module/TEST-X.xml', 'X', 'test', 'passed']],
                          benchmarks=['benchmark-case']) for g, commands in timing.GROUPS.items()]

    def test_complete(self):
        self.assertEqual(len(timing.validate(self.rows, self.identity)), 4)

    def test_reject_invalid_timing(self):
        for key, value in [('elapsed_s', 0), ('elapsed_s', -1), ('elapsed_s', float('nan')),
                           ('elapsed_s', float('inf')), ('elapsed_s', 9),
                           ('started_at', -1), ('finished_at', 99),
                           ('finished_at', float('inf'))]:
            with self.subTest(key=key, value=value):
                changed = copy.deepcopy(self.rows)
                changed[0][key] = value
                with self.assertRaises(AssertionError):
                    timing.validate(changed, self.identity)

    def test_reject_wrong_checkout_before_gradle(self):
        with patch.dict(timing.os.environ, self.identity), \
                patch.object(timing.sys, 'argv', ['timing', 'baseline']), \
                patch.object(timing.subprocess, 'check_output', return_value='b' * 40), \
                patch.object(timing.subprocess, 'Popen') as process:
            with self.assertRaisesRegex(ValueError, 'Checkout HEAD'):
                timing.main()
            process.assert_not_called()

    def test_summary_download_layout(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for row in self.rows:
                artifact = root / ('bex-timing-' + row['group'])
                artifact.mkdir()
                (artifact / 'timing.json').write_text(json.dumps(row))
            summary = root / 'summary.md'
            with patch.dict(timing.os.environ, dict(self.identity, GITHUB_STEP_SUMMARY=str(summary))), \
                    patch.object(timing.sys, 'argv', ['timing', 'summary', str(root)]), \
                    patch('builtins.print'):
                timing.main()
            self.assertIn('All groups succeeded', summary.read_text())

    def test_missing(self):
        with self.assertRaises(AssertionError):
            timing.validate(self.rows[:-1], self.identity)

    def test_reject_mutations(self):
        for index, key, value in [(0, 'exit_code', 1), (0, 'tests', []),
                                   (1, 'tests', []), (2, 'benchmarks', []),
                                   (3, 'tests', []), (3, 'commands', []),
                                   (1, 'identity', {'GITHUB_SHA': 'b' * 40}),
                                   (1, 'group', 'baseline')]:
            with self.subTest(key=key, index=index):
                changed = copy.deepcopy(self.rows)
                changed[index][key] = value
                with self.assertRaises(AssertionError):
                    timing.validate(changed, self.identity)

if __name__ == '__main__':
    unittest.main()
