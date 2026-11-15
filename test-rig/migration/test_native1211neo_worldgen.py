"""Adversarial provider/generation controls; synthetic samples are not runtime proof."""

import copy
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch

import native1211neo_worldgen as worldgen


def provider(scenario="family"):
    ores = {}
    for identity, block, frequency, state in (
            (worldgen.LI, "baseelectric:li_ore", 0.125, scenario != "override"),
            (worldgen.SULFUR, "baseelectric:sulfur_ore", 1.0, scenario != "mineralogy")):
        ores[identity] = dict(block=block, enabled=state, dimensions={worldgen.OVERWORLD: dict(
            enabled=True, min_y=1, max_y=31, frequency=frequency)})
    if scenario == "mineralogy":
        ores[worldgen.MINERAL_SULFUR] = dict(block="mineralogy:sulfur_ore", enabled=True,
                                          dimensions={worldgen.OVERWORLD: dict(enabled=True)})
    return dict(ores=ores, fluid_deposits={worldgen.OIL: dict(block="basepower:crude_oil",
        enabled=scenario != "override", dimensions={worldgen.OVERWORLD: dict(
            enabled=True, min_y=0, max_y=48, frequency=0.08, biome_dictionary=["SANDY"],
            excluded_biome_dictionary=["BEACH", "MESA"])})})


def legacy_provider():
    blocks = (("basepower:legacy/basepower_crude_oil_0", "basepower:crude_oil"),
              ("baseelectric:legacy/baseelectric_li_ore_0", "baseelectric:li_ore"),
              ("baseelectric:legacy/baseelectric_sulfur_ore_1", "baseelectric:sulfur_ore"))
    return dict(ores={identity: dict(block=block, enabled=True, dimensions={
        worldgen.OVERWORLD: dict(enabled=True)}) for identity, block in blocks}, fluid_deposits={})


def sample(mineralogy=False):
    return dict(format=1, minecraft="1.21.1", loader="neoforge", neoforge="21.1.265", kind="sample",
                station="worldgen", radius=4, chunksScanned=243,
                centers={"spawn": "0,0", "DESERT": "20,4", "OCEAN": "-30,8"},
                counts={"basepower:crude_oil": 79, "baseelectric:li_ore": 11,
                        "baseelectric:sulfur_ore": 0 if mineralogy else 150,
                        "mineralogy:sulfur_ore": 95 if mineralogy else 0, "mineralogy:crude_oil": 0},
                biomeViolations={"basepower:crude_oil": 0, "mineralogy:crude_oil": 0},
                biomeBoundaryBlocks={"basepower:crude_oil": 0, "mineralogy:crude_oil": 0},
                chunkLoading={"protocol": "native_FULL_off_thread_future_poll_v1", "requests": 243,
                              "releasedTickets": 243, "activeTickets": 0,
                              "dispatcherClosed": True, "executorShutdown": True})


