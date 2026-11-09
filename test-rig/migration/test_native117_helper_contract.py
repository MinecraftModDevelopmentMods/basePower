"""Synthetic partition/tamper controls, not a claim of runtime migration coverage."""

import copy
import json
from pathlib import Path
import unittest

import nbtlib as n
import native117_entity_storage as native
import native117_entity_storage_replay as replay
import native117_helper_contract as policy
import native117_stopped_entities as stopped
from test_native117_entity_storage import entity, entry, put
import test_native117_entity_storage_replay as fixtures


class NativeHelperContractTests(unittest.TestCase):
    def setUp(self):
        self.fixture = fixtures.RetainedEntityReplayTests()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        f = self.fixture
        self.holder = entity(identity='minecraft:horse')
        self.holder['Leash'] = n.Compound(X=n.Int(8), Y=n.Int(80), Z=n.Int(8))
        f.stage(actor=self.holder)
        self.knot = entity((5, 6, 7, 8), pos=(8.5, 80.375, 8.5), identity=policy.ID)
        self.identity = native._identity(self.knot)
        self.helper = entry(self.knot, uuid=self.identity, id=policy.ID, nativeClass=policy.CLASS,
            worldDimensionKey='minecraft:overworld', canSerialize=False, removed=False, passenger=False,
            passengerCount=0, policy=policy.POLICY, attachmentPosition=[8, 80, 8])
        f.document.update(format=2, helperPolicy=policy.POLICY, helperContractSha256=policy.CONTRACT_SHA,
            nonpersistentHelperCount=1, observedEntityCount=2)
        for record in f.document['dimensions'].values():
            record.update(nonpersistentHelpers=[], nonpersistentHelperCount=0, observedEntityCount=record['entityCount'])
        f.document['dimensions']['minecraft:overworld'].update(nonpersistentHelpers=[self.helper],
            nonpersistentHelperCount=1, observedEntityCount=2)
        self.lifecycle = dict(worldDimensionKey='minecraft:overworld', gameTime=19, nativeOnLoad=True,
            nativeEntityContextVersion=1, entities=[dict(uuid=native._identity(self.holder),
                worldDimensionKey='minecraft:overworld', sameWorld=True, leashHolderUuid=self.identity,
                leashHolderType=policy.ID, leashHolderPosition=[8, 80, 8])])
        self.contract = (Path(__file__).resolve().parents[1] /
            'src/main/resources/assets/advantageworkstest/native117-helper-contract.json').read_bytes()
        (f.runtime / policy.CONTRACT).write_bytes(self.contract)
        fixtures.write(f.runtime / 'dimensions-runtime.json', self.lifecycle)

    def validate(self):
        return policy.validate(self.fixture.document, self.contract, self.lifecycle)

    def test_exact_helper_partition_has_separate_native_lifecycle_not_persistence_credit(self):
        result = self.validate()
        self.assertEqual('PASS_NATIVE_HELPER_LIFECYCLE', result['status'])
        self.assertEqual([native._identity(self.holder)], result['helpers'][self.identity]['holderUuids'])
        proof = self.fixture.retain()
        self.assertEqual((1, 1), (proof['snapshotRecords'], proof['matchedEntities']))
        result = replay.replay(self.fixture.evidence)
        self.assertEqual('PASS', result['status'])
        self.assertIn(policy.CONTRACT, result['files'])
        self.assertIn(policy.LIFECYCLE, result['files'])

    def test_unknown_nosave_type_and_wrong_class_fail_closed(self):
        for name, value in [('id', 'minecraft:lightning_bolt'), ('nativeClass', policy.CLASS + 'Subclass'),
                            ('canSerialize', True), ('removed', True), ('passenger', True), ('policy', 'unproven')]:
            with self.subTest(name=name):
                before = self.helper[name]
                self.helper[name] = value
                with self.assertRaises(AssertionError): self.validate()
                self.helper[name] = before

    def test_duplicate_uuid_cannot_hide_persistent_entity(self):
        self.helper['uuid'] = native._identity(self.holder)
        with self.assertRaisesRegex(AssertionError, 'Duplicate'): self.validate()

    def test_forged_counts_and_boolean_counts_fail(self):
        for target, key in [(self.fixture.document, 'entityCount'), (self.fixture.document, 'observedEntityCount'),
                            (self.fixture.document['dimensions']['minecraft:overworld'], 'nonpersistentHelperCount')]:
            previous = target[key]
            for value in (True, 999):
                target[key] = value
                with self.assertRaises(AssertionError): self.validate()
            target[key] = previous

    def test_complete_helper_nbt_identity_and_attachment_must_match(self):
        self.knot['Pos'][1] = n.Double(79.375)
        self.helper['nbtBase64'] = entry(self.knot)['nbtBase64']
        with self.assertRaisesRegex(AssertionError, 'attachment'): self.validate()

    def test_hanging_entity_tile_coordinates_cannot_substitute_for_native_knot_pos(self):
        self.knot['TileY'] = n.Int(80)
        self.helper['nbtBase64'] = entry(self.knot)['nbtBase64']
        with self.assertRaisesRegex(AssertionError, 'attachment'): self.validate()

    def test_missing_persistent_holder_is_not_accepted_as_transient(self):
        record = self.fixture.document['dimensions']['minecraft:overworld']
        record.update(entities=[], entityCount=0, observedEntityCount=1)
        self.fixture.document.update(entityCount=0, observedEntityCount=1)
        with self.assertRaisesRegex(AssertionError, 'persistent holder'): self.validate()

    def test_wrong_dimension_future_or_missing_relationship_fails(self):
        for key, value in [('worldDimensionKey', 'minecraft:the_nether'), ('gameTime', 21), ('entities', [])]:
            previous = self.lifecycle[key]
            self.lifecycle[key] = value
            with self.assertRaises(AssertionError): self.validate()
            self.lifecycle[key] = previous

    def test_changed_native_contract_is_rejected(self):
        self.contract += b' '
        with self.assertRaisesRegex(AssertionError, 'pin differs'): self.validate()

    def test_saved_helper_is_rejected_even_if_native_payload_is_well_formed(self):
        put(self.fixture.world, (0, 0), [self.holder, self.knot])
        with self.assertRaisesRegex(AssertionError, 'unexpectedly appears'):
            self.fixture.retain()

    def test_missing_saved_persistent_entity_still_fails(self):
        put(self.fixture.world, (0, 0), [])
        with self.assertRaisesRegex(AssertionError, 'absent'):
            self.fixture.retain()

    def test_replay_checks_helper_data_not_only_claimed_pass(self):
        self.fixture.retain()
        path = self.fixture.evidence / policy.LIFECYCLE
        data = fixtures.read(path)
        data['entities'][0]['leashHolderPosition'][1] = 79
        fixtures.write(path, data)
        with self.assertRaisesRegex(AssertionError, 'persistent holder'):
            replay.replay(self.fixture.evidence)

    def test_replay_rejects_claimed_helper_counts(self):
        self.fixture.retain()
        path = self.fixture.evidence / 'receipt.json'
        data = fixtures.read(path)
        data['helperLifecycle']['helpers'] = {}
        fixtures.write(path, data)
        with self.assertRaisesRegex(AssertionError, 'metadata differs'):
            replay.replay(self.fixture.evidence)


if __name__ == '__main__': unittest.main()
