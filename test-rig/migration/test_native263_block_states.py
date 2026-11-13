import copy
import hashlib
import json
import math
from pathlib import Path
import unittest
import nbtlib

import native263_block_states as adapter
import native263
from matrix import digest, write_json
from native117_format import decode_palette as historical_decode


def contract_document():
    """Synthetic custody only; vanilla entries are captured from the actual native Java CODEC."""
    fixture = Path(__file__).with_name("fixtures") / "native263-block-state-catalog-vanilla.json"
    if digest(fixture) != "7DF51B812C2A0AE16391BB2E375F9F60F4FFD319C23134BBF76BD32674F7B99F":
        raise AssertionError("Actual native Java CODEC fixture bytes changed")
    blocks = json.loads(fixture.read_text())["blocks"]
    for identity, defaults, legal in (
            ("basepower:fluid_storage_tank", dict(facing="north", active="false"),
             dict(facing=["east", "north", "south", "west"], active=["false", "true"])),
            ("baseelectric:electric_battery_array", dict(facing="north", active="false"),
             dict(facing=["east", "north", "south", "west"], active=["false", "true"])),
            ("basepower:fluidtank", {}, {}), ("basepower:test", {}, {})):
        blocks[identity] = dict(defaults=defaults, legalValues=legal,
            stateCount=math.prod(map(len, legal.values())), defaultEncoded=identity,
            defaultDecoded=dict(id=identity, properties=copy.deepcopy(defaults)))
    return dict(format=1, engineProtocol="NATIVE66", minecraft="26.3", forge="66.0.0", dataVersion=5023,
                javaRuntimeVersion="25.0.3+9-LTS", mods=dict(native263.VERSIONS, minecraft="26.3", forge="66.0.0"),
                modJars={key + ".jar": hashlib.sha256(key.encode()).hexdigest().upper() for key in native263.VERSIONS},
                blockCount=len(blocks), stateCount=sum(row["stateCount"] for row in blocks.values()), blocks=blocks)


def contract_catalog():
    return adapter.StateCatalog(contract_document())


def write_contract_catalog(runtime):
    runtime = Path(runtime)
    mods = runtime / "mods"
    mods.mkdir(parents=True, exist_ok=True)
    for key in native263.VERSIONS:
        (mods / (key + ".jar")).write_bytes(key.encode())
    write_json(runtime / adapter.CATALOG_FILE, contract_document())


def block_state(value):
    return adapter.block_state(value, catalog=contract_catalog())


def decode_palette(*args):
    return adapter.decode_palette(*args, catalog=contract_catalog())


def palette_state(value):
    return adapter.palette_state(value, catalog=contract_catalog())