class ProviderControls(unittest.TestCase):
    def test_only_exact_frozen_f_families_are_accepted(self):
        for mineralogy in (False, True):
            mods = {key: ("fixture", Path("fixture.jar"), sha) for key, sha in worldgen.FROZEN.items()
                    if mineralogy or key != "mineralogy"}
            worldgen.require_frozen(mods, mineralogy)
            for changed in ({key: row for key, row in mods.items() if key != "basepower"},
                            mods | {"basepower": ("fixture", Path("fixture.jar"), "0" * 64)},
                            mods | {"unknown": ("fixture", Path("fixture.jar"), "1" * 64)}):
                with self.subTest(mineralogy=mineralogy, changed=changed), self.assertRaises(AssertionError):
                    worldgen.require_frozen(changed, mineralogy)

    def test_visible_fresh_defaults_and_explicit_disabled_alternative(self):
        for scenario in ("family", "mineralogy", "override"):
            value = provider(scenario)
            before = copy.deepcopy(value)
            worldgen.require_profile(value, scenario)
            self.assertEqual(before, value)
        value = provider("mineralogy")
        for mutate in (lambda row: row["ores"][worldgen.SULFUR].update(enabled=True),
                       lambda row: row["ores"].pop(worldgen.SULFUR),
                       lambda row: row["ores"][worldgen.MINERAL_SULFUR].update(enabled=False)):
            changed = copy.deepcopy(value)
            mutate(changed)
            with self.subTest(changed=changed), self.assertRaises(AssertionError):
                worldgen.require_profile(changed, "mineralogy")

    def test_rates_ranges_and_oil_biomes_cannot_be_relaxed(self):
        for identity, section, field, bad in (
                (worldgen.LI, "ores", "frequency", 1.0), (worldgen.SULFUR, "ores", "min_y", 0),
                (worldgen.OIL, "fluid_deposits", "max_y", 96),
                (worldgen.OIL, "fluid_deposits", "biome_dictionary", []),
                (worldgen.OIL, "fluid_deposits", "excluded_biome_dictionary", [])):
            value = provider()
            value[section][identity]["dimensions"][worldgen.OVERWORLD][field] = bad
            with self.subTest(identity=identity, field=field), self.assertRaises(AssertionError):
                worldgen.require_profile(value, "family")

    def test_enabled_is_a_real_boolean_not_a_truthy_label(self):
        for bad in (1, "true", None):
            value = provider()
            value["ores"][worldgen.LI]["enabled"] = bad
            with self.subTest(bad=bad), self.assertRaises(AssertionError):
                worldgen.require_profile(value, "family")

    def test_user_override_is_required_to_survive_exactly_across_restart(self):
        first = provider("override")
        worldgen.require_profile(first, "override")
        worldgen.require_restart(first, copy.deepcopy(first))
        changed = copy.deepcopy(first)
        changed["ores"][worldgen.LI]["enabled"] = True
        with self.assertRaises(AssertionError):
            worldgen.require_restart(first, changed)
        with self.assertRaises(AssertionError):
            worldgen.require_profile(changed, "override")

    def policy_pair(self):
        first = provider("override")
        first["ore_source_policies"] = {"lithium": {"mode": "keep_separate", "candidates": [
            dict(source_id=worldgen.LI, placement_active=True, external=False, owner="baseelectric")]}}
        second = copy.deepcopy(first)
        second["ore_source_policies"]["lithium"]["candidates"][0]["placement_active"] = False
        return first, second

    def test_only_the_known_derived_activity_is_rebuilt_from_the_authored_rule(self):
        first, second = self.policy_pair()
        untouched = copy.deepcopy((first, second))
        worldgen.require_provider_restart(first, second)
        self.assertEqual(untouched, (first, second))

    def test_derived_activity_cannot_hide_a_lost_override_or_wrong_owner(self):
        first, second = self.policy_pair()
        mutations = (
            lambda row: row["ores"][worldgen.LI].update(enabled=True),
            lambda row: row["ore_source_policies"]["lithium"]["candidates"][0].update(owner="another"),
            lambda row: row["ore_source_policies"]["lithium"]["candidates"][0].update(placement_active=True),
            lambda row: row["ore_source_policies"]["lithium"].update(mode="discard"),
        )
        for mutate in mutations:
            changed = copy.deepcopy(second)
            mutate(changed)
            with self.subTest(changed=changed), self.assertRaises(AssertionError):
                worldgen.require_provider_restart(first, changed)

    def test_unknown_external_activity_is_not_normalized(self):
        first, second = self.policy_pair()
        for value in (first, second):
            value["ore_source_policies"]["lithium"]["candidates"][0]["external"] = True
        with self.assertRaises(AssertionError):
            worldgen.require_provider_restart(first, second)

    def test_legacy_import_requires_three_unique_active_rules_and_no_modern_duplicates(self):
        value = legacy_provider()
        worldgen.require_profile(value, "legacy")
        for section, identity, row in (
                ("ores", worldgen.LI, provider()["ores"][worldgen.LI]),
                ("fluid_deposits", worldgen.OIL, provider()["fluid_deposits"][worldgen.OIL]),
                ("ores", "unknown:duplicate", copy.deepcopy(next(iter(value["ores"].values()))))):
            changed = copy.deepcopy(value)
            changed[section][identity] = row
            with self.subTest(identity=identity), self.assertRaises(AssertionError):
                worldgen.require_profile(changed, "legacy")
        for identity in value["ores"]:
            changed = copy.deepcopy(value)
            changed["ores"].pop(identity)
            with self.subTest(missing=identity), self.assertRaises(AssertionError):
                worldgen.require_profile(changed, "legacy")


