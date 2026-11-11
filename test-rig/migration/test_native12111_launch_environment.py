import contextlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

from matrix import digest
import native117_code as engine
import native12111 as native


JVM_OPTIONS = ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")


class Native61LaunchEnvironmentTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.runtime = self.root / "runtime with spaces"
        self.runtime.mkdir()
        self.java = self.root / "java.exe"
        self.environment = {key: value for key, value in os.environ.items() if key not in JVM_OPTIONS}
        self.environment["NATIVE_ENV_TEST"] = "preserved"
        self.manifest = self.root / "mixin-export.json"

    @contextlib.contextmanager
    def launch_fixture(self, server_type=native.Server, environment=None, failure=None):
        logging = self.runtime / ("libraries/net/minecraftforge/fmlloader/" + server_type.forge_version
                                  + "/fmlloader-" + server_type.forge_version + ".jar")
        logging.parent.mkdir(parents=True, exist_ok=True)
        logging.touch()
        with (patch.dict(os.environ, self.environment if environment is None else environment, clear=True),
              patch.object(engine.subprocess, "Popen", return_value=Mock(pid=61)) as popen,
              patch.object(engine.threading, "Thread"),
              patch.object(engine.NativeLaunch, "wait", side_effect=failure) as wait,
              patch.object(server_type, "logging_configuration", return_value="fixture://log4j") as configure):
            yield popen, configure, wait

    def construct(self, server_type=native.Server, **kwargs):
        server = server_type(self.runtime, self.java, native.LAUNCHER, "boot", **kwargs)
        self.addCleanup(server.log.close)
        return server

    def sealed_manifest(self, **changes):
        document = dict(format=1, purpose="NATIVE61_MIXIN_EXPORT_DIAGNOSTIC",
                        runtime=str(self.runtime.resolve()), label="boot",
                        properties={"mixin.debug.export": True})
        document.update(changes)
        self.manifest.write_text(json.dumps(document), encoding="utf-8")
        return dict(diagnostic_environment=self.manifest, diagnostic_environment_sha256=digest(self.manifest))

    def receipt(self):
        return self.runtime / "native61-launch-environment-boot.json"

    def assert_refused(self, kwargs=None, environment=None, error=ValueError):
        with self.launch_fixture(environment=environment) as (popen, configure, _):
            with self.assertRaises(error):
                self.construct(**(kwargs or {}))
            popen.assert_not_called()
            configure.assert_not_called()
            self.assertFalse((self.runtime / "console-boot.log").exists())

    def test_legacy_launcher_keeps_implicit_environment_inheritance(self):
        environment = dict(self.environment, JAVA_TOOL_OPTIONS="-Dlegacy.example=true")
        with self.launch_fixture(engine.NativeLaunch, environment) as (popen, _, _):
            self.construct(engine.NativeLaunch)
        self.assertIsNone(popen.call_args.kwargs.get("env"))

    def test_optional_engine_hook_passes_an_explicit_child_environment(self):
        class ExplicitLaunch(engine.NativeLaunch):
            def launch_environment(self):
                return {"EXPLICIT_HOOK": "called"}
        with self.launch_fixture(ExplicitLaunch) as (popen, _, _):
            self.construct(ExplicitLaunch)
        self.assertEqual({"EXPLICIT_HOOK": "called"}, popen.call_args.kwargs.get("env"))

    def test_engine_hook_failure_precedes_logging_and_process_creation(self):
        class RefusingLaunch(engine.NativeLaunch):
            def launch_environment(self):
                raise ValueError("deliberate environment refusal")
        with self.launch_fixture(RefusingLaunch) as (popen, configure, _):
            with self.assertRaisesRegex(ValueError, "environment refusal"):
                self.construct(RefusingLaunch)
            popen.assert_not_called()
            configure.assert_not_called()
        self.assertFalse((self.runtime / "console-boot.log").exists())

    def test_native61_passes_a_snapshot_without_mutating_or_dropping_parent_values(self):
        with self.launch_fixture() as (popen, _, _):
            before = dict(os.environ)
            self.construct()
            self.assertTrue(before == dict(os.environ), "Parent environment changed")
        child = popen.call_args.kwargs.get("env")
        self.assertTrue(self.environment == child, "Native61 child environment differs from the approved snapshot")
        child["CHILD_ONLY"] = "separate"
        self.assertNotIn("CHILD_ONLY", self.environment)

    def test_all_inherited_jvm_options_fail_closed_including_mixin_export(self):
        for variable in JVM_OPTIONS:
            for value in ("-javaagent:outside.jar", "-agentpath:outside.dll", "-agentlib:jdwp",
                          "@outside.args", "-XX:ActiveProcessorCount=4", "-Dmixin.debug.export=true"):
                with self.subTest(variable=variable, value=value):
                    self.assert_refused(environment=dict(self.environment, **{variable: value}))
        self.assertFalse(self.receipt().exists())

    def test_blank_jvm_options_are_preserved_like_the_java_runner(self):
        environment = dict(self.environment, **{variable: " " for variable in JVM_OPTIONS})
        with self.launch_fixture(environment=environment) as (popen, _, _):
            self.construct()
        self.assertTrue(environment == popen.call_args.kwargs.get("env"), "Blank JVM option values were not preserved")

    def test_java_version_probe_also_rejects_inherited_options_before_execution(self):
        self.java.touch()
        for variable in JVM_OPTIONS:
            with (self.subTest(variable=variable),
                  patch.dict(os.environ, dict(self.environment, **{variable: "-javaagent:outside.jar"}), clear=True),
                  patch.object(native.subprocess, "run") as run):
                with self.assertRaisesRegex(ValueError, variable):
                    native.java21(self.java)
                run.assert_not_called()

    def test_java_version_probe_uses_an_approved_snapshot_and_keeps_its_version_gate(self):
        self.java.touch()
        result = Mock(stdout="", stderr='openjdk version "21.0.7"')
        with (patch.dict(os.environ, self.environment, clear=True),
              patch.object(native.subprocess, "run", return_value=result) as run):
            self.assertEqual((self.java, result.stderr), native.java21(self.java))
            self.assertTrue(self.environment == run.call_args.kwargs["env"], "Version probe environment changed")
            result.stderr = 'openjdk version "17.0.1"'
            with self.assertRaisesRegex(ValueError, "requires Java 21"):
                native.java21(self.java)

    def test_checksum_sealed_export_is_explicit_and_diagnostic_not_qualified(self):
        kwargs = self.sealed_manifest()
        with self.launch_fixture() as (popen, _, _):
            before = dict(os.environ)
            self.construct(**kwargs)
            self.assertTrue(before == dict(os.environ), "Diagnostic launch changed the parent environment")
        expected = dict(self.environment, JAVA_TOOL_OPTIONS="-Dmixin.debug.export=true")
        self.assertTrue(expected == popen.call_args.kwargs["env"], "Diagnostic child environment differs from the seal")
        receipt = json.loads(self.receipt().read_text())
        self.assertEqual("DIAGNOSTIC_NOT_QUALIFIED", receipt["status"])
        self.assertFalse(receipt["qualificationAllowed"])
        self.assertEqual(kwargs["diagnostic_environment_sha256"], receipt["manifestSha256"])
        self.assertEqual({"JAVA_TOOL_OPTIONS": "-Dmixin.debug.export=true"}, receipt["environment"])

    def test_export_filter_and_decompiler_controls_remain_available(self):
        kwargs = self.sealed_manifest(properties={"mixin.debug.export": True,
                    "mixin.debug.export.decompile": False,
                    "mixin.debug.export.filter": "net.minecraft.**.SimpleRegionStorage$*"})
        with self.launch_fixture() as (popen, _, _):
            self.construct(**kwargs)
        self.assertEqual("-Dmixin.debug.export=true -Dmixin.debug.export.decompile=false "
                         "-Dmixin.debug.export.filter=net.minecraft.**.SimpleRegionStorage$*",
                         popen.call_args.kwargs["env"]["JAVA_TOOL_OPTIONS"])

    def test_export_file_alone_cannot_enable_inherited_options(self):
        self.sealed_manifest()
        self.assert_refused(environment=dict(self.environment, JAVA_TOOL_OPTIONS="-Dmixin.debug.export=true"))

    def test_opt_in_requires_both_explicit_arguments(self):
        kwargs = self.sealed_manifest()
        for partial in ({"diagnostic_environment": self.manifest},
                        {"diagnostic_environment_sha256": kwargs["diagnostic_environment_sha256"]}):
            with self.subTest(partial=partial):
                self.assert_refused(partial)

    def test_bad_or_changed_checksum_is_rejected_before_launch(self):
        kwargs = self.sealed_manifest()
        for sha in ("", "0" * 64, "not-a-hash"):
            with self.subTest(sha=sha):
                self.assert_refused(dict(kwargs, diagnostic_environment_sha256=sha))
        self.manifest.write_text(self.manifest.read_text() + " ")
        self.assert_refused(kwargs)

    def test_seal_is_bound_to_this_runtime_label_and_purpose(self):
        for change in ({"format": True}, {"format": 2}, {"purpose": "QUALIFIED"},
                       {"runtime": str(self.root)}, {"label": "another-boot"}, {"unexpected": True}):
            with self.subTest(change=change):
                self.assert_refused(self.sealed_manifest(**change))

    def test_seal_cannot_add_agents_other_properties_or_argument_files(self):
        for properties in ({"mixin.debug.export": False}, {"mixin.debug.export": "true"},
                           {"mixin.debug.export": True, "javaagent": "outside.jar"},
                           {"mixin.debug.export": True, "other.setting": True},
                           {"mixin.debug.export": True, "mixin.debug.export.decompile": "false"},
                           {"mixin.debug.export": True, "mixin.debug.export.filter": "* -javaagent:outside.jar"},
                           {"mixin.debug.export": True, "mixin.debug.export.filter": "@outside.args"}):
            with self.subTest(properties=properties):
                self.assert_refused(self.sealed_manifest(properties=properties))

    def test_duplicate_fields_malformed_and_oversized_seals_are_rejected(self):
        kwargs = self.sealed_manifest()
        valid = self.manifest.read_text()
        for payload in (valid.replace('"format": 1', '"format": 1, "format": 1'),
                        valid.replace('"mixin.debug.export": true', '"mixin.debug.export": true, "mixin.debug.export": true'),
                        "[]", "{", " " * (16384 + 1)):
            with self.subTest(payload=payload[:80]):
                self.manifest.write_text(payload)
                self.assert_refused(dict(kwargs, diagnostic_environment_sha256=digest(self.manifest)))

    def test_sealed_diagnostic_does_not_excuse_inherited_agent_or_other_options(self):
        kwargs = self.sealed_manifest()
        for variable in JVM_OPTIONS:
            with self.subTest(variable=variable):
                self.assert_refused(kwargs, environment=dict(self.environment, **{variable: "-javaagent:outside.jar"}))

    def test_existing_diagnostic_receipt_is_not_overwritten(self):
        kwargs = self.sealed_manifest()
        self.receipt().write_text("retained earlier receipt")
        self.assert_refused(kwargs, error=FileExistsError)
        self.assertEqual("retained earlier receipt", self.receipt().read_text())

    def test_startup_failure_still_captures_diagnostics_and_preserves_primary_error(self):
        kwargs = self.sealed_manifest()
        failure = TimeoutError("original startup deadline")
        cleanup = RuntimeError("cleanup failure")
        def close(server):
            server.log.close()
            raise cleanup
        with (self.launch_fixture(failure=failure) as (popen, _, _),
              patch.object(native.Server, "close", autospec=True, side_effect=close),
              patch.object(engine.startup_diagnostics, "observe", return_value={}) as observe,
              patch.object(engine.startup_diagnostics, "retain") as retain):
            with self.assertRaises(TimeoutError) as caught:
                self.construct(**kwargs)
            self.assertIs(failure, caught.exception)
            self.assertEqual(1, popen.call_count)
            observe.assert_called_once()
            self.assertIs(cleanup, retain.call_args.args[3])
        self.assertEqual("DIAGNOSTIC_NOT_QUALIFIED", json.loads(self.receipt().read_text())["status"])

    def test_environment_snapshot_works_in_a_real_small_child_process(self):
        with patch.dict(os.environ, self.environment, clear=True):
            environment = native.native_launch_environment(self.runtime, "boot")
        child = subprocess.run([sys.executable, "-I", "-B", "-c",
                                "import json,os; print(json.dumps({k:os.environ.get(k) for k in "
                                "('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','NATIVE_ENV_TEST')}))"],
                               env=environment, check=True, capture_output=True, text=True, timeout=20)
        self.assertEqual({**dict.fromkeys(JVM_OPTIONS), "NATIVE_ENV_TEST": "preserved"}, json.loads(child.stdout))


if __name__ == "__main__":
    unittest.main()
