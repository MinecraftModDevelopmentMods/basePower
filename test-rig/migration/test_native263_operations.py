import copy
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import nbtlib

from matrix import WORLD
import native263_operations as native
import test_native12111_operations_actor_load as retained_controls
from native263_layout import region_file


class Native263ExpectedActorLoadTest(retained_controls.NativeExpectedActorLoadTest):
    def compare(self, fixture, inspected):
        original = copy.deepcopy((fixture, inspected))
        try:
            return native.validate_restart(fixture, inspected)
        finally:
            self.assertEqual(original, (fixture, inspected))

    def test_selected_native_sources_are_exact(self):
        self.assertEqual(2, len(native.native_load_sources()))


class Native263StoppedMachineTest(unittest.TestCase):
    def test_full_job_count_cannot_be_a_smaller_passing_catalog(self):
        for count in (0, 131, 133):
            with self.subTest(count=count), patch.object(native, "frozen_validate_operation", return_value=dict(status="PASS", cases=count)):
                with self.assertRaisesRegex(AssertionError, "132"):
                    native.validate_operation({}, {}, "all")
        with patch.object(native, "frozen_validate_operation", return_value=dict(status="FAIL", cases=132)):
            self.assertEqual("FAIL", native.validate_operation({}, {}, "all")["status"])

    def payload(self, version=5023):
        tile = nbtlib.Compound(id=nbtlib.String("basepower:fluidtank"), x=nbtlib.Int(-1),
                              y=nbtlib.Int(80), z=nbtlib.Int(-1), keepPacked=nbtlib.Byte(0),
                              unknown=nbtlib.Compound(ordered=nbtlib.IntArray([3, 2, 1])))
        root = nbtlib.File(dict(DataVersion=nbtlib.Int(version), xPos=nbtlib.Int(-1), zPos=nbtlib.Int(-1),
                sections=nbtlib.List[nbtlib.Compound]([nbtlib.Compound(Y=nbtlib.Byte(5), block_states=nbtlib.Compound(
                    palette=nbtlib.List[nbtlib.Compound]([nbtlib.Compound(id=nbtlib.String("basepower:fluidtank"))])))]),
                block_entities=nbtlib.List[nbtlib.Compound]([tile]), opaque=nbtlib.IntArray([9, 8, 7])))
        buffer = io.BytesIO()
        root.write(buffer)
        body = b"\x03" + buffer.getvalue()
        return len(body).to_bytes(4, "big") + body, tile

    def test_old_data_version_and_wrong_type_are_not_coerced(self):
        with self.assertRaises(ValueError):
            native.modern_chunk(self.payload(4671)[0])
        payload, _ = self.payload()
        native.modern_chunk(payload)
        with self.assertRaises(ValueError):
            native.modern_chunk(payload[:-1])

    def test_all_dimensions_and_custom_negative_coordinates_use_explicit_paths(self):
        for dimension in ("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end", "example:deep/cave"):
            with self.subTest(dimension=dimension), tempfile.TemporaryDirectory() as folder:
                runtime = Path(folder) / "runtime"
                evidence = Path(folder) / "evidence"
                region = region_file(runtime / WORLD, dimension, -1, -1)
                region.parent.mkdir(parents=True)
                region.write_bytes(b"immutable native region fixture")
                payload, tile = self.payload()
                expected = copy.deepcopy(tile)
                del expected["keepPacked"]
                row = dict(x=-1, y=80, z=-1, block="basepower:fluidtank",
                           state="Block{basepower:fluidtank}", nbt=expected.snbt())
                fixture = dict(cases=[dict(id="typed", dimension=dimension, positions=[row])])
                with patch.object(native, "read_region", return_value=(None, {1023: payload})) as read:
                    result = native.audit_saved(runtime, fixture, evidence)
                    self.assertEqual("PASS", result["status"])
                    read.assert_called_once_with(region)
                self.assertEqual(payload, (evidence / "chunk-00000.bin").read_bytes())
                self.assertEqual(b"immutable native region fixture", region.read_bytes())
                changed = copy.deepcopy(fixture)
                changed["cases"][0]["positions"][0]["nbt"] = expected.snbt().replace("[I; 3, 2, 1]", "[I; 1, 2, 3]")
                self.assertNotEqual(fixture, changed)
                with patch.object(native, "read_region", return_value=(None, {1023: payload})):
                    self.assertEqual("FAIL", native.audit_saved(runtime, changed, evidence.parent / "changed")["status"])


if __name__ == "__main__":
    unittest.main()
