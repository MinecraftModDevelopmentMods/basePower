"""Target declarations and API boundaries; no builds, servers or source-world writes."""
import json
from pathlib import Path
import unittest

RIG = Path(__file__).resolve().parents[1]
VERSIONS = {"basepower": ("basePower", "2.4.4.110021"),
            "basesteam": ("baseSteam", "2.2.6.110021"), "baseelectric": ("baseElectric", "2.2.6.110021")}


class RebrandTargetContractTest(unittest.TestCase):
    def test_all_eleven_current_profiles_are_explicitly_unqualified_new_targets(self):
        profiles = list((RIG / "profiles").glob("*.json"))
        self.assertEqual(11, len(profiles))
        for path in profiles:
            doc = json.loads(path.read_text())
            self.assertEqual("UNQUALIFIED_REBRAND_WORKSPACE_INPUTS", doc["qualificationStatus"])
            for mod in doc["mods"]:
                self.assertNotIn(mod["id"], ("poweradvantage", "steamadvantage", "electricadvantage"))
                if mod["id"] in VERSIONS:
                    stem, version = VERSIONS[mod["id"]]
                    self.assertEqual(version, mod["version"])
                    suffix = "-deobf.jar" if doc["mode"] == "development" else ".jar"
                    self.assertEqual(stem + "-" + version + suffix, mod["file"])
                    self.assertRegex(mod.get("sha256", ""), r"^[A-F0-9]{64}$")
                    if doc["mode"] == "packaged":
                        expected = {"basepower": "164693E756A935A8C51085CF95AECCCAF3506390A6F0A3888E46765F29896F31",
                                    "basesteam": "728E37A1A0551596D8167976A7022E31EF075A383640BB385470160DD9A8F07D",
                                    "baseelectric": "05F1CF02C97C6112C7EB4913DB46F65C45058CC6E818D81E159DBF5AD0F93DD1"}
                        self.assertEqual(expected[mod["id"]], mod["sha256"])
                if mod["id"] == "advantageworkstest":
                    self.assertEqual("1.1.0.110021", mod["version"])
                    if doc["mode"] == "development":
                        self.assertTrue(mod["path"].startswith("build/gradle/libs-dev"))
                    else:
                        self.assertEqual("521AC1FF85FCC8D449D84E10888CB888B5E9B01455D1DB05F31839648652D525",mod["sha256"])
                        self.assertIn("/survival-candidate-06/artifacts/" + mod["sha256"] + "/", mod["path"])
                        self.assertTrue(mod["path"].endswith("/" + mod["file"]))

    def test_current_targets_keep_the_legacy_public_api_and_use_new_implementation_packages(self):
        roots = (RIG / "src", RIG / "client-resources/java")
        seen_api = False
        for root in roots:
            for path in root.rglob("*.java"):
                source = path.read_text()
                seen_api |= "cyano.poweradvantage.api" in source
                self.assertNotIn("cyano.basepower.api", source)
                for old in ("poweradvantage", "steamadvantage", "electricadvantage"):
                    self.assertNotIn("zone.moddev.mc." + old, source)
                    self.assertNotIn("com.mcmoddev." + old, source)
        self.assertTrue(seen_api)
        build = (RIG / "build.gradle").read_text()
        self.assertIn("../build/libs-dev/basePower-2.4.4.110021-deobf.jar", build)

    def test_java_profile_runner_cannot_treat_unpinned_rebrand_bytes_as_qualified(self):
        source = (RIG / "src/runner/java/zone/moddev/mc/advantageworks/runner/WorksRunner.java").read_text()
        self.assertIn('input.id.matches("base(?:power|steam|electric)")', source)
        self.assertIn("requires a fresh exact SHA-256 pin", source)
        self.assertIn('manifest.put("qualificationStatus", profile.qualificationStatus)', source)

    def test_harness_metadata_depends_only_on_current_target_ids(self):
        harness = json.loads((RIG / "src/main/resources/mcmod.info").read_text())
        client = json.loads((RIG / "client-resources/resources/mcmod.info").read_text())
        self.assertEqual(["basepower"], harness[0]["dependencies"])
        self.assertEqual(["basepower", "basesteam", "baseelectric"], client[0]["dependencies"])


if __name__ == "__main__":
    unittest.main()
