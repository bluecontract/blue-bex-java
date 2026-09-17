import json
import tarfile
import importlib.util
import unittest
import tempfile
import os
import subprocess
from unittest.mock import patch
from pathlib import Path

spec = importlib.util.spec_from_file_location('release_ci', Path(__file__).with_name('release-ci.py'))
ci = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ci)

class ReleaseCommands(unittest.TestCase):
    def test_single_production_graph_and_nonpublishing_verification(self):
        self.assertEqual(ci.release_tasks('verify'), ['bexReleaseVerify'])
        for mode in ['rc', 'stable']:
            self.assertEqual(ci.release_tasks(mode), ['bexReleaseVerify', 'publish', 'jreleaserFullRelease'])
        with self.assertRaises(ValueError):
            ci.release_tasks('unknown')

    def test_production_modes_require_their_exact_branch(self):
        ci.validate_mode('rc', 'refs/heads/next')
        ci.validate_mode('stable', 'refs/heads/main')
        ci.validate_mode('verify', 'refs/heads/codex/ci/bex-parallel-experiment')
        for mode in ['rc', 'stable']:
            with self.assertRaises(ValueError):
                ci.validate_mode(mode, 'refs/heads/codex/ci/bex-parallel-experiment')

    def test_prepared_identity_is_bound_to_run_attempt_mode_and_job_outputs(self):
        meta = dict(commit='a'*40, tree='b'*40, workflowCommit='c'*40,
                    run='123', attempt='1', mode='verify')
        expected = dict(meta)
        ci.validate_identity(meta, expected)
        for key in meta:
            bad = dict(meta, **{key: 'wrong'})
            with self.subTest(key=key), self.assertRaises(ValueError):
                ci.validate_identity(bad, expected)

class PreparedSource(unittest.TestCase):
    def test_real_untagged_bundle_roundtrip_and_wrong_run_rejected(self):
        previous = Path.cwd()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / 'source'
            source.mkdir()
            subprocess.run(['git', 'init', '-q', str(source)], check=True)
            subprocess.run(['git', '-C', str(source), 'config', 'user.email', 'test@example.invalid'], check=True)
            subprocess.run(['git', '-C', str(source), 'config', 'user.name', 'Fixture'], check=True)
            (source / '.cz.toml').write_text('version = "1.0.0-rc.1"\n')
            subprocess.run(['git', '-C', str(source), 'add', '.cz.toml'], check=True)
            subprocess.run(['git', '-C', str(source), 'commit', '-qm', 'fixture'], check=True)
            try:
                os.chdir(source)
                commit = ci.git('rev-parse', 'HEAD')
                tree = ci.git('rev-parse', 'HEAD^{tree}')
                env = dict(GITHUB_REF='refs/heads/codex/ci/bex-parallel-experiment',
                           GITHUB_SHA=commit, GITHUB_RUN_ID='123', GITHUB_RUN_ATTEMPT='1',
                           GITHUB_OUTPUT=str(root/'outputs'), PREPARED_COMMIT=commit, PREPARED_TREE=tree, PREPARED_ATTEMPT='1')
                with patch.dict(os.environ, env):
                    ci.prepare('verify', root / 'bundle')
                    self.assertEqual((root / 'outputs').read_text().splitlines(),
                                     ['commit=' + commit, 'tree=' + tree, 'attempt=1'])
                    restored = root / 'restored'
                    subprocess.run(['git', 'clone', '-q', str(source), str(restored)], check=True)
                    os.chdir(restored)
                    ident = ci.restore_source('verify', root / 'bundle')
                    self.assertEqual(ident['GITHUB_SHA'], commit)
                    self.assertEqual(ci.git('tag', '--points-at', 'HEAD'), '')
                    self.assertEqual(ci.git('status', '--porcelain'), '')
                    with patch.dict(os.environ, GITHUB_RUN_ATTEMPT='2'):
                        self.assertEqual(ci.restore_source('verify', root / 'bundle'), ident)
                    for bad in [dict(PREPARED_ATTEMPT='2'), dict(GITHUB_RUN_ID='456'),
                                dict(PREPARED_COMMIT='b'*40), dict(PREPARED_TREE='c'*40)]:
                        with patch.dict(os.environ, bad), self.assertRaisesRegex(ValueError, 'Prepared source identity'):
                            ci.restore_source('verify', root / 'bundle')
            finally:
                os.chdir(previous)


class PartialRerunInputs(unittest.TestCase):
    def test_mixed_execution_attempts_require_same_source_generation(self):
        ident = dict(GITHUB_SHA='a'*40, GITHUB_RUN_ID='123', GITHUB_RUN_ATTEMPT='1')
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            downloads = root / 'downloads'
            for index in range(1, 5):
                directory = downloads / ('bex-rc-input-1-' + str(index))
                directory.mkdir(parents=True)
                row = dict(identity=ident, execution_attempt='2' if index == 2 else '1',
                           group='independent-' + str(index), success=True,
                           started_at=100., finished_at=110., elapsed_s=10.,
                           commands=ci.v.INDEPENDENT, tests=[['case', 'passed']])
                receipt = directory / 'receipt.json'
                receipt.write_text(json.dumps(row))
                with tarfile.open(directory / 'inputs.tar.gz', 'w:gz') as archive:
                    archive.add(receipt, arcname='receipt.json')
            ci.v.restore(downloads, root / 'restored', ident)
            self.assertEqual(json.loads((root / 'restored/2/receipt.json').read_text())['execution_attempt'], '2')
            for key in ident:
                with self.subTest(key=key), self.assertRaises((ValueError, FileNotFoundError)):
                    ci.v.restore(downloads, root / ('bad-' + key), dict(ident, **{key: 'wrong'}))

if __name__ == '__main__':
    unittest.main()
