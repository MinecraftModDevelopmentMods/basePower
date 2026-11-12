import copy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from matrix import digest
from native117_oil import construction_commands
import native262_oil as oil
from test_native262_resources import level_fixture


def in_world_fixture(root):
    world, _ = level_fixture(root, "unit-oil-world")
    record = dict(file="unit-oil-world/level.dat", sha256=digest(world / "level.dat"))

    def pixels(name, broken=False):
        a, b = root / (name + "-actual.rgba"), root / (name + "-reference.rgba")
        a.write_bytes(bytes((1, 2, 3, 255)) * 4)
        b.write_bytes(bytes((10 if broken else 1, 2, 3, 255)) * 4)
        return dict(width=2, height=2, actualRgba=dict(file=a.name, sha256=digest(a)),
                    referenceRgba=dict(file=b.name, sha256=digest(b)))

    phases = []
    for index, phase in enumerate(("initial", "after-resource-reload")):
        rows = []
        for number, (name, flowing) in enumerate((name, flowing) for name in oil.OILS for flowing in (False, True)):
            sprite = "basepower:blocks/" + name + ("_flow" if flowing else "_still")
            rows.append(dict(oil=name, flowing=flowing, worldBlock="basepower:" + name, modelLayer="TRANSLUCENT",
                             missingModel=False, modelSprite=sprite, atlasSprite=sprite,
                             modelLookup="IClientFluidTypeExtensions.getModel", modelSpriteIdentity=number + 1 + index * 100,
                             atlasSpriteIdentity=number + 1 + index * 100, modelIdentity=number + 10 + index * 100,
                             vertices=4, pos=[number, 80, 0], worldTime=100, pixels=pixels(phase + str(number))))
        phases.append(dict(phase=phase, atlasGeneration=index, reloadCompleted=bool(index), cases=rows))
    return dict(protocol="NATIVE65_IN_WORLD_OIL_ATLAS_V1", minecraft="26.2", forge="65.1.0",
                profileSha256="A" * 64, worldLoaded=True, dimension="minecraft:overworld", syntheticWorld=False,
                stoppedLevel=record, phases=phases,
                negativeControls={key: pixels(key, True) for key in ("missing-model", "wrong-sprite", "stale-atlas")})


