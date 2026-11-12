import copy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import nbtlib

from matrix import digest, fingerprint
from native117_geometry import require_geometry
from native117_partial import exercise_bridge, exercise_partial
from native117_ticket_networks import NativeTicketExtendedRun
import native117_extended
import native262_networks as networks


class NativeNetworkAdapterTest(unittest.TestCase):
    def test_native_sdk_and_historical_oracles_remain_explicit(self):
        self.assertEqual(("26.2", "65.1.0", "20260616.103818", "25.0.3+9"),
                         (networks.native.MINECRAFT, networks.native.FORGE,
                          networks.native.MCP, networks.native.JAVA_VERSION))
        self.assertEqual(11, networks.MAX_TICKET_DISTANCE)
        self.assertEqual(13, networks.HOLDER_RADIUS)
        self.assertTrue(issubclass(networks.PartialRun, NativeTicketExtendedRun))
        self.assertIs(networks.CAMPAIGNS["partial"][1], exercise_partial)
        self.assertIs(networks.CAMPAIGNS["bridge"][1], exercise_bridge)
        self.assertIs(networks.CAMPAIGNS["extended"][1], native117_extended.exercise)

    def test_live_geometry_cannot_accept_an_old_radius_or_unobserved_forced_ticket(self):
        original = dict(ticketGeometry=dict(fullLevel=33, entityTickingLevel=31, maxDistance=11,
                        lastHolderLevel=44, holderRadius=13, observedForcedLevels=[31]))
        require_geometry(original, forced=True, max_distance=networks.MAX_TICKET_DISTANCE)
        for key, value in (("maxDistance", 12), ("lastHolderLevel", 45),
                           ("holderRadius", 12), ("observedForcedLevels", []),
                           ("observedForcedLevels", [32])):
            wrong = copy.deepcopy(original)
            wrong["ticketGeometry"][key] = value
            with self.assertRaises(AssertionError):
                require_geometry(wrong, forced=True, max_distance=networks.MAX_TICKET_DISTANCE)

    def test_three_network_types_are_outside_each_others_loaded_holder_apron(self):
        linear = networks.linear_fixtures(networks.HOLDER_RADIUS)
        bridges = networks.bridge_fixtures(networks.HOLDER_RADIUS)
        self.assertEqual({"C-STEAM", "C-ELECTRIC", "C-FLUID"}, {row["id"] for row in linear})
        self.assertEqual(3, len(bridges))
        for rows, left, right in ((linear, "supply", "machine"), (bridges, "bridge", "endpoints")):
            for row in rows:
                a, b = row["groups"][left][0], row["groups"][right][0]
                self.assertGreater(max(abs(a[0] - b[0]), abs(a[1] - b[1])), 2 * networks.HOLDER_RADIUS)

    def test_extended_retains_six_facings_vertical_routes_and_junctions_for_both_drills(self):
        rows = networks.extended_fixtures(networks.HOLDER_RADIUS)
        self.assertEqual(21, len(rows))
        faces = {"down", "up", "north", "south", "west", "east"}
        for prefix in ("S-EXT-", "E-EXT-"):
            routes = [row for row in rows if row["id"].startswith(prefix)]
            self.assertEqual(9, len(routes))
            self.assertEqual(faces, {row["facing"] for row in routes})
            self.assertTrue({prefix + suffix for suffix in ("vertical-up", "vertical-down", "junction")}
                            <= {row["id"] for row in routes})

    def test_item_command_adapter_preserves_all_typed_custom_fields(self):
        old = 'minecraft:dirt{AdvWorksWitness:"unit",Extra:{kind:5s}}'
        converted = networks.native_inventory_item(old)
        self.assertTrue(converted.startswith("minecraft:dirt[minecraft:custom_data="))
        self.assertEqual(nbtlib.parse_nbt(old.partition("{")[1] + old.partition("{")[2]),
                         nbtlib.parse_nbt(converted.partition("=")[2][:-1]))
        self.assertEqual("minecraft:coal", networks.native_inventory_item("minecraft:coal"))
        with self.assertRaises(ValueError):
            networks.native_inventory_item('minecraft:dirt[minecraft:custom_data={A:1}]')

    def test_derived_inventory_view_preserves_components_unknown_fields_and_original(self):
        original = dict(nbt='{Items:[{id:"minecraft:dirt",count:7,components:{'
                            '"minecraft:custom_data":{AdvWorksWitness:"unit",Typed:2b},'
                            '"future:payload":{Value:9L}}}],Outside:3s}', extra=[dict(value=4)])
        frozen = copy.deepcopy(original)
        derived = networks.compatibility_view(original)
        self.assertEqual(frozen, original)
        self.assertIsNot(derived, original)
        raw, converted = nbtlib.parse_nbt(original["nbt"]), nbtlib.parse_nbt(derived["nbt"])
        self.assertEqual(raw["Outside"], converted["Outside"])
        self.assertEqual(raw["Items"][0]["components"], converted["Items"][0]["components"])
        self.assertEqual(raw["Items"][0]["count"], converted["Items"][0]["Count"])
        self.assertEqual(raw["Items"][0]["components"]["minecraft:custom_data"], converted["Items"][0]["tag"])

    def test_native_inventory_rejects_legacy_ambiguous_and_badly_typed_stacks(self):
        invalid = ['{Items:{}}', '{Items:[{count:1}]}', '{Items:[{id:"minecraft:dirt",Count:1b}]}',
                   '{Items:[{id:"minecraft:dirt",count:1,Count:1b}]}',
                   '{Items:[{id:"minecraft:dirt",count:1,tag:{}}]}',
                   '{Items:[{id:"minecraft:dirt",count:1,components:[]}]}',
                   '{Items:[{id:"minecraft:dirt",count:1,components:{"minecraft:custom_data":1}}]}']
        invalid += ['{Items:[{id:"minecraft:dirt",count:' + value + '}]}'
                    for value in ("0", "-1", "1b", "1s", "1L", "1.0f", '"1"')]
        for nbt in invalid:
            with self.subTest(nbt=nbt), self.assertRaises(AssertionError):
                networks.compatibility_view(dict(nbt=nbt))

    def test_native_snapshot_identity_and_clock_cannot_be_coerced(self):
        good = dict(minecraft="26.2", forge="65.1.0", station="lifecycle", worldTime=0)
        self.assertIs(good, networks.validate_snapshot(good))
        for key, value in (("minecraft", "1.21.11"), ("forge", "64.0.9"), ("station", "works"),
                           ("worldTime", True), ("worldTime", -1), ("worldTime", "0")):
            wrong = dict(good, **{key: value})
            with self.assertRaises(AssertionError):
                networks.validate_snapshot(wrong)

    def test_custody_rejects_changed_seed_and_oracle_input(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            seed = root / "seed"
            seed.mkdir()
            (seed / "library").write_bytes(b"sealed seed")
            source = root / "oracle.py"
            source.write_bytes(b"sealed oracle")
            hashes = networks.oracle_closure()
            hashes[source] = digest(source)
            inputs = networks.Inputs((seed, {}, {}), root / "java", "unit", root / "output", hashes, fingerprint(seed))
            inputs.verify(False)
            source.write_bytes(b"changed oracle")
            with self.assertRaises(AssertionError):
                inputs.verify(False)
            source.write_bytes(b"sealed oracle")
            (seed / "library").write_bytes(b"changed seed")
            with self.assertRaises(AssertionError):
                inputs.verify(False)

    def test_preflight_rejects_inherited_jvm_options_before_any_world_work(self):
        from types import SimpleNamespace
        for key in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH"):
            with patch.dict(networks.native.os.environ, {key: "unexpected"}), self.assertRaises(ValueError):
                networks.prepare_inputs(SimpleNamespace())

    def test_explicit_cpu_cap_preserves_the_real_pinned_command(self):
        original = ["actual-java.exe", "-Xms512M", "-Xmx2G", "@native-win-args.txt", "nogui"]
        actual = networks.capped_command(original)
        self.assertEqual([original[0], "-XX:ActiveProcessorCount=4", *original[1:]], actual)
        self.assertNotIn("-XX:ActiveProcessorCount=4", original)
        self.assertEqual(1, actual.count("-XX:ActiveProcessorCount=4"))
        for command in ([], ["java", "-version"], ["java", "-Xmx3G", "@args"],
                        ["java", "-Xmx2G", "@args", "-XX:ActiveProcessorCount=8"]):
            with self.assertRaises(ValueError):
                networks.capped_command(command)

    def test_finite_server_retains_the_actual_core_mro_and_local_constructor_binding(self):
        self.assertTrue(issubclass(networks.Server, networks.native.Server))
        mro = networks.Server.__mro__
        self.assertLess(mro.index(networks.native.Server), mro.index(networks._CappedLaunch))
        self.assertLess(mro.index(networks._CappedLaunch), mro.index(networks.native.NativeLaunch))
        self.assertIs(networks.native.NativeLaunch.__init__.__globals__["subprocess"], networks.subprocess)

    def test_even_empty_inherited_options_are_rejected(self):
        from types import SimpleNamespace
        for key in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH"):
            with patch.dict(networks.native.os.environ, {key: ""}), self.assertRaises(ValueError):
                networks.prepare_inputs(SimpleNamespace())


if __name__ == "__main__":
    unittest.main()
