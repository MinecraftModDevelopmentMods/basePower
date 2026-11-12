import unittest

import verify


class ReleaseTargetTests(unittest.TestCase):
    def test_released_minecraft_lines_keep_their_runtime(self):
        for java, versions in {
            8: ("1.10.2", "1.12.2", "1.13.2", "1.14.4", "1.15.2", "1.16.5"),
            16: ("1.17.1",),
            17: ("1.18.2", "1.19.4", "1.20.1"),
            21: ("1.20.6", "1.21.1", "1.21.11"),
            25: ("26.1.2",),
        }.items():
            for version in versions:
                with self.subTest(version=version):
                    self.assertEqual((version, f"Java {java}"), verify.release_target(f"master-{version}"))

    def test_unqualified_or_malformed_targets_are_rejected(self):
        for branch in ("master-26.2", "master-26.1.2-extra", "feature/26.1.2", "26.1.2", "master-main", "master-26.1.2\n"):
            with self.subTest(branch=branch), self.assertRaises(ValueError):
                verify.release_target(branch)


if __name__ == "__main__":
    unittest.main()
