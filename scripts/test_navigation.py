"""Check local link behavior using small Markdown documents, not repository snapshots."""
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('check-navigation.py')


class NavigationCLI(unittest.TestCase):
    def test_missing_local_link_fails_but_code_examples_and_urls_are_ignored(self):
        with tempfile.TemporaryDirectory() as directory:
            page = Path(directory) / 'index.md'
            page.write_text('[missing](missing.md)\n[web](https://example.invalid)\n'
                            '```md\n[example](not-real.md)\n```\n')
            result = subprocess.run(['python3', str(SCRIPT), str(page)],
                                    text=True, capture_output=True)
            self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
            self.assertIn('missing.md', result.stdout)
            self.assertNotIn('not-real.md', result.stdout)

    def test_valid_relative_and_reference_links_with_spaces_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'guide page.md').write_text('# Guide')
            page = root / 'index.md'
            page.write_text('[guide](<guide page.md>)\n[ref]: guide%20page.md#title\n')
            result = subprocess.run(['python3', str(SCRIPT), str(page)],
                                    text=True, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('local destinations=2, broken=0', result.stdout)


if __name__ == '__main__':
    unittest.main()
