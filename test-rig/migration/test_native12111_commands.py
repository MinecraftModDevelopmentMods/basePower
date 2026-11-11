import unittest
import tempfile
from pathlib import Path
from zipfile import ZipFile
from unittest.mock import patch
import native12111 as native


class NativeCommandsTest(unittest.TestCase):
    def test_legacy_rule_is_translated_and_requires_a_native_acknowledgement(self):
        server = object.__new__(native.Server)
        with patch.object(native.NativeLaunch, "send") as send, patch.object(server, "wait") as wait:
            server.send("gamerule doMobSpawning false")
            send.assert_called_once_with("gamerule minecraft:spawn_mobs false")
            wait.assert_called_once_with("Gamerule spawn_mobs is now set to: false", 15)

    def test_missing_acknowledgement_fails_the_fixture_setup(self):
        server = object.__new__(native.Server)
        with patch.object(native.NativeLaunch, "send"), patch.object(server, "wait", side_effect=TimeoutError("no ack")):
            with self.assertRaisesRegex(TimeoutError, "no ack"):
                server.send("gamerule randomTickSpeed 0")

    def test_unknown_old_rule_cannot_silently_leave_the_world_uncontrolled(self):
        server = object.__new__(native.Server)
        with patch.object(native.NativeLaunch, "send") as send:
            with self.assertRaisesRegex(ValueError, "Unknown legacy gamerule"):
                server.send("gamerule inventedOldRule false")
            send.assert_not_called()

    def test_non_rule_commands_keep_the_original_path(self):
        server = object.__new__(native.Server)
        with patch.object(native.NativeLaunch, "send") as send:
            server.send("save-all flush")
            send.assert_called_once_with("save-all flush")


class NativeLoggingTest(unittest.TestCase):
    def fixture(self, root, count=2):
        jar = root / "loader.jar"
        xml = '<Configuration><Appenders>' + '<PatternLayout pattern="%msg%n%xEx"/>' * count
        xml += '</Appenders><Loggers><Root level="all"><AppenderRef ref="DebugFile" level="debug"/></Root></Loggers></Configuration>'
        with ZipFile(jar, "w") as output:
            output.writestr("log4j2.xml", xml)
        server = object.__new__(native.Server)
        server.native_runtime = root
        return server, jar

    def test_only_extended_rendering_changes_and_all_log_levels_are_kept(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            server, jar = self.fixture(root)
            before = native.digest(jar)
            uri = server.logging_configuration(jar)
            config = root / "forge61-log4j2-plain-stacktraces.xml"
            tree = native.ET.fromstring(config.read_bytes())
            self.assertEqual(config.as_uri(), uri)
            self.assertEqual(["%msg%n%ex"] * 2, [node.get("pattern") for node in tree.iter("PatternLayout")])
            self.assertEqual("all", tree.find("./Loggers/Root").get("level"))
            self.assertEqual("debug", tree.find("./Loggers/Root/AppenderRef").get("level"))
            self.assertEqual(before, native.digest(jar))
            self.assertEqual(uri, server.logging_configuration(jar))

    def test_a_changed_native_format_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            server, jar = self.fixture(Path(directory), count=1)
            with self.assertRaisesRegex(AssertionError, "logging changed"):
                server.logging_configuration(jar)

    def test_runtime_logging_tampering_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            server, jar = self.fixture(root)
            server.logging_configuration(jar)
            config = root / "forge61-log4j2-plain-stacktraces.xml"
            config.write_text(config.read_text().replace('level="all"', 'level="off"'))
            with self.assertRaisesRegex(AssertionError, "configuration changed"):
                server.logging_configuration(jar)


class NativeDiagnosticClassificationTest(unittest.TestCase):
    def probe(self, level="DEBUG", cause="Only supported on OSX/BSD", extra=""):
        return (f"[11Nov2026 12:00:00.000] [Server thread/{level}] [io.netty.channel.kqueue.KQueue/]: KQueue support is not available\n"
                "java.lang.ExceptionInInitializerError\n\tat io.netty.channel.kqueue.KQueue.test(KQueue.java:1)\n"
                f"Caused by: java.lang.IllegalStateException: {cause}\n"
                "\tat io.netty.channel.kqueue.Native.test(Native.java:1)\n\t... 7 more\n" + extra)

    def test_exact_debug_probe_is_retained_as_a_platform_observation(self):
        errors, observations = native.log_findings(self.probe())
        self.assertEqual([], errors)
        self.assertEqual(1, len(observations))
        self.assertEqual("DEBUG", observations[0]["level"])
        self.assertIn("Caused by:", "\n".join(observations[0]["stack"]))

    def test_error_level_and_unrecognized_causes_are_never_accepted(self):
        for text in (self.probe(level="ERROR"), self.probe(cause="Inventory was lost")):
            self.assertTrue(native.log_findings(text)[0])

    def test_a_second_exception_cannot_hide_under_the_debug_probe(self):
        self.assertTrue(native.log_findings(self.probe(extra="java.lang.IllegalStateException: lost inventory\n"))[0])

    def test_a_mod_stack_frame_is_not_a_platform_probe(self):
        self.assertTrue(native.log_findings(self.probe(extra="\tat zone.moddev.mc.basepower.Machine.tick(Machine.java:1)\n"))[0])

    def test_an_unrelated_runtime_error_after_a_probe_still_fails(self):
        error = "[11Nov2026 12:00:01.000] [Server thread/ERROR] [basepower/]: Recipe load failed\n"
        self.assertTrue(native.log_findings(self.probe(extra=error))[0])
