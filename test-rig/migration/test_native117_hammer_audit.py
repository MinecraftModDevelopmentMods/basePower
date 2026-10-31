from copy import deepcopy
import unittest
from audit_native117_hammer import console_label, validate


def case():
    source = '{Count:1b,id:"electricadvantage:li_ingot"}'
    result = '{Count:1b,id:"electricadvantage:li_powder"}'
    identity = '77102ccd-9ef5-4a98-b1ce-4c759d3c6b17'
    hammer = 'zone.moddev.mc.basemetals.content.CrackhammerItem'
    before = dict(protocol='native117-hammer-query-v1', recipe='electricadvantage:crushing_lithium_ingot',
        sourceUuid=identity, accepted=True, sourceMatchesInput=True, inputNbt=source, expectedNbt=result,
        before=dict(sourceRemoved=False, sourceInAabb=True, sourceIntersectsArea=True, uuidLookupIsSource=True,
            clientSide=False, creative=False, shift=False, sourceNbt=source,
            hammerClass=hammer, hammerDamage=0, hammerNbt='{Count:1b,id:"basemetals:iron_crackhammer",tag:{Damage:0}}',
            dimension='minecraft:overworld', gameTime=20,
            items=[dict(uuid=identity, isSource=True, removed=False, stackNbt=source)]),
        baseSelection=dict(status='MATCH', matched=True, requestedIdPresent=True,
            inputNbt=source, id='electricadvantage:crushing_lithium_ingot',
            resultNbt=result, **{'class':'zone.moddev.mc.basemetals.recipe.CrushingRecipe'}))
    after = deepcopy(before)
    after.update(action='SUCCESS', success=True, sourceConsumed=True, damageIsOne=True,
        after=dict(dimension='minecraft:overworld', gameTime=20, sourceNbt='{}', hammerClass=hammer,
            hammerDamage=1, hammerNbt='{Count:1b,id:"basemetals:iron_crackhammer",tag:{Damage:1}}',
            items=[dict(isSource=False, stackNbt=result)]))
    return before, after


class HammerAuditControls(unittest.TestCase):
    def test_initial_reload_shares_original_console_not_initial_initial(self):
        for mode in ('normal', 'tech_progression', 'apocalyptic'):
            for rubber in ('rubber', 'no-rubber'):
                prefix = mode + '-' + rubber
                self.assertEqual(console_label('progression-' + prefix + '-initial.json'), prefix + '-initial')
                self.assertEqual(console_label('progression-' + prefix + '-initial-reload.json'), prefix + '-initial')
                self.assertEqual(console_label('progression-' + prefix + '-restart.json'), prefix + '-restart')
        for bad in ('progression-normal-rubber-reload.json', 'progression-normal-rubber-initial-initial.json', 'other.json'):
            with self.assertRaises(AssertionError): console_label(bad)

    def test_complete_positive(self):
        self.assertEqual(validate(*case()), ('electricadvantage:crushing_lithium_ingot', 1, 1))

    def test_nine_setup_and_selector_negative_controls(self):
        changes = {
            'addition_rejected': lambda b: b.update(accepted=False),
            'input_altered': lambda b: b.update(sourceMatchesInput=False),
            'query_invisible': lambda b: b['before'].update(sourceInAabb=False),
            'uuid_invisible': lambda b: b['before'].update(uuidLookupIsSource=False),
            'decoy': lambda b: b['before']['items'][0].update(uuid='57e677cb-2bfb-41d1-a314-dd3b2ed72105'),
            'extra_item': lambda b: b['before']['items'].append(deepcopy(b['before']['items'][0])),
            'typed_count_changed': lambda b: b['before'].update(sourceNbt='{Count:1s,id:"electricadvantage:li_ingot"}'),
            'no_match': lambda b: b['baseSelection'].update(status='NO_MATCH', matched=False),
            'observer_error': lambda b: b['baseSelection'].update(status='OBSERVER_ERROR'),
        }
        for name, change in changes.items():
            with self.subTest(name=name):
                before, after = case()
                change(before)
                for key, value in before.items(): after[key] = deepcopy(value)
                with self.assertRaises((AssertionError, KeyError, ValueError)):
                    validate(before, after)

    def test_eight_independent_callback_and_output_failures(self):
        changes = {
            'pass_action': lambda a: a.update(action='PASS'),
            'false_success': lambda a: a.update(success=False),
            'source_not_consumed': lambda a: a.update(sourceConsumed=False),
            'source_raw_remaining': lambda a: a['after'].update(sourceNbt='{Count:1b,id:"electricadvantage:li_ingot"}'),
            'damage_flag_false': lambda a: a.update(damageIsOne=False),
            'raw_damage_two': lambda a: a['after'].update(hammerNbt='{Count:1b,id:"basemetals:iron_crackhammer",tag:{Damage:2}}'),
            'foreign_output': lambda a: a['after']['items'][0].update(stackNbt='{Count:1b,id:"minecraft:dirt"}'),
            'extra_output': lambda a: a['after']['items'][0].update(stackNbt='{Count:2b,id:"electricadvantage:li_powder"}'),
        }
        for name, change in changes.items():
            with self.subTest(name=name):
                before, after = case()
                change(after)
                with self.assertRaises((AssertionError, KeyError, ValueError)):
                    validate(before, after)

    def test_two_custody_failures(self):
        before, after = case()
        after['sourceUuid'] = '57e677cb-2bfb-41d1-a314-dd3b2ed72105'
        with self.assertRaises(AssertionError): validate(before, after)
        before, after = case()
        after['after']['gameTime'] += 1
        with self.assertRaises(AssertionError): validate(before, after)


if __name__ == '__main__':
    unittest.main(verbosity=2)
