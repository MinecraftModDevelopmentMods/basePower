"""Native dimension layouts keep respawn, boat, attribute and horse-gear evidence."""

import copy
import hashlib
from pathlib import Path
import unittest
import zipfile

import dimensions
import native12111_actors as native


class NativeDimensionLayoutTest(unittest.TestCase):
    def test_respawn_all_fields_migrate_without_editing_source(self):
        before = {"SpawnX": 160, "SpawnY": 100, "SpawnZ": 24, "SpawnDimension": -1,
                  "SpawnAngle": 37.5, "SpawnForced": 1, "Health": 20}
        original = copy.deepcopy(before)
        expected = {"respawn": {"pos": [160, 100, 24], "dimension": "minecraft:the_nether",
                                 "yaw": 37.5, "pitch": 0.0, "forced": 1}, "Health": 20}
        self.assertEqual(expected, native.expected_player_respawn(before))
        self.assertEqual(original, before)
        self.assertEqual(expected, native.expected_player_respawn(expected))

    def test_respawn_omitted_vanilla_defaults_are_explicit_and_false_forced_is_not_written(self):
        before = {"SpawnX": 1, "SpawnY": 2, "SpawnZ": 3, "SpawnForced": 0}
        self.assertEqual({"respawn": {"pos": [1, 2, 3], "dimension": "minecraft:overworld",
                                    "yaw": 0.0, "pitch": 0.0}}, native.expected_player_respawn(before))

    def test_partial_malformed_or_ambiguous_respawn_is_rejected(self):
        for value in ({"SpawnX": 1}, {"SpawnAngle": 10},
                      {"SpawnX": 1, "SpawnY": 2, "SpawnZ": 3, "respawn": {}},
                      {"SpawnX": 1, "SpawnY": 2, "SpawnZ": 3, "SpawnAngle": float("nan")},
                      {"SpawnX": 1, "SpawnY": 2, "SpawnZ": 3, "SpawnForced": 2},
                      {"SpawnX": 1, "SpawnY": 2, "SpawnZ": 3, "SpawnDimension": "other:unknown"}):
            with self.subTest(value=value), self.assertRaises(ValueError):
                native.expected_player_respawn(value)

    def test_capture_keeps_legacy_angle_and_complete_native_respawn(self):
        self.assertEqual({"SpawnAngle": 19.25}, native.player_semantic({"SpawnAngle": 19.25}, dimensions.PLAYER_STATE, {}))
        value = {"respawn": {"pos": [1, 2, 3], "dimension": "minecraft:the_end",
                              "yaw": 41.5, "pitch": -12.0, "forced": 1, "other:opaque": 7}}
        actual = native.player_semantic(value, dimensions.PLAYER_STATE, {})
        self.assertEqual(value, actual)
        with self.assertRaises(ValueError):
            native.player_semantic({**value, "SpawnAngle": 19.25}, dimensions.PLAYER_STATE, {})

    def test_each_respawn_field_missing_or_changed_or_added_remains_red(self):
        before = {"file": {"respawn": {"pos": [1, 2, 3], "dimension": "minecraft:the_end",
                                      "yaw": 37.5, "pitch": 0.0, "forced": 1}}, "entities": {}}
        for key, changed in (("pos", [1, 2, 4]), ("dimension", "minecraft:overworld"),
                             ("yaw", 0.0), ("pitch", 10.0), ("forced", 0), ("unknown", 5)):
            for missing in (False, True):
                actual = copy.deepcopy(before)
                if missing:
                    actual["file"]["respawn"].pop(key, None)
                else:
                    actual["file"]["respawn"][key] = changed
                if missing and key == "unknown":
                    continue
                with self.subTest(key=key, missing=missing):
                    self.assertEqual("FAIL", native.verdict(dimensions.verdict, before, actual)["status"])

    def test_all_eighteen_boat_variants_have_exact_species_identity(self):
        for chest in (False, True):
            for variant in native.BOAT_VARIANTS:
                entity = {"id": "minecraft:chest_boat" if chest else "minecraft:boat", "Type": variant,
                          "UUIDMost": 5, "UUIDLeast": 7, "Passengers": []}
                native.expected_dimension_entity(entity)
                suffix = ("_chest" if chest else "") + ("_raft" if variant == "bamboo" else "_boat")
                self.assertEqual("minecraft:" + variant + suffix, entity["id"])
                self.assertNotIn("Type", entity)
                self.assertEqual((5, 7), (entity["UUIDMost"], entity["UUIDLeast"]))
        with self.assertRaises(ValueError):
            native.expected_dimension_entity({"id": "minecraft:boat", "Type": "unproven"})

    def test_horse_equipment_is_complete_exact_and_ambiguous_sources_rejected(self):
        stack = {"id": "minecraft:saddle", "count": 1, "components": {"minecraft:custom_data": {"Note": "intact"}}}
        horse = {"id": "minecraft:horse", "SaddleItem": stack, "ArmorItem": {"id": "minecraft:iron_horse_armor", "count": 1}}
        native.expected_dimension_entity(horse)
        self.assertEqual({"saddle", "body"}, set(horse["equipment"]))
        self.assertEqual(stack, horse["equipment"]["saddle"])
        self.assertEqual(horse, native.dimension_entity_state(horse))
        before = {"entities": {"horse": horse}}
        for slot in ("saddle", "body"):
            after = copy.deepcopy(before)
            del after["entities"]["horse"]["equipment"][slot]
            self.assertEqual("FAIL", native.verdict(dimensions.verdict, before, after)["status"])
        for extra in ("saddle", "other"):
            with self.assertRaises(ValueError):
                native.expected_dimension_entity({"id": "minecraft:horse", "SaddleItem": stack, "equipment": {extra: stack}})
        with self.assertRaises(ValueError):
            native.dimension_entity_state({"id": "minecraft:horse", "SaddleItem": stack, "equipment": {"saddle": stack}})

    def test_attribute_aliases_change_only_known_id_spellings(self):
        original = {"attributes": [{"id": key, "base": 3.5} for key in native.NATIVE_ATTRIBUTE_ALIASES]}
        original["attributes"].append({"id": "other:max_health", "base": 8.5, "modifiers": [{"unknown": 7}]})
        before = copy.deepcopy(original)
        actual = native.native_attribute_view(original)["Attributes"]
        self.assertEqual([*native.NATIVE_ATTRIBUTE_ALIASES.values(), "other:max_health"], [a["Name"] for a in actual])
        self.assertEqual(8.5, actual[-1]["Base"])
        self.assertEqual([{"unknown": 7}], actual[-1]["Modifiers"])
        self.assertEqual(before, original)
        with self.assertRaises(ValueError):
            native.native_attribute_view({"attributes": [{"id": "minecraft:max_health"}, {"id": "minecraft:generic.max_health"}]})

    def test_primary_equipment_source_is_the_reviewed_native61_archive(self):
        path = (Path(__file__).resolve().parents[1] / ".gradle/mavenizer/repo/net/minecraftforge/forge"
                / "1.21.11-61.1.0/forge-1.21.11-61.1.0-sources.jar")
        self.assertEqual("DFE912C3B1E769B60DA2B4C5EA0B7F9171637B26DA640A9D85DE8CA683B30661", native.digest(path))
        with zipfile.ZipFile(path) as archive:
            source = archive.read("net/minecraft/util/datafix/fixes/PlayerEquipmentFix.java")
            self.assertEqual("DC9E526B9695B1EF635914AF051B99945CC1399FE5AC813BF172CD7A3E8CF13F",
                             hashlib.sha256(source).hexdigest().upper())


if __name__ == "__main__":
    unittest.main()
