import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import native117_respawn as requests
import native117_audit_respawn as audit


class NativeRespawnRequestTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.runtime = Path(self.temp.name)
        self.definition = {'dimension': 1, 'playerName': 'Witness',
                           'playerUuid': 'e036830e-303d-3ac3-bd23-062b72eed895',
                           'geometry': {'x': 176}, 'respawn': {'retained': 'base-only'}}
        self.base = self.runtime / 'dimension-fixture.json'
        self.base.write_text(json.dumps(self.definition) + '\n', encoding='utf-8')
        self.original = self.base.read_bytes()

    def publish(self, label='respawn-keep-bed', keep=True, mode='bed'):
        return requests.publish_request(self.runtime, label, keep, mode)

    def record(self, binding):
        definition = copy.deepcopy(self.definition)
        definition['respawn'].update(keepInventory=binding['keepInventory'], bedMode=binding['bedMode'])
        return {'definition': definition, 'operation': 'respawn', 'respawnRequest': binding,
                'respawn': {'keepInventory': binding['keepInventory'], 'bedMode': binding['bedMode']}}

    def test_request_is_closed_and_hash_bound_without_touching_root(self):
        binding = self.publish()
        path = self.runtime / binding['path']
        payload = json.loads(path.read_bytes())
        self.assertEqual({'protocol', 'id', 'baseFixtureSha256', 'keepInventory', 'bedMode'}, set(payload))
        self.assertEqual('native117-respawn-request-v1', payload['protocol'])
        self.assertEqual('respawn-keep-bed', payload['id'])
        self.assertIs(True, payload['keepInventory'])
        self.assertEqual('bed', payload['bedMode'])
        self.assertEqual(hashlib.sha256(self.original).hexdigest().upper(), payload['baseFixtureSha256'])
        self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest().upper(), binding['sha256'])
        self.assertEqual(self.original, self.base.read_bytes())
        self.assertEqual(binding, requests.verify_request(self.runtime, payload['id'], self.record(binding), binding))

    def test_publishing_does_not_replace_any_existing_file(self):
        with self.base.open('rb'), patch.object(Path, 'replace', side_effect=AssertionError('No file replacement')):
            self.publish()
        self.assertEqual(self.original, self.base.read_bytes())

    def test_duplicate_case_is_refused_even_when_bytes_would_match(self):
        first = self.publish()
        raw = (self.runtime / first['path']).read_bytes()
        with self.assertRaises(FileExistsError):
            self.publish()
        self.assertEqual(raw, (self.runtime / first['path']).read_bytes())
        self.assertEqual(self.original, self.base.read_bytes())

    def test_labels_options_and_boolean_types_must_agree(self):
        for label, keep, mode in (('../escape', True, 'bed'), ('respawn-keep-bed.json', True, 'bed'),
                                  ('respawn-drop-bed', True, 'bed'), ('respawn-keep-forced', True, 'bed'),
                                  ('respawn-keep-bed', 1, 'bed'), ('respawn-keep-bed', True, 'other')):
            with self.subTest(label=label, keep=keep, mode=mode), self.assertRaises((ValueError, AssertionError)):
                self.publish(label, keep, mode)
        self.assertEqual(self.original, self.base.read_bytes())

    def test_changed_base_fixture_invalidates_published_request(self):
        binding = self.publish()
        self.base.write_bytes(self.original + b' ')
        with self.assertRaises((ValueError, AssertionError)):
            requests.verify_request(self.runtime, binding['id'], self.record(binding), binding)

    def test_changed_request_bytes_are_not_accepted_by_native_ack(self):
        binding = self.publish()
        path = self.runtime / binding['path']
        path.write_bytes(path.read_bytes() + b' ')
        with self.assertRaises((ValueError, AssertionError)):
            requests.verify_request(self.runtime, binding['id'], self.record(binding), binding)

    def test_unknown_and_duplicate_request_keys_are_rejected(self):
        binding = self.publish()
        path = self.runtime / binding['path']
        original = path.read_bytes()
        payload = json.loads(original)
        payload['dimension'] = -1
        for raw in (json.dumps(payload).encode(), original.rstrip()[:-1] + b', "keepInventory": false}'):
            with self.subTest(raw=raw):
                path.write_bytes(raw)
                with self.assertRaises((ValueError, AssertionError)):
                    requests.verify_request(self.runtime, binding['id'], self.record(binding), binding)

    def test_native_ack_cannot_claim_another_request_or_option(self):
        binding = self.publish()
        for key, value in (('sha256', '0' * 64), ('id', 'respawn-drop-bed'), ('path', '../outside.json'),
                           ('protocol', 'other-v1'), ('baseFixtureSha256', 'F' * 64),
                           ('keepInventory', 1), ('bedMode', 'forced')):
            record = self.record(copy.deepcopy(binding))
            record['respawnRequest'][key] = value
            with self.subTest(key=key), self.assertRaises((ValueError, AssertionError)):
                requests.verify_request(self.runtime, binding['id'], record, binding)

    def test_native_definition_only_merges_the_two_validated_options(self):
        binding = self.publish()
        for mutate in (lambda value: value['definition'].update(dimension=-1),
                       lambda value: value['definition'].update(playerUuid='other'),
                       lambda value: value['definition']['respawn'].update(retained='changed'),
                       lambda value: value['respawn'].update(keepInventory=False),
                       lambda value: value['respawn'].update(bedMode='missing')):
            record = self.record(binding)
            mutate(record)
            with self.assertRaises((ValueError, AssertionError)):
                requests.verify_request(self.runtime, binding['id'], record, binding)

    def test_request_directory_requires_exact_regular_case_files(self):
        binding = self.publish()
        self.assertEqual({binding['id']: binding}, requests.verify_request_files(self.runtime, [binding['id']]))
        extra = self.runtime / 'respawn-requests/orphan.json'
        extra.write_text('{}')
        with self.assertRaises((ValueError, AssertionError)):
            requests.verify_request_files(self.runtime, [binding['id']])
        extra.unlink()
        with self.assertRaises((ValueError, AssertionError)):
            requests.verify_request_files(self.runtime, [binding['id'], 'respawn-drop-forced'])

    def test_runner_publishes_before_command_and_never_rewrites_root(self):
        source = Path(requests.__file__).read_text()
        self.assertNotIn('publish_definition(', source)
        self.assertNotIn('definition["respawn"] =', source)
        self.assertLess(source.index('binding = publish_request('), source.index('server.send("advdimensions respawn '))

    def test_independent_auditor_checks_request_inventory_not_only_status(self):
        self.assertTrue(callable(getattr(audit, 'request_integrity', None)))
        source = Path(audit.__file__).read_text()
        self.assertIn('request_integrity(root, sources, results)', source)
        self.assertIn('requestProtocol', source)

    def campaign(self):
        root = self.runtime / 'campaign'
        root.mkdir()
        source = self.runtime / 'protected-source'
        source.mkdir()
        (source / 'dimension-fixture.json').write_bytes(self.original)
        runtime = root / 'original110'
        runtime.mkdir()
        (runtime / 'dimension-fixture.json').write_bytes(self.original)
        index = {'protocol': requests.REQUEST_PROTOCOL, 'cases': {}}
        results = {}
        for label in requests.CASE_LABELS:
            keep, mode = requests.case_options(label)
            binding = requests.publish_request(runtime, label, keep, mode)
            record = self.record(binding)
            path = runtime / ('dimensions-' + label + '.json')
            path.write_text(json.dumps(record))
            case = 'original110/' + label
            index['cases'][case] = {'request': binding, 'recordSha256': requests.digest(path)}
            results[case] = {'status': 'PASS'}
            evidence = runtime / 'respawn-evidence' / label
            evidence.mkdir(parents=True)
            (evidence / 'request.json').write_bytes((runtime / binding['path']).read_bytes())
        (root / requests.REQUEST_INDEX).write_text(json.dumps(index))
        sources = {'original110': {'path': str(source), 'baseFixtureSha256': requests.digest(source / 'dimension-fixture.json')}}
        return root, runtime, sources, results, index

    def test_independent_inventory_verifies_all_twelve_case_bindings(self):
        root, runtime, sources, results, index = self.campaign()
        proof = audit.request_integrity(root, sources, results)
        self.assertEqual([], proof['findings'])
        self.assertEqual(12, proof['expectedCases'])
        self.assertEqual(12, proof['verifiedCases'])
        self.assertEqual(set(results), set(proof['bindings']))

    def test_independent_inventory_rejects_extra_request_files(self):
        root, runtime, sources, results, index = self.campaign()
        (runtime / 'respawn-requests/orphan.json').write_text('{}')
        self.assertTrue(audit.request_integrity(root, sources, results)['findings'])

    def test_independent_inventory_rejects_changed_root_and_missing_case(self):
        root, runtime, sources, results, index = self.campaign()
        (runtime / 'dimension-fixture.json').write_bytes(self.original + b' ')
        results.pop(next(iter(results)))
        self.assertTrue(audit.request_integrity(root, sources, results)['findings'])

    def test_independent_inventory_rejects_an_altered_request_evidence_copy(self):
        root, runtime, sources, results, index = self.campaign()
        label = requests.CASE_LABELS[0]
        path = runtime / 'respawn-evidence' / label / 'request.json'
        path.write_bytes(path.read_bytes() + b' ')
        proof = audit.request_integrity(root, sources, results)
        self.assertTrue(proof['findings'])
        self.assertEqual(11, proof['verifiedCases'])

    def test_independent_inventory_rejects_native_record_changes_even_if_rehashed(self):
        root, runtime, sources, results, index = self.campaign()
        label = requests.CASE_LABELS[0]
        path = runtime / ('dimensions-' + label + '.json')
        record = json.loads(path.read_bytes())
        record['definition']['playerUuid'] = 'another-player'
        path.write_text(json.dumps(record))
        index['cases']['original110/' + label]['recordSha256'] = requests.digest(path)
        (root / requests.REQUEST_INDEX).write_text(json.dumps(index))
        proof = audit.request_integrity(root, sources, results)
        self.assertTrue(proof['findings'])
        self.assertEqual(11, proof['verifiedCases'])

    def test_empty_request_inventory_and_legacy_protocol_cannot_qualify(self):
        (self.runtime / requests.REQUEST_INDEX).write_text(json.dumps({'protocol': 'legacy', 'cases': {}}))
        proof = audit.request_integrity(self.runtime, {}, {})
        self.assertTrue(proof['findings'])
        self.assertEqual(0, proof['verifiedCases'])


if __name__ == '__main__':
    unittest.main()
