"""Stopped-source receipt controls. All worlds here are synthetic unit fixtures.

No Minecraft process may run in this suite; real source generation is separately
authorized and cannot be replaced by these tests or an earlier destination PASS.
"""

import copy
from contextlib import contextmanager
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile

import nbtlib
import connected
import native116
import native116_code
import native117_sources116 as sources
from native116_resource_audit import SLOT_LIMITS
from matrix import WORLD, digest, fingerprint, write_json
from test_connected import advanced, entries, nbt, sample
from test_semantic_machine_audit import pair


class Source116Tests(unittest.TestCase):
    def setUp(self):
        parent = sources.RIG / "build/native117-source116-unit"
        parent.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=parent)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.no_process = patch("subprocess.Popen", side_effect=AssertionError("No Minecraft process in source116 unit tests"))
        self.no_process.start()
        self.addCleanup(self.no_process.stop)
        self.seed = self.root / "seed with spaces"
        (self.seed / "libraries").mkdir(parents=True)
        (self.seed / "libraries/pinned.jar").write_bytes(b"unit native library")
        (self.seed / native116.LAUNCHER).write_bytes(b"unit native launcher")
        self.code = dict(files=native116_code.code_files(self.seed))
        write_json(self.seed / native116_code.INVENTORY, self.code)
        self.jars = []
        for identity in ("advantageworkstest", "poweradvantage", "steamadvantage", "electricadvantage", "basemetals", "orespawn"):
            jar = self.root / "pinned mods" / (identity + ".jar")
            jar.parent.mkdir(exist_ok=True)
            with zipfile.ZipFile(jar, "w") as archive:
                archive.writestr("META-INF/mods.toml", '[[mods]]\nmodId="%s"\nversion="1.0.0.116051"\n' % identity)
            self.jars.append(dict(id=identity, version="1.0.0.116051", path=str(jar), sha256=digest(jar)))
        self.manifest = self.root / "accepted native116.json"
        self.target = dict(seed=str(self.seed), launcher=native116.LAUNCHER,
            launcher_sha256=digest(self.seed / native116.LAUNCHER), jars=self.jars, serverCode=self.code)
        write_json(self.manifest, dict(target116=self.target))
        self.java = self.root / "jdk8/bin/java.exe"
        self.java.parent.mkdir(parents=True)
        self.java.write_bytes(b"unit java; never execute")
        (self.java.parent.parent / "release").write_text('JAVA_VERSION="1.8.0_502"\n')
        self.rows = []
        for kind, name in [("frozen", "original110-chained-113")] + [("connected", name) for name in sources.HISTORICAL]:
            source = self.root / "protected fixtures" / (kind + "-" + name)
            self.world(source)
            (source / "mods").mkdir()
            write_json(source / "input-jars.json", [])
            if kind == "frozen":
                snapshot = "migration-restarted.json"
                rows = self.frozen_rows()
                write_json(source / snapshot, rows)
                write_json(source / "migration-fixture.json", rows)
            else:
                snapshot = "source"
                write_json(source / "connected-fixture.json", connected.fixture(name))
                write_json(source / "migration-fixture.json", connected.fixture(name)["blocks"])
                write_json(source / "connected-cold.json", sample())
                worked = advanced(sample())
                write_json(source / "connected-source.json", worked)
                write_json(source / "migration-source.json", worked["snapshot"])
            hashes = self.root / (kind + "-" + name + "-world.json")
            write_json(hashes, fingerprint(source / WORLD))
            pins = self.root / (kind + "-" + name + "-pins.json")
            write_json(pins, {name: dict(seed=str(self.seed), launcher=native116.LAUNCHER, jars=[])})
            path = source / (snapshot if kind == "frozen" else "migration-source.json")
            self.rows.append(dict(id="legacy/" + name, kind=kind, source=str(source), sourceKey=name,
                sourceManifest=str(pins), sourceManifestSha256=digest(pins), sourceHashes=str(hashes),
                sourceHashesSha256=digest(hashes), sourceSnapshot=snapshot, sourceSnapshotSha256=digest(path),
                sourceFixtureKey=None))
        self.routes = self.root / "archived Ready.json"
        write_json(self.routes, dict(format=1, routes=self.rows))
        self.args = SimpleNamespace(source_routes=self.routes, target116_manifest=self.manifest, java8=self.java,
            output=self.root / "NEW disposable sources", port=25588, validate_only=False)

    def world(self, runtime):
        (runtime / WORLD).mkdir(parents=True)
        nbtlib.File({"Data": nbtlib.Compound({"DataVersion": nbtlib.Int(2586)})}).save(runtime / WORLD / "level.dat", gzipped=True)

    def frozen_rows(self):
        rows = []
        for index in range(355):
            row = dict(id=str(index), name="minecraft:stone", actualName="minecraft:stone", x=index, y=64, z=0, properties={})
            if index < 332:
                nbt(row, '{id:"minecraft:chest",Items:[]}')
            rows.append(row)
        return rows

    @contextmanager
    def pinned(self):
        with patch.object(sources, "TARGET116_SHA256", digest(self.manifest)), \
                patch.object(sources, "SOURCE_ROUTES_SHA256", digest(self.routes)):
            yield

    def plan(self):
        with self.pinned():
            return sources.prepare(self.args)

    def stage(self, runtime):
        import shutil
        self.world(runtime)
        shutil.copytree(self.seed / "libraries", runtime / "libraries")
        shutil.copy2(self.seed / native116.LAUNCHER, runtime / native116.LAUNCHER)
        write_json(runtime / native116_code.INVENTORY, self.code)
        (runtime / "mods").mkdir()
        for row in self.jars:
            shutil.copy2(row["path"], runtime / "mods" / Path(row["path"]).name)
        write_json(runtime / "input-jars.json", [dict(file=Path(row["path"]).name, sha256=row["sha256"]) for row in self.jars])

    def stops(self, runtime, kind):
        result = []
        for label, pid in (("converted", 111), ("restarted", 222)):
            logs = {}
            for prefix in ("console", "latest", "debug"):
                name = prefix + "-" + label + ".log"
                (runtime / name).write_text(
                    "Starting minecraft server version 1.16.5\n"
                    "Forge mod loading, version 36.2.34, for MC 1.16.5 with MCP 20210115.111550\n"
                    + label + "\nSaved the game\nStopping server\nSaving worlds\nAll chunks are saved\n")
                logs[name] = digest(runtime / name)
            result.append(dict(label=label, pid=pid, returncode=0, commands=sources._commands(kind, label), logs=logs))
        return result

    def frozen_result(self):
        runtime = self.root / "new frozen runtime"
        self.stage(runtime)
        source = Path(self.rows[0]["source"])
        (runtime / "migration-fixture.json").write_bytes((source / "migration-fixture.json").read_bytes())
        for label in ("converted", "restarted"):
            write_json(runtime / ("migration-" + label + ".json"), self.frozen_rows())
        report = {gate: dict(status="PASS", witnesses=dict(blocks=355, tiles=332))
                  for gate in ("converted", "restarted", "idempotence")}
        write_json(runtime / "state-comparison.json", report)
        write_json(runtime / "native116-provenance.json", dict(nativeSaveFormat={label: {"label": label}
                   for label in ("converted", "restarted")}))
        settings = sources.source116_settings(self.args, self.rows[0], runtime)
        return settings, runtime, self.stops(runtime, "frozen")

    @contextmanager
    def saved_format(self):
        # These are receipt-layer tests. Real native chunk decoding has its own
        # negative-control suite; audit_save must still be called for both boots.
        with patch.object(sources.native116_save_io, "audit_save") as audit, \
                patch.object(sources.native116_save_io, "require_same_settings") as same:
            yield audit, same

    def connected_result(self):
        output = self.root / "connected result"
        runtime = output / "target116"
        self.stage(runtime)
        recipe = self.rows[-1]
        source = Path(recipe["source"])
        definition = connected.fixture("original110")
        write_json(runtime / "connected-fixture.json", definition)
        write_json(runtime / "migration-fixture.json", definition["blocks"])
        initial, first = pair()
        for document in (initial, first):
            for row in document["snapshot"]:
                row["properties"] = {}
                if row["actualName"] in SLOT_LIMITS and "nbt" not in row:
                    nbt(row, "{Items:[]}")
                    row.update(witness=row["id"], objectIdentity=len(row["id"]))
        first.update(gameTime=612, requestedTicks=512, advancedTicks=512)
        # The canonical source is unit data, never a saved gameplay fixture.
        write_json(source / "migration-source.json", initial["snapshot"])
        second = copy.deepcopy(first)
        second.update(gameTime=1124)
        rows = entries(second)
        items = ','.join('{Slot:%db,id:"minecraft:dirt",Count:%db}' % (slot, 62 if slot == 26 else 64) for slot in range(27))
        nbt(rows["C-CONVEYOR-input"], '{Items:[' + items + ']}')
        nbt(rows["C-CONVEYOR-output"], '{Items:[{Slot:0b,id:"minecraft:dirt",Count:2b}]}')
        nbt(rows["C-STEAM-furnace"], '{Items:[{Slot:0b,id:"minecraft:coal",Count:32b},'
            '{Slot:1b,id:"minecraft:iron_ore",Count:62b},{Slot:2b,id:"minecraft:iron_ore",Count:64b},'
            '{Slot:3b,id:"minecraft:iron_ore",Count:64b},{Slot:4b,id:"minecraft:iron_ingot",Count:2b}]}')
        for key, raw, product in (("C-ELECTRIC-furnace", "iron_ore", "iron_ingot"), ("C-STEAM-crusher", "cobblestone", "gravel")):
            nbt(rows[key], '{Items:[{Slot:0b,id:"minecraft:%s",Count:62b},{Slot:1b,id:"minecraft:%s",Count:2b}]}' % (raw, product))
        for station, product, count in (("C-SDRILL", "gravel", 2), ("C-EDRILL", "cobblestone", 18)):
            targets = [row for row in second["snapshot"] if row["station"] == station and row["kind"].startswith("target-")]
            for row in targets[:count]:
                row["actualName"] = "minecraft:air"
            nbt(rows[station + "-drill"], '{Items:[{Slot:0b,id:"minecraft:%s",Count:%db}]}' % (product, count))
        for label, old, worked in (("converted", initial, first), ("restarted", first, second)):
            loaded = copy.deepcopy(old)
            loaded.update(requestedTicks=0, advancedTicks=0)
            for suffix, document in (("loaded", loaded), ("worked", worked)):
                write_json(runtime / ("connected-" + label + "-" + suffix + ".json"), document)
                write_json(runtime / ("migration-" + label + "-" + suffix + ".json"), document["snapshot"])
        gates = {label + "_" + gate: dict(status="PASS") for label in ("converted", "restarted")
                 for gate in ("preservation", "exact_resources", "operation", "completed_work", "native_boot")}
        gates["real_output_across_restarts"] = dict(status="PASS")
        write_json(output / "native116-connected-results.json", dict(status="PASS", sourcePreservation=dict(status="PASS"),
            results=gates, sourceRuntimeBefore=fingerprint(source), sourceRuntimeAfter=fingerprint(source),
            nativeSaveFormat={label: {"label": label} for label in ("converted", "restarted")}))
        settings = sources.source116_settings(self.args, recipe, output)
        return settings, runtime, self.stops(runtime, "connected")

    def test_pending_exact_six_cannot_be_approved_by_destination_pass(self):
        rows = sources.pending_routes()
        self.assertEqual(6, len(rows))
        self.assertEqual({"native116/full", *("native116/" + name for name in sources.HISTORICAL)}, {row["id"] for row in rows})
        for row in rows:
            with self.subTest(id=row["id"]), self.assertRaisesRegex(AssertionError, "stopped-source receipt"):
                sources.verify_source_receipt(dict(row, status="PASS"))

    def test_prepare_is_read_only_and_preserves_exact_recipe_authority(self):
        before = fingerprint(self.root)
        plan = self.plan()
        self.assertEqual(before, fingerprint(self.root))
        self.assertFalse(self.args.output.exists())
        self.assertEqual(6, len(plan["sources"]))
        self.assertEqual(self.rows, [row["recipe"] for row in plan["sources"]])
        self.assertEqual(sources.ACCEPTED_POWER116, plan["acceptedPower116"])
        self.assertIn(str(Path(sources.__file__).resolve()), plan["protocolFingerprint"])

    def test_real_authority_constants_are_exact(self):
        self.assertEqual("D191DADA303689D6575ABC2BE63DD5439499A048F433E84FB0CDEA598D1483BB", sources.TARGET116_SHA256)
        self.assertEqual("c310d0ca63ad9a8408dd4bba75172afafa5c71b7", sources.ACCEPTED_POWER116)

    def test_prepare_rejects_wrong_manifest_before_writes(self):
        with self.assertRaisesRegex(AssertionError, "final accepted"):
            sources.prepare(self.args)
        self.assertFalse(self.args.output.exists())

    def test_prepare_rejects_changed_archived_plan(self):
        with self.pinned(), patch.object(sources, "SOURCE_ROUTES_SHA256", "0" * 64), self.assertRaisesRegex(AssertionError, "Ready source plan"):
            sources.prepare(self.args)

    def test_prepare_rejects_duplicate_or_missing_recipe(self):
        for rows in (self.rows + [self.rows[0]], self.rows[1:]):
            write_json(self.routes, dict(routes=rows))
            with self.subTest(count=len(rows)), self.assertRaisesRegex(AssertionError, "exactly once"):
                self.plan()

    def test_prepare_rejects_changed_world(self):
        (Path(self.rows[0]["source"]) / WORLD / "changed.dat").write_bytes(b"loss")
        with self.assertRaisesRegex(AssertionError, "world fingerprint"):
            self.plan()

    def test_prepare_rejects_wrong_snapshot_hash(self):
        path = Path(self.rows[0]["source"]) / self.rows[0]["sourceSnapshot"]
        path.write_text("[]")
        with self.assertRaisesRegex(AssertionError, "source snapshot"):
            self.plan()

    def test_prepare_rejects_resealed_354_blocks_or_331_tiles(self):
        for change in ("block", "tile"):
            rows = self.frozen_rows()
            if change == "block":
                rows.pop()
            else:
                rows[331].pop("nbtBase64")
            path = Path(self.rows[0]["source"]) / self.rows[0]["sourceSnapshot"]
            write_json(path, rows)
            self.rows[0]["sourceSnapshotSha256"] = digest(path)
            write_json(self.routes, dict(routes=self.rows))
            with self.subTest(change=change), self.assertRaisesRegex(AssertionError, "355 blocks and 332 tiles"):
                self.plan()

    def test_prepare_rejects_output_outside_build_and_existing_output(self):
        for output in (sources.RIG.parent / "forbidden", self.root):
            self.args.output = output
            with self.subTest(output=str(output)), self.assertRaises((ValueError, FileExistsError)):
                self.plan()

    def test_prepare_rejects_output_under_canonical_source(self):
        self.args.output = Path(self.rows[0]["source"]) / "output"
        with self.assertRaisesRegex(ValueError, "overlaps a protected input"):
            self.plan()

    def test_prepare_rejects_target_java16(self):
        (self.java.parent.parent / "release").write_text('JAVA_VERSION="16.0.2"\n')
        with self.assertRaisesRegex(ValueError, "Java 8"):
            self.plan()

    def test_prepare_canonical_lineage_cannot_change_authorities(self):
        self.args.routes = self.root / "canonical117.json"
        rows = copy.deepcopy(self.rows)
        rows[-1]["sourceKey"] = "different"
        write_json(self.args.routes, dict(routes=rows))
        with self.assertRaisesRegex(AssertionError, "archived authority"):
            self.plan()

    def test_protocol_baseline_is_checked(self):
        with patch.dict(sources.RUNNER_BASELINE, {"native116.py": "0" * 64}), self.assertRaisesRegex(AssertionError, "accepted baseline"):
            self.plan()

    def test_settings_keep_java8_snapshot_lineage_and_512_ticks(self):
        settings = sources.source116_settings(self.args, self.rows[-1], self.args.output)
        self.assertEqual((512, "source", "original110", self.java.resolve()),
                         (settings.ticks, settings.source_checkpoint, settings.source_key, settings.java8))

    def test_after_preflight_mutation_is_rejected(self):
        plan = self.plan()
        (self.seed / "libraries/pinned.jar").write_bytes(b"mutated")
        with self.assertRaisesRegex(AssertionError, "sealed inventory"):
            sources.verify_source116_inputs(plan)

    def test_after_preflight_original_runtime_mutation_is_rejected(self):
        plan = self.plan()
        (Path(self.rows[0]["source"]) / "unrelated-state").write_bytes(b"mutated")
        with self.assertRaisesRegex(AssertionError, "canonical source runtime"):
            sources.verify_source116_inputs(plan)

    def test_frozen_recomputes_raw_semantics_and_audits_both_native_saves(self):
        settings, runtime, stops = self.frozen_result()
        with self.saved_format() as (audit, same):
            actual, snapshot, fixture, proof = sources.source116_result("frozen", settings, stops)
        self.assertEqual((runtime, "migration-restarted.json", None), (actual, snapshot, fixture))
        self.assertEqual((355, 332, True), (proof["blocks"], proof["tiles"], proof["stoppedCleanly"]))
        self.assertEqual(2, audit.call_count)
        self.assertEqual(1, same.call_count)

    def test_failed_native_save_audit_is_not_a_pass(self):
        settings, _, stops = self.frozen_result()
        with patch.object(sources.native116_save_io, "audit_save", side_effect=AssertionError("native save corrupt")), self.assertRaisesRegex(AssertionError, "native save corrupt"):
            sources.source116_result("frozen", settings, stops)

    def test_success_exit_does_not_hide_missing_report_gate(self):
        settings, runtime, stops = self.frozen_result()
        write_json(runtime / "state-comparison.json", dict(converted=dict(status="PASS")))
        with self.assertRaisesRegex(AssertionError, "semantic comparison"):
            sources.source116_result("frozen", settings, stops)

    def test_claimed_pass_cannot_hide_lost_tile_or_changed_block(self):
        settings, runtime, stops = self.frozen_result()
        path = runtime / "migration-restarted.json"
        for mutation in ("tile", "block"):
            rows = self.frozen_rows()
            if mutation == "tile":
                rows[331].pop("nbtBase64")
            else:
                rows[-1]["actualName"] = "minecraft:air"
            write_json(path, rows)
            with self.subTest(mutation=mutation), self.assertRaisesRegex(AssertionError, "332 tiles|state/resources"):
                sources.source116_result("frozen", settings, stops)

    def test_inventory_and_jar_tampering_are_rejected(self):
        settings, runtime, stops = self.frozen_result()
        (runtime / "mods/extra.jar").write_bytes(b"unexpected")
        with self.assertRaisesRegex(AssertionError, "final accepted"):
            sources.source116_result("frozen", settings, stops)

    def test_fixture_cannot_be_rebuilt(self):
        settings, runtime, stops = self.frozen_result()
        (runtime / "migration-fixture.json").write_text("[]")
        with self.assertRaisesRegex(AssertionError, "rebuilt or changed"):
            sources.source116_result("frozen", settings, stops)

    def test_stops_require_two_separate_successful_processes_and_exact_commands(self):
        settings, _, stops = self.frozen_result()
        for changed in (stops[:1], [dict(row, returncode=1) for row in stops],
                        [dict(row, commands=["stop"]) for row in stops],
                        [dict(row, pid=111) for row in stops]):
            with self.subTest(stops=changed), self.assertRaisesRegex(AssertionError, "stop|process"):
                sources.source116_result("frozen", settings, changed)

    def test_missing_empty_and_changed_logs_all_fail(self):
        settings, runtime, stops = self.frozen_result()
        path = runtime / "debug-restarted.log"
        original = path.read_bytes()
        for mutation in ("missing", "empty", "changed"):
            path.write_bytes(original)
            if mutation == "missing":
                path.unlink()
            else:
                path.write_bytes(b"" if mutation == "empty" else b"changed")
            with self.subTest(mutation=mutation), self.assertRaises((AssertionError, FileNotFoundError)):
                sources.source116_result("frozen", settings, stops)

    def test_all_three_logs_need_native_header_clean_logs_and_shutdown(self):
        settings, runtime, stops = self.frozen_result()
        path = runtime / "debug-restarted.log"
        original = path.read_text()
        for bad in (original.replace("1.16.5", "1.17.1"), original + "[main/ERROR] failure\n",
                    original + "Missing mappings\n", original.replace("All chunks are saved", "")):
            path.write_text(bad)
            altered = copy.deepcopy(stops)
            altered[-1]["logs"][path.name] = digest(path)
            with self.subTest(bad=bad[-60:]), self.assertRaises(AssertionError):
                sources.source116_result("frozen", settings, altered)

    def test_connected_two_exact_intervals_recompute_unchanged_oracles(self):
        settings, runtime, stops = self.connected_result()
        with self.saved_format() as (audit, _):
            actual, snapshot, fixture, proof = sources.source116_result("connected", settings, stops)
        self.assertEqual((runtime, "restarted-worked", "original110"), (actual, snapshot, fixture))
        self.assertEqual(([512, 512], [512, 512]), (proof["requestedTicks"], proof["advancedTicks"]))
        self.assertEqual(2, audit.call_count)

    def test_claimed_connected_pass_cannot_hide_511_ticks_or_a_rebuild(self):
        settings, runtime, stops = self.connected_result()
        path = runtime / "connected-restarted-worked.json"
        original = json.loads(path.read_text())
        for key, value in (("advancedTicks", 511), ("buildCallsThisProcess", 1), ("gameTime", 612)):
            write_json(path, dict(original, **{key: value}))
            with self.subTest(key=key), self.assertRaisesRegex(AssertionError, "512-tick|construction"):
                sources.source116_result("connected", settings, stops)

    def test_connected_claimed_pass_cannot_hide_empty_or_failed_semantic_gates(self):
        settings, _, stops = self.connected_result()
        path = settings.output / "native116-connected-results.json"
        original = json.loads(path.read_text())
        for changed in ({}, dict(original["results"], converted_completed_work=dict(status="INCOMPLETE"))):
            write_json(path, dict(original, results=changed))
            with self.assertRaisesRegex(AssertionError, "operating gates"):
                sources.source116_result("connected", settings, stops)

    def test_connected_migration_snapshot_must_equal_observed_snapshot(self):
        settings, runtime, stops = self.connected_result()
        write_json(runtime / "migration-restarted-worked.json", [])
        with self.assertRaisesRegex(AssertionError, "snapshots disagree"):
            sources.source116_result("connected", settings, stops)

    def test_adapter_refuses_canonical_frozen_launch_before_popen(self):
        settings = sources.source116_settings(self.args, self.rows[0], self.args.output)
        def wrong(arguments):
            sources.native116.start_snapshot(Path(self.rows[0]["source"]), self.java, native116.LAUNCHER, "converted")
        with patch.object(sources.native116, "run", wrong), self.assertRaisesRegex(AssertionError, "NEW disposable"):
            sources.run_source116(settings, "frozen")

    def test_adapter_refuses_canonical_connected_launch_before_popen(self):
        settings = sources.source116_settings(self.args, self.rows[-1], self.args.output)
        def wrong(arguments):
            sources.native116_connected.NativeConnectedServer(Path(self.rows[-1]["source"]), self.java, native116.LAUNCHER, "converted", lambda label: None)
        with patch.object(sources.native116_connected, "run", wrong), self.assertRaisesRegex(AssertionError, "NEW disposable"):
            sources.run_source116(settings, "connected")

    def test_validate_only_never_creates_output_or_calls_runner(self):
        self.args.validate_only = True
        with self.pinned(), patch.object(sources, "run_source116", side_effect=AssertionError("no lease")):
            self.assertEqual(0, sources.run(self.args))
        self.assertFalse(self.args.output.exists())

    def receipt(self):
        settings, runtime, stops = self.frozen_result()
        plan = self.plan()
        selected = plan["sources"][0]
        provenance = self.root / "source116-input-provenance.json"
        write_json(provenance, plan)
        with self.saved_format():
            _, snapshot, fixture, proof = sources.source116_result("frozen", settings, stops)
        path = self.root / "source116-receipt.json"
        receipt = dict(status="PASS", scope=sources.SCOPE, acceptedPower116=sources.ACCEPTED_POWER116,
            id=selected["id"], kind=selected["kind"], sourceRuntime=str(runtime.resolve()),
            runtimeFingerprint=fingerprint(runtime), worldFingerprint=fingerprint(runtime / WORLD),
            sourceRecipe=selected["recipe"], target116Manifest=str(self.manifest.resolve()),
            target116ManifestSha256=digest(self.manifest), java8=str(self.java.resolve()), port=self.args.port,
            inputProvenance=str(provenance.resolve()), inputProvenanceSha256=digest(provenance), **proof)
        write_json(path, receipt)
        row = sources._source_row(self.root, selected, runtime, snapshot, fixture, self.manifest)
        row.update(sourceStatus=sources.SOURCE_STATUS, source116Receipt=str(path), source116ReceiptSha256=digest(path))
        return path, receipt, row

    def test_receipt_replays_raw_evidence_and_rejects_later_world_change(self):
        _, _, row = self.receipt()
        with self.pinned(), self.saved_format():
            self.assertEqual("PASS", sources.verify_source_receipt(row)["status"])
            (Path(row["source"]) / WORLD / "changed").write_bytes(b"changed")
            with self.assertRaisesRegex(AssertionError, "fingerprint"):
                sources.verify_source_receipt(row)

    def test_receipt_cannot_change_scope_baseline_stop_or_route(self):
        path, original, row = self.receipt()
        for key, value in (("scope", "DESTINATION_PASS"), ("acceptedPower116", "0" * 40),
                           ("stoppedCleanly", False), ("id", "native116/wrong"), ("status", "UNQUALIFIED")):
            write_json(path, dict(original, **{key: value}))
            changed = dict(row, source116ReceiptSha256=digest(path))
            with self.subTest(key=key), self.pinned(), self.saved_format(), self.assertRaises(AssertionError):
                sources.verify_source_receipt(changed)

    def test_receipt_cannot_change_checkpoint_or_fixture_key(self):
        _, _, row = self.receipt()
        for key, value in (("sourceSnapshot", "migration-converted.json"), ("sourceFixtureKey", "wrong"), ("sourceKey", "legacy")):
            with self.subTest(key=key), self.pinned(), self.saved_format(), self.assertRaises(AssertionError):
                sources.verify_source_receipt(dict(row, **{key: value}))

    def test_changed_receipt_cannot_be_accepted_with_old_hash(self):
        path, _, row = self.receipt()
        path.write_text("{}")
        with self.assertRaisesRegex(AssertionError, "source116 receipt"):
            sources.verify_source_receipt(row)

    def test_failed_runner_keeps_fail_receipt_without_promoting_routes(self):
        with self.pinned(), patch.object(sources, "run_source116", return_value=(1, [])), self.assertRaisesRegex(AssertionError, "protocol failed"):
            sources.run(self.args)
        bundle = json.loads((self.args.output / "source116-receipts.json").read_text())
        self.assertEqual(("FAIL", []), (bundle["status"], bundle["receipts"]))
        receipt = json.loads((self.args.output / "native116-full/source116-receipt.json").read_text())
        self.assertEqual("FAIL", receipt["status"])
        self.assertFalse(receipt["stoppedCleanly"])
        routes = json.loads((self.args.output / "source116-routes.json").read_text())["routes"]
        self.assertTrue(all(row["sourceStatus"].startswith("REQUIRES_APPROVED") for row in routes))


if __name__ == "__main__":
    unittest.main()
