"""Packet registry bindings must survive independent native registry orderings."""

import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from matrix import digest
import native1211neo as native
from native1211neo_recipes import recipe_fixture, registry_exports


class NativeRecipeExports(unittest.TestCase):
    def test_native_material_checklist_adds_vanilla_copper_without_changing_the_historical_fixture(self):
        original = dict(recipes=[], materials=['basemetals:copper_ingot'])
        with patch('native1211neo_recipes.historical_recipe_fixture', return_value=(dict(original), {})):
            definition, sources = recipe_fixture([])
        self.assertEqual(['basemetals:copper_ingot', 'minecraft:copper_ingot'], definition['materials'])
        self.assertEqual(['basemetals:copper_ingot'], original['materials'])
        self.assertEqual({}, sources)

    def fixture(self, root, label, data):
        report = root / f'recipes-{label}.json'
        packet, snapshot = report.with_suffix('.bin'), report.with_suffix('.registry.bin')
        packet.write_bytes(b'packet-' + data)
        snapshot.write_bytes(b'registry-' + data)
        row = dict(schema=1, minecraftVersion=native.MINECRAFT, neoForgeVersion=native.NEOFORGE,
                   snapshotType='SYNC_TO_CLIENT', sameRegistries=True, file=snapshot.name,
                   packetFile=packet.name, packetBytes=packet.stat().st_size, packetSha256=digest(packet),
                   bytes=snapshot.stat().st_size, sha256=digest(snapshot), registryCount=3, recipeCount=1)
        document = dict(status='PASS', bookRecipes=[dict(id='basepower:steel_frame')], registrySnapshot=row)
        report.write_text(json.dumps(document), encoding='utf-8')
        return report, document, packet, snapshot

    def test_each_packet_uses_its_own_cold_boot_registry_ids(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root, 'normal-sweep', b'first')
            self.fixture(root, 'tech-sweep', b'second')
            rows = registry_exports(root)
            self.assertEqual(2, len(rows))
            self.assertNotEqual(rows[0]['sha256'], rows[1]['sha256'])
            self.assertNotEqual(rows[0]['packetSha256'], rows[1]['packetSha256'])

    def test_rejects_changed_packet_snapshot_or_shared_snapshot_filename(self):
        for change in ('packet', 'snapshot', 'name', 'loader', 'count', 'empty'):
            with self.subTest(change=change), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                report, document, packet, snapshot = self.fixture(root, 'normal-sweep', b'first')
                if change == 'packet':
                    packet.write_bytes(b'x' * packet.stat().st_size)
                elif change == 'snapshot':
                    snapshot.write_bytes(b'x' * snapshot.stat().st_size)
                elif change == 'name':
                    document['registrySnapshot']['file'] = 'server-registry-snapshot.bin'
                elif change == 'loader':
                    document['registrySnapshot']['neoForgeVersion'] = '52.1.0'
                elif change == 'count':
                    document['registrySnapshot']['recipeCount'] = 2
                else:
                    snapshot.write_bytes(b'')
                report.write_text(json.dumps(document), encoding='utf-8')
                with self.assertRaises(AssertionError):
                    registry_exports(root)

    def test_no_export_is_not_a_success(self):
        with tempfile.TemporaryDirectory() as directory, self.assertRaises(AssertionError):
            registry_exports(Path(directory))


if __name__ == '__main__':
    unittest.main()
