"""Strict format-2 partition and native no-save helper corroboration; no worlds are written."""

import base64
import hashlib
import uuid
import nbtlib

import native117_entity_storage as native

CONTRACT = 'native117-helper-contract.json'
CONTRACT_SHA = '59B45FFB7D3CA1F726EFDE111D1716FB0583B092D4F7EAA1A2AE09FA71C7F73F'
POLICY = 'native117-leash-knot-nosave-v1'
CLASS = 'net.minecraft.world.entity.decoration.LeashFenceKnotEntity'
ID = 'minecraft:leash_knot'
LIFECYCLE = 'native117-helper-lifecycle.json'


def count(value, expected, name):
    if type(value) is not int or value != expected:
        raise AssertionError('Incorrect native partition count: ' + name)


def validate(document, contract, lifecycle=None):
    if hashlib.sha256(contract).hexdigest().upper() != CONTRACT_SHA:
        raise AssertionError('Native helper class-control evidence pin differs')
    if document.get('helperPolicy') != POLICY or document.get('helperContractSha256') != CONTRACT_SHA:
        raise AssertionError('Stopping observer helper policy differs from native contract')
    seen, helpers, total, records = set(), {}, 0, {}
    for key, record in document['dimensions'].items():
        entries, excluded = record['entities'], record.get('nonpersistentHelpers')
        if not isinstance(excluded, list):
            raise ValueError('Missing explicit nonpersistent helper partition')
        count(record.get('entityCount'), len(entries), key + '/persistent')
        count(record.get('nonpersistentHelperCount'), len(excluded), key + '/helpers')
        count(record.get('observedEntityCount'), len(entries) + len(excluded), key + '/observed')
        rows = native._snapshots(record)[1] if entries else {}
        for identity in rows:
            if identity in seen:
                raise AssertionError('Duplicate observed UUID across classifications/dimensions')
            if str(rows[identity]['id']) == ID:
                raise AssertionError('No-save helper misclassified as persistent')
            seen.add(identity)
            records[identity] = rows[identity]
        total += len(entries)
        for helper in excluded:
            identity = helper.get('uuid')
            if str(uuid.UUID(identity)) != identity or identity in seen:
                raise AssertionError('Duplicate or noncanonical helper UUID')
            seen.add(identity)
            if (helper.get('id') != ID or helper.get('nativeClass') != CLASS or helper.get('policy') != POLICY
                    or helper.get('worldDimensionKey') != key or helper.get('canSerialize') is not False
                    or helper.get('removed') is not False or helper.get('passenger') is not False):
                raise AssertionError('Unconfirmed native nonpersistent helper type/state')
            count(helper.get('passengerCount'), 0, 'helper passengers')
            tag = native._nbt(native._decompress(base64.b64decode(helper['nbtBase64'], validate=True), 1))
            if native._identity(tag) != identity or str(tag['id']) != ID or 'Passengers' in tag:
                raise AssertionError('Helper attestation differs from complete actual native NBT')
            position = helper.get('attachmentPosition')
            # LeashFenceKnotEntity overrides the HangingEntity additional writer
            # with an empty method. Its native bounding-box recalculation writes
            # Pos at attachment + (0.5, 0.375, 0.5); TileX/Y/Z are not serialized.
            native_pos = tag.get('Pos')
            if (not isinstance(position, list) or len(position) != 3 or any(type(v) is not int for v in position)
                    or not isinstance(native_pos, nbtlib.List) or native_pos.subtype is not nbtlib.Double
                    or len(native_pos) != 3 or any(name in tag for name in ('TileX', 'TileY', 'TileZ'))
                    or list(map(float, native_pos)) != [position[0] + 0.5, position[1] + 0.375, position[2] + 0.5]):
                raise AssertionError('Helper native attachment coordinates differ')
            helpers[identity] = dict(dimension=key, attachmentPosition=position)
    count(document.get('entityCount'), total, 'total persistent')
    count(document.get('nonpersistentHelperCount'), len(helpers), 'total helpers')
    count(document.get('observedEntityCount'), total + len(helpers), 'total observed')
    if not helpers:
        if lifecycle is not None:
            raise ValueError('Unexpected helper lifecycle evidence without helpers')
        return dict(policy=POLICY, contractSha256=CONTRACT_SHA, status='NOT_REQUIRED', helpers={})
    if not isinstance(lifecycle, dict):
        raise AssertionError('Missing separate actual dimension helper lifecycle observation')
    key = lifecycle.get('worldDimensionKey')
    record = document['dimensions'].get(key)
    if (record is None or type(lifecycle.get('gameTime')) is not int
            or not 0 <= lifecycle['gameTime'] <= record['gameTime']
            or lifecycle.get('nativeOnLoad') is not True or lifecycle.get('nativeEntityContextVersion') != 1):
        raise AssertionError('Helper lifecycle belongs to another dimension/time/native context')
    derived = {}
    for identity, helper in helpers.items():
        if helper['dimension'] != key:
            raise AssertionError('No actual lifecycle observation for helper dimension')
        matches = []
        for row in lifecycle.get('entities', []):
            if row.get('leashHolderUuid') != identity:
                continue
            holder = records.get(row.get('uuid'))
            if (holder is None or row.get('worldDimensionKey') != key or row.get('sameWorld') is not True
                    or row.get('leashHolderType') != ID or row.get('leashHolderPosition') != helper['attachmentPosition']
                    or 'Leash' not in holder
                    or [int(holder['Leash'][name]) for name in ('X', 'Y', 'Z')] != helper['attachmentPosition']):
                raise AssertionError('Helper lifecycle is not corroborated by actual persistent holder NBT')
            matches.append(row['uuid'])
        if not matches:
            raise AssertionError('No actual persistent holder for observed non-saving helper')
        derived[identity] = dict(**helper, holderUuids=sorted(matches))
    return dict(policy=POLICY, contractSha256=CONTRACT_SHA, status='PASS_NATIVE_HELPER_LIFECYCLE', helpers=derived,
                observationGameTime=lifecycle['gameTime'])


def raw_helpers_absent(files, helpers):
    """Reparse retained regions; no no-save helper may masquerade as saved proof."""
    for name, data in files.items():
        if '/regions/' not in name:
            continue
        rx, rz = map(int, native.REGION_NAME.fullmatch(name.rsplit('/', 1)[1]).groups())
        for coordinates, payload in native._payloads(data, rx, rz):
            for root in native._entity_chunk(payload, coordinates):
                for identity, tag, _ in native._tree(root):
                    if identity in helpers or str(tag['id']) == ID:
                        raise AssertionError('Native no-save helper unexpectedly appears in saved entity storage')
