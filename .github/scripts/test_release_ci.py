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
            self.assertEqual(ci.release_tasks(mode), ['bexReleaseVerify', 'publish', 'jreleaserDeploy', '-PbexSeparateMavenWait=true'])
        with self.assertRaises(ValueError):
            ci.release_tasks('unknown')

    def test_metadata_excludes_named_deployer_after_proof_validation(self):
        for mode, ref in [('rc', 'refs/heads/next'), ('stable', 'refs/heads/main')]:
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as tmp:
                env = dict(GITHUB_REF=ref, RUNNER_TEMP=tmp)
                with patch.dict(os.environ, env), patch.object(ci.sys, 'argv', ['release-ci.py', mode, 'metadata']), \
                     patch.object(ci, 'validate_metadata_proof') as proof, patch.object(ci, 'git', return_value='123'), \
                     patch.object(ci.v, 'gradle') as gradle:
                    ci.main()
                    proof.assert_called_once()
                    self.assertEqual(gradle.call_args.args[2], [
                        'jreleaserFullRelease', '--exclude-deployer-name=sonatype', '-PbexReleaseMetadataOnly=true'])

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

class ReleaseWorkflowConfiguration(unittest.TestCase):
    def test_branch_is_explicit_in_the_executing_reusable_job(self):
        workflow = Path(__file__).parents[1] / 'workflows/release-verification.yml'
        final = workflow.read_text().split('  final:\n', 1)[1].split('    steps:\n', 1)[0]
        self.assertIn("JRELEASER_BRANCH: ${{ inputs.mode == 'rc' && 'next' || 'main' }}", final)
        for branch in ['next', 'main']:
            with patch.dict(os.environ, JRELEASER_BRANCH=branch), patch.object(ci.v, 'run') as run:
                ci.v.gradle(Path('/source'), Path('/home'), ['jreleaserConfig'], Path('/log'))
                self.assertEqual(run.call_args.args[2]['JRELEASER_BRANCH'], branch)

    def test_release_chore_bypasses_the_real_rc_concurrency_group(self):
        workflow = (Path(__file__).parents[1] / 'workflows/release-rc.yml').read_text()
        self.assertIn("${{ startsWith(github.event.head_commit.message, 'chore: release ') && format('bex-release-rc-skip-{0}', github.run_id) || 'bex-release-rc' }}", workflow)
        self.assertIn('cancel-in-progress: false', workflow)
        self.assertIn("startsWith(github.event.head_commit.message, 'chore: release ') == false", workflow)


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


class MetadataProof(unittest.TestCase):
    def test_metadata_requires_same_verified_source_and_published_artifacts(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp)
            (source / '.cz.toml').write_text('version = "1.0.0-rc.1"\n')
            report = source / 'build/reports/bex-release/final.json'
            report.parent.mkdir(parents=True)
            report.write_text(json.dumps(dict(releaseReady=True, blockers=[], bexCommit='a'*40)))
            props = source / 'build/jreleaser/maven-central-submitted.properties'
            props.parent.mkdir(parents=True)
            props.write_text('deployMavenCentralSonatypeDeploymentId=28570f16-da32-4c14-bd2e-c1acc0782365\n')
            staged = source / 'build/staging-deploy/x.jar'
            staged.parent.mkdir(parents=True)
            staged.write_bytes(b'verified artifact')
            env = dict(GITHUB_REF='refs/heads/next', GITHUB_SHA='c'*40, GITHUB_RUN_ID='123',
                       GITHUB_RUN_ATTEMPT='2', PREPARED_COMMIT='a'*40, PREPARED_TREE='b'*40,
                       PREPARED_ATTEMPT='1')
            ident = dict(GITHUB_SHA='a'*40, GITHUB_RUN_ID='123', GITHUB_RUN_ATTEMPT='1')
            git_results = {('rev-parse','HEAD'):'a'*40, ('rev-parse','HEAD^{tree}'):'b'*40,
                           ('status','--porcelain'):'', ('tag','--points-at','HEAD'):'v1.0.0-rc.1'}
            proof = source / 'proof.json'
            published = source / 'published.json'
            with patch.dict(os.environ, env), patch.object(ci, 'git', side_effect=lambda *args: git_results[args]):
                ci.write_deployment_proof(source, ident, 'rc', proof)
                with self.assertRaises(FileNotFoundError): ci.validate_metadata_proof(source, 'rc', proof, published)
                published.write_text(json.dumps(dict(deploymentId='28570f16-da32-4c14-bd2e-c1acc0782365',
                    deploymentState='PUBLISHED', propertiesSha256=ci.sha256(props))))
                ci.validate_metadata_proof(source, 'rc', proof, published)
                for changes in [dict(GITHUB_RUN_ID='other'), dict(GITHUB_RUN_ATTEMPT='3'),
                                dict(PREPARED_ATTEMPT='2'), dict(PREPARED_TREE='d'*40), dict(GITHUB_SHA='d'*40)]:
                    with self.subTest(changes=changes), patch.dict(os.environ, changes), self.assertRaises(ValueError):
                        ci.validate_metadata_proof(source, 'rc', proof, published)
                for file in [report, props, staged, published]:
                    original=file.read_bytes()
                    file.write_bytes(b'{}' if file.suffix == '.json' else b'changed')
                    with self.subTest(file=file), self.assertRaises(ValueError):
                        ci.validate_metadata_proof(source, 'rc', proof, published)
                    file.write_bytes(original)
                git_results[('status','--porcelain')]=' M build.gradle.kts'
                with self.assertRaises(ValueError): ci.validate_metadata_proof(source, 'rc', proof, published)

if __name__ == '__main__':
    unittest.main()
