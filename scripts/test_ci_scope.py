"""Exercise the changed-path CLI used by every CI workflow."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("ci-scope.py")


class ScopeTests(unittest.TestCase):
    def scope(self, *paths):
        result = subprocess.run(
            [sys.executable, str(SCRIPT)], input="\0".join(paths).encode(),
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True,
        )
        return json.loads(result.stdout)

    def test_docs_and_archived_code_only_check_navigation(self):
        self.assertEqual(self.scope("docs/testing/README.md", "archive/old/main.go"), {
            "targets": ["navigation"], "db": False, "android": False, "motion": False,
        })

    def test_provider_changes_check_both_sides_and_android_consumers(self):
        for path in ("agent/internal/server/server.go", "cloud-api/internal/http/router.go",
                     "cloud-api/contracttest/session_test.go", "migrations/001.sql"):
            with self.subTest(path=path):
                self.assertEqual(self.scope(path), {
                    "targets": ["agent", "officialast", "cloud", "contract", "admin-web", "web", "navigation"],
                    "db": True, "android": True, "motion": True,
                })

    def test_consumers_and_download_tools_select_only_related_checks(self):
        cases = [
            (("cloud-api/admin-web/src/App.tsx",), ["admin-web", "navigation"], False),
            (("web/src/main.ts",), ["web", "navigation"], False),
            (("site/download/releases.json", "scripts/prepare-download-release.py"),
             ["download", "navigation", "tooling"], False),
            (("android/app/src/main/Example.kt",), ["navigation"], True),
            ((".pi/skills/a/SKILL.md",), ["navigation"], False),
        ]
        for paths, targets, android in cases:
            with self.subTest(paths=paths):
                self.assertEqual(self.scope(*paths), {
                    "targets": targets, "db": False, "android": android, "motion": android,
                })

    def test_mixed_changes_union_checks_and_handle_spaces_and_deleted_paths(self):
        self.assertEqual(self.scope("android/deleted file.kt", "site/download/index.html", "README.md"), {
            "targets": ["download", "navigation"], "db": False, "android": True, "motion": True,
        })

    def test_check_infrastructure_and_unknown_source_fail_open_to_all_checks(self):
        for path in ("scripts/check.sh", "scripts/ci-scope.py", "Makefile",
                     ".github/workflows/change-scope.yml", "new-component/main.go"):
            with self.subTest(path=path):
                self.assertEqual(self.scope(path), {
                    "targets": ["agent", "officialast", "cloud", "contract", "admin-web", "web", "download", "navigation", "tooling"],
                    "db": True, "android": True, "motion": True,
                })

    def test_manual_or_unknown_base_can_request_all_checks(self):
        result = subprocess.run([sys.executable, str(SCRIPT), "--all"], check=True, capture_output=True)
        self.assertEqual(json.loads(result.stdout), self.scope("scripts/check.sh"))

    def test_real_workflow_diff_includes_old_provider_path_when_archived(self):
        # Execute the workflow's shell step with a real Git rename, not a mock path list.
        workflow = SCRIPT.parents[1] / ".github/workflows/change-scope.yml"
        shell = workflow.read_text().split("        run: |\n", 1)[1]
        shell = "\n".join(line[10:] for line in shell.splitlines())
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "scripts").mkdir()
            shutil.copy(SCRIPT, root / "scripts/ci-scope.py")
            def git(*args):
                return subprocess.check_output(["git", *args], cwd=root, text=True).strip()
            git("init", "-q")
            git("config", "user.name", "CI scope test")
            git("config", "user.email", "ci@example.test")
            (root / "agent").mkdir()
            (root / "agent/main.go").write_text("package main\n")
            git("add", ".")
            git("commit", "-qm", "provider")
            base = git("rev-parse", "HEAD")
            (root / "archive").mkdir()
            git("mv", "agent/main.go", "archive/main.go")
            git("commit", "-qm", "archive provider")
            env = dict(os.environ, EVENT_NAME="pull_request", BASE_SHA=base,
                       HEAD_SHA=git("rev-parse", "HEAD"), RUNNER_TEMP=directory,
                       GITHUB_OUTPUT=str(root / "output"))
            subprocess.run(["bash", "-c", shell], cwd=root, env=env, check=True, capture_output=True)
            selected = json.loads((root / "output").read_text().removeprefix("scope="))
            self.assertEqual(selected, self.scope("agent/main.go"))

    def test_every_workflow_reaches_shared_selection_without_outer_path_filter(self):
        workflows = SCRIPT.parents[1] / ".github/workflows"
        for name, condition in (("android-ci.yml", ".android"),
                                ("android-motion-regression.yml", ".motion"),
                                ("product-checks.yml", ".targets")):
            with self.subTest(workflow=name):
                source = (workflows / name).read_text()
                trigger = source.split("on:\n", 1)[1].split("\npermissions:", 1)[0]
                self.assertNotRegex(trigger, r"(?m)^\s+paths(?:-ignore)?:")
                self.assertIn("pull_request:", trigger)
                self.assertIn("workflow_dispatch:", trigger)
                self.assertIn("uses: ./.github/workflows/change-scope.yml", source)
                self.assertIn("needs: changes", source)
                self.assertIn("fromJSON(needs.changes.outputs.scope)" + condition, source)


if __name__ == "__main__":
    unittest.main()
