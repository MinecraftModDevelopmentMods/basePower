"""Bounded process/custody negatives plus the actual selected SDK logging source."""

import json
import os
from pathlib import Path
import socket
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile

import native263 as native


SDK = Path(os.environ.get("NATIVE263_TEST_SDK",
           "D:/PowerAdvantage/_verification/base-263-port-20261112/server-seed-66.0.0"))
LOGGING = SDK / f"libraries/net/minecraftforge/fmlloader/{native.FORGE_VERSION}/fmlloader-{native.FORGE_VERSION}.jar"


class Native263GuardsTest(unittest.TestCase):
    def test_native_single_slot_ack_is_exact_not_any_count(self):
        server = object.__new__(native.Server)
        with patch.object(native.NativeLaunch, "wait") as wait:
            server.wait("Replaced a slot", 30)
            wait.assert_called_once_with("Replaced 1 slot(s) at ", 30)
            wait.reset_mock()
            server.wait("Replaced 2 slot(s) at ", 30)
            wait.assert_called_once_with("Replaced 2 slot(s) at ", 30)

    def test_native_gamerule_assignment_requires_exact_ack_and_state_query(self):
        server = object.__new__(native.Server)
        with patch.object(native.NativeLaunch, "send") as send, patch.object(native.NativeLaunch, "wait") as wait:
            server.send("gamerule doMobSpawning false")
            self.assertEqual([("gamerule minecraft:spawn_mobs false",), ("gamerule minecraft:spawn_mobs",)],
                             [call.args for call in send.call_args_list])
            self.assertEqual([("Game rule spawn_mobs is now set to false", 15),
                              ("Game rule spawn_mobs is currently set to false", 15)],
                             [call.args for call in wait.call_args_list])

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()

    def test_all_inherited_option_channels_fail(self):
        for key in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH"):
            with self.subTest(key=key), patch.dict(os.environ, {key: "injected"}, clear=True):
                with self.assertRaises(ValueError):
                    native.native_launch_environment()

    def test_empty_environment_is_not_given_an_agent(self):
        with patch.dict(os.environ, {}, clear=True):
            self.assertEqual({}, native.native_launch_environment())

    def test_wrong_java_hash_fails_before_execution(self):
        executable = self.root / "java.exe"
        executable.write_bytes(b"wrong Java")
        with patch.object(native.subprocess, "run") as run:
            with self.assertRaises(AssertionError):
                native.java25(executable)
            run.assert_not_called()

    def test_hash_pin_is_required_and_not_coerced(self):
        file = self.root / "input"
        file.write_bytes(b"exact")
        for sha in (None, "", "pending", "0" * 63):
            with self.subTest(sha=sha), self.assertRaises(ValueError):
                native.validate_hash(file, sha)
        with self.assertRaises(AssertionError):
            native.validate_hash(file, "0" * 64)
        native.validate_hash(file, native.digest(file))

    def test_duplicate_document_fields_fail_closed(self):
        path = self.root / "profile.json"
        path.write_text('{"mods":[],"mods":["hidden"]}')
        with self.assertRaises(ValueError):
            native.read_json(path)

    def test_missing_family_mod_does_not_create_runtime(self):
        runtime = self.root / "not-created"
        for missing in native.VERSIONS:
            mods = {key: (version, self.root / (key + ".jar"), "0" * 64)
                    for key, version in native.VERSIONS.items() if key != missing}
            with self.subTest(missing=missing), patch.dict(os.environ, {}, clear=True), self.assertRaises(ValueError):
                native.stage(runtime, self.root, {}, mods, 26660)
            self.assertFalse(runtime.exists())

    def test_occupied_port_does_not_get_reclaimed(self):
        with socket.socket() as occupied:
            occupied.bind(("127.0.0.1", 0))
            occupied.listen()
            with self.assertRaises(ValueError):
                native.require_free_port(occupied.getsockname()[1])
            self.assertGreater(occupied.fileno(), -1)

    def test_invalid_ports_fail_without_coercion(self):
        for port in (True, "26660", 80, 65536, -1):
            with self.subTest(port=port), self.assertRaises(ValueError):
                native.require_free_port(port)

    def settings(self, value):
        (self.root / "server.properties").write_text(value)

    def test_pause_guard_is_explicit(self):
        for pause in ("", "pause-when-empty-seconds=0\n", "pause-when-empty-seconds=60\n"):
            self.settings("server-ip=127.0.0.1\nserver-port=26660\n" + pause)
            with self.subTest(pause=pause), self.assertRaises(ValueError):
                native.require_launch_settings(self.root)

    def test_duplicate_settings_fail_before_port_probe(self):
        self.settings("server-port=26660\nserver-port=26661\n")
        with patch.object(native, "require_free_port") as probe:
            with self.assertRaises(ValueError):
                native.require_launch_settings(self.root)
            probe.assert_not_called()

    def test_good_settings_check_actual_selected_port(self):
        self.settings("server-ip=127.0.0.1\nserver-port=26660\npause-when-empty-seconds=-1\n")
        with patch.object(native, "require_free_port") as probe:
            native.require_launch_settings(self.root)
            probe.assert_called_once_with(26660)

    def manifest(self, **changes):
        document = dict(format=1, purpose="NATIVE66_MIXIN_EXPORT_DIAGNOSTIC",
                        runtime=str(self.root), label="order-a", properties={"mixin.debug.export": True})
        document.update(changes)
        path = self.root / "diagnostic.json"
        path.write_text(json.dumps(document))
        return path

    def test_diagnostic_manifest_is_explicit_and_nonqualifying(self):
        path = self.manifest()
        with patch.dict(os.environ, {}, clear=True):
            environment = native.native_launch_environment(self.root, "order-a", diagnostic_environment=path,
                                                          diagnostic_environment_sha256=native.digest(path))
        self.assertEqual(environment, {"JAVA_TOOL_OPTIONS": "-Dmixin.debug.export=true"})
        receipt = native.read_json(self.root / "native66-launch-environment-order-a.json")
        self.assertFalse(receipt["qualificationAllowed"])
        self.assertEqual("DIAGNOSTIC_NOT_QUALIFIED", receipt["status"])
        with patch.dict(os.environ, {}, clear=True), self.assertRaises(FileExistsError):
            native.native_launch_environment(self.root, "order-a", diagnostic_environment=path,
                                             diagnostic_environment_sha256=native.digest(path))

    def test_old_purpose_wrong_binding_and_injected_agent_rejected(self):
        for change in (dict(purpose="NATIVE61_MIXIN_EXPORT_DIAGNOSTIC"), dict(label="another"),
                       dict(properties={"mixin.debug.export": True, "javaagent": "hidden"})):
            path = self.manifest(**change)
            with self.subTest(change=change), patch.dict(os.environ, {}, clear=True), self.assertRaises(ValueError):
                native.native_launch_environment(self.root, "order-a", diagnostic_environment=path,
                                                 diagnostic_environment_sha256=native.digest(path))
        self.assertFalse((self.root / "native66-launch-environment-order-a.json").exists())

    def test_diagnostic_tamper_is_preserved_not_resealed(self):
        path = self.manifest()
        pin = native.digest(path)
        path.write_text(path.read_text() + " ")
        with patch.dict(os.environ, {}, clear=True), self.assertRaises(AssertionError):
            native.native_launch_environment(self.root, "order-a", diagnostic_environment=path,
                                             diagnostic_environment_sha256=pin)

    @staticmethod
    def complete_log():
        return "\n".join((f"Forge mod loading, version {native.FORGE}, for MC {native.MINECRAFT} with MCP {native.MCP}",
                          f"Starting minecraft server version {native.MINECRAFT}", "Done (0.1s)!",
                          "Stopping server", "Saving players", "Saving worlds",
                          "Saving chunks for level 'ServerLevel[Test]'/minecraft:overworld",
                          "Saving chunks for level 'ServerLevel[Test]'/minecraft:the_nether",
                          "Saving chunks for level 'ServerLevel[Test]'/minecraft:the_end"))

    def test_real_boot_requires_all_witnesses_in_order(self):
        text = self.complete_log()
        native.require_complete_boot(text, stop_sent=True, returncode=0)
        for line in text.splitlines():
            with self.subTest(line=line), self.assertRaises(AssertionError):
                native.require_complete_boot(text.replace(line, ""), stop_sent=True, returncode=0)
        with self.assertRaises(AssertionError):
            native.require_complete_boot("\n".join(reversed(text.splitlines())), stop_sent=True, returncode=0)

    def test_crash_exit_zero_and_external_termination_are_not_cold_stop(self):
        with self.assertRaises(AssertionError):
            native.require_complete_boot("Crash report saved", stop_sent=False, returncode=0)
        with self.assertRaises(AssertionError):
            native.require_complete_boot(self.complete_log(), stop_sent=False, returncode=0)
        with self.assertRaises(AssertionError):
            native.require_complete_boot(self.complete_log(), stop_sent=True, returncode=1)

    def test_earlier_autosave_and_explicit_flush_cannot_shadow_final_save(self):
        text = self.complete_log().replace("Done (", "All dimensions are saved\nDone (")
        text = text.replace("Stopping server", "All dimensions are saved\nStopping server")
        native.require_complete_boot(text, stop_sent=True, returncode=0)
        without_final = text.rsplit("Saving worlds", 1)[0]
        with self.assertRaisesRegex(AssertionError, "Saving worlds"):
            native.require_complete_boot(without_final, stop_sent=True, returncode=0)

    def test_stop_before_done_or_save_before_stop_cannot_qualify(self):
        misordered = self.complete_log().replace("Done (0.1s)!\nStopping server", "Stopping server\nDone (0.1s)!")
        with self.assertRaisesRegex(AssertionError, "Stopping server"):
            native.require_complete_boot(misordered, stop_sent=True, returncode=0)
        no_final_flush = self.complete_log().replace("Stopping server\nSaving players\nSaving worlds",
                                                   "Saving players\nSaving worlds\nStopping server")
        with self.assertRaisesRegex(AssertionError, "Saving players"):
            native.require_complete_boot(no_final_flush, stop_sent=True, returncode=0)

    def test_native_dimension_iteration_order_is_not_fixed_but_exact_membership_is(self):
        lines = self.complete_log().splitlines()
        native.require_complete_boot("\n".join(lines[:-3] + list(reversed(lines[-3:]))), stop_sent=True, returncode=0)
        for text in (self.complete_log() + "\n" + lines[-1],
                     self.complete_log().replace("/minecraft:the_end", "/minecraft:unproven"),
                     self.complete_log().replace("[Test]'/minecraft:the_end", "[Other]'/minecraft:the_end"),
                     self.complete_log() + "\n[now] [Server thread/ERROR] [minecraft/]: Exception closing the level"):
            with self.subTest(text=text), self.assertRaises(AssertionError):
                native.require_complete_boot(text, stop_sent=True, returncode=0)

    def test_stopped_session_lock_observation_preserves_all_bytes(self):
        path = self.root / native.WORLD / "session.lock"
        path.parent.mkdir()
        path.write_bytes(b"native-lock-observation")
        before = native.digest(path)
        native.require_released_session_lock(self.root)
        self.assertEqual(before, native.digest(path))
        path.unlink()
        with self.assertRaises(FileNotFoundError):
            native.require_released_session_lock(self.root)

    def test_formatter_faults_and_debug_runtime_errors_stay_failures(self):
        for text in ("Unable to write to stream DebugFile", "java.lang.ExceptionInInitializerError",
                     "[now] [main/DEBUG] [mod/]: java.lang.IllegalStateException: actual defect",
                     "[now] [main/ERROR] [io.netty.channel.epoll.Epoll/]: Epoll support is not available",
                     "Server empty for 60 seconds, pausing"):
            with self.subTest(text=text):
                self.assertTrue(native.log_findings(text)[0])


