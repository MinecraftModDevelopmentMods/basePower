import copy
import hashlib
import json
from pathlib import Path
import unittest
from unittest.mock import patch

import dimensions
import native117_dimension_assessment as assessment
from test_native117_dimension_assessment import PROOF, states


FIXTURE = Path(__file__).parent / "fixtures/native117_player_spawn_default.json"
LEGACY_FIXTURE = FIXTURE.with_name("native116_player_spawn_default.json")
COORDINATES = ("SpawnX", "SpawnY", "SpawnZ")


def player_states():
    before, after = states()
    for state in (before, after):
        state["file"].update(SpawnX=160, SpawnY=100, SpawnZ=24, SpawnForced=1, Dimension=-1)
        state["adapter"] = copy.deepcopy(state["file"])
        state["playerWorldDimension"] = -1
    for root in ("file", "adapter"):
        after[root]["SpawnDimension"] = 0
    return before, after


def assess(before, after):
    raw = dimensions.verdict(before, after)
    with patch.object(assessment, "load_native_proof", return_value=PROOF):
        return assessment.assess(before, after, raw, Path("reviewed-control.log"), "survival")


class NativePlayerSpawnDefaultTest(unittest.TestCase):
    def test_inherited_payloads_and_historical_provenance_are_preserved_exactly(self):
        legacy_bytes = LEGACY_FIXTURE.read_bytes()
        legacy = json.loads(legacy_bytes)
        fixture = json.loads(FIXTURE.read_text())
        self.assertEqual("BE92D8F3544634BF2E8BAF12BDD4EA4DCDED8467D5BE748893640963E71EC4E3",
                         hashlib.sha256(legacy_bytes).hexdigest().upper())
        self.assertEqual(LEGACY_FIXTURE.name, fixture["inheritedFixture"]["fixture"])
        self.assertEqual(hashlib.sha256(legacy_bytes).hexdigest().upper(),
                         fixture["inheritedFixture"]["sha256"])
        # Canonical JSON also distinguishes bool/int and int/float payload types.
        self.assertEqual(json.dumps(legacy["cases"], sort_keys=True),
                         json.dumps(fixture["cases"], sort_keys=True))
        self.assertEqual(legacy["primarySourceProof"][:2], fixture["primarySourceProof"][:2])
        self.assertEqual(legacy["scope"], fixture["scope"])
        self.assertEqual("minecraft:overworld", fixture["nativeTargetSemantics"]["nativeEncodedOverworld"])
        self.assertEqual(0, fixture["nativeTargetSemantics"]["historicalSemanticOverworld"])
        self.assertEqual("PATCHED_117_PRIMARY_SOURCE_PROOF_NOT_NEW_PLAYER_RUNTIME_QUALIFICATION",
                         fixture["nativeTargetSemantics"]["qualificationScope"])

    def test_authentic_110_and_112_player_roots_differ_only_by_explicit_default(self):
        fixture = json.loads(FIXTURE.read_text())
        self.assertEqual(["original110", "legacy112"], [case["name"] for case in fixture["cases"]])
        for case in fixture["cases"]:
            with self.subTest(case=case["name"]):
                original = copy.deepcopy(case)
                self.assertEqual(case["after"], assessment.semantic_player_spawn_defaults(case["before"]))
                self.assertEqual(case["after"], assessment.semantic_player_spawn_defaults(case["after"]))
                self.assertEqual(original, case)

    def test_authentic_player_payload_preserves_raw_health_fail_and_exact_inventory(self):
        for case in json.loads(FIXTURE.read_text())["cases"]:
            with self.subTest(case=case["name"]):
                before, after = states()
                before.update(copy.deepcopy(case["before"]))
                after.update(copy.deepcopy(case["after"]))
                raw = dimensions.verdict(before, after)
                result = assess(before, after)
                self.assertEqual("PASS_WITH_NATIVE_DIFFERENCE", result["status"])
                self.assertEqual("FAIL", result["rawStrictStatus"])
                self.assertEqual(raw, result["rawStrictResult"])
                self.assertEqual([assessment.HEALTH_DIFFERENCE], raw["differences"])

    def test_primary_patched_source_hashes_and_exact_entry_lines_are_captured(self):
        proof = json.loads(FIXTURE.read_text())["primarySourceProof"]
        self.assertEqual([
            "7D0148D301495D7A6E24D1030B7FD98163E748A8E0F37FCF74D1B7C4A05BB94B",
            "C2C7CAF7A08BD441992F6A38B2EC93195465D41D4127630A9C0F2102C921CF58",
            "531053A81F6591CA8CFD252154920B62D2CC509CB3DDC3FAC2E37F368003C5B6",
        ], [record["sha256"] for record in proof])
        old110 = proof[0]["entries"]
        self.assertEqual("net/minecraft/world/WorldProvider.java", old110[1]["entry"])
        self.assertEqual(404, old110[1]["excerpts"][0]["firstLine"])
        self.assertIn("return 0;", old110[1]["excerpts"][0]["text"])
        old112 = proof[1]["entries"][0]["excerpts"]
        self.assertEqual(1005, old112[0]["firstLine"])
        self.assertIn('getBoolean("HasSpawnDimensionSet")', old112[0]["text"])
        self.assertIn("spawnDimension != null ? spawnDimension : 0", old112[2]["text"])
        target = proof[2]
        self.assertEqual(("1.17.1", "37.1.1", "official", "1.17.1", "20210706.113038"),
                         tuple(target[key] for key in ("minecraftVersion", "forgeVersion", "mappingChannel",
                                                       "mappingVersion", "mcpVersion")))
        self.assertTrue(target["archive"].replace("\\", "/").endswith(
            "/1.17.1-37.1.1/official/1.17.1/remapped-javadoc.jar"))
        native, level, location = target["entries"]
        self.assertEqual("net/minecraft/server/level/ServerPlayer.java", native["entry"])
        self.assertEqual("FAA1A410A3D7CAC6DFDE7F15BAF5AFFC179E0573DC56E67FA21C9020BB682561",
                         native["entrySha256"])
        self.assertEqual([(171, 171), (294, 301), (329, 339)],
                         [(excerpt["firstLine"], excerpt["lastLine"]) for excerpt in native["excerpts"]])
        self.assertEqual("   private ResourceKey<Level> respawnDimension = Level.OVERWORLD;",
                         native["excerpts"][0]["text"])
        self.assertIn('contains("SpawnX", 99)', native["excerpts"][1]["text"])
        self.assertIn('contains("SpawnY", 99)', native["excerpts"][1]["text"])
        self.assertIn('contains("SpawnZ", 99)', native["excerpts"][1]["text"])
        self.assertIn('if (p_9131_.contains("SpawnDimension"))', native["excerpts"][1]["text"])
        self.assertIn("orElse(Level.OVERWORLD)", native["excerpts"][1]["text"])
        self.assertIn("if (this.respawnPosition != null)", native["excerpts"][2]["text"])
        self.assertIn("ResourceLocation.CODEC.encodeStart(NbtOps.INSTANCE, this.respawnDimension.location())",
                      native["excerpts"][2]["text"])
        self.assertIn('put("SpawnDimension"', native["excerpts"][2]["text"])
        self.assertEqual("net/minecraft/world/level/Level.java", level["entry"])
        self.assertEqual("927DD75F1CFEEA6CD63FC679992258327DCF7A1BDD2543FEFEB5E3166547A6D7",
                         level["entrySha256"])
        self.assertIn('OVERWORLD = ResourceKey.create(Registry.DIMENSION_REGISTRY, new ResourceLocation("overworld"))',
                      level["excerpts"][0]["text"])
        self.assertEqual("net/minecraft/resources/ResourceLocation.java", location["entry"])
        self.assertEqual("F392A113C24DF2B087AF8289D987C9F1F20C2ECD2966D522199CF6C6E486CD98",
                         location["entrySha256"])
        self.assertIn("CODEC = Codec.STRING.comapFlatMap(ResourceLocation::read, ResourceLocation::toString)",
                      location["excerpts"][0]["text"])
        self.assertIn('new String[]{"minecraft", p_135833_}', location["excerpts"][3]["text"])
        self.assertIn('return this.namespace + ":" + this.path;', location["excerpts"][4]["text"])

    def test_native_excerpts_record_valid_exact_line_spans(self):
        for entry in json.loads(FIXTURE.read_text())["primarySourceProof"][2]["entries"]:
            for excerpt in entry["excerpts"]:
                with self.subTest(entry=entry["entry"], firstLine=excerpt["firstLine"]):
                    self.assertGreaterEqual(excerpt["firstLine"], 1)
                    self.assertGreaterEqual(excerpt["lastLine"], excerpt["firstLine"])
                    self.assertEqual(excerpt["lastLine"] - excerpt["firstLine"] + 1,
                                     len(excerpt["text"].split("\n")))

    def test_equivalence_is_symmetric_idempotent_and_never_mutates_callers(self):
        before, after = player_states()
        original = copy.deepcopy((before, after))
        old = assessment.semantic_player_spawn_defaults(before)
        new = assessment.semantic_player_spawn_defaults(after)
        self.assertEqual(old["file"], new["file"])
        self.assertEqual(old["adapter"], new["adapter"])
        self.assertEqual(old, assessment.semantic_player_spawn_defaults(old))
        self.assertEqual(original, (before, after))

    def test_all_signed32_integral_coordinates_are_retained_exactly(self):
        for value in (-(1 << 31), -1, 0, (1 << 31) - 1):
            before, after = player_states()
            for state in (before, after):
                for root in ("file", "adapter"):
                    for key in COORDINATES:
                        state[root][key] = value
            self.assertEqual("PASS_WITH_NATIVE_DIFFERENCE", assess(before, after)["status"])

    def test_missing_any_coordinate_does_not_default(self):
        for root in ("file", "adapter"):
            for key in COORDINATES:
                before, after = player_states()
                del before[root][key]; del after[root][key]
                self.assertNotIn("SpawnDimension", assessment.semantic_player_spawn_defaults(before)[root])
                self.assertEqual("FAIL", assess(before, after)["status"])

    def test_no_coordinates_do_not_default(self):
        before, after = player_states()
        for state in (before, after):
            for root in ("file", "adapter"):
                for key in COORDINATES:
                    del state[root][key]
        self.assertEqual("FAIL", assess(before, after)["status"])

    def test_bool_float_string_malformed_or_overflow_coordinates_do_not_default(self):
        for value in (True, False, 0.0, 160.0, "160", None, [], {}, 1 << 31, -(1 << 31) - 1):
            for root in ("file", "adapter"):
                for key in COORDINATES:
                    before, after = player_states()
                    before[root][key] = value; after[root][key] = value
                    self.assertNotIn("SpawnDimension", assessment.semantic_player_spawn_defaults(before)[root])
                    self.assertEqual("FAIL", assess(before, after)["status"])

    def test_explicit_nonzero_bool_float_string_or_malformed_dimensions_are_not_equated(self):
        for value in (-1, 1, True, False, 0.0, "0", "minecraft:overworld", None, [], {}):
            for root in ("file", "adapter"):
                before, after = player_states(); after[root]["SpawnDimension"] = value
                self.assertEqual(after[root], assessment.semantic_player_spawn_defaults(after)[root])
                self.assertEqual("FAIL", assess(before, after)["status"])

    def test_matching_nonzero_dimension_is_preserved_without_rewriting(self):
        before, after = player_states()
        for state in (before, after):
            for root in ("file", "adapter"):
                state[root]["SpawnDimension"] = -1
        self.assertEqual(-1, assessment.semantic_player_spawn_defaults(before)["file"]["SpawnDimension"])
        self.assertEqual("PASS_WITH_NATIVE_DIFFERENCE", assess(before, after)["status"])

    def test_changed_coordinate_or_forced_flag_cannot_be_classified(self):
        for root in ("file", "adapter"):
            for key in (*COORDINATES, "SpawnForced"):
                before, after = player_states(); after[root][key] += 1
                self.assertEqual("FAIL", assess(before, after)["status"])

    def test_removed_or_changed_type_of_forced_flag_remains_exact(self):
        for root in ("file", "adapter"):
            for value in (None, True, 1.0, "1"):
                before, after = player_states()
                if value is None:
                    del after[root]["SpawnForced"]
                else:
                    after[root]["SpawnForced"] = value
                self.assertEqual("FAIL", assess(before, after)["status"])

    def test_unknown_extra_root_or_player_keys_cannot_be_dropped(self):
        for root in (None, "file", "adapter"):
            before, after = player_states()
            target = after if root is None else after[root]
            target["UnknownPrivateKey"] = {"keep": [1, 2]}
            self.assertEqual("FAIL", assess(before, after)["status"])

    def test_entity_item_nested_and_private_player_like_compounds_are_untouched(self):
        before, _ = player_states()
        payload = {"SpawnX": 160, "SpawnY": 100, "SpawnZ": 24, "SpawnForced": 1}
        before["entities"]["passenger-wolf"]["Private"] = copy.deepcopy(payload)
        before["entities"]["other"] = copy.deepcopy(payload)
        before["file"]["Private"] = copy.deepcopy(payload)
        before["file"]["Inventory"][0]["tag"]["PrivateSpawn"] = copy.deepcopy(payload)
        before["file"]["RootVehicle"] = {"Entity": copy.deepcopy(payload)}
        before["other"] = copy.deepcopy(payload)
        result = assessment.semantic_player_spawn_defaults(before)
        for root in ("entities", "other"):
            self.assertEqual(before[root], result[root])
        for key in ("Private", "Inventory", "RootVehicle"):
            self.assertEqual(before["file"][key], result["file"][key])

    def test_new_nested_spawn_dimension_is_not_classified_as_a_player_default(self):
        for location in ("entity", "item", "private"):
            before, after = player_states()
            if location == "entity":
                after["entities"]["passenger-wolf"]["SpawnDimension"] = 0
            elif location == "item":
                after["file"]["Inventory"][0]["tag"]["SpawnDimension"] = 0
            else:
                after["adapter"]["Private"] = {"SpawnDimension": 0}
            self.assertEqual("FAIL", assess(before, after)["status"])

    def test_attribute_count_and_item_payload_changes_still_fail(self):
        for change in ("attribute", "count", "itemPayload"):
            before, after = player_states()
            if change == "attribute":
                after["entities"]["passenger-wolf"]["Attributes"]["generic.maxHealth"]["Base"] = 21.0
            elif change == "count":
                after["file"]["Inventory"][0]["Count"] += 1
            else:
                after["adapter"]["Inventory"][0]["tag"]["extra"] = 1
            self.assertEqual("FAIL", assess(before, after)["status"])

    def test_world_entity_identity_and_relationship_context_remains_exact(self):
        for change in ("world", "dimension", "uuid", "owner", "relationship"):
            before, after = player_states()
            if change == "world": after["playerWorldDimension"] = 0
            elif change == "dimension": after["file"]["Dimension"] = 0
            elif change == "uuid": after["entities"]["passenger-wolf"]["UUIDMost"] = 29
            elif change == "owner": after["entities"]["passenger-wolf"]["OwnerUUID"] = "00000000-0000-0000-0000-000000000001"
            else: after["relationships"]["extra"] = {"passengerUuids": ["unapproved"]}
            self.assertEqual("FAIL", assess(before, after)["status"])

    def test_default_equivalence_does_not_tolerate_other_health_values(self):
        for health in (19.0, 20.00001, 21.0):
            before, after = player_states(); after["entities"]["passenger-wolf"]["Health"] = health
            self.assertEqual("FAIL", assess(before, after)["status"])


if __name__ == "__main__":
    unittest.main()
