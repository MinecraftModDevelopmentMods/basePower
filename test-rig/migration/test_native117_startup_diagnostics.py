import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

import native117_code
import native117_startup_diagnostics as diagnostics


class StartupDiagnosticsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.java = self.root / "java.exe"
        self.jcmd = self.root / "jcmd.exe"
        self.java.touch()
        self.jcmd.touch()
        self.process = Mock(pid=12345)
        self.process.poll.return_value = None
        self.failure = TimeoutError("original startup deadline")

    def test_live_process_gets_only_read_only_bounded_thread_dump(self):
        with patch.object(diagnostics.subprocess, "run", return_value=Mock(returncode=0, stdout=b"raw dump")) as run:
            result = diagnostics.observe(self.root, self.java, self.process, "boot-1", self.failure)
        self.assertEqual(result["status"], "STARTUP_FAILURE_NOT_QUALIFIED")
        self.assertFalse(result["startupRetry"])
        self.assertFalse(result["productionMutation"])
        self.assertEqual(result["threadDumpStatus"], "CAPTURED")
        self.assertEqual(Path(result["threadDump"]).read_bytes(), b"raw dump")
        self.assertEqual(run.call_args.args[0], [str(self.jcmd), "12345", "Thread.print", "-l"])
        self.assertEqual(run.call_args.kwargs["timeout"], 20)
        self.process.kill.assert_not_called()
        self.process.terminate.assert_not_called()
        self.process.wait.assert_not_called()

    def test_exited_process_never_invokes_jcmd(self):
        self.process.poll.return_value = 1
        with patch.object(diagnostics.subprocess, "run") as run:
            result = diagnostics.observe(self.root, self.java, self.process, "exited", self.failure)
        self.assertEqual(result["threadDumpStatus"], "PROCESS_ALREADY_EXITED")
        run.assert_not_called()

    def test_missing_jcmd_never_substitutes_another_executable(self):
        self.jcmd.unlink()
        with patch.object(diagnostics.subprocess, "run") as run:
            result = diagnostics.observe(self.root, self.java, self.process, "missing", self.failure)
        self.assertEqual(result["threadDumpStatus"], "JCMD_UNAVAILABLE")
        run.assert_not_called()

    def test_jcmd_timeout_retains_partial_bytes_without_retry(self):
        with patch.object(diagnostics.subprocess, "run", side_effect=subprocess.TimeoutExpired(["jcmd"], 20, output=b"partial")) as run:
            result = diagnostics.observe(self.root, self.java, self.process, "slow", self.failure)
        self.assertEqual(result["threadDumpStatus"], "JCMD_TIMED_OUT")
        self.assertEqual(Path(result["threadDump"]).read_bytes(), b"partial")
        self.assertEqual(run.call_count, 1)

    def test_failed_jcmd_is_not_a_success_or_startup_pass(self):
        with patch.object(diagnostics.subprocess, "run", return_value=Mock(returncode=1, stdout=b"attach failed")):
            result = diagnostics.observe(self.root, self.java, self.process, "failed", self.failure)
        self.assertEqual(result["threadDumpStatus"], "JCMD_FAILED")
        self.assertEqual(result["threadDumpExitCode"], 1)
        self.assertEqual(result["status"], "STARTUP_FAILURE_NOT_QUALIFIED")

    def test_existing_dump_is_never_overwritten(self):
        path = self.root / "startup-thread-existing.txt"
        path.write_bytes(b"retained")
        with patch.object(diagnostics.subprocess, "run") as run:
            with self.assertRaises(FileExistsError):
                diagnostics.observe(self.root, self.java, self.process, "existing", self.failure)
        self.assertEqual(path.read_bytes(), b"retained")
        run.assert_not_called()

    def test_unsafe_label_fails_before_process_or_file_access(self):
        with self.assertRaises(ValueError):
            diagnostics.observe(self.root, self.java, self.process, "../escape", self.failure)
        self.process.poll.assert_not_called()

    def test_primary_and_cleanup_failures_are_both_retained(self):
        path = diagnostics.retain(self.root, "both", dict(failure="primary"), RuntimeError("cleanup"))
        record = json.loads(path.read_text())
        self.assertEqual(record["failure"], "primary")
        self.assertEqual(record["cleanupFailure"]["message"], "cleanup")
        with self.assertRaises(FileExistsError):
            diagnostics.retain(self.root, "both", dict(failure="replacement"))

    def test_retention_rejects_path_escape(self):
        with self.assertRaises(ValueError):
            diagnostics.retain(self.root, "../escape", {})

    def launch_failure(self, failure, cleanup=None, observation=None):
        logging = self.root / "libraries/net/minecraftforge/fmlloader/1.17.1-37.1.1/fmlloader-1.17.1-37.1.1.jar"
        logging.parent.mkdir(parents=True, exist_ok=True)
        logging.touch()
        def close(server):
            server.log.close()
            if cleanup:
                raise cleanup
        with patch.object(native117_code.subprocess, "Popen", return_value=self.process) as launch, \
                patch.object(native117_code.threading, "Thread"), \
                patch.object(native117_code.NativeLaunch, "wait", side_effect=failure) as wait, \
                patch.object(native117_code.NativeLaunch, "close", autospec=True, side_effect=close) as stop, \
                patch.object(diagnostics, "observe", side_effect=observation, return_value={}) as observe, \
                patch.object(diagnostics, "retain") as retain:
            try:
                native117_code.NativeLaunch(self.root, self.java, "native-args", "startup")
            except BaseException as error:
                self.assertIs(error, failure)
            else:
                self.fail("Startup failure became success")
        self.assertEqual(launch.call_count, 1)
        wait.assert_called_once_with("Done (", 300)
        self.assertEqual(stop.call_count, 1)
        return observe, retain

    def test_cleanup_error_does_not_mask_original_timeout(self):
        cleanup = RuntimeError("failed shutdown")
        observe, retain = self.launch_failure(self.failure, cleanup)
        self.assertEqual(observe.call_count, 1)
        self.assertIs(retain.call_args.args[3], cleanup)
        self.assertIn("Startup cleanup also failed", " ".join(getattr(self.failure, "__notes__", ())))

    def test_diagnostic_error_does_not_mask_original_timeout(self):
        observe, retain = self.launch_failure(self.failure, observation=RuntimeError("capture failed"))
        retain.assert_not_called()
        self.assertIn("Startup diagnostic observation failed", " ".join(getattr(self.failure, "__notes__", ())))

    def test_user_cancellation_is_not_delayed_by_a_thread_dump(self):
        observe, retain = self.launch_failure(KeyboardInterrupt())
        observe.assert_not_called()
        retain.assert_not_called()


if __name__ == "__main__":
    unittest.main()