class Native263SelectedSdkTest(unittest.TestCase):
    def test_selected_sdk_identity_and_compact_headers(self):
        inventory = native.inspect_seed(SDK)
        self.assertEqual("26.3-66.0.0", inventory["forge"])
        self.assertEqual(94, len(inventory["files"]))
        self.assertEqual(native.SHIM_SHA, inventory["files"][native.SHIM]["sha256"])

    def test_actual_logging_adapter_changes_only_two_renderers(self):
        native.validate_hash(LOGGING, native.LOGGING_JAR_SHA)
        with ZipFile(LOGGING) as jar:
            original = jar.read("log4j2.xml")
        changed = native.plain_logging_bytes(original)
        self.assertEqual(original.replace(b"%xEx", b"%ex"), changed)
        self.assertIn(b'forge.logging.debugFile.level:-debug', changed)
        self.assertIn(b'<Root level="all">', changed)
        self.assertEqual(2, original.count(b"%xEx"))
        with self.assertRaises(AssertionError):
            native.plain_logging_bytes(original.replace(b'level="all"', b'level="info"'))

    def test_logging_custody_rejects_existing_configuration_tamper(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            native.logging_configuration(root, LOGGING)
            native.logging_configuration(root, LOGGING)
            config = root / "forge66-log4j2-plain-stacktraces.xml"
            config.write_bytes(config.read_bytes() + b"<!--tamper-->")
            with self.assertRaises(AssertionError):
                native.logging_configuration(root, LOGGING)
            self.assertTrue(config.read_bytes().endswith(b"<!--tamper-->"))


if __name__ == "__main__":
    unittest.main()
