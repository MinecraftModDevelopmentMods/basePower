"""Native target layout and unchanged historical actor assertion controls."""
import copy
from pathlib import Path
import tempfile
import unittest
import sys
from unittest.mock import patch

import nbtlib
import native263_actors as target
import native263_layout as layout
from native117_actor_io import bind
import test_native12111_actor_network_adapters as previous_adapters
import test_native12111_actor_fixtures as previous_fixtures
import test_native12111_actor_comparison as previous_comparison
import test_native12111_stopped_actor_reference as previous_reference
import native263_actor_comparison as comparison
import native263_actor_fixtures as fixtures
import native263_stopped_actor_reference as reference
from test_native117_entity_storage import entity, entry, put, root as entity_root


def retained_controls(source, **bindings):
    """Run every unchanged assertion against the new adapter, not its old module."""
    values = {
        name: bind(value, **bindings) if callable(value) and hasattr(value, '__code__') else value
        for name, value in vars(source).items() if name not in ('__dict__', '__weakref__')}
    values['__module__'] = __name__
    return type('Native263' + source.__name__, (unittest.TestCase,), values)


Native263FixtureControls = retained_controls(previous_fixtures.NativeActorFixtureControls,
    legal_ender_batteries=fixtures.legal_ender_batteries, source_components=target.source_components)
Native263ComparisonControls = retained_controls(previous_comparison.NativeActorComparisonTest, diagnosis=comparison)
Native263StoppedReferenceControls = retained_controls(previous_reference.SourceStoppedReferenceTest, reference=reference)
Native263PayloadControls = retained_controls(previous_adapters.ActorAdapters, actor_driver=target)