class Native263BlockStates(unittest.TestCase):
    def test_compact_and_partial_states_use_only_the_independent_catalog_defaults(self):
        self.assertEqual(dict(Name="baseelectric:electric_battery_array", Properties=dict(facing="north", active="false")),
                         palette_state({"": "baseelectric:electric_battery_array"}))
        chest = block_state(dict(id="minecraft:chest", properties={"facing": "east"}))
        self.assertEqual(dict(facing="east", type="single", waterlogged="false"), chest["Properties"])
        self.assertEqual(dict(facing="north", type="single", waterlogged="false"), block_state("minecraft:chest")["Properties"])
        self.assertNotEqual(block_state("minecraft:chest"), chest, "A wrong but legal explicit facing must not be defaulted away")

    def test_unknown_identity_property_and_value_are_rejected_not_silently_defaulted(self):
        for value in ("missing:block", dict(id="minecraft:chest", properties={"fake": "true"}),
                      dict(id="minecraft:chest", properties={"facing": "up"}),
                      dict(id="minecraft:chest", properties={"waterlogged": "maybe"})):
            with self.subTest(value=value), self.assertRaises(ValueError):
                block_state(value)
        with self.assertRaises(TypeError):
            adapter.block_state("minecraft:chest")

    def test_corrupt_catalog_defaults_roundtrip_domains_versions_and_counts_fail(self):
        baseline = contract_document()
        callbacks = [lambda d: d.__setitem__("dataVersion", 4903), lambda d: d.__setitem__("blockCount", True),
                     lambda d: d.__setitem__("stateCount", 1), lambda d: d["mods"].__setitem__("forge", "65.1.0"),
                     lambda d: d["modJars"].clear(),
                     lambda d: d["blocks"]["minecraft:chest"]["defaults"].__setitem__("facing", "up"),
                     lambda d: d["blocks"]["minecraft:chest"]["defaults"].pop("type"),
                     lambda d: d["blocks"]["minecraft:chest"].__setitem__("defaultEncoded", "minecraft:stone"),
                     lambda d: d["blocks"]["minecraft:chest"]["defaultDecoded"].__setitem__("id", "minecraft:stone"),
                     lambda d: d["blocks"]["minecraft:chest"]["legalValues"]["facing"].append("north")]
        for callback in callbacks:
            changed = copy.deepcopy(baseline)
            callback(changed)
            with self.assertRaises(ValueError):
                adapter.StateCatalog(changed)

    def test_catalog_input_is_copied_and_never_reconstructed_from_expected_rows(self):
        document = contract_document()
        catalog = adapter.StateCatalog(document)
        document["blocks"]["minecraft:chest"]["defaults"]["facing"] = "south"
        self.assertEqual("north", adapter.block_state("minecraft:chest", catalog=catalog)["Properties"]["facing"])

    def test_order_probe_catalog_never_opens_the_six_jar_baseline_implicitly(self):
        document = contract_document()
        document["mods"]["advantageorderprobe"] = "1.0.0.2603001"
        document["modJars"]["AdvantageOrderProbe-1.0.0.2603001.jar"] = "A" * 64
        with self.assertRaises(ValueError):
            adapter.StateCatalog(document)
        self.assertEqual(7, len(adapter.StateCatalog(document, order_probe=True).document["modJars"]))
        with self.assertRaises(ValueError):
            adapter.StateCatalog(document, order_probe="true")

    def test_sidecar_binds_actual_jar_bytes_and_duplicate_json_fields_are_rejected(self):
        import tempfile
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write_contract_catalog(root)
            self.assertEqual("north", adapter.load_catalog(root).blocks["minecraft:chest"]["defaults"]["facing"])
            (root / "mods/basepower.jar").write_bytes(b"changed actual staged input")
            with self.assertRaises(AssertionError):
                adapter.load_catalog(root)
            (root / adapter.CATALOG_FILE).write_text('{"format":1,"format":1}')
            with self.assertRaises(ValueError):
                adapter.load_catalog(root)
    def test_compact_default_and_full_compound_are_exact_projections(self):
        self.assertEqual(dict(Name="minecraft:stone", Properties={}), block_state("minecraft:stone"))
        source = dict(id="basepower:fluid_storage_tank", properties={"facing": "north", "active": "false"})
        before = copy.deepcopy(source)
        self.assertEqual(dict(Name=source["id"], Properties=source["properties"]), block_state(source))
        self.assertEqual(source, before)

    def test_historical_mixed_unknown_and_wrong_types_reject(self):
        for value in ({"Name": "minecraft:stone"}, {"id": "minecraft:stone", "Properties": {}},
                      {"id": "minecraft:stone", "Name": "minecraft:stone"},
                      {"id": "minecraft:stone", "properties": {"active": False}},
                      {"id": "minecraft:stone", "properties": []},
                      {"id": "minecraft:stone", "unknown": 1}, "stone", "minecraft:stone[active=false]", 1, None):
            with self.subTest(value=value), self.assertRaises(ValueError):
                block_state(value)

    def test_palette_padding_and_index_negatives_remain_strict(self):
        palette = ["minecraft:air", {"id": "minecraft:oak_log", "properties": {"axis": "x"}}]
        result = decode_palette(palette, [0x10] + [0] * 255)
        self.assertEqual(dict(Name="minecraft:air", Properties={}), result[0])
        self.assertEqual(dict(Name="minecraft:oak_log", Properties={"axis": "x"}), result[1])
        for entries, words in ((palette, []), (palette, [2] + [0] * 255), ([], [])):
            with self.subTest(entries=entries, words=len(words)), self.assertRaises(ValueError):
                decode_palette(entries, words)

    def test_historical_reader_is_unchanged_and_keeps_its_original_fields(self):
        source = [{"Name": "minecraft:stone", "Properties": {"unchanged": "true"}}]
        self.assertEqual(source * 4096, historical_decode(source, []))
        self.assertEqual([dict(Name="minecraft:stone", Properties={})] * 4096,
                         decode_palette(["minecraft:stone"], []))

    def test_native_list_storage_wrapper_is_unwrapped_once_not_normalized_recursively(self):
        palette = [{"": "minecraft:air"}, {"id": "minecraft:oak_log", "properties": {"axis": "x"}}]
        self.assertEqual("minecraft:air", decode_palette(palette, [0] * 256)[0]["Name"])
        for value in ({"": {"": "minecraft:air"}}, {"": "minecraft:air", "unknown": 1}, {"": 1}):
            with self.subTest(value=value), self.assertRaises(ValueError):
                palette_state(value)

    def test_real_native_written_mixed_palette_fixture_keeps_both_states(self):
        path = Path(__file__).with_name("fixtures") / "native263-mixed-palette.dat"
        self.assertEqual("6B8446EECE93C2098710495E720F8594F405EF2400F2E5A2A94046CFA02CAE97",
                         hashlib.sha256(path.read_bytes()).hexdigest().upper())
        raw = nbtlib.load(path)
        self.assertEqual(5023, int(raw["DataVersion"]))
        values = raw["palette"].unpack()
        self.assertEqual({"": "minecraft:stone"}, values[0])
        actual = decode_palette(values, [0x10] + [0] * 255)
        self.assertEqual(dict(Name="minecraft:stone", Properties={}), actual[0])
        self.assertEqual(dict(Name="minecraft:oak_log", Properties={"axis": "x"}), actual[1])


if __name__ == "__main__":
    unittest.main()
