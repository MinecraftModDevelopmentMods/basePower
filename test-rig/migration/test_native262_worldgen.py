import copy
from pathlib import Path
import tempfile
import unittest

import nbtlib

import native12111_worldgen as old
import native262_worldgen as worldgen
from test_native262_resources import level_fixture


class WorldgenAdapterTest(unittest.TestCase):
    def test_existing_assertion_bodies_and_templates_are_not_rewritten(self):
        self.assertIs(worldgen.validate_profile, old.validate_profile)
        self.assertIs(worldgen.validate_sample.__code__, old.validate_sample.__code__)
        self.assertIs(worldgen.validate_sample.__globals__["native"], worldgen.native)
        self.assertIsNot(old.validate_sample.__globals__["native"], worldgen.native)
        spec = worldgen.campaign_spec("advantage")
        source = worldgen.native.read_json(spec["template"])
        for key in ("worldgenExpectations", "firstProfileAssertions", "restartProfileAssertions"):
            self.assertEqual(source[key], spec[key])
        with self.assertRaisesRegex(AssertionError, "provider pin"):
            worldgen.campaign_spec("mineralogy")

    def test_all_six_jars_remain_pinned_and_extra_providers_are_not_smuggled_in(self):
        spec = worldgen.campaign_spec("advantage")
        mods = dict.fromkeys(worldgen.FAMILY, "synthetic-unit-pin")
        self.assertEqual(mods, worldgen.select_mods(mods, spec))
        for wrong in ({**mods, "mineralogy": "unapproved"}, {k: v for k, v in mods.items() if k != "basesteam"}):
            with self.assertRaises(AssertionError):
                worldgen.select_mods(wrong, spec)

    def test_separate_native_saved_data_is_lossless_and_no_flat_or_legacy_fallback(self):
        with tempfile.TemporaryDirectory() as directory:
            world, level = level_fixture(directory, worldgen.WORLD)
            settings_path = worldgen.layout.shared_file(world, "minecraft:world_gen_settings")
            settings_path.parent.mkdir(parents=True)
            settings = nbtlib.File({"DataVersion": nbtlib.Int(4903), "data": nbtlib.Compound(
                seed=nbtlib.Long(1234), unknown_retained=nbtlib.String("not-projected-away"), dimensions=nbtlib.Compound({
                    "minecraft:overworld": nbtlib.Compound(generator=nbtlib.Compound(type=nbtlib.String("minecraft:noise"),
                                                                                  settings=nbtlib.String("minecraft:overworld")))}))})
            settings.save(settings_path, gzipped=True)
            before = settings_path.read_bytes()
            actual = worldgen.normal_world(world)
            self.assertEqual(settings, actual)
            self.assertEqual(before, settings_path.read_bytes())
            self.assertEqual("not-projected-away", actual["data"]["unknown_retained"])
            for wrong in (nbtlib.Int(4671), nbtlib.String("4903")):
                changed = copy.deepcopy(settings)
                changed["DataVersion"] = wrong
                changed.save(settings_path, gzipped=True)
                with self.assertRaises(ValueError):
                    worldgen.normal_world(world)
            settings["data"]["dimensions"]["minecraft:overworld"]["generator"]["type"] = nbtlib.String("minecraft:flat")
            settings.save(settings_path, gzipped=True)
            with self.assertRaisesRegex(AssertionError, "normal terrain"):
                worldgen.normal_world(world)

    def test_native_restart_does_not_inherit_old_oil_exclusion_as_a_waiver(self):
        sample = dict(centers={"DESERT": "0,0"}, chunksScanned=1,
                      counts={"basepower:crude_oil": 5}, biomeViolations={"basepower:crude_oil": 0},
                      biomeBoundaryBlocks={"basepower:crude_oil": 0})
        spec = dict(name="worldgen-legacy-import", stableWorldgenCountExclusions=["basepower:crude_oil"])
        worldgen.validate_restart(sample, copy.deepcopy(sample), spec)
        changed = copy.deepcopy(sample)
        changed["counts"]["basepower:crude_oil"] += 1
        old.validate_restart(sample, changed, spec)
        with self.assertRaisesRegex(AssertionError, "no historical exclusion"):
            worldgen.validate_restart(sample, changed, spec)

    def test_stage_changes_only_disposable_normal_world_properties(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            settings = root / "server.properties"
            settings.write_text("level-type=minecraft:flat\ngenerator-settings=flat\nserver-ip=127.0.0.1\n")
            worldgen.configure_normal_world(root, worldgen.campaign_spec("advantage"), 26520)
            data = dict(line.split("=", 1) for line in settings.read_text().splitlines())
            self.assertEqual("minecraft:normal", data["level-type"])
            self.assertEqual("", data["generator-settings"])
            self.assertEqual("-1", data["pause-when-empty-seconds"])
            self.assertEqual("127.0.0.1", data["server-ip"])


if __name__ == "__main__":
    unittest.main()