class PhysicalGenerationControls(unittest.TestCase):
    def test_positive_bounded_samples_preserve_the_raw_document(self):
        for mineralogy in (False, True):
            value = sample(mineralogy)
            before = copy.deepcopy(value)
            worldgen.require_sample(value, 4, mineralogy)
            self.assertEqual(before, value)

    def test_zero_counts_are_not_generation_proof_even_with_a_pass_label(self):
        for identity in ("basepower:crude_oil", "baseelectric:li_ore", "baseelectric:sulfur_ore"):
            value = sample()
            value["status"] = "PASS"
            value["counts"][identity] = 0
            with self.subTest(identity=identity), self.assertRaisesRegex(AssertionError, "Zero-count"):
                worldgen.require_sample(value, 4)

    def test_missing_negative_float_or_boolean_quantities_fail(self):
        for bad in (None, -1, 1.5, True):
            value = sample()
            value["counts"]["basepower:crude_oil"] = bad
            with self.subTest(bad=bad), self.assertRaises(AssertionError):
                worldgen.require_sample(value, 4)
        value = sample()
        value["counts"].pop("baseelectric:li_ore")
        with self.assertRaises(AssertionError):
            worldgen.require_sample(value, 4)

    def test_missing_or_duplicated_desert_and_counter_biomes_fail(self):
        for key, bad in (("DESERT", "NOT_FOUND"), ("OCEAN", "NOT_FOUND"), ("OCEAN", "20,4")):
            value = sample()
            value["centers"][key] = bad
            with self.subTest(key=key, bad=bad), self.assertRaises(AssertionError):
                worldgen.require_sample(value, 4)

    def test_nonzero_missing_or_truthy_biome_violations_fail(self):
        for bad in (1, "0", False):
            value = sample()
            value["biomeViolations"]["basepower:crude_oil"] = bad
            with self.subTest(bad=bad), self.assertRaises(AssertionError):
                worldgen.require_sample(value, 4)
        value = sample()
        value.pop("biomeViolations")
        with self.assertRaises(AssertionError):
            worldgen.require_sample(value, 4)

    def test_disabled_electric_sulfur_cannot_generate_beside_mineralogy(self):
        value = sample(True)
        value["counts"]["baseelectric:sulfur_ore"] = 1
        with self.assertRaises(AssertionError):
            worldgen.require_sample(value, 4, True)
        value = sample(True)
        value["counts"]["mineralogy:sulfur_ore"] = 0
        with self.assertRaises(AssertionError):
            worldgen.require_sample(value, 4, True)

    def test_partial_wrong_radius_or_diagnostic_free_report_fails(self):
        for key, bad in (("radius", 3), ("chunksScanned", 0), ("station", "other"),
                         ("chunkLoading", None), ("biomeBoundaryBlocks", None)):
            value = sample()
            value[key] = bad
            with self.subTest(key=key), self.assertRaises(AssertionError):
                worldgen.require_sample(value, 4)

    def test_restart_does_not_hide_solid_loss_or_new_ore(self):
        before = sample()["counts"]
        for delta in (-1, 1):
            after = copy.deepcopy(before)
            after["baseelectric:sulfur_ore"] += delta
            with self.subTest(delta=delta), self.assertRaises(AssertionError):
                worldgen.require_restart(before, after)

    def test_historical_loader_or_wrong_native_identity_cannot_supply_generation_proof(self):
        for key, bad in (("loader", "forge"), ("neoforge", "21.1.246"), ("minecraft", "1.20.1"),
                         ("format", 0), ("kind", "snapshot")):
            value = sample()
            value[key] = bad
            with self.subTest(key=key), self.assertRaises(AssertionError):
                worldgen.require_sample(value, 4)

    def test_pending_tickets_or_unclosed_dispatcher_are_not_a_complete_sample(self):
        for key, bad in (("activeTickets", 1), ("releasedTickets", 242), ("dispatcherClosed", False),
                         ("executorShutdown", False), ("protocol", "manual-chunks"), ("requests", 0)):
            value = sample()
            value["chunkLoading"][key] = bad
            with self.subTest(key=key), self.assertRaises(AssertionError):
                worldgen.require_sample(value, 4)


class RuntimeBoundaries(unittest.TestCase):
    def test_normal_settings_are_local_explicit_and_do_not_change_the_reserved_port(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            path = root / "server.properties"
            path.write_text("server-port=26540\nlevel-type=minecraft:flat\ngenerator-settings=old\n")
            worldgen.settings(root, 12345, True)
            text = path.read_text()
            self.assertIn("server-port=26540", text)
            self.assertIn("level-seed=12345", text)
            self.assertIn("level-type=minecraft:normal", text)
            self.assertNotIn("generator-settings=old", text)

    def test_server_startup_is_bounded_and_expired_campaign_never_waits(self):
        server = object.__new__(worldgen.BoundedServer)
        server.deadline = time.monotonic() + 200
        with patch.object(worldgen.native.Server, "wait") as wait:
            server.wait("Done (", 300)
            self.assertLessEqual(wait.call_args.args[1], 120)
            server.deadline = time.monotonic() - 1
            with self.assertRaises(TimeoutError):
                server.wait("Worldgen sample wrote ", 180)


if __name__ == "__main__":
    unittest.main()
