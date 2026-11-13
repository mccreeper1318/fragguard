"""Exercise the actual GitHub release tag parser and classification."""
import os
from pathlib import Path
import subprocess
import unittest
from release_tag_policy import parse_tag


class ReleaseTagPolicyTest(unittest.TestCase):
    def test_stable_production_releases(self):
        self.assertEqual(("26.3-1.2.1", "26.3-1.2.1", False),
                         parse_tag("26.3-1.2.1"))
        self.assertEqual(("26.3-1.2.1", "26.3-1.2.1-h.1", False),
                         parse_tag("26.3-1.2.1-h.1"))
        self.assertEqual(("26.3-1.2.1", "26.3-1.2.1-h.20", False),
                         parse_tag("v26.3-1.2.1-h.20"))
        self.assertEqual(("27.1-3.4.5", "27.1-3.4.5-h.10", False),
                         parse_tag("27.1-3.4.5-h.10"))

    def test_betas_and_release_candidates_remain_prereleases(self):
        for tag in ("26.3-1.2.1-beta.1", "26.3-1.2.1-rc.2",
                    "v26.3-1.2.1-alpha.10", "26.3-1.2.1-beta.1.2"):
            with self.subTest(tag=tag):
                self.assertTrue(parse_tag(tag)[2])

    def test_invalid_hotfix_and_untrusted_versions_rejected(self):
        for tag in ("26.3-1.2.1-h.0", "26.3-1.2.1-h.01",
                    "26.3-1.2.1-h", "26.3-1.2.1-h.bad",
                    "26.3-1.2.1-h.1.beta", "26.3-1.2.1-h.-1",
                    "26.3-1.2.1-h.1;echo bad", "26.3-1.2.1\nBAD",
                    "latest", "26.3-1.2.1-"):
            with self.subTest(tag=tag):
                with self.assertRaises(ValueError):
                    parse_tag(tag)


    def test_actual_release_workflow_bash_regexes(self):
        # Execute the real Bash conditions embedded in release.yml. Python
        # tag-parser tests alone cannot catch over-escaped Bash YAML regexes.
        workflow = (Path(__file__).resolve().parent.parent
                    / ".github/workflows/release.yml").read_text(encoding="utf-8")
        self.assertEqual(1, workflow.count("name: Build Release Package"))
        self.assertEqual(1, workflow.count("      - name: Upload release files"))

        def matches(variable, prefix, value):
            condition = next(
                line.strip() for line in workflow.splitlines()
                if line.strip().startswith(prefix)
            )
            script = condition + "\n echo yes\nelse\n echo no\nfi\n"
            env = dict(os.environ)
            env[variable] = value
            result = subprocess.run(
                ["bash", "-e", "-c", script], env=env, capture_output=True,
                text=True, check=True,
            )
            return result.stdout.strip() == "yes"

        legacy = 'if [[ "$VERSION" =~ ^('
        hotfix = 'if [[ "$VERSION" =~ ^[0-9]+'
        source = 'if [[ "$PROJECT_VERSION" =~ ^('
        latest = 'if [[ "$RELEASE_TAG" =~ -h'

        for value in ("26.3-1.2.1", "26.3-1.2.1-h.1",
                      "26.3-1.2.1-beta.1"):
            self.assertTrue(matches("VERSION", legacy, value), value)
        self.assertFalse(matches("VERSION", legacy, "untrusted;command"))
        for value in ("26.3-1.2.1-h.1", "26.3-1.2.1-h.12"):
            self.assertTrue(matches("VERSION", hotfix, value), value)
            self.assertTrue(matches("PROJECT_VERSION", source, value), value)
            self.assertTrue(matches("RELEASE_TAG", latest, value), value)
        for value in ("26.3-1.2.1", "26.3-1.2.1-rc.1",
                      "26.3-1.2.1-h.0", "26.3-1.2.1-h.01"):
            self.assertFalse(matches("VERSION", hotfix, value), value)
            self.assertFalse(matches("RELEASE_TAG", latest, value), value)
        self.assertFalse(matches("PROJECT_VERSION", source, "26.3-1.2.1-beta.1"))


if __name__ == "__main__":
    unittest.main()
