"""Create stopped 1.16 sources for 1.17; never promote an old destination PASS.

``prepare`` is read-only. ``run`` launches only NEW disposable copies through the
unchanged native116 runners. It requires a separately leased port. This is source
approval only: a successful receipt says nothing about 1.17 migration or operation.
"""

import argparse
from argparse import Namespace
import hashlib
import json
from pathlib import Path
import re
import shutil
import traceback
import types
from unittest.mock import patch

import connected
import matrix
import native116
import native116_code
import native116_connected
import native116_save_io
from matrix import WORLD, digest, fingerprint, write_json
from native116_resource_audit import compare_snapshot_resources
from semantic_machine_audit import connected_interval


RIG = Path(__file__).resolve().parents[1]
ACCEPTED_POWER116 = "c310d0ca63ad9a8408dd4bba75172afafa5c71b7"
TARGET116_MANIFEST = Path("D:/PowerAdvantage/_verification/backport-coverage-20261030/pa116-candidate-c/inputs.json")
TARGET116_SHA256 = "D191DADA303689D6575ABC2BE63DD5439499A048F433E84FB0CDEA598D1483BB"
SOURCE_ROUTES = Path("D:/PowerAdvantage/_verification/advantage-116-port-20261027/Source Recovery B/native116-upgrade-routes-ready.json")
SOURCE_ROUTES_SHA256 = "CB2C1D8151D3C8262B057A888AB826278B66646426410B31E4B5EA056A5892D9"
HISTORICAL = ("early110", "legacy112", "maintained110", "maintained112", "original110")
SOURCE116 = {"frozen": {"native116/full"}, "connected": {"native116/" + name for name in HISTORICAL}}
SCOPE = "DISPOSABLE_NATIVE116_SOURCE_ONLY"
SOURCE_STATUS = "GENERATED_DISPOSABLE_116_VERIFIED"
CONNECTED_CASES = {"C-CONVEYOR", "C-CRUSHER", "C-EDRILL", "C-ELECTRIC", "C-SDRILL", "C-STEAM", "battery"}
# Git's LF-normalized bytes at the accepted baseline; runtime custody uses raw bytes.
RUNNER_BASELINE = {
    "matrix.py": "F0B1C19645464583E776849A82BE3F8EAAC5129A2B17614F222E7AAFA1E2B28E",
    "connected.py": "9E34BAE4E9FDF43AC58C9897FA2F813C53C97145FBDE472102476DCAB4E1051E",
    "native116.py": "5C4B2E615B7CBC7B679F91E782E9F7A314EC4B1DD59E7BE5A5A025520B8C4D7D",
    "native116_connected.py": "34BD1E66EE427980473FC9ACC8031E3BDC195D04C8C05A4CA3E50F30398AAE12",
}


