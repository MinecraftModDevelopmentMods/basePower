import unittest
from pathlib import Path
from unittest.mock import Mock, patch

import native12111_upgrade as upgrade


class UpgradeReadinessTest(unittest.TestCase):
    def test_every_distinct_machine_chunk_is_loaded_before_observation(self):
        server, runtime = Mock(), Path("disposable-upgrade")
        positions = {"a": (16, 5, -1), "same": (31, 8, -8), "b": (-1, 5, 32)}
        with patch.object(upgrade, "await_ready", return_value={"status": "PASS"}) as ready:
            result = upgrade.load_operating_chunks(server, runtime, positions)
        self.assertEqual(["forceload add -16 32", "forceload add 16 -16"],
                         [call.args[0] for call in server.send.call_args_list])
        self.assertEqual([(-1, 2, -1, 2), (1, -1, 1, -1)],
                         [call.args[2] for call in ready.call_args_list])
        self.assertTrue(all(call.kwargs["minecraft"] == "1.21.11" for call in ready.call_args_list))
        self.assertEqual(2, len(result))

    def test_failed_readiness_is_not_accepted_as_an_operating_chunk(self):
        with patch.object(upgrade, "await_ready", side_effect=AssertionError("not ticking")):
            with self.assertRaisesRegex(AssertionError, "not ticking"):
                upgrade.load_operating_chunks(Mock(), Path("disposable-upgrade"), {"a": (16, 5, -1)})

    def test_missing_machine_coordinates_fail_closed(self):
        with self.assertRaisesRegex(AssertionError, "explicit machine coordinates"):
            upgrade.load_operating_chunks(Mock(), Path("disposable-upgrade"), {})