class OilAdapterTest(unittest.TestCase):
    def test_only_the_four_power_lanes_are_defined_without_a_new_mineralogy_pin(self):
        definition = oil.fixtures({"jars": []})
        self.assertEqual(4, len(definition))
        self.assertEqual({"basepower:crude_oil", "basepower:refined_oil"}, {row["fluid"] for row in definition})
        self.assertEqual(oil.native.LAUNCHER, oil.OilRun.expected_launcher)

    def test_missing_tank_or_wrong_identity_cannot_be_defaulted_to_empty(self):
        definition = oil.fixtures({"jars": []})
        original = dict(minecraft="26.2", forge="65.1.0", station="lifecycle", worldTime=0, cases={
            lane["id"]: [dict(pos=pos, loaded=True, nbt="{Tank:{Empty:1b}}") for pos in lane["witnesses"]]
            for lane in definition})
        self.assertIs(original, oil.validate_snapshot(original, definition))
        for text in ("{}", '{Tank:{FluidName:"minecraft:water",Amount:1000}}',
                     '{Tank:{FluidName:"basepower:crude_oil",Amount:-1}}', '{Tank:{Empty:1}}',
                     '{Tank:{Empty:0b}}', '{Tank:{Empty:1b,Amount:0}}'):
            wrong = copy.deepcopy(original)
            lane = definition[0]
            next(row for row in wrong["cases"][lane["id"]] if row["pos"] == lane["drain"])["nbt"] = text
            with self.assertRaises((AssertionError, KeyError)):
                oil.validate_snapshot(wrong, definition)
        wrong = copy.deepcopy(original)
        wrong["minecraft"] = "1.21.11"
        with self.assertRaises(AssertionError):
            oil.validate_snapshot(wrong, definition)

    def test_filled_oil_requires_native_int_amount_and_exact_fluid_payload(self):
        definition = oil.fixtures({"jars": []})
        original = dict(minecraft="26.2", forge="65.1.0", station="lifecycle", worldTime=1, cases={
            lane["id"]: [dict(pos=pos, loaded=True, nbt='{Tank:{Amount:1000,FluidName:"' + lane["fluid"] + '"}}')
                         for pos in lane["witnesses"]] for lane in definition})
        self.assertIs(original, oil.validate_snapshot(original, definition))
        for amount in ("1000s", "1000L", "1000.0f", "1000.0d", '"1000"'):
            wrong = copy.deepcopy(original)
            row = wrong["cases"][definition[0]["id"]][1]
            row["nbt"] = row["nbt"].replace("Amount:1000", "Amount:" + amount)
            with self.assertRaises(AssertionError):
                oil.validate_snapshot(wrong, definition)

    def test_only_exact_first_preconstruction_load_accepts_loaded_air(self):
        definition = oil.fixtures({"jars": []})
        original = dict(minecraft="26.2", forge="65.1.0", station="lifecycle", worldTime=1, cases={
            lane["id"]: [dict(pos=pos, loaded=True, block="minecraft:air", state="Block{minecraft:air}")
                         for pos in lane["witnesses"]] for lane in definition})
        runner = object.__new__(oil.OilRun)
        runner.definition = definition
        runner.boots = ["oil-first-fill"]
        runner.pending_construction = construction_commands(definition)
        runner.construction_sealed = False
        with patch.object(oil.NativeOilRun, "probe", return_value=original):
            self.assertIs(original, runner.probe("load", "oil-loaded"))
            for operation, label in (("snapshot", "oil-loaded"), ("load", "oil-restart-loaded")):
                with self.assertRaises((AssertionError, KeyError)):
                    runner.probe(operation, label)
            runner.pending_construction.pop(0)
            with self.assertRaises((AssertionError, KeyError)):
                runner.probe("load", "oil-loaded")
        runner.pending_construction = construction_commands(definition)
        for change in (lambda row: row.update(loaded=False), lambda row: row.update(block="basepower:fluid_drain"),
                       lambda row: row.update(state="Block{minecraft:stone}"), lambda row: row.update(nbt="{}")):
            wrong = copy.deepcopy(original)
            change(wrong["cases"][definition[0]["id"]][0])
            with patch.object(oil.NativeOilRun, "probe", return_value=wrong):
                with self.assertRaises((AssertionError, KeyError)):
                    runner.probe("load", "oil-loaded")

    def test_worldless_oil_gauges_cannot_claim_actual_world_rendering(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises((AssertionError, KeyError)):
                oil.validate_in_world_render(dict(tank_gauge_pixel_status="PASS", fluid_rendering=[]), Path(directory), "A" * 64)

    def test_actual_world_schema_requires_exact_native_model_and_reload_controls(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            original = in_world_fixture(root)
            before = copy.deepcopy(original)
            result = oil.validate_in_world_render(original, root, "A" * 64)
            self.assertEqual(8, result["cases"])
            self.assertEqual(before, original)
            changes = (
                lambda doc: doc.update(worldLoaded=False),
                lambda doc: doc.update(syntheticWorld=True),
                lambda doc: doc.update(profileSha256="B" * 64),
                lambda doc: doc["phases"][1].update(reloadCompleted=False),
                lambda doc: doc["phases"][1].update(atlasGeneration=0),
                lambda doc: doc["phases"][1]["cases"][0].update(modelIdentity=10),
                lambda doc: doc["phases"][1]["cases"][0].update(modelSpriteIdentity=1, atlasSpriteIdentity=1),
                lambda doc: doc["phases"][0]["cases"][0].update(modelLayer="SOLID"),
                lambda doc: doc["phases"][0]["cases"][0].update(missingModel=True),
                lambda doc: doc["phases"][0]["cases"][0].update(modelSprite="minecraft:block/water_still"),
                lambda doc: doc["phases"][0]["cases"][0].update(atlasSpriteIdentity=999),
                lambda doc: doc["negativeControls"].pop("stale-atlas"),
                lambda doc: doc["phases"][1]["cases"].pop(),
            )
            for change in changes:
                wrong = copy.deepcopy(original)
                change(wrong)
                with self.assertRaises((AssertionError, KeyError)):
                    oil.validate_in_world_render(wrong, root, "A" * 64)

    def test_pixel_mismatch_and_passing_broken_control_are_rejected_from_raw_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            document = in_world_fixture(root)
            row = document["phases"][0]["cases"][0]["pixels"]
            wrong = root / row["actualRgba"]["file"]
            wrong.write_bytes(bytes((4, 5, 6, 255)) * 4)
            row["actualRgba"]["sha256"] = digest(wrong)
            with self.assertRaisesRegex(AssertionError, "pixel comparison"):
                oil.validate_in_world_render(document, root, "A" * 64)
            document = in_world_fixture(root)
            broken = document["negativeControls"]["missing-model"]
            a = root / broken["actualRgba"]["file"]
            b = root / broken["referenceRgba"]["file"]
            b.write_bytes(a.read_bytes())
            broken["referenceRgba"]["sha256"] = digest(b)
            with self.assertRaisesRegex(AssertionError, "pixel comparison"):
                oil.validate_in_world_render(document, root, "A" * 64)


if __name__ == "__main__":
    unittest.main()
