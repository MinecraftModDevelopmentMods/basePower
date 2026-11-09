"""Native117 connected destination safety and unchanged-oracle regression tests."""

import copy
import io
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
import zipfile
from unittest.mock import Mock, patch

import connected
from matrix import WORLD, digest, fingerprint, write_json
from native117 import LAUNCHER, RIG
import native117_connected as native
import native117_code as native_code
from test_connected import advanced, nbt, sample


BOOT_PROOF = ("Starting minecraft server version 1.17.1\n"
              "Forge mod loading, version 37.1.1, for MC 1.17.1 with MCP 20210706.113038\n")


class NativeConnectedTest(unittest.TestCase):
    def setUp(self):
        root = RIG / "build/port-evidence"
        root.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=root)
        self.root = Path(self.temp.name)
        self.definition = connected.fixture("original110")

    def tearDown(self):
        self.temp.cleanup()

    def source(self):
        source = self.root / "original110"
        (source / WORLD).mkdir(parents=True)
        (source / WORLD / "level.dat").write_bytes(b"unit-fixture-only-no-minecraft-process")
        (source / "mods").mkdir()
        jar = source / "mods/old.jar"
        jar.write_bytes(b"archived-unit-test-artifact")
        write_json(source / "input-jars.json", [{"file": jar.name, "sha256": digest(jar)}])
        manifest = self.root / "profiles.json"
        write_json(manifest, {"original110": {"seed": str(source), "launcher": "forge-old.jar",
                   "jars": [{"path": str(jar), "sha256": digest(jar)}]}})
        write_json(source / "connected-fixture.json", self.definition)
        write_json(source / "migration-fixture.json", self.definition["blocks"])
        cold, worked = sample(), advanced(sample())
        write_json(source / "connected-cold.json", cold)
        write_json(source / "connected-source.json", worked)
        write_json(source / "migration-source.json", worked["snapshot"])
        write_json(source / "fixture-hashes.json", fingerprint(source / WORLD))
        write_json(self.root / "connected-results.json", {"status": "FAIL",
                   "sources": {"original110": {"status": "PASS"}}, "routes": {"old-bridge": {"status": "FAIL"}}})
        return SimpleNamespace(source=source, source_manifest=manifest, source_key="original110",
                               source_hashes=source / "fixture-hashes.json", source_checkpoint="source",
                               target_manifest=self.root / "target.json", output=self.root / "new-output",
                               port=25576, ticks=64, java8=Path("unused-java8"))

    def test_only_observation_save_and_stop_commands_are_permitted(self):
        for command in ("stop", "save-all", "save-all flush", "advconnected snapshot imported",
                        "advconnected step 1 first", "advconnected step 4000 last"):
            native.require_command(command)

    def test_build_reset_refill_and_invalid_tick_commands_are_rejected(self):
        for command in ("advconnected build source", "advworks reset all", "setblock 0 80 0 stone",
                        "data merge block 0 80 0 {}", "advconnected step 0 bad", "advconnected step 4001 bad",
                        "advconnected snapshot ok\nadvworks build all", "advconnected snapshot ../escape"):
            with self.subTest(command=command), self.assertRaises(AssertionError):
                native.require_command(command)

    def test_operation_cannot_start_before_preservation_gate(self):
        server = native.NativeConnectedServer.__new__(native.NativeConnectedServer)
        server.loaded_verified = False
        with patch.object(connected.ConnectedServer, "send") as sender, self.assertRaises(AssertionError):
            server.send("advconnected step 64 worked")
        sender.assert_not_called()

    def test_failed_loaded_callback_never_opens_operation_gate(self):
        server = native.NativeConnectedServer.__new__(native.NativeConnectedServer)
        server.loaded_verified = False
        server.on_loaded = Mock(side_effect=AssertionError("lost saved progress"))
        with patch.object(connected.ConnectedServer, "observe"), self.assertRaises(AssertionError):
            server.observe("snapshot", "converted-loaded")
        self.assertFalse(server.loaded_verified)

    def test_valid_destination_document_uses_existing_state_oracle(self):
        self.assertEqual(native.require_document(sample(), self.definition, destination=True), connected.state(sample()))

    def test_missing_duplicate_and_moved_witnesses_fail(self):
        for mutation in (lambda rows: rows.pop(), lambda rows: rows.append(copy.deepcopy(rows[0])),
                         lambda rows: rows[0].update(x=99)):
            document = sample()
            mutation(document["snapshot"])
            with self.assertRaises(AssertionError):
                native.require_document(document, self.definition, destination=True)

    def test_rebuild_or_unfrozen_snapshot_is_not_preservation(self):
        for field, value in (("buildCallsThisProcess", 1), ("held", False)):
            document = sample()
            document[field] = value
            with self.assertRaises(AssertionError):
                native.require_document(document, self.definition, destination=True)

    def test_unchanged_oracle_rejects_quantity_loss_and_wrong_drill_products(self):
        for station, contents in (("C-ELECTRIC", '{Items:[]}'),
                                  ("C-EDRILL", '{Items:[{id:"minecraft:gravel",Count:9b}]}')):
            document = advanced(sample())
            nbt(connected.entries(document)[station + ("-drill" if station.endswith("DRILL") else "-furnace")], contents)
            with self.assertRaises(AssertionError):
                native.require_document(document, self.definition, destination=True)

    def test_idle_and_nonmeasured_intervals_still_fail_existing_operation_oracle(self):
        before = sample()
        idle = copy.deepcopy(before)
        idle.update(requestedTicks=64, advancedTicks=64)
        self.assertEqual(connected.operation(before, idle, 64)["status"], "FAIL")
        worked = advanced(before)
        worked["advancedTicks"] = 0
        self.assertEqual(connected.operation(before, worked, 64)["status"], "FAIL")

    def test_finite_water_creation_remains_failure(self):
        before = sample()
        worked = advanced(before)
        nbt(connected.entries(worked)["C-STEAM-water-0"], '{Tank:{FluidName:"water",Amount:10001}}')
        self.assertEqual(connected.operation(before, worked, 64)["status"], "FAIL")

    def test_boot_proof_needs_both_native_version_and_clean_log(self):
        log = self.root / "console.log"
        log.write_text(BOOT_PROOF)
        native.require_native_boot(log)
        for text in (BOOT_PROOF.replace("1.17.1", "1.13.2"), BOOT_PROOF + "[main/ERROR] native failure\n",
                     BOOT_PROOF + "java.lang.NoSuchMethodError: example\n"):
            log.write_text(text)
            with self.assertRaises(AssertionError):
                native.require_native_boot(log)

    def test_explicit_source_pins_and_historical_failures_are_preserved(self):
        args = self.source()
        before = fingerprint(args.source)
        definition, snapshot, verdict, actual, historical = native.verify_source(args)
        self.assertEqual(definition, self.definition)
        self.assertEqual(verdict["status"], "PASS")
        self.assertEqual(historical["status"], "FAIL")
        self.assertEqual(actual, before)
        self.assertEqual(fingerprint(args.source), before)

    def test_changed_source_world_fails_before_staging(self):
        args = self.source()
        (args.source / WORLD / "level.dat").write_bytes(b"changed")
        with patch.object(native, "prepare_target") as prepare, self.assertRaises(AssertionError):
            native.run(args)
        prepare.assert_not_called()
        self.assertFalse(args.output.exists())

    def test_changed_source_jar_or_wrong_profile_key_fails(self):
        args = self.source()
        args.source_key = "maintained110"
        with self.assertRaises(ValueError):
            native.verify_source(args)
        args.source_key = "original110"
        (args.source / "mods/old.jar").write_bytes(b"changed")
        with self.assertRaises(AssertionError):
            native.verify_source(args)

    def test_nonconnected_operation_fixture_cannot_be_reconstructed(self):
        args = self.source()
        (args.source / "connected-fixture.json").unlink()
        with patch.object(native, "prepare_target") as prepare, self.assertRaises(FileNotFoundError):
            native.run(args)
        prepare.assert_not_called()

    def test_target_prepare_copies_only_fixture_and_has_no_build_authority(self):
        args = self.source()
        (args.source / "connected-build.allow").touch()
        (args.source / "connected-built.json").write_text("{}")
        before = fingerprint(args.source)
        seed = self.root / "native-seed"
        (seed / "libraries").mkdir(parents=True)
        (seed / LAUNCHER).parent.mkdir(parents=True, exist_ok=True)
        (seed / LAUNCHER).write_bytes(b"unit-test-launcher-not-executed")
        jar = self.root / "target.jar"
        jar.write_bytes(b"unit-test-target-not-executed")
        runtime = self.root / "target"
        pins = {"launcher_sha256": digest(seed / LAUNCHER), "jars": [{"path": str(jar), "sha256": digest(jar)}]}
        self.assertEqual(native.prepare_target(runtime, (seed, LAUNCHER, [jar]), self.definition, 25576, args.source, pins), LAUNCHER)
        self.assertEqual(fingerprint(runtime / WORLD), fingerprint(args.source / WORLD))
        self.assertFalse((runtime / "connected-build.allow").exists())
        self.assertFalse((runtime / "connected-built.json").exists())
        self.assertIn("server-port=25576", (runtime / "server.properties").read_text())
        self.assertNotIn("3;minecraft:bedrock", (runtime / "server.properties").read_text())
        self.assertEqual(fingerprint(args.source), before)

    def test_non_native_launcher_is_rejected_without_creating_target(self):
        runtime = self.root / "target"
        with self.assertRaises(ValueError):
            native.prepare_target(runtime, (self.root, "forge-1.13.2.jar", []), self.definition, 25576, self.root, {})
        self.assertFalse(runtime.exists())

    def target(self, args):
        from native117_code import INVENTORY, code_files
        seed = self.root / "seed"
        (seed / "libraries").mkdir(parents=True)
        (seed / LAUNCHER).parent.mkdir(parents=True, exist_ok=True)
        (seed / LAUNCHER).write_bytes(b"unit-test-launcher-never-executed")
        jars = []
        for identity in ("basepower", "basesteam", "baseelectric", "advantageworkstest", "orespawn"):
            jar = seed / (identity + ".jar")
            with zipfile.ZipFile(jar, "w") as archive:
                archive.writestr("META-INF/mods.toml", f'[[mods]]\nmodId="{identity}"\nversion="1.0.0.117011"\n')
            jars.append(dict(path=str(jar), id=identity, version="1.0.0.117011", sha256=digest(jar)))
        server_code = {"files": code_files(seed)}
        write_json(seed / INVENTORY, server_code)
        write_json(args.target_manifest, {"target117": dict(seed=str(seed), launcher=LAUNCHER,
                   launcher_sha256=digest(seed / LAUNCHER), jars=jars, serverCode=server_code)})

    def test_runtime_failure_is_retained_and_source_is_still_rechecked(self):
        args = self.source()
        self.target(args)
        before = fingerprint(args.source)
        with patch.object(native.socket, "socket"), patch.object(native, "prepare_target", side_effect=RuntimeError("retained native failure")), self.assertRaises(RuntimeError):
            native.run(args)
        report = native.read_json(args.output / "native117-connected-results.json")
        self.assertEqual(report["status"], "FAIL")
        self.assertEqual(report["failure"], "retained native failure")
        self.assertEqual(report["sourceRuntimeAfter"], before)
        self.assertEqual(report["sourcePreservation"]["status"], "PASS")

    def test_source_change_on_failure_is_reported_not_hidden(self):
        args = self.source()
        self.target(args)
        def changed(*unused):
            (args.source / WORLD / "level.dat").write_bytes(b"unexpected-source-change")
            raise RuntimeError("native failure")
        with patch.object(native.socket, "socket"), patch.object(native, "prepare_target", side_effect=changed), self.assertRaisesRegex(AssertionError, "Protected source"):
            native.run(args)
        report = native.read_json(args.output / "native117-connected-results.json")
        self.assertEqual(report["status"], "FAIL")
        self.assertEqual(report["sourcePreservation"]["status"], "FAIL")

    def test_candidate_change_after_preflight_cannot_replace_pinned_bytes(self):
        args = self.source()
        seed = self.root / "seed"
        (seed / "libraries").mkdir(parents=True)
        (seed / LAUNCHER).parent.mkdir(parents=True, exist_ok=True)
        (seed / LAUNCHER).write_bytes(b"launcher-not-executed")
        jar = self.root / "candidate.jar"
        jar.write_bytes(b"pinned")
        pins = {"launcher_sha256": digest(seed / LAUNCHER), "jars": [{"path": str(jar), "sha256": digest(jar)}]}
        jar.write_bytes(b"evolving-after-preflight")
        with self.assertRaises(AssertionError):
            native.prepare_target(self.root / "target", (seed, LAUNCHER, [jar]), self.definition, 25576, args.source, pins)

    def test_continued_output_cannot_promote_idle_machinery(self):
        self.assertEqual(native.continued_output(sample(), advanced(sample()))["status"], "PASS")
        self.assertEqual(native.continued_output(sample(), sample())["status"], "FAIL")

    def launch_fixture(self, *, missing_debug=False):
        runtime = self.root / "native launch with spaces"
        logging_jar = runtime / "libraries/net/minecraftforge/fmlloader/1.17.1-37.1.1/fmlloader-1.17.1-37.1.1.jar"
        logging_jar.parent.mkdir(parents=True)
        logging_jar.write_bytes(b"unit logging input; no JVM is started")
        launcher = runtime / LAUNCHER
        launcher.parent.mkdir(parents=True, exist_ok=True)
        launcher.write_bytes(b"unit argument file; no JVM is started")
        write_json(runtime / native_code.INVENTORY, dict(format=1, minecraft="1.17.1", forge="37.1.1",
                   files=native_code.code_files(runtime)))
        process = SimpleNamespace(stdout=io.StringIO(BOOT_PROOF + "Done (unit fixture)\n"
            "ADVCONNECTED COMPLETE converted-loaded\nADVCONNECTED COMPLETE converted-worked\n"),
            stdin=io.StringIO(), returncode=None, pid=117)
        process.poll = lambda: process.returncode
        def wait(timeout=None):
            process.returncode = 0
            return 0
        process.wait = wait
        process.kill = Mock(side_effect=AssertionError("Constructor fixture must stop cleanly"))
        def spawn(*args, **kwargs):
            logs = runtime / "logs"
            logs.mkdir()
            (logs / "latest.log").write_text(BOOT_PROOF)
            if not missing_debug:
                (logs / "debug.log").write_text(BOOT_PROOF)
            return process
        return runtime, logging_jar, process, spawn

    def test_constructor_uses_native_argument_file_and_keeps_connected_command_trace(self):
        runtime, logging_jar, process, spawn = self.launch_fixture()
        callback = Mock()
        with patch.object(native_code.subprocess, "Popen", side_effect=spawn) as popen:
            server = native.NativeConnectedServer(runtime, Path("unit-java16.exe"), LAUNCHER, "converted", callback)
            try:
                argv = popen.call_args.args[0]
                self.assertIn("@" + LAUNCHER, argv)
                self.assertNotIn("-jar", argv)
                self.assertIn("--add-opens", argv)
                self.assertIn("java.base/java.lang.invoke=ALL-UNNAMED", argv)
                self.assertIn("-Dlog4j.configurationFile=jar:" + logging_jar.resolve().as_uri() + "!/log4j2.xml", argv)
                self.assertEqual(runtime, popen.call_args.kwargs["cwd"])
                with self.assertRaisesRegex(AssertionError, "Preservation must pass"):
                    server.send("advconnected step 512 premature")
                server.observe("snapshot", "converted-loaded")
                callback.assert_called_once_with("converted-loaded")
                server.observe("step", "converted-worked", 512)
                server.send("save-all flush")
            finally:
                server.close()
        expected = ["advconnected snapshot converted-loaded", "advconnected step 512 converted-worked", "save-all flush", "stop"]
        self.assertEqual(expected, native.read_json(runtime / "console-converted.commands.json"))
        self.assertEqual(expected, process.stdin.getvalue().splitlines())
        for kind in ("console", "latest", "debug"):
            native.require_native_boot(runtime / (kind + "-converted.log"))
        native_code.verify_code(runtime)

    def test_constructor_close_uses_native_three_log_gate(self):
        runtime, _, _, spawn = self.launch_fixture(missing_debug=True)
        with patch.object(native_code.subprocess, "Popen", side_effect=spawn):
            server = native.NativeConnectedServer(runtime, Path("unit-java16.exe"), LAUNCHER, "converted", Mock())
            with self.assertRaisesRegex(AssertionError, "Missing native runtime log"):
                server.close()
        self.assertEqual(["stop"], native.read_json(runtime / "console-converted.commands.json"))

    def test_constructor_close_still_rejects_code_inventory_mutation(self):
        runtime, logging_jar, _, spawn = self.launch_fixture()
        with patch.object(native_code.subprocess, "Popen", side_effect=spawn):
            server = native.NativeConnectedServer(runtime, Path("unit-java16.exe"), LAUNCHER, "converted", Mock())
            logging_jar.write_bytes(b"changed sealed input")
            with self.assertRaisesRegex(AssertionError, "exact sealed inventory"):
                server.close()
        self.assertEqual(["stop"], native.read_json(runtime / "console-converted.commands.json"))


if __name__ == "__main__":
    unittest.main()
