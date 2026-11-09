import unittest

from native117_commands import native


class TaggedFixtureCommands(unittest.TestCase):
    def test_typed_witness_payload_is_preserved_exactly(self):
        items = (
            'minecraft:dirt{AdvWorksWitness:"conveyor-north"}',
            'minecraft:diamond{Nested:{Items:[{id:"minecraft:stone",Count:2b}]}}',
            'minecraft:stone{display:{Name:\'{"text":"A test witness"}\'}}',
        )
        for item in items:
            with self.subTest(item=item):
                self.assertEqual('item replace block -14 8 230 container.26 with ' + item + ' 7',
                    native('replaceitem block -14 8 230 container.26 ' + item + ' 7'))

    def test_malformed_payload_is_not_forwarded_to_the_server(self):
        for item in ('minecraft:dirt{Count:2b', 'minecraft:dirt{Count:}',
                     'minecraft:dirt{Count:2b} unexpected', 'minecraft:dirt[Count=2]'):
            with self.subTest(item=item), self.assertRaises(ValueError):
                native('replaceitem block 1 2 3 container.0 ' + item + ' 7')


if __name__ == '__main__':
    unittest.main()
