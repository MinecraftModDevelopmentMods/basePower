"""Native 1.17 offline audit controls; these tests never start Minecraft."""

from copy import deepcopy
import json
import unittest
import zipfile

import native117_broad_result_audit as audit
import native117_broad_audit_fixtures as fixtures


class Native117AuditRegistrationTests(unittest.TestCase):
    def test_native117_engine_has_an_explicit_adapter(self):
        self.assertEqual(("1.17.1", "37.1.1"), audit.VERSIONS.get("117"))


class Native117StrictAuditTests(unittest.TestCase):
    def setUp(self):
        self.fixture = fixtures.BroadResultAuditTests("test_complete_both_profiles_accepts_bounded_proof_not_manual_pass")
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)

    def result(self):
        return audit.audit(self.fixture.runtime, "117")

    def rejected(self, message):
        result = self.result()
        self.assertEqual("FAIL", result["status"], result)
        self.assertIn(message, " ".join(result["errors"]))

    def repin(self):
        fixtures.write(self.fixture.manifest, {"target117": self.fixture.pins})
        self.fixture.report["targetManifestSha256"] = audit.sha(self.fixture.manifest.read_bytes())
        self.fixture.save_top()

    def test_complete_native117_fixture_requires_both_profiles_and_all_six_logs(self):
        result = self.result()
        self.assertEqual("PASS", result["status"], result["errors"])
        for profile in result["profiles"].values():
            self.assertEqual({"first", "restarted"}, set(profile["native117LogArchives"]))
            self.assertEqual("NOT_AUTOMATED_BY_BROAD_SWEEP", profile["manualProcedures"])
            self.assertEqual(21, len(profile["checks"]["restarted"]["manualRequired"]))

    def test_missing_debug_cannot_hide_behind_clean_console(self):
        (self.fixture.profile() / "debug-restarted.log").unlink()
        self.rejected("debug-restarted.log")

    def test_missing_latest_cannot_hide_behind_clean_console(self):
        (self.fixture.profile() / "latest-first.log").unlink()
        self.rejected("latest-first.log")

    def test_debug_exception_cannot_hide_behind_clean_console(self):
        path = self.fixture.profile() / "debug-restarted.log"
        path.write_bytes(path.read_bytes() + b"java.lang.IllegalStateException: lost state\n")
        self.rejected("native117 runtime error")

    def test_qualified_java_lang_error_is_still_a_runtime_failure(self):
        path = self.fixture.profile() / "debug-restarted.log"
        path.write_bytes(path.read_bytes() + b"java.lang.Error: failed\n")
        self.rejected("native117 runtime error")

    def test_plain_debug_word_error_is_not_an_exception_type(self):
        path = self.fixture.profile() / "debug-first.log"
        path.write_bytes(path.read_bytes() + b"[main/DEBUG] [mixin/]: Error cleaning class output directory: .mixin.out\n")
        result = self.result()
        self.assertEqual("PASS", result["status"], result["errors"])

    def test_latest_fatal_cannot_hide_behind_clean_console(self):
        path = self.fixture.profile() / "latest-first.log"
        path.write_bytes(path.read_bytes() + b"[Server thread/FATAL] failure\n")
        self.rejected("fatal native runtime error")

    def test_debug_must_contain_the_actual_station_activity(self):
        path = self.fixture.profile() / "debug-first.log"
        path.write_bytes(path.read_bytes().replace(b"Started W-04 -", b"not started W-04 -"))
        self.rejected("station activity")

    def test_debug_restart_cannot_refill_or_rebuild(self):
        path = self.fixture.profile() / "debug-restarted.log"
        path.write_bytes(path.read_bytes() + b"Modified block data of 1, 2, 3\n")
        self.rejected("activity after cold restart")

    def test_debug_requires_native_mcp_and_all_dimension_completion(self):
        path = self.fixture.profile() / "debug-restarted.log"
        path.write_bytes(path.read_bytes().replace(b"with MCP 20210706.113038", b"unknown mapping identity"))
        self.rejected("MCP identity")

    def test_latest_requires_completed_all_dimension_shutdown(self):
        path = self.fixture.profile() / "latest-restarted.log"
        path.write_bytes(path.read_bytes().replace(b"ThreadedAnvilChunkStorage: All dimensions are saved", b"incomplete"))
        self.rejected("all-dimension shutdown")

    def test_native117_cannot_omit_the_engine_inventory(self):
        self.fixture.pins.pop("serverCode")
        self.repin()
        self.rejected("sealed native117 engine inventory")

    def test_native117_cannot_substitute_a_root_legacy_launcher(self):
        self.fixture.pins["launcher"] = "forge.jar"
        (self.fixture.profile() / "forge.jar").write_bytes(b"engine")
        self.repin()
        self.rejected("modular Forge launcher")

    def test_native117_inventory_requires_all_bootstrap_code(self):
        key = sorted(audit.NATIVE117_REQUIRED_CODE)[0]
        self.fixture.pins["serverCode"]["files"].pop(key)
        self.repin()
        self.rejected("omits required code")

    def test_native117_version_cannot_be_a_legacy_mod(self):
        self.fixture.pins["jars"][0]["version"] = "1.0.0.116051"
        self.repin()
        self.rejected("expected mod identities changed")

    def test_native117_staged_metadata_must_match_the_pinned_identity(self):
        pin = next(row for row in self.fixture.pins["jars"] if row["id"] == "poweradvantage")
        staged = self.fixture.profile() / "mods" / (pin["id"] + ".jar")
        with zipfile.ZipFile(staged, "w") as archive:
            archive.writestr("META-INF/mods.toml", '[[mods]]\nmodId="notpower"\nversion="1.0.0.117011"\n')
        pin["sha256"] = audit.sha(staged.read_bytes())
        receipt = self.fixture.profile() / "input-jars.json"
        rows = fixtures.load(receipt)
        next(row for row in rows if row["file"] == "poweradvantage.jar")["sha256"] = pin["sha256"]
        fixtures.write(receipt, rows)
        self.repin()
        self.rejected("native117 staged mod identity")


if __name__ == "__main__":
    unittest.main()