class Native263Actors(unittest.TestCase):
    def test_diagnostic_profile_cannot_be_run_without_explicit_provisional_scope(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            argv = ['native263_actors.py', '--fixture', str(root), '--fixture-receipt', str(root / 'receipt.json'),
                    '--fixture-receipt-sha256', '0' * 64, '--profile', str(root / 'profile.json'),
                    '--seed', str(root / 'seed'), '--inventory', str(root / 'inventory.json'),
                    '--inventory-sha256', '0' * 64, '--java', str(root / 'java'),
                    '--output', str(root / 'output'), '--kind', 'actors', '--port', '28690']
            with patch.object(sys, 'argv', argv), patch.object(target, 'output_path', return_value=root / 'output'), \
                    patch.object(target.native, 'read_json', return_value={'diagnosticScope': 'context-only'}), \
                    patch.object(target.native, 'read_family_target') as stage:
                with self.assertRaisesRegex(ValueError, 'explicit --provisional'):
                    target.main()
                stage.assert_not_called()

    def test_explicit_dimension_paths_do_not_use_legacy_basenames(self):
        for key in layout.VANILLA_DIMENSIONS:
            self.assertEqual(layout.dimension_directory(Path('world'), key),
                             Path('world') / target.DIMENSION_PATHS[key][1])

    def test_player_equipment_and_respawn_keep_exact_native_maps(self):
        state = {'file': {'Inventory': [{'Slot': -106, 'id': 'basesteam:musket', 'count': 1,
                    'components': {'minecraft:damage': 9}}], 'SpawnX': 1, 'SpawnY': 80,
                    'SpawnZ': 3, 'SpawnDimension': -1, 'SpawnAngle': 27.0}, 'entities': {}}
        result = target.expected_state(state, 'dimensions')
        self.assertEqual([], result['file']['Inventory'])
        self.assertEqual(9, result['file']['equipment']['offhand']['components']['minecraft:damage'])
        self.assertEqual('minecraft:the_nether', result['file']['respawn']['dimension'])
        actual = copy.deepcopy(result)
        actual['file']['equipment']['offhand']['components']['minecraft:damage'] = 8
        self.assertEqual('FAIL', target.verdict(target.actors.compare, result, actual)['status'])

    def test_old_dimension_directory_and_wrong_snapshot_dimension_reject(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            old = root / 'DIM-1'
            old.mkdir()
            with self.assertRaisesRegex(ValueError, 'native dimension'):
                target.audit_entity_storage({}, old, root / 'evidence', dimension='minecraft:the_nether')

    def test_native_helper_bytes_are_exact_and_old_contract_rejects(self):
        root = Path(__file__).resolve().parents[1] / 'src/main/resources/assets/advantageworkstest'
        document = dict(helperPolicy='native263-leash-knot-nosave-v1',
                        helperContractSha256=target.HELPER_CONTRACT_SHA, dimensions={},
                        entityCount=0, nonpersistentHelperCount=0, observedEntityCount=0)
        self.assertEqual('NOT_REQUIRED', target.validate_helpers(document, (root / 'native263-helper-contract.json').read_bytes())['status'])
        with self.assertRaises(AssertionError):
            target.validate_helpers(document, (root / 'native12111-helper-contract.json').read_bytes())

    def test_native_entity_audit_keeps_unknown_nbt_and_rejects_payload_loss(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            world = layout.dimension_directory(base / 'world', 'minecraft:the_nether')
            actor = entity()
            chunk = entity_root((0, 0), [actor])
            chunk['DataVersion'] = nbtlib.Int(5023)
            path, _ = put(world, (0, 0), value=chunk)
            before = path.read_bytes()
            record = dict(entities=[entry(actor)], worldDimensionKey='minecraft:the_nether', dimension=-1)
            receipt = target.audit_entity_storage(record, world, base / 'positive')
            self.assertEqual(1, receipt['matchedEntities'])
            self.assertEqual(-1, receipt['dimension'])
            damaged = copy.deepcopy(actor)
            damaged['Items'][0]['tag']['Private'] = nbtlib.String('lost')
            with self.assertRaisesRegex(AssertionError, 'Complete saved entity NBT'):
                target.audit_entity_storage(dict(record, entities=[entry(damaged)]), world, base / 'negative')
            self.assertEqual(before, path.read_bytes())

    def test_old_wolf_proof_cannot_relabel_native26_failure(self):
        old = previous_adapters.NativeDifferenceAssessment()
        old.setUp()
        strict = target.verdict(target.dimensions.verdict, old.before, old.after)
        with patch.object(target.native, 'validate_hash'), patch.object(target.native, 'read_json', return_value={'noPower': True}):
            with self.assertRaisesRegex(ValueError, 'Historical wolf controls'):
                target.assess_native(old.before, old.after, strict, Path('old-proof'), 'strict', True)

    def test_native_wolf_assessment_keeps_raw_failure_and_rejects_unrelated_changes(self):
        old = previous_adapters.NativeDifferenceAssessment()
        old.setUp()
        runtime = dict(minecraftVersion='26.3', targetDataVersion=5023,
                       engineProtocol='NATIVE66',
                       engineSourcesSha256='4571742629CD247753B109B272C8D6BBC583E6D3D5B2E79FA7DEC1EA09D25F8E',
                       inheritedJvmEnvironmentAbsent=True, jvmArguments=['-XX:ActiveProcessorCount=4'],
                       javaSha256=target.native.JAVA_SHA, mainClass='NativeWolfPlatformControl',
                       classpath=[dict(sha256='CAC87178E46226FE8745A45DF1CAB8AF8C2AA12CA820CBEADC2D42F554A5D7C6')])

        def evidence(path):
            if path.name == 'control-runtime.json':
                return runtime
            return dict(sourceDataVersion=int(path.stem.split('-')[1]), targetDataVersion=5023,
                        noPower=True, uuidPreserved=True, ownerPreserved=True,
                        sourceHealth=8, savedHealth=16, sourceMaxHealth=20, savedMaxHealth=40)

        strict = target.verdict(target.dimensions.verdict, old.before, old.after)
        with patch.object(target.native, 'validate_hash'), patch.object(target.native, 'read_json', side_effect=evidence):
            result = target.assess_native(old.before, old.after, strict, Path('proof'), 'strict', True)
            self.assertEqual('PASS_WITH_NATIVE_DIFFERENCE', result['status'])
            self.assertEqual('FAIL', result['rawStrictResult']['status'])
            changed = copy.deepcopy(old.after)
            changed['player']['Inventory'][0]['Count'] = 16
            bad = target.verdict(target.dimensions.verdict, old.before, changed)
            self.assertEqual('FAIL', target.assess_native(old.before, changed, bad, Path('proof'), 'strict', True)['status'])
            runtime['inheritedJvmEnvironmentAbsent'] = False
            with self.assertRaisesRegex(ValueError, 'Historical wolf controls'):
                target.assess_native(old.before, old.after, strict, Path('proof'), 'strict', True)


if __name__ == '__main__':
    unittest.main()
