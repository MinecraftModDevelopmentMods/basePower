import copy
import json
from pathlib import Path
import tempfile
import unittest
import uuid

import nbtlib as n
from matrix import WORLD, digest, fingerprint, write_json
import native12111_stopped_actor_reference as reference
from test_native117_entity_storage import put


class SourceStoppedReferenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.source = self.base / "source"
        self.world = self.source / WORLD
        (self.world / "playerdata").mkdir(parents=True)
        self.player_uuid = uuid.UUID("a614e658-6270-33c3-9297-7ce80908964f")
        words = [(self.player_uuid.int >> shift) & 0xffffffff for shift in (96, 64, 32, 0)]
        self.words = [value - (1 << 32) if value >= (1 << 31) else value for value in words]
        self.stack = n.Compound(id=n.String("minecraft:shield"), count=n.Int(1), Slot=n.Byte(-106),
            components=n.Compound({"minecraft:damage": n.Int(5), "minecraft:custom_data": n.Compound(
                Note=n.String("exact"), Nested=n.Compound(id=n.String("opaque"), Count=n.Int(19)))}))
        self.player = n.Compound(DataVersion=n.Int(3955), UUID=n.IntArray(self.words),
            Inventory=n.List[n.Compound]([copy.deepcopy(self.stack)]), EnderItems=n.List[n.Compound](), Health=n.Float(20))
        self.player_path = self.world / "playerdata" / (str(self.player_uuid) + ".dat")
        n.File(self.player).save(self.player_path, gzipped=True)
        n.File(n.Compound(Data=n.Compound(DataVersion=n.Int(3955), Player=copy.deepcopy(self.player)))).save(self.world / "level.dat", gzipped=True)
        write_json(self.source / "actor-fixture.json", dict(playerUuid=str(self.player_uuid), minX=128, maxX=191))
        self.entities = []
        for index, (witness, identity) in enumerate(reference.WITNESSES.items()):
            entity = n.Compound(id=n.String(identity), UUID=n.IntArray([0, 0, 0, index + 1]),
                Pos=n.List[n.Double]([128.5 + index, 100, 8.5]), Motion=n.List[n.Double]([0, -0.12, 0]),
                Rotation=n.List[n.Float]([0, 0]), ForgeData=n.Compound(AdvWorksActor=n.String(witness)),
                UnknownPersistent=n.Compound(Keep=n.Byte(7)))
            if identity == "minecraft:item":
                entity["Item"] = copy.deepcopy(self.stack)
                del entity["Item"]["Slot"]
            self.entities.append(entity)
        self.save_entities()
        self.approval = self.base / "approval.json"
        self.approve()

    def save_entities(self):
        root = n.Compound(DataVersion=n.Int(3955), Position=n.IntArray([8, 0]), Entities=n.List[n.Compound](self.entities))
        put(self.world, (8, 0), value=root)

    def approve(self):
        write_json(self.approval, dict(source=str(self.source.resolve()), sourceFingerprint=fingerprint(self.source)))

    def seal(self):
        self.reference = reference.seal(self.source, self.approval, digest(self.approval), self.base / "new-reference")
        return self.reference

    def validate(self):
        return reference.validate(self.reference, digest(self.reference), self.source, digest(self.approval))

    def test_only_stopped_disk_builds_complete_reference_and_retains_unknown_nbt(self):
        before = fingerprint(self.source)
        self.seal()
        result = self.validate()
        self.assertEqual(-0.12, result["entities"]["dropped-musket"]["Motion"][1])
        self.assertEqual({"cart", "dropped-musket", "dropped-oil", "owned-wolf", "hydroturbine"}, set(result["entities"]))
        document = json.loads(self.reference.read_text())
        self.assertIs(False, document["targetObservationsUsed"])
        raw = reference.storage._nbt((self.reference.parent / "raw/witnesses/dropped-musket.nbt").read_bytes())
        self.assertEqual(n.Byte(7), raw["UnknownPersistent"]["Keep"])
        self.assertEqual(19, result["file"]["Inventory"][0]["components"]["minecraft:custom_data"]["Nested"]["Count"])
        self.assertEqual(before, fingerprint(self.source))
        self.assertFalse((self.source / "actors-runtime-restart.json").exists())

    def test_live_serializer_snapshot_is_not_a_golden_input(self):
        write_json(self.source / "actors-runtime-restart.json", dict(entities=[dict(Motion=[0, 999, 0])]))
        self.approve()
        self.seal()
        self.assertEqual(-0.12, self.validate()["entities"]["dropped-musket"]["Motion"][1])

    def test_changed_golden_is_rejected_even_after_its_sha_is_refreshed(self):
        self.seal()
        document = json.loads(self.reference.read_text())
        document["expected"]["entities"]["dropped-musket"]["Motion"][1] = 123
        write_json(self.reference, document)
        with self.assertRaisesRegex(AssertionError, "golden does not derive"):
            self.validate()

    def test_changed_source_and_wrong_approval_fail_closed(self):
        self.seal()
        self.entities[0]["UnknownPersistent"]["Keep"] = n.Byte(8)
        self.save_entities()
        with self.assertRaisesRegex(AssertionError, "Unbound or changed"):
            self.validate()
        with self.assertRaises(AssertionError):
            reference.seal(self.source, self.approval, "0" * 64, self.base / "other")

    def test_missing_changed_extra_raw_assets_or_target_evidence_are_rejected(self):
        self.seal()
        artifact = self.reference.parent / "raw/player.dat"
        before = artifact.read_bytes()
        artifact.write_bytes(before + b"tamper")
        with self.assertRaises(AssertionError):
            self.validate()
        artifact.write_bytes(before)
        extra = self.reference.parent / "target-observation.json"
        extra.write_text("{}")
        with self.assertRaisesRegex(AssertionError, "target evidence is forbidden"):
            self.validate()

    def test_target_used_flag_wrong_source_version_or_wrong_player_identity_reject(self):
        self.seal()
        document = json.loads(self.reference.read_text())
        document["targetObservationsUsed"] = True
        write_json(self.reference, document)
        with self.assertRaises(AssertionError):
            self.validate()
        self.player["UUID"] = n.IntArray([0, 0, 0, 99])
        n.File(self.player).save(self.player_path, gzipped=True)
        with self.assertRaisesRegex(AssertionError, "player identity"):
            reference.disk_state(self.source)

    def test_duplicate_uuid_missing_witness_or_wrong_chunk_version_reject(self):
        self.entities[1]["UUID"] = self.entities[0]["UUID"]
        self.save_entities()
        with self.assertRaisesRegex(AssertionError, "Duplicate source entity UUID"):
            reference.disk_state(self.source)
        self.entities.pop(1)
        self.save_entities()
        with self.assertRaisesRegex(AssertionError, "exact five"):
            reference.disk_state(self.source)
        root = n.Compound(DataVersion=n.Int(4671), Position=n.IntArray([8, 0]), Entities=n.List[n.Compound](self.entities))
        put(self.world, (8, 0), value=root)
        with self.assertRaises(ValueError):
            reference.disk_state(self.source)


if __name__ == "__main__":
    unittest.main()
