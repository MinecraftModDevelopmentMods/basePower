"""Replay exact invocation telemetry; never launch or repair a world."""
import argparse
from collections import Counter, defaultdict
import json
from pathlib import Path
import re
import sys
from uuid import UUID

sys.path.insert(0, str(Path(__file__).resolve().parent))
import nbtlib
from matrix import digest, fingerprint
from native117_redstone_reload import typed


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def tag(text):
    value = nbtlib.parse_nbt(text)
    require(isinstance(value, nbtlib.Compound), 'Stack must be a native compound')
    return value


def console_label(report_name):
    require(re.fullmatch(r'progression-(normal|tech_progression|apocalyptic)-(rubber|no-rubber)-(initial|initial-reload|restart)\.json', report_name),
            'Unknown progression report label')
    return report_name.removeprefix('progression-').removesuffix('.json').removesuffix('-reload')


def validate(before, after):
    require(before['protocol'] == 'native117-hammer-query-v1', 'Unknown protocol')
    UUID(before['sourceUuid'])
    for key, value in before.items():
        require(after.get(key) == value, 'Before facts changed: ' + key)
    require(before['accepted'] is True and before['sourceMatchesInput'] is True, 'Source setup rejected or changed')
    sample = before['before']
    require(sample['sourceRemoved'] is False and sample['sourceInAabb'] is True
            and sample['sourceIntersectsArea'] is True and sample['uuidLookupIsSource'] is True,
            'Source is not the exact query-visible object')
    require(sample['clientSide'] is False and sample['creative'] is False and sample['shift'] is False,
            'Wrong callback environment')
    require(sample['hammerClass'] == 'zone.moddev.mc.basemetals.content.CrackhammerItem', 'Wrong native hammer')
    require(len(sample['items']) == 1, 'Contaminated or empty source query')
    source = sample['items'][0]
    require(source['uuid'] == before['sourceUuid'] and source['isSource'] is True
            and source['removed'] is False, 'Decoy source')
    requested = tag(before['inputNbt'])
    require(typed(requested) == typed(tag(sample['sourceNbt']))
            == typed(tag(source['stackNbt'])), 'Input type, quantity or NBT changed')
    selection = before['baseSelection']
    require(selection['status'] == 'MATCH' and selection['matched'] is True, 'No positive native selector observation')
    require(typed(tag(selection['inputNbt'])) == typed(requested), 'Selector observed another input')
    require(selection['class'] == 'zone.moddev.mc.basemetals.recipe.CrushingRecipe', 'Wrong native recipe type')
    require(selection['requestedIdPresent'] is True, 'Requested recipe was absent')
    require(after['action'] == 'SUCCESS' and after['success'] is True, 'Hammer did not return SUCCESS')
    require(after['sourceConsumed'] is True and after['damageIsOne'] is True, 'Debit assertion failed')
    final = after['after']
    require(final['dimension'] == sample['dimension'] and final['gameTime'] == sample['gameTime'],
            'Callback crossed worlds or native ticks')
    require(final['hammerClass'] == sample['hammerClass'], 'Hammer class changed during invocation')
    require(int(tag(final['sourceNbt']).get('Count', 0)) == 0, 'Source still contains items')
    require(sample['hammerDamage'] == 0 and final['hammerDamage'] == 1, 'Wrong damage debit')
    require(int(tag(sample['hammerNbt'])['tag']['Damage']) == 0
            and int(tag(final['hammerNbt'])['tag']['Damage']) == 1, 'Raw hammer NBT disagrees with damage')
    expected = tag(before['expectedNbt'])
    selected = tag(selection['resultNbt'])
    require(str(selected['id']) == str(expected['id']) and int(selected['Count']) == int(expected['Count']),
            'Native recipe selector reports another result')
    produced = 0
    for item in final['items']:
        stack = tag(item['stackNbt'])
        if item['isSource'] or int(stack.get('Count', 0)) == 0:
            continue
        require(str(stack['id']) == str(expected['id']), 'Foreign output')
        produced += int(stack['Count'])
    require(produced == int(expected['Count']), 'Wrong exact output count')
    return (before['recipe'], int(requested['Count']), produced)


def audit(runtime, output):
    require(not output.exists(), 'Refusing to overwrite audit')
    before_files = fingerprint(runtime)
    report_file = runtime / 'native117-recipes-results.json'
    report = json.loads(report_file.read_text(encoding='utf-8-sig'))
    require(report['status'] == 'PASS' and len(report['progression_cases']) == 18, 'Incomplete recipe campaign')
    expected = defaultdict(Counter)
    inputs = {str(report_file): digest(report_file)}
    for case in report['progression_cases']:
        require(case['status'] == 'PASS', 'Failed progression phase')
        result_file = runtime / 'progression' / case['report']
        result = json.loads(result_file.read_text(encoding='utf-8-sig'))
        require(result['status'] == 'PASS' and not result['blocked'], 'Blocked manufacturing')
        inputs[str(result_file)] = digest(result_file)
        label = console_label(case['report'])
        console = runtime / 'progression' / ('console-' + label + '.log')
        for row in result['processWitnesses']:
            if row['operation'].startswith('live_hammer:'):
                expected[console][(row['operation'].removeprefix('live_hammer:'), row['input'], row['output'])] += 1
    seen = set()
    observations = []
    for console, required in expected.items():
        pending = {}
        actual = Counter()
        inputs[str(console)] = digest(console)
        for line in console.read_text(encoding='utf-8-sig').splitlines():
            if 'ADVHAMMER BEFORE ' in line:
                data = json.loads(line.split('ADVHAMMER BEFORE ', 1)[1])
                identity = data['sourceUuid']
                require(identity not in seen, 'Reused source receipt')
                seen.add(identity)
                pending[identity] = data
            elif 'ADVHAMMER AFTER ' in line:
                data = json.loads(line.split('ADVHAMMER AFTER ', 1)[1])
                require(data['sourceUuid'] in pending, 'Unpaired or duplicate callback result')
                source = pending.pop(data['sourceUuid'])
                actual[validate(source, data)] += 1
                observations.append(dict(console=str(console), sourceUuid=data['sourceUuid'],
                    recipe=data['recipe'], selected=data['baseSelection']['id'],
                    entityTicking=data['before']['entityTicking']))
        require(not pending, 'Missing callback result')
        require(actual == required, 'Invocation telemetry does not cover exact manufacturing witnesses')
    require(observations, 'No hammer invocation proof')
    require(fingerprint(runtime) == before_files, 'Stopped inputs changed during audit')
    result = dict(status='PASS', scope='Read-only native hammer invocation telemetry and exact debit/output replay; not a diagnosis of historical O failure',
        calls=len(observations), observations=observations, sourceUnchanged=True, inputs=inputs,
        originalOFailureExplained=False, gameplayRepairClaimed=False)
    with output.open('x', encoding='utf-8') as stream:
        json.dump(result, stream, indent=2, sort_keys=True)
    print('PASS:', len(observations), 'single native hammer invocations independently replayed')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    audit(args.run.resolve(), args.output.resolve())
