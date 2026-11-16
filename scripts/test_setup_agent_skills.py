"""Exercise skill installation through the CLI with an independent local Git repo."""
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("setup-agent-skills.py")


class SkillSetupTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.project = self.base / "project with spaces"
        (self.project / "scripts").mkdir(parents=True)
        shutil.copyfile(SCRIPT, self.project / "scripts" / SCRIPT.name)
        self.upstream = self.base / "upstream"
        self.upstream.mkdir()
        self.git("init", "-q")
        skill = self.upstream / "skills/engineering/example/SKILL.md"
        skill.parent.mkdir(parents=True)
        skill.write_text("---\nname: example\n---\nExample skill\n")
        self.git("add", ".")
        self.git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                 "commit", "-qm", "Fixture skill")
        revision = self.git("rev-parse", "HEAD").stdout.strip()
        links = self.project / ".pi/skills"
        links.mkdir(parents=True)
        self.link = links / "mattpocock-engineering-example"
        self.link.symlink_to("../git/github.com/mattpocock/skills/skills/engineering/example")
        self.cache = self.project / ".pi/git/github.com/mattpocock/skills"
        (self.project / ".pi/skill-sources.json").write_text(json.dumps({
            "repository": str(self.upstream), "revision": revision,
            "destination": ".pi/git/github.com/mattpocock/skills",
        }))

    def git(self, *args):
        return subprocess.run(["git", "-C", str(self.upstream), *args],
                              text=True, capture_output=True, check=True)

    def cli(self, *args):
        return subprocess.run([sys.executable, str(self.project / "scripts" / SCRIPT.name), *args],
                              cwd=self.base, text=True, capture_output=True)

    def test_install_makes_skills_discoverable_from_another_directory(self):
        result = self.cli()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Example skill", (self.link / "SKILL.md").read_text())
        self.assertEqual(self.cli("--check").returncode, 0)

    def test_check_reports_missing_cache_without_installing(self):
        result = self.cli("--check")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("cache missing", result.stderr)
        self.assertFalse(self.cache.exists())

    def test_repeated_install_preserves_existing_local_changes(self):
        self.assertEqual(self.cli().returncode, 0)
        skill = self.link / "SKILL.md"
        skill.write_text("Local work to preserve\n")
        result = self.cli()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("local changes", result.stderr)
        self.assertEqual(skill.read_text(), "Local work to preserve\n")

    def test_revision_unavailable_at_source_leaves_no_cache(self):
        config_path = self.project / ".pi/skill-sources.json"
        config = json.loads(config_path.read_text())
        config["revision"] = "1" * 40
        config_path.write_text(json.dumps(config))
        result = self.cli()
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.cache.exists())
        self.assertEqual(list(self.cache.parent.glob(".skills-install-*")), [])


if __name__ == "__main__":
    unittest.main()
