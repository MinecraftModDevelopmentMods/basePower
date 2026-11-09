import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

from native117_actors import ready_entities


class ActorReadinessTests(unittest.TestCase):
    def test_observe_in_the_actual_fixture_dimension_before_snapshotting(self):
        with tempfile.TemporaryDirectory() as temporary:
            runtime = Path(temporary)
            for kind, definition, expected in (
                ('actors', {}, 'minecraft:overworld'),
                ('dimensions', {'dimension': -1}, 'minecraft:the_nether'),
                ('dimensions', {'dimension': 1}, 'minecraft:the_end')):
                name = 'actor-fixture.json' if kind == 'actors' else 'dimension-fixture.json'
                (runtime / name).write_text(json.dumps(definition))
                server = Mock()
                with patch('native117_actors.await_ready', return_value={'dimension': expected, 'status': 'PASS'}) as ready:
                    self.assertEqual('PASS', ready_entities(server, runtime, kind, 'converted')['status'])
                server.send.assert_called_once_with('execute in ' + expected + ' run forceload add 96 -32 223 47')
                self.assertEqual((6, -2, 13, 2), ready.call_args.args[2])

    def test_overworld_readiness_cannot_stand_in_for_a_nether_fixture(self):
        with tempfile.TemporaryDirectory() as temporary:
            runtime = Path(temporary)
            (runtime / 'dimension-fixture.json').write_text('{"dimension":-1}')
            with patch('native117_actors.await_ready', return_value={'dimension': 'minecraft:overworld'}):
                with self.assertRaisesRegex(AssertionError, 'wrong dimension'):
                    ready_entities(Mock(), runtime, 'dimensions', 'converted')


if __name__ == '__main__':
    unittest.main()
