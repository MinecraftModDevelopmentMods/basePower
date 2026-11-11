"""Fail-closed checks for native saved terrain and the optional oil input pin."""

import io
from pathlib import Path
import unittest
from unittest.mock import Mock
import zlib

import nbtlib

from native1211_machines import LifecycleRun, MINERALOGY, oil_pins
from native1211_operations import modern_chunk


class MachineAdapters(unittest.TestCase):
    def test_lifecycle_inventory_uses_native_custom_data_command(self):
        runner = object.__new__(LifecycleRun)
        runner.command = Mock()
        runner.inventory([1, 2, 3], 0, 'minecraft:dirt{AdvWorksWitness:"S-MOVE-east"}', 7)
        runner.command.assert_called_once_with(
            'item replace block 1 2 3 container.0 with '
            'minecraft:dirt[minecraft:custom_data={AdvWorksWitness:"S-MOVE-east"}] 7')

    def test_oil_input_keeps_verified_source_and_rejects_another_build(self):
        entry = (MINERALOGY["version"], Path("mineralogy.jar"), MINERALOGY["sha256"])
        pins = oil_pins({"mineralogy": entry})
        self.assertEqual(MINERALOGY["sourceRef"], pins["jars"][0]["sourceRef"])
        self.assertNotIn("sourceRef", oil_pins({"basepower": ("v", Path("p.jar"), "A" * 64)})["jars"][0])
        for version, sha in (("wrong", entry[2]), (entry[0], "B" * 64)):
            with self.assertRaises(ValueError):
                oil_pins({"mineralogy": (version, entry[1], sha)})

    @staticmethod
    def terrain(version=3955, legacy=False):
        root = nbtlib.File({
            "DataVersion": nbtlib.Int(version), "xPos": nbtlib.Int(3), "zPos": nbtlib.Int(-2),
            "sections": nbtlib.List[nbtlib.Compound]([nbtlib.Compound({
                "Y": nbtlib.Byte(4), "block_states": nbtlib.Compound({
                    "palette": nbtlib.List[nbtlib.Compound]([nbtlib.Compound({
                        "Name": nbtlib.String("basepower:fluid_container")})]),
                    "data": nbtlib.LongArray([17, -3])})})]),
            "block_entities": nbtlib.List[nbtlib.Compound]([nbtlib.Compound({
                "id": nbtlib.String("basepower:fluid_container"),
                "Tank": nbtlib.Compound({"Amount": nbtlib.Int(1234)})})]),
        })
        if legacy:
            root["Level"] = nbtlib.Compound()
        raw = io.BytesIO()
        root.write(raw)
        compressed = b"\x02" + zlib.compress(raw.getvalue())
        return len(compressed).to_bytes(4, "big") + compressed

    def test_saved_chunk_accessors_preserve_typed_payload(self):
        observed = modern_chunk(self.terrain())
        self.assertEqual(1234, int(observed["TileEntities"][0]["Tank"]["Amount"]))
        self.assertIsInstance(observed["TileEntities"][0]["Tank"]["Amount"], nbtlib.Int)
        self.assertEqual([17, -3], list(observed["Sections"][0]["BlockStates"]))
        self.assertEqual("basepower:fluid_container", observed["Sections"][0]["Palette"][0]["Name"])
        self.assertEqual(-2, int(observed["zPos"]))

    def test_wrong_version_legacy_shape_and_broken_length_are_rejected(self):
        for payload in (self.terrain(version=3839), self.terrain(legacy=True), self.terrain()[:-1]):
            with self.subTest(payload=payload[:12]), self.assertRaises(ValueError):
                modern_chunk(payload)


if __name__ == "__main__":
    unittest.main()
