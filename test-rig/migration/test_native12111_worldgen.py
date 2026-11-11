import copy
import json
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch

import native12111_worldgen as worldgen


class WorldgenAdapterTest(unittest.TestCase):
    def setUp(self):
        self.spec = worldgen.campaign_spec("advantage")
        self.mods = {key: ("fixture.121111", Path(key + ".jar"), "A" * 64)
                     for key in self.spec["requiredMods"] | {"basemetals"}}

    def sample(self, spec=None, mods=None):
        spec, mods = spec or self.spec, mods or self.mods
        counts = dict.fromkeys(worldgen.BLOCKS, 0)
        counts.update(spec["worldgenExpectations"]["minimumCounts"])
        return dict(minecraft=worldgen.native.MINECRAFT, forge=worldgen.native.FORGE,
                    station="worldgen", kind="sample", mods={key: value[0] for key, value in mods.items()},
                    radius=spec["worldgenSampleRadius"], chunksScanned=12,
                    centers={"spawn": "0,0", "DESERT": "16,-16", "OCEAN": "-20,20"},
                    counts=counts, biomeViolations=dict.fromkeys(worldgen.OILS, 0),
                    biomeBoundaryBlocks=dict.fromkeys(worldgen.OILS, 0),
                    chunkLoading=dict(protocol="native_FULL_off_thread_future_poll_v1", maximumInFlight=4,
                                      peakInFlight=2, requests=30, releasedTickets=30, activeTickets=0,
                                      dispatcherClosed=True, executorShutdown=True))

    def test_all_existing_campaign_oracles_are_retained(self):
        for name in worldgen.CAMPAIGNS:
            spec = worldgen.campaign_spec(name)
            original = worldgen.native.read_json(spec["template"])
            for key in ("firstProfileAssertions", "restartProfileAssertions", "worldgenExpectations",
                        "profileMutations", "firstFileAssertions", "restartFileAssertions",
                        "stableWorldgenCountExclusions"):
                self.assertEqual(original.get(key, []), spec[key], (name, key))
        legacy = worldgen.campaign_spec("legacy-import")
        self.assertEqual(2, len(legacy["configFixtures"]))
        self.assertTrue(all(source.is_file() and "PA12111" in source.parts for source, _ in legacy["configFixtures"]))

    def test_mod_selection_keeps_all_frozen_family_jars_without_adding_other_providers(self):
        mods = {**self.mods, "mineralogy": ("extra.121111", Path("extra.jar"), "B" * 64)}
        self.assertEqual(self.mods, worldgen.select_mods(mods, self.spec))
        with self.assertRaisesRegex(ValueError, "lacks worldgen inputs"):
            worldgen.select_mods({key: value for key, value in mods.items() if key != "orespawn"}, self.spec)

    def test_staging_explicitly_removes_flat_properties_before_first_boot(self):
        with tempfile.TemporaryDirectory() as directory:
            runtime = Path(directory)
            properties = runtime / "server.properties"
            properties.write_text("level-type=minecraft:flat\ngenerator-settings=flat-data\n"
                                  "generate-structures=false\nserver-ip=127.0.0.1\n", encoding="utf-8")
            worldgen.configure_normal_world(runtime, self.spec, 26480)
            lines = dict(line.split("=", 1) for line in properties.read_text().splitlines())
            self.assertEqual("minecraft:normal", lines["level-type"])
            self.assertEqual("", lines["generator-settings"])
            self.assertEqual("true", lines["generate-structures"])
            self.assertEqual("-1", lines["pause-when-empty-seconds"])
            self.assertEqual("127.0.0.1", lines["server-ip"])
            self.assertEqual(worldgen.WORLD, lines["level-name"])
            with self.assertRaisesRegex(AssertionError, "Unsafe"):
                worldgen.configure_normal_world(runtime, {**self.spec, "levelSeed": "bad\nserver-ip=0.0.0.0"}, 26480)

    def test_valid_native_sample_is_read_without_mutation(self):
        sample = self.sample()
        before = copy.deepcopy(sample)
        worldgen.validate_sample(sample, self.spec, self.mods)
        self.assertEqual(before, sample)

    def test_sampler_and_command_failures_abort_waits_without_waiting_for_the_full_deadline(self):
        for line in ("Worldgen sample failed", "Advantage Works command failed",
                     "Server empty for 60 seconds, pausing"):
            self.assertTrue(worldgen.WorldgenServer.unexpected_line(line))
        self.assertFalse(worldgen.WorldgenServer.unexpected_line("Worldgen sample wrote 12 chunks"))

    def test_missing_wrong_typed_negative_and_missing_deposit_counts_fail_closed(self):
        for change in (lambda row: row.pop("counts"), lambda row: row["counts"].pop("mineralogy:crude_oil"),
                       lambda row: row["counts"].update({"basepower:crude_oil": 0}),
                       lambda row: row["counts"].update({"basepower:crude_oil": True}),
                       lambda row: row["counts"].update({"basepower:crude_oil": -1})):
            row = self.sample()
            change(row)
            with self.assertRaises(AssertionError):
                worldgen.validate_sample(row, self.spec, self.mods)

    def test_wrong_native_receipt_or_unfound_desert_cannot_qualify(self):
        for key, value in (("minecraft", "1.21.1"), ("forge", "52.1.0"), ("kind", "sample-failed"),
                           ("radius", 1), ("chunksScanned", 0)):
            sample = self.sample()
            sample[key] = value
            with self.assertRaises(AssertionError):
                worldgen.validate_sample(sample, self.spec, self.mods)
        sample = self.sample()
        sample["centers"]["DESERT"] = "NOT_FOUND"
        with self.assertRaisesRegex(AssertionError, "biome was not found"):
            worldgen.validate_sample(sample, self.spec, self.mods)
        sample = self.sample()
        sample["mods"]["basepower"] = "another-version"
        with self.assertRaisesRegex(AssertionError, "mod versions"):
            worldgen.validate_sample(sample, self.spec, self.mods)

    def test_oil_biome_violation_is_not_silenced_as_a_boundary_block(self):
        sample = self.sample()
        sample["biomeBoundaryBlocks"]["basepower:crude_oil"] = 3
        worldgen.validate_sample(sample, self.spec, self.mods)
        sample["biomeViolations"]["basepower:crude_oil"] = 1
        with self.assertRaisesRegex(AssertionError, "outside"):
            worldgen.validate_sample(sample, self.spec, self.mods)

    def test_incomplete_ticket_custody_and_unbounded_dispatch_fail(self):
        for key, value in (("protocol", "blocking"), ("maximumInFlight", 5), ("peakInFlight", 5),
                           ("requests", 0), ("releasedTickets", 29), ("activeTickets", 1),
                           ("dispatcherClosed", False), ("executorShutdown", False)):
            sample = self.sample()
            sample["chunkLoading"][key] = value
            with self.assertRaisesRegex(AssertionError, "bounded tickets"):
                worldgen.validate_sample(sample, self.spec, self.mods)

    def test_restart_preserves_coverage_and_counts_with_only_historical_legacy_oil_exception(self):
        first, restarted = self.sample(), self.sample()
        worldgen.validate_restart(first, restarted, self.spec)
        restarted["counts"]["baseelectric:li_ore"] += 1
        with self.assertRaisesRegex(AssertionError, "measurements changed"):
            worldgen.validate_restart(first, restarted, self.spec)
        restarted = self.sample()
        restarted["centers"]["DESERT"] = "100,100"
        with self.assertRaisesRegex(AssertionError, "coverage moved"):
            worldgen.validate_restart(first, restarted, self.spec)
        legacy = worldgen.campaign_spec("legacy-import")
        restarted = self.sample()
        restarted["counts"]["basepower:crude_oil"] += 1
        worldgen.validate_restart(first, restarted, legacy)
        with self.assertRaisesRegex(AssertionError, "Unreviewed"):
            worldgen.validate_restart(first, first, {**self.spec, "stableWorldgenCountExclusions": ["baseelectric:li_ore"]})

    def test_native_stopped_save_must_prove_normal_terrain(self):
        data = dict(DataVersion=4671, LevelName=worldgen.WORLD, WorldGenSettings={
            "seed": 1234, "dimensions": {"minecraft:overworld": {"generator": {
                "type": "minecraft:noise", "settings": "minecraft:overworld"}}}})
        before = copy.deepcopy(data)
        self.assertEqual(data["WorldGenSettings"], worldgen.normal_world(data))
        self.assertEqual(before, data)
        data["WorldGenSettings"]["dimensions"]["minecraft:overworld"]["generator"]["type"] = "minecraft:flat"
        with self.assertRaisesRegex(AssertionError, "actual normal terrain"):
            worldgen.normal_world(data)

    def test_profile_bool_types_presence_and_schema_remain_strict(self):
        assertion = dict(id="enabled", section="ores", entry="baseelectric:ore/sulfur", field="enabled",
                         present=True, expectedBoolean=True)
        document = dict(schema_version=7, ores={"baseelectric:ore/sulfur": {"enabled": True}})
        self.assertEqual("PASS", worldgen.validate_profile(document, [assertion])[0]["status"])
        for value in (1, "true", False, None):
            document["ores"]["baseelectric:ore/sulfur"]["enabled"] = value
            with self.assertRaises(AssertionError):
                worldgen.validate_profile(document, [assertion])
        document["schema_version"] = 6
        with self.assertRaisesRegex(AssertionError, "schema 7"):
            worldgen.validate_profile(document, [])

    def test_explicit_null_entry_does_not_count_as_an_absent_duplicate_rule(self):
        assertion = dict(id="no-duplicate", section="ores", entry="modern", present=False)
        document = dict(schema_version=7, ores={})
        worldgen.validate_profile(document, [assertion])
        document["ores"]["modern"] = None
        with self.assertRaisesRegex(AssertionError, "presence"):
            worldgen.validate_profile(document, [assertion])

    def test_provider_hierarchy_matches_native_electric_defaults(self):
        for providers, sulfur, lithium in ((set(), True, True), ({"mineralogy"}, False, True),
                                           ({"baseminerals"}, False, False), ({"mineralogy", "baseminerals"}, False, False)):
            document = dict(fluid_deposits={
                "basepower:fluid_deposit/desert_crude_oil": {"source_provider": "basepower"},
                "mineralogy:fluid_deposit/crude_oil": {"source_provider": "mineralogy"}}, ores={
                "baseelectric:ore/sulfur": {"source_provider": "baseelectric", "enabled": sulfur},
                "baseelectric:ore/lithium": {"source_provider": "baseelectric", "enabled": lithium}})
            worldgen.validate_initial_hierarchy(document, providers)
            document["ores"]["baseelectric:ore/sulfur"]["enabled"] = not sulfur
            with self.assertRaisesRegex(AssertionError, "hierarchy"):
                worldgen.validate_initial_hierarchy(document, providers)

    def test_user_profile_mutations_do_not_modify_other_fields_or_original_document(self):
        before = dict(ores={"ore": {"enabled": True, "dimensions": {"kept": 7}}}, unrelated={"keep": [1, 2]})
        expected = copy.deepcopy(before)
        expected["ores"]["ore"]["enabled"] = False
        changed = worldgen.mutated_profile(before, [dict(section="ores", entry="ore", field="enabled", booleanValue=False)])
        self.assertEqual(expected, changed)
        self.assertTrue(before["ores"]["ore"]["enabled"])
        with self.assertRaises(AssertionError):
            worldgen.mutated_profile(before, [dict(section="ores", entry="ore", field="absent", booleanValue=False)])

    def test_import_file_witnesses_reject_missing_wrong_and_escaping_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            runtime = Path(directory)
            row = dict(id="import", path="report.json", contains="os1_config_source", minimumOccurrences=2)
            with self.assertRaisesRegex(AssertionError, "Missing"):
                worldgen.check_files(runtime, [row])
            (runtime / "report.json").write_text("os1_config_source\nos1_config_source\n")
            worldgen.check_files(runtime, [row])
            with self.assertRaisesRegex(AssertionError, "Incomplete"):
                worldgen.check_files(runtime, [{**row, "minimumOccurrences": 3}])
            with self.assertRaisesRegex(AssertionError, "escaped"):
                worldgen.check_files(runtime, [{**row, "path": "../outside.json"}])

    def test_mineralogy_campaign_refuses_unapproved_public_artifact(self):
        with self.assertRaisesRegex(AssertionError, "separately pinned"):
            worldgen.mineralogy_input({}, None)
        with self.assertRaisesRegex(AssertionError, "approved public artifact"):
            worldgen.mineralogy_input({"mineralogy": ("6.1.4.121111", Path("mineralogy.jar"), "0" * 64)}, None)

    def test_mineralogy_freeze_keeps_every_family_pin_and_separates_profile_and_jar_hashes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            jar = root / "Mineralogy-6.1.4.121111.jar"
            jar.write_bytes(b"public jar fixture")
            sha = worldgen.digest(jar)
            profile = root / "family.json"
            family = [{"id": key, "version": value[0], "path": str(value[1]), "sha256": value[2]}
                      for key, value in self.mods.items()]
            profile.write_text(json.dumps(dict(mods=family)))
            before = profile.read_bytes()
            runtime = root / "new-evidence"
            runtime.mkdir()
            mods = {**self.mods, "mineralogy": (worldgen.MINERALOGY["version"], jar, sha)}
            with patch.dict(worldgen.MINERALOGY, sha256=sha):
                frozen = worldgen.freeze_mineralogy_profile(runtime, profile, worldgen.digest(profile), mods)
            document = json.loads(Path(frozen["path"]).read_text())
            self.assertEqual(family, [row for row in document["mods"] if row["id"] != "mineralogy"])
            self.assertEqual(before, profile.read_bytes())
            self.assertEqual(sha, frozen["jar"]["sha256"])
            self.assertEqual(worldgen.digest(Path(frozen["path"])), frozen["sha256"])
            self.assertNotEqual(frozen["sha256"], frozen["jar"]["sha256"])
            self.assertEqual(worldgen.MINERALOGY["sourceRef"], document["mineralogySourceRef"])
            self.assertEqual(jar.read_bytes(), Path(frozen["jar"]["path"]).read_bytes())

    def test_preflight_does_not_create_runtime_or_launch_any_server(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ("profile.json", "inventory.json", "java"):
                (root / name).write_text("fixture")
            mods = {}
            for key, (version, _, sha) in self.mods.items():
                jar = root / (key + ".jar")
                jar.write_bytes(b"fixture")
                mods[key] = version, jar, sha
            seed = root / "seed"
            seed.mkdir()
            args = SimpleNamespace(port=26480, sample_timeout=1800, profile=root / "profile.json", profile_sha256="A" * 64,
                                   inventory=root / "inventory.json", inventory_sha256="B" * 64, seed=seed,
                                   java=root / "java", output=root / "new-output", campaign="advantage",
                                   preflight_only=True, mineralogy=None)
            with (patch.object(worldgen.native, "validate_hash"),
                  patch.object(worldgen.native, "read_family_target", return_value=(seed, {}, mods)),
                  patch.object(worldgen.native, "java21", return_value=(args.java, "Java 21")),
                  patch.object(worldgen, "output_path", return_value=args.output),
                  patch.object(worldgen.native, "stage") as stage, patch.object(worldgen.native, "Server") as server):
                result = worldgen.run(args)
                self.assertEqual("READY_NOT_RUN", result["status"])
                self.assertEqual(sorted(mods), result["selectedMods"])
                stage.assert_not_called()
                server.assert_not_called()
                self.assertFalse(args.output.exists())


if __name__ == "__main__":
    unittest.main()
