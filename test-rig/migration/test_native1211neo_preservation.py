"""Mutation controls for reviewed native adaptations, not new oracle exemptions."""

from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import historical_test_inputs as historical
import native1211neo_preservation as preservation


class ActiveAdapterPreservation(unittest.TestCase):
    def accepted(self, relative):
        return historical.git_blob(historical.ACCEPTED_FORGE1211, relative)

    def native(self, relative):
        return preservation.translate_adapter(relative, self.accepted(relative))

    def test_anchor_is_the_accepted_forge_tree_and_only_four_java_tests_are_translated(self):
        self.assertEqual("8d9937b3c0c9ef28a231b90fc2ff6adfea1af733", historical.ACCEPTED_FORGE1211)
        self.assertEqual({historical.JAVA_TESTS + suffix for suffix in (
            "ProfileManifestTest.java", "WorldgenProtocolContractTest.java",
            "runner/Native114ProfileTest.java", "runner/Native115ProfileTest.java")},
            preservation.REVIEWED_TRANSLATIONS.keys())
        self.assertEqual(10, sum(map(len, preservation.REVIEWED_TRANSLATIONS.values())))

    def test_all_active_adapters_round_trip_to_the_exact_accepted_input(self):
        for relative in sorted(historical.ACTIVE_ADAPTERS):
            with self.subTest(path=relative):
                accepted = self.accepted(relative)
                native = preservation.translate_adapter(relative, accepted)
                preservation.require_active_adapter(relative, native)
                self.assertEqual(preservation.normalized(accepted),
                                 preservation.translate_adapter(relative, native, reverse=True))
                preservation.require_active_adapter(relative, native.replace(b"\n", b"\r\n"))

    def test_current_active_files_match_only_the_reviewed_native_translations(self):
        for relative in sorted(historical.ACTIVE_ADAPTERS):
            with self.subTest(path=relative):
                preservation.require_active_adapter(relative, (historical.RIG / relative).read_bytes())

    def test_missing_duplicate_mixed_or_wrong_path_translations_are_rejected(self):
        for relative, pairs in preservation.REVIEWED_TRANSLATIONS.items():
            accepted, native = self.accepted(relative), self.native(relative)
            for old, new in pairs:
                for changed in (native.replace(new, b"unreviewed.expression()", 1),
                                native + b"\n" + new, native + b"\n" + old):
                    with self.subTest(path=relative, expression=new, direction="reverse"):
                        with self.assertRaises(AssertionError):
                            preservation.translate_adapter(relative, changed, reverse=True)
                for changed in (accepted.replace(old, b"unreviewed.expression()", 1),
                                accepted + b"\n" + old, accepted + b"\n" + new):
                    with self.subTest(path=relative, expression=old, direction="forward"):
                        with self.assertRaises(AssertionError):
                            preservation.translate_adapter(relative, changed)

    def test_translation_does_not_authorize_any_other_file_edit(self):
        for relative in sorted(historical.ACTIVE_ADAPTERS):
            with self.subTest(path=relative), self.assertRaises(AssertionError):
                preservation.require_active_adapter(relative, self.native(relative) + b"// unrelated edit\n")
        with self.assertRaises(ValueError):
            preservation.translate_adapter("migration/native117_readiness.py", b"protected oracle")

    def test_profile_counts_failure_guards_and_recipe_mode_coverage_cannot_be_weakened(self):
        mutations = (
            ("ProfileManifestTest.java", b"assertEquals(10, profiles.length)", b"assertEquals(9, profiles.length)"),
            ("ProfileManifestTest.java", b'assertTrue(recipeModes.contains("APOCALYPTIC"))',
             b'assertTrue(recipeModes.contains("NORMAL"))'),
            ("runner/Native114ProfileTest.java", b"assertEquals(10, ported)", b"assertEquals(9, ported)"),
            ("runner/Native115ProfileTest.java", b'contains("SHA-256 pin")', b'contains("")'),
            ("runner/Native115ProfileTest.java", b'fail("Historical 1.14.4 profile must not launch native115")',
             b'System.out.println("Historical 1.14.4 profile must not launch native115")'),
        )
        for suffix, before, after in mutations:
            relative = historical.JAVA_TESTS + suffix
            native = self.native(relative)
            self.assertEqual(1, native.count(before))
            changed = native.replace(before, after, 1)
            # These edits survive the path-only inverse, so the full baseline
            # comparison, not just recognition of helper calls, must reject them.
            self.assertNotEqual(preservation.normalized(self.accepted(relative)),
                                preservation.translate_adapter(relative, changed, reverse=True))
            with self.subTest(path=relative, mutation=after), self.assertRaises(AssertionError):
                preservation.require_active_adapter(relative, changed)

    def test_worldgen_post_tick_and_completion_before_save_checks_cannot_be_weakened(self):
        relative = historical.JAVA_TESTS + "WorldgenProtocolContractTest.java"
        native = self.native(relative)
        for before, after in ((b"ServerTickEvent.Post", b"ServerTickEvent.Pre"),
                              (b"wait >= 0 && wait < save", b"wait >= 0"),
                              (b'assertFalse("The command must not synchronously populate the halo",',
                               b'assertTrue("The command must not synchronously populate the halo",')):
            self.assertEqual(1, native.count(before))
            with self.subTest(mutation=after), self.assertRaises(AssertionError):
                preservation.require_active_adapter(relative, native.replace(before, after, 1))

    def test_readme_does_not_inherit_old_acceptance_for_a_rebuilt_candidate(self):
        source = self.native("README.md")
        before = b"Earlier results do not qualify a rebuilt jar."
        self.assertEqual(1, source.count(before))
        with self.assertRaises(AssertionError):
            preservation.require_active_adapter("README.md", source.replace(
                before, b"Earlier results qualify a rebuilt jar.", 1))

    def test_historical_oracle_lookup_still_uses_the_original_commits(self):
        for relative in preservation.REVIEWED_TRANSLATIONS:
            with self.subTest(path=relative):
                self.assertEqual(historical.git_blob(historical.POWER116, relative),
                                 historical.oracle_bytes(historical.POWER116, relative))
        protected = "migration/native117_readiness.py"
        self.assertNotIn(protected, historical.ACTIVE_ADAPTERS)
        with tempfile.TemporaryDirectory() as folder:
            rig = Path(folder)
            path = rig / protected
            path.parent.mkdir()
            path.write_bytes(b"unapproved oracle mutation")
            with patch.object(historical, "RIG", rig), patch.object(historical, "git_blob") as archived:
                self.assertEqual(b"unapproved oracle mutation", historical.oracle_bytes(historical.POWER116, protected))
            archived.assert_not_called()


