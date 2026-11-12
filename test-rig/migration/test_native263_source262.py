"""Actual native262 profile03 serializer fixtures, plus explicit source-protocol negatives."""

import copy
from pathlib import Path
import shutil
import tempfile
from types import SimpleNamespace
import unittest

import nbtlib as n

from matrix import WORLD, digest, fingerprint
import native263_actors as actors
import native263_source262 as source
import native263_upgrade as upgrade


class Native263Source262Test(unittest.TestCase):
    def fixture(self, kind):
        return Path(__file__).with_name("fixtures") / ("native263-source262-" + kind)

    def test_actual_65_source_protocol_is_not_the_2612_route(self):
        self.assertEqual(("26.2", "65.1.0", "NATIVE65", 4903),
                         (source.MINECRAFT, source.FORGE, source.ENGINE_PROTOCOL, source.DATA_VERSION))
        self.assertEqual(4903, upgrade.SOURCE_VERSIONS["26.2"])
        self.assertEqual(4790, upgrade.SOURCE_VERSIONS["26.1.2"])

    def test_real_captured_player_actor_and_dimension_payloads_are_read_without_mutation(self):
        for kind in ("actors", "dimensions"):
            root = self.fixture(kind)
            original = fingerprint(root)
            proof = actors.native.read_json(root / "source-proof.json")
            self.assertEqual(source.SERVER_JAR_SHA, proof["engine"]["serverJarSha256"])
            self.assertEqual(4903, proof["engine"]["actualVersion"]["world_version"])
            for row in proof["files"]:
                self.assertEqual(row["sha256"], digest(root / row["path"]))
            state, label = actors.baseline(root, kind)
            self.assertEqual("runtime-restart", label)
            self.assertEqual({"cart", "dropped-musket", "dropped-oil", "owned-wolf", "hydroturbine"}
                             if kind == "actors" else {"boat", "horse", "hydroturbine", "passenger-wolf"},
                             set(state["entities"]))
            self.assertIn("Inventory", state["file"])
            self.assertIn("adapter", state)
            self.assertNotIn("embedded", state)
            if kind == "dimensions":
                self.assertEqual(-1, state["dimension"])
                self.assertEqual(-1, state["file"]["Dimension"])
                definition = actors.native.read_json(root / "dimension-fixture.json")
                self.assertEqual("minecraft:the_nether", str(source.player(root / WORLD, definition["playerUuid"])["Dimension"]))
            self.assertEqual(original, fingerprint(root))

    def test_real_262_components_are_preserved_including_opaque_nested_payloads(self):
        item = dict(id="baseelectric:lithium_battery", count=1,
                    components={"minecraft:custom_data": dict(charge=517, private=dict(Count=9, Name="not-an-item")),
                                "minecraft:enchantments": {"basesteam:musket_damage": 2}})
        original = copy.deepcopy(item)
        self.assertEqual(original, actors.source_components(item, 4903))
        self.assertEqual(original, item)
        for wrong in (4903.0, True, 4902, 4904):
            with self.subTest(version=wrong), self.assertRaises(ValueError):
                actors.source_components(item, wrong)
        for broken in (dict(item, Count=1), dict(item, count=True), dict(item, count=0)):
            with self.assertRaises(AssertionError):
                source.components(broken)

    def test_source_player_path_never_uses_a_legacy_or_embedded_decoy(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "fixture"
            shutil.copytree(self.fixture("actors"), root)
            definition = actors.native.read_json(root / "actor-fixture.json")
            world = root / WORLD
            player = source.source_layout.player_file(world, definition["playerUuid"])
            legacy = world / "playerdata" / player.name
            legacy.parent.mkdir()
            shutil.copyfile(player, legacy)
            player.unlink()
            with self.assertRaises(FileNotFoundError):
                actors.baseline(root, "actors")

    def test_wrong_source_level_version_and_ambiguous_host_fail_closed(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            world = root / WORLD
            world.mkdir()
            for version in (n.Int(4790), n.Int(5023), n.Long(4903), n.String("4903")):
                n.File(n.Compound(Data=n.Compound(DataVersion=version)), gzipped=True).save(world / "level.dat")
                with self.subTest(version=version), self.assertRaises(ValueError):
                    source.source_protocol(root)
            n.File(n.Compound(Data=n.Compound(DataVersion=n.Int(4903), Player=n.Compound())), gzipped=True).save(world / "level.dat")
            with self.assertRaises(AssertionError):
                source.source_protocol(root)
