import unittest
from unittest.mock import Mock, call

from native12111_endurance import stop_at_checkpoint


class EnduranceStoppedCheckpointTest(unittest.TestCase):
    def test_freezes_before_observation_and_keeps_frozen_until_saved_and_stopped(self):
        rig = Mock()
        rig.structure.return_value = {"W-01": "MANUAL_REQUIRED"}
        self.assertEqual({"W-01": "MANUAL_REQUIRED"}, stop_at_checkpoint(rig))
        self.assertEqual([
            call.command("tick freeze", "The game is frozen"),
            call.checkpoint("restart-resumed"),
            call.batch("restart-resumed"),
            call.structure(),
            call.command("save-all flush", "Saved the game"),
            call.shutdown(),
        ], rig.mock_calls)

    def test_failed_tick_gate_cannot_produce_a_checkpoint_or_successful_save(self):
        rig = Mock()
        rig.command.side_effect = RuntimeError("tick gate refused")
        with self.assertRaisesRegex(RuntimeError, "tick gate refused"):
            stop_at_checkpoint(rig)
        rig.checkpoint.assert_not_called()
        rig.batch.assert_not_called()
        rig.structure.assert_not_called()
        rig.shutdown.assert_not_called()

    def test_failed_measurement_cannot_be_reported_as_a_completed_save(self):
        rig = Mock()
        rig.batch.side_effect = AssertionError("wrong item total")
        with self.assertRaisesRegex(AssertionError, "wrong item total"):
            stop_at_checkpoint(rig)
        self.assertEqual([
            call.command("tick freeze", "The game is frozen"),
            call.checkpoint("restart-resumed"),
            call.batch("restart-resumed"),
        ], rig.mock_calls)


if __name__ == "__main__":
    unittest.main()