def _read(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def _approved(path, checksum, label):
    path = Path(path).resolve()
    if digest(path) != checksum.upper():
        raise AssertionError("Changed " + label + ": " + str(path))
    return path


def _disjoint(output, protected):
    native116.require_disjoint(Path(output), Path(protected))


def _new_output(path):
    path = Path(path).resolve()
    if not path.is_relative_to((RIG / "build").resolve()) or path == (RIG / "build").resolve():
        raise ValueError("Source116 outputs must be NEW paths under Power117/test-rig/build")
    if path.exists():
        raise FileExistsError(path)
    return path


def _protocol():
    root = Path(__file__).parent
    for name, checksum in RUNNER_BASELINE.items():
        actual = hashlib.sha256((root / name).read_bytes().replace(b"\r\n", b"\n")).hexdigest().upper()
        if actual != checksum:
            raise AssertionError("Historical source runner differs from accepted baseline: " + name)
    names = {"matrix.py", "connected.py", "actors.py", "transformer_order.py", "semantic_machine_audit.py"}
    names.update(path.name for path in root.glob("native116*.py") if not path.name.startswith("test_"))
    names.add(Path(__file__).name)
    return {str((root / name).resolve()): digest(root / name) for name in sorted(names)}


def pending_routes():
    return [dict(id=identity, kind=kind, sourceStatus="REQUIRES_APPROVED_DISPOSABLE_116_SOURCE")
            for kind, identities in SOURCE116.items() for identity in sorted(identities)]


def source116_settings(args, recipe, output):
    return Namespace(source=Path(recipe["source"]), source_manifest=Path(recipe["sourceManifest"]),
        source_key=recipe["sourceKey"], source_hashes=Path(recipe["sourceHashes"]),
        source_snapshot=recipe["sourceSnapshot"], source_checkpoint=recipe["sourceSnapshot"],
        source_fixture_key=recipe.get("sourceFixtureKey"), target_manifest=Path(args.target116_manifest).resolve(),
        java8=Path(args.java8).resolve(), output=Path(output), ticks=512, port=args.port)


def _java8(java):
    java = Path(java).resolve()
    release = java.parent.parent / "release"
    if not java.is_file() or not re.search(r'^JAVA_VERSION="1\.8\.', release.read_text(), re.MULTILINE):
        raise ValueError("Source116 generation requires an installed Java 8, not the target's Java 16")
    return {str(java): digest(java), str(release): digest(release)}


def prepare(args):
    """Validate six archived recipes and final accepted 1.16 inputs; no writes/launch."""
    if type(args.port) is not int or not 1 <= args.port <= 65535:
        raise ValueError("An explicitly leased server port is required")
    output = _new_output(args.output)
    manifest = _approved(args.target116_manifest, TARGET116_SHA256, "final accepted native116 manifest")
    routes = _approved(args.source_routes, SOURCE_ROUTES_SHA256, "archived Ready source plan")
    target = native116.pinned_profile(manifest, "target116")
    identities = {entry["id"] for entry in _read(manifest)["target116"]["jars"]}
    if not {"advantageworkstest", "poweradvantage", "steamadvantage", "electricadvantage", "basemetals", "orespawn"}.issubset(identities):
        raise AssertionError("The final accepted source manifest must include all three mods and its harness/dependencies")
    files = {str(path): digest(path) for path in (manifest, routes)}
    files.update(_java8(args.java8))
    files.update(_protocol())
    for protected in (manifest, routes, target[0], *target[2], *map(Path, files)):
        _disjoint(output, protected)
    canonical = getattr(args, "routes", None)
    if canonical is not None:
        canonical = Path(canonical).resolve()
        _disjoint(output, canonical)
        files[str(canonical)] = digest(canonical)
    recipes, rows, runtimes = _read(routes)["routes"], [], {}
    selections = [("frozen", "native116/full", "legacy/original110-chained-113")]
    selections += [("connected", "native116/" + name, "legacy/" + name) for name in HISTORICAL]
    for kind, identity, selected in selections:
        matches = [row for row in recipes if row["kind"] == kind and row["id"] == selected]
        if len(matches) != 1:
            raise AssertionError("Each archived source recipe is required exactly once: " + selected)
        recipe = matches[0]
        if canonical is not None:
            found = [row for row in _read(canonical)["routes"] if row["kind"] == kind and row["id"] == selected]
            if len(found) != 1 or any(found[0].get(key) != value for key, value in recipe.items()):
                raise AssertionError("Canonical route changed archived authority: " + selected)
        source = Path(recipe["source"]).resolve()
        _disjoint(output, source)
        pins = _approved(recipe["sourceManifest"], recipe["sourceManifestSha256"], "source manifest")
        hashes = _approved(recipe["sourceHashes"], recipe["sourceHashesSha256"], "source world hashes")
        name = recipe["sourceSnapshot"] if kind == "frozen" else "migration-" + recipe["sourceSnapshot"] + ".json"
        if not re.fullmatch(r"migration-[A-Za-z0-9_-]+\.json", name):
            raise ValueError("Unsafe archived snapshot name")
        snapshot = _approved(source / name, recipe["sourceSnapshotSha256"], "source snapshot")
        native116.pinned_profile(pins, recipe["sourceKey"])
        if not _read(hashes) or fingerprint(source / WORLD) != _read(hashes):
            raise AssertionError("Archived source world fingerprint changed: " + str(source))
        fixture = source / "migration-fixture.json"
        if not fixture.is_file():
            raise FileNotFoundError("Missing canonical migration fixture")
        if kind == "frozen":
            document = _read(snapshot)
            if (len(document), sum("nbtBase64" in row for row in document)) != (355, 332):
                raise AssertionError("Frozen source must retain all 355 blocks and 332 tiles")
        else:
            native116_connected.verify_source(source116_settings(args, recipe, output / identity.replace("/", "-")))
            files[str((source / "connected-fixture.json").resolve())] = digest(source / "connected-fixture.json")
        for path in (pins, hashes, snapshot, fixture):
            _disjoint(output, path)
            files[str(path.resolve())] = digest(path)
        runtimes[str(source)] = fingerprint(source)
        rows.append(dict(id=identity, kind=kind, recipe=recipe))
    return dict(format=1, output=str(output), acceptedPower116=ACCEPTED_POWER116,
        target116Manifest=str(manifest), target116ManifestSha256=digest(manifest),
        sourcePlan=str(routes), sourcePlanSha256=digest(routes), sources=rows, files=files,
        protocolFingerprint=_protocol(), protectedRuntimes=runtimes, seed=str(target[0]),
        seedCode=native116_code.verify_code(target[0]))


def verify_source116_inputs(plan):
    if plan.get("acceptedPower116") != ACCEPTED_POWER116:
        raise AssertionError("Source plan accepted baseline changed")
    for name, checksum in plan["files"].items():
        _approved(name, checksum, "source-generation input/protocol")
    if _protocol() != plan["protocolFingerprint"]:
        raise AssertionError("Source-generation protocol fingerprint changed")
    native116_code.verify_code(Path(plan["seed"]), plan["seedCode"])
    for name, before in plan["protectedRuntimes"].items():
        if fingerprint(Path(name)) != before:
            raise AssertionError("Protected canonical source runtime changed: " + name)


def _commands(kind, label):
    actions = (["advprobe snapshot " + label] if kind == "frozen" else
               ["advconnected snapshot " + label + "-loaded", "advconnected step 512 " + label + "-worked"])
    return actions + ["save-all flush", "stop"]


def run_source116(settings, kind):
    """Inject process observation only, leaving the source runners/oracles intact."""
    if kind not in SOURCE116 or settings.ticks != 512:
        raise ValueError("Exactly 512 measured ticks are required for source generation")
    stops = []
    runtime = settings.output if kind == "frozen" else settings.output / "target116"

    class Observation:
        def before_boot(self, current, java, launcher, label):
            if (current.resolve() != runtime.resolve() or launcher != native116.LAUNCHER
                    or Path(java).resolve() != settings.java8.resolve() or label not in ("converted", "restarted")):
                raise AssertionError("Only the NEW disposable native116 copy may launch")
            self.observed_label, self.observed_closed = label, False
            self.previous_logs = {name: digest(current / "logs" / name) if (current / "logs" / name).is_file() else None
                                  for name in ("latest.log", "debug.log")}
            for name in ("console-" + label + ".log", "latest-" + label + ".log", "debug-" + label + ".log"):
                if (current / name).exists():
                    raise FileExistsError("Refusing to replace source evidence: " + name)

        def close(self):
            if self.observed_closed:
                return
            try:
                return super().close()
            finally:
                self.observed_closed = True
                record = dict(label=self.observed_label, returncode=self.process.returncode,
                    pid=self.process.pid, commands=list(self.commands), logs={})
                stops.append(record)
                for name in ("latest.log", "debug.log"):
                    path = runtime / "logs" / name
                    if not path.is_file() or not path.stat().st_size or digest(path) == self.previous_logs[name]:
                        raise AssertionError("Missing or stale source boot log: " + str(path))
                    archive = runtime / (name.removesuffix(".log") + "-" + self.observed_label + ".log")
                    if archive.exists():
                        raise FileExistsError(archive)
                    shutil.copy2(path, archive)
                    record["logs"][archive.name] = digest(archive)
                record["logs"]["console-" + self.observed_label + ".log"] = digest(Path(self.log.name))

    if kind == "frozen":
        class FrozenServer(Observation, native116.Server):
            def __init__(self, current, java, launcher, label):
                self.before_boot(current, java, launcher, label)
                self.commands = []
                super().__init__(current, java, launcher, label)

            def send(self, command):
                if command not in _commands("frozen", self.observed_label):
                    raise AssertionError("Forbidden source-generation mutation: " + command)
                self.commands.append(command)
                super().send(command)

        snapshot = types.FunctionType(native116.start_snapshot.__code__,
            dict(native116.start_snapshot.__globals__, Server=FrozenServer),
            native116.start_snapshot.__name__, native116.start_snapshot.__defaults__)
        original_stage = native116.stage

        def stage(current, target):
            if current.resolve() != runtime.resolve():
                raise AssertionError("Refusing to stage outside the NEW native116 copy")
            launcher = original_stage(current, target)
            path = current / "server.properties"
            lines = [line for line in path.read_text().splitlines() if not line.startswith("server-port=")]
            path.write_text("\n".join(lines) + f"\nserver-port={settings.port}\n", encoding="utf-8")
            return launcher

        with patch.object(native116, "start_snapshot", snapshot), patch.object(native116, "stage", stage):
            code = native116.run(settings)
    else:
        class ConnectedServer(Observation, native116_connected.NativeConnectedServer):
            def __init__(self, current, java, launcher, label, on_loaded):
                self.before_boot(current, java, launcher, label)
                super().__init__(current, java, launcher, label, on_loaded)

            def send(self, command):
                if command not in _commands("connected", self.observed_label):
                    raise AssertionError("Forbidden source-generation mutation: " + command)
                super().send(command)

        with patch.object(native116_connected, "NativeConnectedServer", ConnectedServer):
            code = native116_connected.run(settings)
    return code, stops


def _logs_and_stops(runtime, kind, stops):
    if [row.get("label") for row in stops] != ["converted", "restarted"]:
        raise AssertionError("Both source boots must be cleanly saved and stopped")
    for row in stops:
        label = row["label"]
        if (row.get("returncode") != 0 or type(row.get("pid")) is not int or row["pid"] <= 0
                or row.get("commands") != _commands(kind, label)):
            raise AssertionError("Both source boots need exact commands and a clean stop/exit status")
        names = {prefix + "-" + label + ".log" for prefix in ("console", "latest", "debug")}
        if set(row.get("logs", {})) != names:
            raise AssertionError("All console/latest/debug source boot logs are required")
        for name in sorted(names):
            path = _approved(runtime / name, row["logs"][name], "stopped-source log")
            if not path.stat().st_size:
                raise AssertionError("Empty source boot log")
            native116_connected.require_native_boot(path)
            text = path.read_text(encoding="utf-8", errors="replace")
            if any(re.search(pattern, text, re.IGNORECASE) for pattern in (
                    r"Missing minecraft:", r"missing (?:registry entries|mappings)\b",
                    r"unidentified mapping", r"registry remapping failed")):
                raise AssertionError("Missing mappings in native116 source boot")
            for marker in ("Saved the game", "Stopping server", "Saving worlds", "All chunks are saved"):
                if marker not in text:
                    raise AssertionError("Missing actual source save/shutdown evidence: " + marker)
    if stops[0]["pid"] == stops[1]["pid"]:
        raise AssertionError("Source restart must use a separate process")


def source116_result(kind, settings, stops):
    """Recheck raw evidence, including saved-format/resource and operation oracles."""
    runtime = settings.output if kind == "frozen" else settings.output / "target116"
    _logs_and_stops(runtime, kind, stops)
    target = native116.pinned_profile(settings.target_manifest, "target116")
    native116_code.verify_code(runtime, _read(settings.target_manifest)["target116"]["serverCode"])
    pins = sorted((jar.name, digest(jar)) for jar in target[2])
    if (sorted((jar.name, digest(jar)) for jar in (runtime / "mods").glob("*.jar")) != pins
            or sorted((row["file"], row["sha256"].upper()) for row in _read(runtime / "input-jars.json")) != pins):
        raise AssertionError("Generated source mods differ from final accepted native116 pins")
    if digest(runtime / "migration-fixture.json") != digest(settings.source / "migration-fixture.json"):
        raise AssertionError("Source fixture specification was rebuilt or changed")
    proof = dict(stoppedCleanly=True, processStops=stops, nativeVersion="1.16.5", codeInventoryVerified=True)
    source_snapshot = settings.source / (settings.source_snapshot if kind == "frozen" else
                                         "migration-" + settings.source_checkpoint + ".json")
    if kind == "frozen":
        report = _read(runtime / "state-comparison.json")
        for gate in ("converted", "restarted", "idempotence"):
            result = report.get(gate, {})
            if result.get("status") != "PASS" or any(result.get("witnesses", {}).get(key) != count
                    for key, count in (("blocks", 355), ("tiles", 332))):
                raise AssertionError("Source116 comprehensive semantic comparison/counts failed")
        for label in ("converted", "restarted"):
            path = runtime / ("migration-" + label + ".json")
            document = _read(path)
            if (len(document), sum("nbtBase64" in row for row in document)) != (355, 332):
                raise AssertionError("Source116 must retain all 355 blocks and 332 tiles")
            if (matrix.compare(source_snapshot, path, native116.native_normalized)["status"] != "PASS"
                    or compare_snapshot_resources(source_snapshot, path)["status"] != "PASS"):
                raise AssertionError("Raw frozen source state/resources were not preserved")
        save = _read(runtime / "native116-provenance.json")["nativeSaveFormat"]
        proof.update(blocks=355, tiles=332, semanticComparison=report)
        snapshot, fixture = "migration-restarted.json", None
    else:
        report = _read(settings.output / "native116-connected-results.json")
        required = {label + "_" + gate for label in ("converted", "restarted")
                    for gate in ("preservation", "exact_resources", "operation", "completed_work", "native_boot")}
        required.add("real_output_across_restarts")
        if (report.get("status") != "PASS" or report.get("sourcePreservation", {}).get("status") != "PASS"
                or report.get("sourceRuntimeBefore") != fingerprint(settings.source)
                or report.get("sourceRuntimeAfter") != fingerprint(settings.source)
                or set(report.get("results", {})) != required
                or any(row.get("status") != "PASS" for row in report["results"].values())):
            raise AssertionError("Source116 connected preservation/operating gates failed")
        definition = _read(runtime / "connected-fixture.json")
        if definition != _read(settings.source / "connected-fixture.json"):
            raise AssertionError("Connected source fixture changed")
        requested, advanced = [], []
        previous = source_snapshot
        for label in ("converted", "restarted"):
            loaded_path = runtime / ("migration-" + label + "-loaded.json")
            loaded = connected.read(runtime, label + "-loaded")
            worked = connected.read(runtime, label + "-worked")
            if loaded.get("requestedTicks") != 0 or loaded.get("advancedTicks") != 0:
                raise AssertionError("Loaded source must be frozen before operation")
            for suffix, document in (("loaded", loaded), ("worked", worked)):
                state = native116_connected.require_document(document, definition, destination=True)
                if set(state["cases"]) != CONNECTED_CASES:
                    raise AssertionError("Source116 lost a connected lane/drill/battery")
                if document["snapshot"] != _read(runtime / ("migration-" + label + "-" + suffix + ".json")):
                    raise AssertionError("Connected and migration snapshots disagree")
            if (matrix.compare(previous, loaded_path, native116.native_normalized)["status"] != "PASS"
                    or compare_snapshot_resources(previous, loaded_path)["status"] != "PASS"):
                raise AssertionError("Raw connected source state/resources were not preserved")
            if (connected.operation(loaded, worked, 512)["status"] != "PASS"
                    or connected_interval(loaded, worked, definition, 512)["status"] != "PASS"):
                raise AssertionError("Source116 requires exact 512-tick completed work on both boots")
            requested.append(worked["requestedTicks"])
            advanced.append(worked["advancedTicks"])
            previous = runtime / ("migration-" + label + "-worked.json")
        if requested != [512, 512] or advanced != [512, 512]:
            raise AssertionError("Source116 requires both exact 512-tick boots")
        if native116_connected.continued_output(connected.read(runtime, "converted-loaded"),
                connected.read(runtime, "restarted-worked"))["status"] != "PASS":
            raise AssertionError("Connected source did not produce across full restart")
        save = report["nativeSaveFormat"]
        proof.update(preservationStatus="PASS", requestedTicks=requested, advancedTicks=advanced,
            connectedCases=sorted(CONNECTED_CASES), operatingComparison=report["results"])
        snapshot, fixture = "restarted-worked", definition["sourceProfile"]
    for label in ("converted", "restarted"):
        native116_save_io.audit_save(runtime, save[label])
    native116_save_io.require_same_settings(save["converted"], save["restarted"])
    proof["nativeSaveFormat"] = save
    return runtime, snapshot, fixture, proof


def _source_row(folder, selected, runtime, snapshot, fixture, manifest):
    hashes = folder / "world-hashes.json"
    write_json(hashes, fingerprint(runtime / WORLD))
    path = runtime / (snapshot if selected["kind"] == "frozen" else "migration-" + snapshot + ".json")
    return dict(id=selected["id"], kind=selected["kind"], source=str(runtime.resolve()),
        sourceKey="target116", sourceManifest=str(manifest), sourceManifestSha256=digest(manifest),
        sourceHashes=str(hashes.resolve()), sourceHashesSha256=digest(hashes),
        sourceSnapshot=snapshot, sourceSnapshotSha256=digest(path), sourceFixtureKey=fixture)


def verify_source_receipt(row):
    """Required fail-closed guard for every native116/* 1.17 route."""
    kind, identity = row.get("kind"), row.get("id")
    if kind not in SOURCE116 or identity not in SOURCE116[kind]:
        raise AssertionError("Unknown or mismatched Source116 route")
    if row.get("sourceStatus") != SOURCE_STATUS:
        raise AssertionError("An explicit stopped-source receipt is required; prior destination PASS is not approval")
    path = _approved(row["source116Receipt"], row["source116ReceiptSha256"], "source116 receipt")
    receipt = _read(path)
    runtime = Path(row["source"]).resolve()
    if not runtime.is_relative_to((RIG / "build").resolve()):
        raise ValueError("Approved source must remain a disposable Power117 copy")
    if (receipt.get("status") != "PASS" or receipt.get("scope") != SCOPE
            or receipt.get("acceptedPower116") != ACCEPTED_POWER116 or receipt.get("stoppedCleanly") is not True
            or (receipt.get("id"), receipt.get("kind")) != (identity, kind)
            or receipt.get("sourceRuntime") != str(runtime)
            or receipt.get("runtimeFingerprint") != fingerprint(runtime)
            or receipt.get("worldFingerprint") != fingerprint(runtime / WORLD)):
        raise AssertionError("Source116 stopped-source identity/fingerprint changed or was never approved")
    plan_path = _approved(receipt["inputProvenance"], receipt["inputProvenanceSha256"], "source116 provenance")
    plan = _read(plan_path)
    verify_source116_inputs(plan)
    selections = [item for item in plan["sources"] if item["id"] == identity and item["kind"] == kind]
    if len(selections) != 1 or selections[0]["recipe"] != receipt["sourceRecipe"]:
        raise AssertionError("Source116 archived recipe authority changed")
    manifest = _approved(receipt["target116Manifest"], TARGET116_SHA256, "final accepted native116 manifest")
    if (receipt.get("target116ManifestSha256") != TARGET116_SHA256 or row.get("sourceKey") != "target116"
            or Path(row["sourceManifest"]).resolve() != manifest or row.get("sourceManifestSha256") != TARGET116_SHA256):
        raise AssertionError("Source116 manifest/route authority changed")
    args = Namespace(target116_manifest=manifest, java8=receipt["java8"], port=receipt["port"])
    settings = source116_settings(args, receipt["sourceRecipe"], runtime if kind == "frozen" else runtime.parent)
    actual, snapshot, fixture, proof = source116_result(kind, settings, receipt["processStops"])
    if any(receipt.get(key) != value for key, value in proof.items()):
        raise AssertionError("Source116 receipt no longer matches the raw stopped evidence")
    if (actual.resolve() != runtime or row.get("sourceSnapshot") != snapshot or row.get("sourceFixtureKey") != fixture):
        raise AssertionError("Source116 checkpoint/fixture identity changed")
    hashes = _approved(row["sourceHashes"], row["sourceHashesSha256"], "generated source world hashes")
    if _read(hashes) != fingerprint(runtime / WORLD):
        raise AssertionError("Generated source world hash authority differs")
    filename = snapshot if kind == "frozen" else "migration-" + snapshot + ".json"
    _approved(runtime / filename, row["sourceSnapshotSha256"], "generated source snapshot")
    return receipt


def run(args):
    """Generate exactly six independent sources; retain failures and partial evidence."""
    plan = prepare(args)
    if getattr(args, "validate_only", False):
        print(json.dumps(dict(status="PREFLIGHT_ONLY_NO_RUNTIME", sourceCount=len(plan["sources"]),
            target116ManifestSha256=plan["target116ManifestSha256"], output=plan["output"]), indent=2))
        return 0
    output = Path(plan["output"])
    output.mkdir(parents=True, exist_ok=False)
    provenance = output / "source116-input-provenance.json"
    write_json(provenance, plan)
    bundle = dict(format=1, status="RUNNING", scope=SCOPE, acceptedPower116=ACCEPTED_POWER116, receipts=[])
    routes = dict(format=1, qualificationStatus="UNQUALIFIED", acceptedPower116=ACCEPTED_POWER116, routes=pending_routes())
    try:
        for selected in plan["sources"]:
            verify_source116_inputs(plan)
            folder = output / selected["id"].replace("/", "-")
            folder.mkdir()
            settings = source116_settings(args, selected["recipe"], folder / "runtime")
            receipt_path = folder / "source116-receipt.json"
            receipt = dict(status="RUNNING", scope=SCOPE, acceptedPower116=ACCEPTED_POWER116,
                id=selected["id"], kind=selected["kind"], stoppedCleanly=False,
                target116Manifest=plan["target116Manifest"], target116ManifestSha256=plan["target116ManifestSha256"],
                sourceRecipe=selected["recipe"], java8=str(settings.java8), port=args.port,
                inputProvenance=str(provenance.resolve()), inputProvenanceSha256=digest(provenance))
            write_json(receipt_path, receipt)
            try:
                code, stops = run_source116(settings, selected["kind"])
                if code != 0:
                    raise AssertionError("Native116 source-generation protocol failed")
                runtime, snapshot, fixture, proof = source116_result(selected["kind"], settings, stops)
                verify_source116_inputs(plan)
                receipt.update(proof, status="PASS", sourceRuntime=str(runtime.resolve()),
                    runtimeFingerprint=fingerprint(runtime), worldFingerprint=fingerprint(runtime / WORLD))
                write_json(receipt_path, receipt)
                row = _source_row(folder, selected, runtime, snapshot, fixture, settings.target_manifest)
                row.update(sourceStatus=SOURCE_STATUS, source116Receipt=str(receipt_path.resolve()),
                    source116ReceiptSha256=digest(receipt_path))
                verify_source_receipt(row)
                index = next(i for i, item in enumerate(routes["routes"]) if item["id"] == selected["id"])
                routes["routes"][index] = row
                bundle["receipts"].append(dict(id=selected["id"], receipt=str(receipt_path.resolve()), sha256=digest(receipt_path)))
                print("PASS stopped source " + selected["id"] + "; 1.17 remains UNQUALIFIED", flush=True)
            except BaseException as error:
                receipt.update(status="FAIL", failure=str(error), traceback=traceback.format_exc())
                write_json(receipt_path, receipt)
                raise
            finally:
                write_json(output / "source116-routes.json", routes)
                write_json(output / "source116-receipts.json", bundle)
        verify_source116_inputs(plan)
        bundle.update(status="PASS", canonicalSourcesUnchanged=True, seedCodeUnchanged=True)
    except BaseException as error:
        bundle.update(status="FAIL", failure=str(error), traceback=traceback.format_exc())
        raise
    finally:
        try:
            verify_source116_inputs(plan)
        except BaseException as error:
            bundle.update(status="FAIL", protectionFailure=str(error))
            raise
        finally:
            write_json(output / "source116-receipts.json", bundle)
            write_json(output / "source116-routes.json", routes)
    return 0


# Matching names for the existing native115 guard's campaign integration pattern.
preflight_source116 = prepare
require_source116_receipt = verify_source_receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("prepare", "run"):
        command = commands.add_parser(name)
        command.add_argument("--source-routes", type=Path, default=SOURCE_ROUTES)
        command.add_argument("--target116-manifest", type=Path, default=TARGET116_MANIFEST)
        command.add_argument("--routes", type=Path, help="Optional canonical 1.17 recipe lineage check; never modified")
        command.add_argument("--java8", type=Path, required=True)
        command.add_argument("--port", type=int, required=True, help="Port leased by the caller")
        command.add_argument("--output", type=Path, required=True)
        command.add_argument("--validate-only", action="store_true")
    verify = commands.add_parser("verify")
    verify.add_argument("--routes", type=Path, required=True, help="Generated source116-routes.json")
    args = parser.parse_args()
    if args.command == "verify":
        rows = _read(args.routes)["routes"]
        if len(rows) != 6 or {(row["kind"], row["id"]) for row in rows} != {
                (kind, name) for kind, names in SOURCE116.items() for name in names}:
            raise AssertionError("Exactly six stopped source116 receipts are required")
        for row in rows:
            verify_source_receipt(row)
        print("PASS six stopped native116 sources; 1.17 remains UNQUALIFIED")
        return 0
    args.validate_only = args.command == "prepare" or args.validate_only
    return run(args)


if __name__ == "__main__":
    raise SystemExit(main())
