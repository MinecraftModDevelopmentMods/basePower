import copy
from pathlib import Path
import tempfile
import unittest
import uuid

import nbtlib

import native2612_layout as layout


class Native2612LayoutTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.world = Path(self.temp.name) / "world"
        self.world.mkdir()
        self.identity = uuid.UUID("12345678-1234-5678-9abc-def012345678")

    def save(self, path, value):
        path.parent.mkdir(parents=True, exist_ok=True)
        nbtlib.File(value, gzipped=True).save(path)

    def level(self, **extra):
        data = nbtlib.Compound(DataVersion=nbtlib.Int(4790), **extra)
        self.save(self.world / "level.dat", nbtlib.Compound(Data=data))
        return data

    def words(self, identity=None):
        value = (identity or self.identity).int
        return nbtlib.IntArray([((value >> shift) & 0xffffffff) -
                               (0x100000000 if (value >> shift) & 0x80000000 else 0)
                               for shift in (96, 64, 32, 0)])

    def test_all_vanilla_dimensions_are_namespaced_not_legacy_folders(self):
        for key, name in (("minecraft:overworld", "overworld"),
                          ("minecraft:the_nether", "the_nether"),
                          ("minecraft:the_end", "the_end")):
            self.assertEqual(self.world / "dimensions/minecraft" / name,
                             layout.dimension_directory(self.world, key))

    def test_custom_nested_dimension_identity_is_not_inferred_from_basename(self):
        for key in ("test:deep/caverns", "test:dim-1", "other:dim1"):
            namespace, path = key.split(":")
            self.assertEqual(self.world / "dimensions" / namespace / path,
                             layout.dimension_directory(self.world, key))
        self.assertNotEqual(layout.dimension_directory(self.world, "test:dim-1"),
                            layout.dimension_directory(self.world, "minecraft:the_nether"))

    def test_legacy_folders_never_override_target_layout(self):
        for path in ("region", "DIM-1/region", "DIM1/region", "playerdata"):
            (self.world / path).mkdir(parents=True)
        self.assertEqual(self.world / "dimensions/minecraft/overworld/region/r.0.0.mca",
                         layout.region_file(self.world, "minecraft:overworld", 0, 0))
        self.assertEqual(self.world / "players/data" / (str(self.identity) + ".dat"),
                         layout.player_file(self.world, self.identity))

    def test_negative_chunk_coordinates_use_floor_division_for_every_storage_kind(self):
        for x, z, rx, rz in ((-33, -1, -2, -1), (-32, -32, -1, -1),
                             (-1, 32, -1, 1), (31, -33, 0, -2), (32, 0, 1, 0)):
            for kind in ("region", "entities", "poi"):
                path = layout.region_file(self.world, "test:deep/caverns", x, z, kind)
                self.assertEqual(self.world / "dimensions/test/deep/caverns" / kind /
                                 f"r.{rx}.{rz}.mca", path)

    def test_coordinates_and_storage_kinds_fail_closed(self):
        for x, z in ((True, 0), (0, False), (1.5, 0), (0, "1")):
            with self.subTest(x=x, z=z), self.assertRaises(ValueError):
                layout.region_file(self.world, "minecraft:overworld", x, z)
        for kind in ("DIM-1", "../entities", "data", ""):
            with self.subTest(kind=kind), self.assertRaises(ValueError):
                layout.region_file(self.world, "minecraft:overworld", 0, 0, kind)

    def test_resource_keys_cannot_escape_or_change_phase(self):
        for key in ("overworld", "../minecraft:overworld", "minecraft:../overworld",
                    "minecraft:/overworld", "minecraft:a//b", "minecraft:a/./b",
                    "minecraft:a\\b", "Minecraft:overworld", "minecraft:", "a:b:c"):
            with self.subTest(key=key), self.assertRaises(ValueError):
                layout.dimension_directory(self.world, key)

    def test_shared_data_uses_its_full_namespaced_resource_key(self):
        self.assertEqual(self.world / "data/minecraft/world_gen_settings.dat",
                         layout.shared_file(self.world, "minecraft:world_gen_settings"))
        self.assertEqual(self.world / "data/test/deep/unknown.dat",
                         layout.shared_file(self.world, "test:deep/unknown"))

    def test_player_uuid_is_not_a_path_or_a_loose_alias(self):
        for value in ("../player", "not-a-uuid", "12345678123456789abcdef012345678",
                      str(self.identity).upper(), None, 0):
            with self.subTest(value=value), self.assertRaises(ValueError):
                layout.player_file(self.world, value)

    def test_level_requires_the_native_target_version_without_coercion(self):
        for value in (nbtlib.Int(4671), nbtlib.String("4790"), nbtlib.Long(4790)):
            self.save(self.world / "level.dat", nbtlib.Compound(Data=nbtlib.Compound(DataVersion=value)))
            with self.subTest(value=value), self.assertRaises(ValueError):
                layout.read_level(self.world)
        self.level(Unknown=nbtlib.Compound(keep=nbtlib.String("exact")))
        data = layout.read_level(self.world)
        self.assertEqual("exact", data["Unknown"]["keep"])

    def test_missing_shared_worldgen_does_not_fall_back_to_embedded_legacy_data(self):
        self.level(WorldGenSettings=nbtlib.Compound(seed=nbtlib.Long(17)))
        with self.assertRaises(FileNotFoundError):
            layout.read_worldgen(self.world)

    def test_shared_worldgen_preserves_unknown_complete_nbt(self):
        self.level()
        settings = nbtlib.Compound(seed=nbtlib.Long(-17),
                                  Unknown=nbtlib.Compound(raw=nbtlib.ByteArray([1, -2, 3])))
        path = layout.shared_file(self.world, "minecraft:world_gen_settings")
        self.save(path, nbtlib.Compound(DataVersion=nbtlib.Int(4790), data=settings,
                                      UnknownEnvelope=nbtlib.String("retain")))
        before = path.read_bytes()
        result = layout.read_worldgen(self.world)
        self.assertEqual(settings, result["data"])
        self.assertEqual("retain", result["UnknownEnvelope"])
        result["data"]["Unknown"]["raw"][0] = 99
        self.assertEqual(before, path.read_bytes())

    def test_shared_data_requires_native_version_and_compound_payload(self):
        self.level()
        path = layout.shared_file(self.world, "minecraft:world_gen_settings")
        for tag in (nbtlib.Compound(DataVersion=nbtlib.Int(4671), data=nbtlib.Compound()),
                    nbtlib.Compound(DataVersion=nbtlib.Int(4790)),
                    nbtlib.Compound(DataVersion=nbtlib.Int(4790), data=nbtlib.String("wrong"))):
            self.save(path, tag)
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                layout.read_worldgen(self.world)

    def test_singleplayer_uuid_is_explicit_authority_and_missing_is_not_invented(self):
        self.level()
        self.assertIsNone(layout.singleplayer_uuid(self.world))
        self.level(singleplayer_uuid=self.words())
        self.assertEqual(self.identity, layout.singleplayer_uuid(self.world))

    def test_malformed_or_embedded_player_authority_is_rejected(self):
        for value in (nbtlib.String(str(self.identity)), nbtlib.IntArray([1, 2]),
                      nbtlib.List[nbtlib.Int]([1, 2, 3, 4])):
            self.level(singleplayer_uuid=value)
            with self.subTest(value=value), self.assertRaises(ValueError):
                layout.singleplayer_uuid(self.world)
        for extra in ({"Player": nbtlib.Compound(UUID=self.words())},
                      {"singleplayer_uuid": self.words(),
                       "Player": nbtlib.Compound(UUID=self.words(uuid.uuid4()))}):
            self.level(**extra)
            with self.assertRaises(ValueError):
                layout.singleplayer_uuid(self.world)

    def test_player_file_keeps_all_unknown_fields_and_checks_identity(self):
        tag = nbtlib.Compound(DataVersion=nbtlib.Int(4790), UUID=self.words(),
                             Dimension=nbtlib.String("test:deep/caverns"),
                             Unknown=nbtlib.Compound(opaque=nbtlib.LongArray([1, -2])))
        path = layout.player_file(self.world, self.identity)
        self.save(path, tag)
        expected = copy.deepcopy(tag)
        self.assertEqual(expected.snbt(), layout.read_player(self.world, self.identity).snbt())
        for changed in (dict(UUID=self.words(uuid.uuid4())), dict(DataVersion=nbtlib.Int(4671))):
            self.save(path, nbtlib.Compound({**tag, **changed}))
            with self.assertRaises(ValueError):
                layout.read_player(self.world, self.identity)


if __name__ == "__main__":
    unittest.main()
