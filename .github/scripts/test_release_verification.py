import copy
import importlib.util
import json
import tempfile
from unittest.mock import patch
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('rc', Path(__file__).with_name('release-verification.py'))
rc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rc)

class ExpectedFailure(unittest.TestCase):
    def setUp(self):
        self.commit = 'a' * 40
        self.tag = 'v1.2.3-rc.4'
        self.report = {'schema': 'blue-bex-strict-release/2.0', 'dependencyPolicy': 'published-only',
                       'bexCommit': self.commit, 'releaseReady': False,
                       'sourceState': {'clean': True, 'expectedTag': self.tag,
                                       'exactReleaseTag': False, 'tagsAtHead': []},
                       'modernizationStatus': 'passed', 'conformanceReleaseStatus': 'passed',
                       'publishedLanguageStatus': 'passed', 'independentCleanBuildStatus': 'passed',
                       'publishedRepeatabilityStatus': 'passed',
                       'blockers': ['HEAD is not tagged exactly ' + self.tag]}
        self.log = ('> Task :generateBexReleaseReport FAILED\n'
                    "Execution failed for task ':generateBexReleaseReport'.\n"
                    '> BEX public release remains fail-closed; see /tmp/final.json\n'
                    'BUILD FAILED in 8m\n')

    def test_accept_only_expected_tag_failure(self):
        rc.validate_final(self.report, 1, self.log, self.commit, self.tag)

    def test_accept_gradle_96_registration_annotation(self):
        log = self.log.replace(
            "Execution failed for task ':generateBexReleaseReport'.",
            "Execution failed for task ':generateBexReleaseReport' "
            "(registered by plugin 'blue.bex.root-orchestration').")
        rc.validate_final(self.report, 1, log, self.commit, self.tag)
        with self.assertRaisesRegex(ValueError, 'Missing expected final failure evidence'):
            rc.validate_final(self.report, 1, log.replace('blue.bex.root-orchestration',
                                                        'unrelated.plugin'),
                              self.commit, self.tag)

    def test_reject_report_mutations(self):
        for key, value in [('blockers', []), ('blockers', self.report['blockers'] + ['tests failed']),
                           ('releaseReady', True), ('bexCommit', 'b' * 40),
                           ('modernizationStatus', 'failed'), ('publishedLanguageStatus', 'failed'),
                           ('independentCleanBuildStatus', 'not-executed')]:
            with self.subTest(key=key):
                report = copy.deepcopy(self.report)
                report[key] = value
                with self.assertRaises(ValueError):
                    rc.validate_final(report, 1, self.log, self.commit, self.tag)

    def test_reject_other_task_failure(self):
        for log in [self.log + '> Task :test FAILED\n', '',
                    self.log.replace('generateBexReleaseReport', 'test')]:
            with self.subTest(log=log), self.assertRaises(ValueError):
                rc.validate_final(self.report, 1, log, self.commit, self.tag)

    def test_reject_wrong_exit_or_dirty_source(self):
        with self.assertRaises(ValueError):
            rc.validate_final(self.report, 0, self.log, self.commit, self.tag)
        self.report['sourceState']['clean'] = False
        with self.assertRaises(ValueError):
            rc.validate_final(self.report, 1, self.log, self.commit, self.tag)

class Transport(unittest.TestCase):
    def test_stale_or_failed_receipt_rejected(self):
        ident = {'GITHUB_SHA': 'a' * 40, 'GITHUB_RUN_ID': '1', 'GITHUB_RUN_ATTEMPT': '1'}
        receipt = dict(identity=ident, group='independent-1', success=True,
                       started_at=100., finished_at=110., elapsed_s=10.)
        rc.validate_receipt(receipt, ident, 'independent-1')
        for key, value in [('identity', {}), ('group', 'independent-2'), ('success', False),
                           ('elapsed_s', float('nan')), ('elapsed_s', 0), ('finished_at', 111.)]:
            bad = dict(receipt, **{key: value})
            with self.subTest(key=key), self.assertRaises(ValueError):
                rc.validate_receipt(bad, ident, 'independent-1')

    def test_actual_required_inputs_survive_transport(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            original = root / 'original'
            files = ['checkout/.git/HEAD', 'checkout/build.gradle.kts',
                     'checkout/blue-bex-core/build/libs/core.jar',
                     'checkout/build/distributions/bex-source-release.zip',
                     'checkout/blue-bex-conformance/build/reports/bex-conformance/report.json',
                     'checkout/blue-bex-core/build/test-results/test/TEST-X.xml',
                     'gradle-home/caches/modules-2/files-2.1/blue.language/core/1/sha/core.jar',
                     'manifest.sha256', 'receipt.json']
            for name in files:
                path = original / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(name)
            unrelated = original / 'gradle-home/credentials.properties'
            unrelated.write_text('not transported')
            archive = root / 'inputs.tar.gz'
            with patch.object(rc.subprocess, 'check_output', return_value=b'build.gradle.kts\0'):
                rc.package(original, archive)
            with rc.tarfile.open(archive) as packed:
                restored = root / 'restored'
                packed.extractall(restored, filter='data')
            for name in files:
                self.assertEqual((restored / name).read_bytes(), (original / name).read_bytes())
            self.assertFalse((restored / 'gradle-home/credentials.properties').exists())

    def test_restore_requires_all_four_artifacts(self):
        with tempfile.TemporaryDirectory() as tmp, self.assertRaises(FileNotFoundError):
            rc.restore(Path(tmp) / 'absent', Path(tmp) / 'restored', {'GITHUB_RUN_ATTEMPT': '1'})

if __name__ == '__main__':
    unittest.main()
