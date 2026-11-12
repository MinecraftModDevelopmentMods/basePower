from pathlib import Path
import tempfile
import unittest

import lifecycle
from native117_identity import fixture_ids
from native117_lifecycle import NativeLifecycleRun, exercise
import native1201_machines
import native2612_machines as machines


class NativeMachineAdapterTest(unittest.TestCase):
    def test_harvest_reuses_the_old_oracle_without_mutating_its_module(self):
        self.assertIs(machines.harvest.__globals__["native1201"].Server, machines.Server)
        self.assertEqual(machines.harvest.__globals__["native1201"].MINECRAFT, machines.native.MINECRAFT)
        self.assertIsNot(native1201_machines.harvest.__globals__["native1201"], machines.native)
        self.assertIs(machines.harvest.__globals__["require_checks"],
                      native1201_machines.harvest.__globals__["require_checks"])
        self.assertIs(machines.harvest.__globals__["require_isolation"],
                      native1201_machines.harvest.__globals__["require_isolation"])

    def test_lifecycle_keeps_all_twelve_moves_and_three_processing_networks(self):
        self.assertTrue(issubclass(machines.LifecycleRun, NativeLifecycleRun))
        self.assertIs(machines.exercise, exercise)
        rows = fixture_ids(lifecycle.fixtures())
        self.assertEqual(15, len(rows))
        for prefix in ("S-MOVE-", "E-MOVE-"):
            selected = [row for row in rows if row["id"].startswith(prefix)]
            self.assertEqual(6, len(selected))
            self.assertEqual({"down", "up", "north", "south", "west", "east"},
                             {row["facing"] for row in selected})
        self.assertEqual({"C-STEAM", "C-ELECTRIC", "C-FLUID"}, {row["id"] for row in rows if "from" not in row})

    def test_boot_identity_rejects_historical_engines_and_missing_native_markers(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "console.log"
            valid = ("Starting minecraft server version 26.1.2\n"
                     "Forge mod loading, version 64.1.5, for MC 26.1.2 with MCP 20260409.101008\n")
            log.write_text(valid, encoding="utf-8")
            machines.native_boot(log)
            for wrong in (valid.replace("26.1.2", "1.21.11"), valid.replace("64.1.5", "64.0.9"),
                          "Starting minecraft server version 26.1.2\n",
                          valid + "[00:00:00] [main/ERROR] Resource failure\n"):
                log.write_text(wrong, encoding="utf-8")
                with self.assertRaises(AssertionError):
                    machines.native_boot(log)

    def test_native_lifecycle_configuration_does_not_redirect_to_old_java_or_launcher(self):
        self.assertEqual("26.1.2", machines.LifecycleRun.minecraft)
        self.assertEqual(11, machines.LifecycleRun.max_ticket_distance)
        self.assertEqual("25.0.3+9", machines.native.JAVA_VERSION)
        self.assertIn("26.1.2-64.1.5", machines.native.LAUNCHER)


if __name__ == "__main__":
    unittest.main()
