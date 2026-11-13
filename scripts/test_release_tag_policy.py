"""Exercise the actual GitHub release tag parser and classification."""
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
                    "26.3-1.2.1-h.1;echo bad", "26.3-1.2.1
BAD",
                    "latest", "26.3-1.2.1-"):
            with self.subTest(tag=tag):
                with self.assertRaises(ValueError):
                    parse_tag(tag)


if __name__ == "__main__":
    unittest.main()