class NativeReadinessTickPreservation(unittest.TestCase):
    def setUp(self):
        root = historical.RIG / historical.JAVA
        self.source = (root / "Native117Readiness.java").read_text(encoding="utf-8")
        self.bridge = (root / "WorksLevelTick.java").read_text(encoding="utf-8")

    def test_native_readiness_uses_post_level_tick_in_the_requested_world(self):
        preservation.require_native_readiness_tick(self.source, self.bridge)

    def test_pre_tick_wrong_world_missing_or_duplicate_subscription_is_not_native_readiness(self):
        mutations = (
            ("WorksLevelTick.Phase.END", "WorksLevelTick.Phase.START"),
            ("event.level != world", "event.level == world"),
            ("WorksLevelTick.listen(NeoForge.EVENT_BUS, probe::tick);", ""),
            ("WorksLevelTick.listen(NeoForge.EVENT_BUS, probe::tick);",
             "WorksLevelTick.listen(NeoForge.EVENT_BUS, probe::tick);" * 2),
        )
        for before, after in mutations:
            self.assertEqual(1, self.source.count(before))
            with self.subTest(mutation=after), self.assertRaises(AssertionError):
                preservation.require_native_readiness_tick(self.source.replace(before, after, 1), self.bridge)

    def test_native_event_bridge_cannot_swap_phases_or_add_another_callback(self):
        mutations = (
            ("LevelTickEvent.Post event", "LevelTickEvent.Pre event"),
            ("event.getLevel(), Phase.END", "event.getLevel(), Phase.START"),
            ("event.getLevel(), Phase.START", "event.getLevel(), Phase.END"),
            ("bus.addListener((LevelTickEvent.Post event)",
             "bus.addListener((LevelTickEvent.Post event)" * 2),
        )
        for before, after in mutations:
            self.assertEqual(1, self.bridge.count(before))
            with self.subTest(mutation=after), self.assertRaises(AssertionError):
                preservation.require_native_readiness_tick(self.source, self.bridge.replace(before, after, 1))


if __name__ == "__main__":
    unittest.main()
