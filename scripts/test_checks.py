"""Behavior tests at the public check CLI, using external-tool fixtures."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[1]


class CheckCLI(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'scripts').mkdir()
        shutil.copy(SOURCE / 'scripts/check.sh', self.root / 'scripts/check.sh')
        (self.root / 'agent').mkdir()
        (self.root / 'cloud-api/contracttest').mkdir(parents=True)
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ['PATH'])
        self.env.pop('CLOUD_API_TEST_DATABASE_URL', None)

    def tool(self, name, body):
        path = self.bin / name
        path.write_text('#!/bin/bash\n' + body + '\n')
        path.chmod(0o755)

    def run_cli(self, *args):
        return subprocess.run(['bash', str(self.root / 'scripts/check.sh'), *args],
                              cwd='/', env=self.env, text=True, capture_output=True)

    def test_unknown_target_is_rejected_before_any_check_runs(self):
        self.tool('go', 'echo TOOL_EXECUTED')
        result = self.run_cli('agent', 'typo')
        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
        self.assertNotIn('TOOL_EXECUTED', result.stdout)

    def test_failure_does_not_hide_later_independent_contract_check(self):
        self.tool('go', "if [[ $1 == list ]]; then echo 'example/business|build'; elif [[ $PWD == */cloud-api && $1 == test ]]; then exit 7; fi; exit 0")
        result = self.run_cli('cloud', 'contract')
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn('FAIL cloud:', result.stdout)
        self.assertIn('PASS contract:', result.stdout)

    def test_explicit_database_check_requires_environment_without_calling_go(self):
        self.tool('go', 'echo TOOL_EXECUTED')
        result = self.run_cli('db')
        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
        self.assertIn('MISSING_ENV db:', result.stdout)
        self.assertNotIn('TOOL_EXECUTED', result.stdout)

    def test_database_skip_is_a_failure_even_when_go_exits_zero(self):
        self.env['CLOUD_API_TEST_DATABASE_URL'] = 'fixture-secret-not-for-output'
        self.tool('go', """printf '%s\\n' '{"Action":"pass","Test":"TestCompleted"}' '{"Action":"skip","Test":"TestCloudAgentSessionAuthorization"}'""")
        result = self.run_cli('db')
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn('FAIL db:', result.stdout)
        self.assertNotIn('fixture-secret-not-for-output', result.stdout + result.stderr)

    def test_database_download_diagnostics_on_stderr_do_not_corrupt_json_report(self):
        self.env['CLOUD_API_TEST_DATABASE_URL'] = 'fixture-secret-not-for-output'
        self.tool('go', """echo 'go: downloading example.test/dependency v1.0.0' >&2
printf '%s\\n' '{"Action":"pass","Test":"TestCloudAgentSessionAuthorization"}'""")
        result = self.run_cli('db')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('PASS db:', result.stdout)
        self.assertIn('go: downloading example.test/dependency v1.0.0', result.stderr)
        self.assertNotIn('fixture-secret-not-for-output', result.stdout + result.stderr)

    def test_database_go_failure_cannot_pass_with_completed_json_test(self):
        self.env['CLOUD_API_TEST_DATABASE_URL'] = 'fixture-secret-not-for-output'
        self.tool('go', """echo 'go: build failed' >&2
printf '%s\\n' '{"Action":"pass","Test":"TestCloudAgentSessionAuthorization"}'
exit 7""")
        result = self.run_cli('db')
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn('FAIL db:', result.stdout)
        self.assertIn('go: build failed', result.stderr)

    def test_database_malformed_stdout_json_cannot_pass_with_completed_test(self):
        self.env['CLOUD_API_TEST_DATABASE_URL'] = 'fixture-secret-not-for-output'
        self.tool('go', """printf '%s\\n' 'broken-json' '{"Action":"pass","Test":"TestCloudAgentSessionAuthorization"}'""")
        result = self.run_cli('db')
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn('FAIL db:', result.stdout)
        self.assertIn('broken-json', result.stdout)

    def test_official_client_missing_dependency_is_not_a_pass(self):
        self.tool('go', 'echo TOOL_EXECUTED')
        result = self.run_cli('officialast', 'manual')
        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
        self.assertIn('MISSING_ENV officialast:', result.stdout)
        self.assertIn('SKIP manual:', result.stdout)
        self.assertNotIn('TOOL_EXECUTED', result.stdout)

    def test_frontend_without_installed_packages_is_missing_environment(self):
        self.tool('npm', 'echo TOOL_EXECUTED')
        result = self.run_cli('web')
        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
        self.assertIn('MISSING_ENV web:', result.stdout)
        self.assertNotIn('TOOL_EXECUTED', result.stdout)

    def test_cloud_excludes_npm_dependency_go_packages_but_checks_own_packages(self):
        self.tool('go', """if [[ $1 == list ]]; then
  printf '%s\\n' 'github.com/dngmeng/cloud-api/internal/http|build' 'github.com/dngmeng/cloud-api/integration|' 'github.com/dngmeng/cloud-api/admin-web/node_modules/flatted/golang/pkg/flatted|build'
else
  [[ $* != *node_modules* && $* == *internal/http* ]] || exit 9
  if [[ $1 == build ]]; then [[ $* != *integration* ]] || exit 10
  else [[ $* == *integration* ]] || exit 11; fi
  echo CLOUD_BUSINESS_PACKAGES_VERIFIED
fi""")
        result = self.run_cli('cloud')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('PASS cloud:', result.stdout)
        self.assertIn('CLOUD_BUSINESS_PACKAGES_VERIFIED', result.stdout)

    def test_unwritable_database_report_location_cannot_pass(self):
        self.env['CLOUD_API_TEST_DATABASE_URL'] = 'fixture-secret-not-for-output'
        self.env['TMPDIR'] = str(self.root / 'missing-directory')
        self.tool('go', 'exit 0')
        result = self.run_cli('db')
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn('FAIL db:', result.stdout)


if __name__ == '__main__':
    unittest.main()
