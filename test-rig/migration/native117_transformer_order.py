"""Observe real Forge 37 transformer callbacks and their emitted migration hooks.

The three shared class targets can be registered in either order. Worldgen guards
use different methods, and Power's entity-storage transformer is unpaired.
"""

import argparse
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import time
from collections import Counter, defaultdict

from native117 import boot as native117_boot
import actors
from native117_actor_io import capture_actors, capture_dimensions
from native117_actors import ready_entities
from native117_stopped_entities import audit_stopped
import transformer_order as historical
from matrix import WORLD, compare, copy_fixture, digest, fingerprint, write_json
from native117_code import Server
from native117_observation_plan import BRIDGE_SOURCE, CUSTODY, make_plan, seal_runtime, audit_runtime, read
from native117 import LEGACY_POWER, native_normalized, output_path, pinned_profile, require_clean_log, require_disjoint


# These are the packaged Forge 37.1.1 installer libraries, not the newer dev-cache versions.
COREMODS = Path("libraries/net/minecraftforge/coremods/5.0.1/coremods-5.0.1.jar")
BRIDGE_LIBRARIES = (
    "org/ow2/asm/asm/9.1/asm-9.1.jar", "org/ow2/asm/asm-tree/9.1/asm-tree-9.1.jar",
    "cpw/mods/modlauncher/9.0.7/modlauncher-9.0.7.jar",
    "com/google/code/gson/gson/2.8.0/gson-2.8.0.jar",
)
SUFFIXES = {
    "world_info": ("net/minecraft/world/level/storage/LevelStorageSource", ["prepareLegacyWorld"]),
    "chunk_status": ("net/minecraft/world/level/chunk/storage/ChunkStorage", ["prepareLegacyChunk", "finalizeLegacyChunk"]),
    "worldgen_guard": ("net/minecraft/server/level/WorldGenRegion", ["shouldBlockWorldgenWrite"]),
}
ENTITY_NAME = "poweradvantage_separate_entity_storage"
ENTITY_CLASS = "net/minecraft/world/level/chunk/storage/EntityStorage"
NBT = "Lnet/minecraft/nbt/CompoundTag;"
WORLD_METHOD = ("(Lcom/mojang/serialization/DynamicOps;Lnet/minecraft/world/level/DataPackConfig;"
                "Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;"
                "Ljava/io/File;Lcom/mojang/datafixers/DataFixer;)Lnet/minecraft/world/level/storage/PrimaryLevelData;")
CHUNK_METHOD = "(Lnet/minecraft/resources/ResourceKey;Ljava/util/function/Supplier;" + NBT + ")" + NBT
ENTITY_METHOD = "(" + NBT + ")" + NBT
GUARD_METHODS = {"bm": "(Lnet/minecraft/core/BlockPos;)Z",
                 "pa": "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z"}
OWNERS = {"bm": "zone/moddev/mc/basemetals/migration/LegacyWorldDataHook",
          "pa": "zone/moddev/mc/poweradvantage/migration/LegacyWorldDataHook"}


def inspection_command(runtime):
    custody = runtime / CUSTODY
    receipt = read(custody / "receipt.json")
    plan_path = custody / "observation-plan.json"
    audit_runtime(runtime, plan_path, receipt["planSha256"])
    plan = read(plan_path)
    build = read(plan["buildProof"])
    # Load the inspector from the pinned observer jar, never loose, mutable class output.
    jars = [plan["observerJar"], *(str(Path(plan["seed"]) / name) for name in plan["dependencies"])]
    return [build["commands"][1][0], "-cp", os.pathsep.join(jars), "qualification.order.TransformerOrderBridge", "--inspect"]


def inspect_dump(command, path):
    result = subprocess.run([*command, str(path)], capture_output=True, text=True,
                            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    if result.returncode:
        raise AssertionError("Bytecode inspection failed: " + result.stdout + result.stderr)
    return json.loads(result.stdout)


def event_dump(runtime, event, phase, label, command):
    relative = Path(event[phase + "Dump"])
    if (relative.is_absolute() or "\\" in event[phase + "Dump"] or ":" in event[phase + "Dump"]
            or ".." in relative.parts or len(relative.parts) < 2 or relative.parts[0] != "transformer-order-bytecode"):
        raise AssertionError("Unsafe bytecode dump path")
    if label:
        relative = Path("transformer-order-bytecode-" + label, *relative.parts[1:])
    path = runtime / relative
    if not path.resolve().is_relative_to(runtime.resolve()) or digest(path) != event[phase + "Sha256"]:
        raise AssertionError("Bytecode dump hash changed")
    inspected = inspect_dump(command, path)
    if inspected != {"class": event["class"], "hooks": event[phase]}:
        raise AssertionError("Event hooks differ from actual bytecode dump")


def hook_owner(hook, power_mod="poweradvantage"):
    for mod, owner in OWNERS.items():
        if mod == "pa":
            owner = "zone/moddev/mc/" + power_mod + "/migration/LegacyWorldDataHook"
        if hook["owner"] == owner:
            return mod
    raise AssertionError("Unexpected hook owner")


def own_hooks(event, phase, mod, name, power_mod):
    return [hook for hook in event[phase] if hook_owner(hook, power_mod) == mod and hook["name"] == name]


def require_injection(event, mod, name, method_descriptor, call_descriptor, multiple=False, *,
                      power_mod="poweradvantage"):
    before = own_hooks(event, "before", mod, name, power_mod)
    after = own_hooks(event, "after", mod, name, power_mod)
    if before or not after or (not multiple and len(after) != 1):
        raise AssertionError("Missing or duplicate injection: " + mod + "/" + name)
    if any(not hook["method"].endswith(method_descriptor) or hook["descriptor"] != call_descriptor for hook in after):
        raise AssertionError("Wrong native hook descriptor or target method: " + name)


def native_order_evidence(runtime, mode, label=None, *, power_mod="poweradvantage"):
    if power_mod not in ("poweradvantage", "basepower"):
        raise ValueError("Unsupported Power mod identity")
    inject = lambda *args, **kwargs: require_injection(*args, power_mod=power_mod, **kwargs)
    runtime = Path(runtime)
    suffix = "-" + label if label else ""
    events = [json.loads(line) for line in (runtime / ("transformer-order-events" + suffix + ".jsonl")).read_text().splitlines()]
    registrations = [event for event in events if event["event"] == "registration"]
    if (len(registrations) != 1 or not events or events[0] != registrations[0]
            or registrations[0].get("mode") != mode or registrations[0].get("bridge") != "native117-v1"
            or [event.get("sequence") for event in events] != list(range(1, len(events) + 1))):
        raise AssertionError("Missing, duplicated or unordered real Forge registration evidence")
    registration = registrations[0]
    natural, forced = registration["natural"], registration["forced"]
    selected = [mod + "_legacy_" + suffix for suffix in SUFFIXES for mod in ("basemetals", "poweradvantage")] + [ENTITY_NAME]
    if any(natural.count(name) != 1 for name in selected) or Counter(natural) != Counter(forced):
        raise AssertionError("Missing, duplicate or substituted real transformer registration")
    expected_registration = list(natural)
    for suffix in SUFFIXES:
        bm, pa = (expected_registration.index(mod + "_legacy_" + suffix) for mod in ("basemetals", "poweradvantage"))
        if mode != "natural" and ((bm < pa) != (mode == "bm-first")):
            expected_registration[bm], expected_registration[pa] = expected_registration[pa], expected_registration[bm]
    if mode not in historical.ORDERS or forced != expected_registration:
        raise AssertionError("Registration changed beyond the requested real pairs")
    command = inspection_command(runtime)
    groups = defaultdict(list)
    for event in events[1:]:
        if event.get("event") != "application" or event.get("transformer") not in selected:
            raise AssertionError("Unexpected transformer application event")
        name = event["transformer"]
        owner = "basemetals" if name.startswith("basemetals_") else power_mod
        if event.get("labels") != [owner, name] or event.get("delegate") != "net.minecraftforge.coremod.transformer.CoreModClassTransformer":
            raise AssertionError("Application is not a labelled real CoreMods class transformer")
        for phase in ("before", "after"):
            event_dump(runtime, event, phase, label, command)
        groups[event["class"]].append(event)
    if set(groups) != {value[0] for value in SUFFIXES.values()} | {ENTITY_CLASS}:
        raise AssertionError("Missing or unexpected native class applications")
    proof = {}
    for suffix, (class_name, methods) in SUFFIXES.items():
        applications = groups[class_name]
        expected = [name for name in forced if name in ("basemetals_legacy_" + suffix, "poweradvantage_legacy_" + suffix)]
        if [event["transformer"] for event in applications] != expected:
            raise AssertionError("Wrong actual application order for " + class_name)
        if applications[1]["before"] != applications[0]["after"]:
            raise AssertionError("Transformers did not receive the same evolving class node")
        for event in applications:
            mod = "bm" if event["transformer"].startswith("basemetals_") else "pa"
            if suffix == "world_info":
                inject(event, mod, "prepareLegacyWorld", WORLD_METHOD, "(Ljava/io/File;)V")
            elif suffix == "chunk_status":
                inject(event, mod, "prepareLegacyChunk", CHUNK_METHOD, "(" + NBT + ")V")
                inject(event, mod, "finalizeLegacyChunk", CHUNK_METHOD, ENTITY_METHOD, multiple=True)
                if mod == "pa":
                    inject(event, mod, "prepareLegacyEntities", CHUNK_METHOD, "(" + NBT + ")V")
                    inject(event, mod, "copyChunkForMigration", "(Lnet/minecraft/world/level/ChunkPos;)" + NBT, ENTITY_METHOD, multiple=True)
            else:
                call = "(Lnet/minecraft/server/level/WorldGenRegion;Lnet/minecraft/core/BlockPos;)Z" if mod == "bm" else GUARD_METHODS["bm"]
                inject(event, mod, "shouldBlockWorldgenWrite", GUARD_METHODS[mod], call)
        execution = {}
        for method in methods:
            grouped = defaultdict(list)
            for hook in applications[-1]["after"]:
                if hook["name"] == method:
                    grouped[hook["method"]].append(hook_owner(hook, power_mod))
            mods = ["bm" if name.startswith("basemetals_") else "pa" for name in expected]
            if suffix != "worldgen_guard":
                pair = mods if method == "finalizeLegacyChunk" else list(reversed(mods))
                if not grouped or any(len(values) % 2 or values != pair * (len(values) // 2) for values in grouped.values()):
                    raise AssertionError("Wrong emitted hook order: " + method)
            else:
                if sorted(grouped.values()) != [["bm"], ["pa"]]:
                    raise AssertionError("Independent native worldgen guard sites were conflated")
            execution[method] = dict(grouped)
        if suffix == "chunk_status":
            hooks = [hook for hook in applications[-1]["after"] if hook["method"].endswith(CHUNK_METHOD)]
            entities = next(index for index, hook in enumerate(hooks) if hook["name"] == "prepareLegacyEntities")
            prepares = [index for index, hook in enumerate(hooks) if hook["name"] == "prepareLegacyChunk"]
            finalizers = [index for index, hook in enumerate(hooks) if hook["name"] == "finalizeLegacyChunk"]
            if len(prepares) != 2 or max(prepares) >= entities or min(finalizers) <= entities:
                raise AssertionError("Entity preparation must follow both chunk preparation hooks and precede finalization")
        proof[suffix] = {"application_order": expected, "emitted_hook_order": execution,
                         "bytecode_sha256": applications[-1]["afterSha256"]}
    entities = groups[ENTITY_CLASS]
    if len(entities) != 1 or entities[0]["transformer"] != ENTITY_NAME:
        raise AssertionError("Missing or duplicated Power-only entity-storage observation")
    inject(entities[0], "pa", "prepareEntityChunkForMigration", ENTITY_METHOD, ENTITY_METHOD)
    inject(entities[0], "pa", "finalizeLegacyChunk", ENTITY_METHOD, ENTITY_METHOD, multiple=True)
    if entities[0]["after"][0]["name"] != "prepareEntityChunkForMigration":
        raise AssertionError("Separate entity preparation must precede finalization")
    if any(hook_owner(hook, power_mod) != "pa" for hook in entities[0]["after"]):
        raise AssertionError("Invented Base Metals entity-storage partner")
    proof["separate_entity_storage"] = {"application_order": [ENTITY_NAME], "paired": False,
        "hooks": entities[0]["after"], "bytecode_sha256": entities[0]["afterSha256"]}
    return {"status": "PASS", "mode": mode, "classes": proof, "registration": registration}


def build_bridge(root, target, java):
    seed = target[0]
    libraries = seed / "libraries"
    dependencies = [libraries / path for path in BRIDGE_LIBRARIES]
    for dependency in dependencies:
        if not dependency.is_file():
            raise FileNotFoundError("Missing exact packaged bridge library: " + str(dependency))
    classes = root / "probe-classes"
    classes.mkdir()
    source = BRIDGE_SOURCE
    cp = os.pathsep.join(str(path) for path in dependencies)
    commands = [[str(java.with_name("javac.exe" if os.name == "nt" else "javac")), "--release", "16", "-encoding", "UTF-8",
                 "-cp", cp, "-d", str(classes), str(source)],
                [str(java), "-cp", str(classes) + os.pathsep + cp, "qualification.order.TransformerOrderBridge",
                 str(seed / COREMODS), str(root / "coremods-order-test.jar"), str(classes)]]
    for command in commands:
        result = subprocess.run(command, capture_output=True, text=True,
                                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        if result.returncode:
            raise RuntimeError(result.stdout + result.stderr)
    proof = historical.validate_patch(seed / COREMODS, root / "coremods-order-test.jar")
    proof.update({"sourceSha256": digest(source), "commands": commands,
                  "dependencies": {str(path): digest(path) for path in dependencies}})
    write_json(root / "probe-build.json", proof)
    return root / "coremods-order-test.jar"


def checkpoint(runtime, java, launcher, label, first):
    server = Server(runtime, java, launcher, label)
    try:
        server.snapshot(label)
        readiness = ready_entities(server, runtime, "actors", "order-" + label)
        actors.actor_command(server, True, "snapshot" if first else "read", label)
        frozen = actors.freeze_result(json.loads((runtime / f"actors-{label}.json").read_text()))
        state = capture_actors(runtime, label)
        historical.retain_player_evidence(runtime, label)
        if first:
            actors.actor_command(server, True, "read", label + "-adapter")
            adapter = capture_actors(runtime, label + "-adapter")
            adapter_freeze = actors.freeze_result(json.loads((runtime / f"actors-{label}-adapter.json").read_text()))
            historical.retain_player_evidence(runtime, label + "-adapter")
        else:
            adapter, adapter_freeze = state, frozen
        adapter.pop("embedded", None)
        if not first:
            state.pop("embedded", None)
        if frozen["status"] != "PASS" or adapter_freeze["status"] != "PASS":
            raise AssertionError("Transformer controls require genuinely frozen actors")
        remote = 1024 if first else 2048
        server.send(f"forceload add {remote} 1024")
        server.send("say NATIVE117-ORDER-GUARD-SCHEDULED")
        server.wait("NATIVE117-ORDER-GUARD-SCHEDULED", 30)
        event_file = runtime / "transformer-order-events.jsonl"
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            if event_file.exists() and event_file.read_text().count('"class":"net/minecraft/server/level/WorldGenRegion"') == 2:
                break
            time.sleep(0.2)
        else:
            raise AssertionError("Native remote generation did not apply both real guard transformers")
        server.send(f"forceload remove {remote} 1024")
        server.send("save-all flush")
        server.wait("Saved the", 45)
    finally:
        server.close()
    stopped = audit_stopped(runtime, runtime / "order-stopped-entities" / label)
    if stopped["status"] != "PASS" or stopped["matchedEntities"] < 1:
        raise AssertionError("Transformer route lacks nonempty saved-entity proof")
    for prefix in ("console", "latest", "debug"):
        require_clean_log(runtime / f"{prefix}-{label}.log")
    proof = native_order_evidence(runtime, (runtime / "transformer-order.mode").read_text().strip())
    for old, new in (("transformer-order-events.jsonl", f"transformer-order-events-{label}.jsonl"),
                     ("transformer-order-bytecode", f"transformer-order-bytecode-{label}")):
        shutil.move(str(runtime / old), str(runtime / new))
    write_json(runtime / f"order-proof-{label}.json", proof)
    write_json(runtime / f"semantic-{label}.json", state)
    write_json(runtime / f"semantic-{label}-adapter.json", adapter)
    return state, adapter, {"status": "PASS", "order": proof, "freeze": frozen,
        "adapterFreeze": adapter_freeze, "readiness": readiness,
        "savedEntities": {key: stopped[key] for key in
            ("status", "snapshotSha256", "matchedEntities", "auditedDimensions")}}


def run(args):
    root = output_path(args.output)
    require_disjoint(root, args.fixtures)
    target = pinned_profile(args.target_manifest.resolve(), "target117")
    if not args.probe_only:
        with socket.socket() as port:
            port.bind(("127.0.0.1", args.port))
    root.mkdir(parents=True)
    patched = build_bridge(root, target, args.java8)
    plan_path = root / "observation-plan.json"
    write_json(plan_path, make_plan(target[0], digest(target[0] / "native117-code-inventory.json"),
               patched, digest(patched), root / "probe-build.json", digest(root / "probe-build.json")))
    plan_sha256 = digest(plan_path)
    if args.probe_only:
        write_json(root / "probe-only-result.json", dict(status="PASS_BRIDGE_BUILD_ONLY_NO_MC",
            coremods=COREMODS.as_posix(), modLauncher="9.0.7", observerJarSha256=digest(patched),
            observationPlanSha256=plan_sha256, realApplicationOrders="NOT_RUN", gameplay="NOT_RUN"))
        print("PASS_BRIDGE_BUILD_ONLY_NO_MC: real transformer application and gameplay checks remain pending")
        return 0
    results, cross, fingerprints = {}, {}, {}
    for name in args.sources:
        source = args.fixtures / name
        before = fingerprint(source)
        if fingerprint(source / WORLD) != json.loads((source / "fixture-hashes.json").read_text()):
            raise AssertionError("Protected original actor fixture changed")
        fingerprints[name] = {"source": str(source.resolve()), "fingerprint": before}
        write_json(root / "source-fingerprints.json", fingerprints)
        try:
            mixed = root / (name + "-mixed-input")
            mixed.mkdir()
            expected = historical.prepare_fixture(source, mixed)
            mixed_hashes = fingerprint(mixed)
            blocks = LEGACY_POWER / "run-migration-matrix" / (
                "original-cyano-20261005-r1/original110" if name == "original110" else "full-20261005-r1/maintained112")
            modes = {}
            for mode in args.orders:
                runtime = root / (name + "-" + mode)
                launcher = native117_boot(runtime, target, args.java8, args.port)
                (runtime / "advantage-works.allow").touch()
                shutil.copy2(patched, runtime / COREMODS)
                seal_runtime(runtime, plan_path, plan_sha256)
                (runtime / "transformer-order.mode").write_text(mode)
                copy_fixture(mixed, runtime)
                shutil.copy2(mixed / "actor-fixture.json", runtime / "actor-fixture.json")
                converted, adapter, first = checkpoint(runtime, args.java8, launcher, "converted", True)
                backups = historical.migration_backups(runtime)
                provenance = historical.backup_provenance(mixed, runtime)
                restarted, _, second = checkpoint(runtime, args.java8, launcher, "restarted", False)
                checks = {
                    "conversion": actors.compare({k: v for k, v in expected.items() if k != "adapter"}, converted),
                    "adapter": actors.compare({k: v for k, v in expected.items() if k != "embedded"}, adapter),
                    "restart": actors.compare({k: v for k, v in expected.items() if k != "embedded"}, restarted),
                    "idempotence": historical.strict_equal(adapter, restarted),
                    "backup-stability": historical.strict_equal(backups, historical.migration_backups(runtime)),
                    "backup-provenance": provenance, "converted-controls": first, "restart-controls": second,
                    "observation-code-custody": dict(status="PASS", proof=audit_runtime(runtime, plan_path, plan_sha256)),
                }
                for label in ("converted", "restarted"):
                    entries = json.loads((runtime / f"migration-{label}.json").read_text())
                    stock = runtime / f"migration-stock-{label}.json"
                    write_json(stock, entries[:-len(historical.METAL_BLOCKS)])
                    checks["blocks-" + label] = compare(blocks / "migration-source.json", stock, native_normalized)
                    checks["metals-" + label] = historical.strict_equal(list(historical.METAL_BLOCKS),
                        [entry["actualName"] for entry in entries[-len(historical.METAL_BLOCKS):]])
                historical.assert_staged(runtime)
                modes[mode] = {"actors": adapter, "actorsRestart": restarted,
                               "blocks": historical.block_semantics(runtime, "converted"),
                               "blocksRestart": historical.block_semantics(runtime, "restarted")}
                results[name + "/" + mode] = checks
                write_json(runtime / "order-results.json", checks)
                write_json(root / "results.json", results)
                print(name + "/" + mode + ": " + ", ".join(key + "=" + value["status"] for key, value in checks.items()), flush=True)
            cross[name] = {mode: historical.strict_equal(modes[args.orders[0]], value) for mode, value in modes.items()}
            if fingerprint(mixed) != mixed_hashes:
                raise AssertionError("Disposable mixed input was altered by destination runs")
        finally:
            if fingerprint(source) != before:
                raise AssertionError("Protected transformer source changed")
    failed = any(value["status"] != "PASS" for checks in results.values() for value in checks.values())
    failed |= any(value["status"] != "PASS" for modes in cross.values() for value in modes.values())
    dependent = any(not checks["backup-provenance"]["power_backup_matches_original"] for checks in results.values())
    status = "FAIL" if failed else "PASS_WITH_BACKUP_ORDER_DEPENDENCE" if dependent else "PASS"
    write_json(root / "summary.json", {"status": status, "semanticStatus": "FAIL" if failed else "PASS", "crossOrder": cross,
               "runtimes": len(results), "serverLaunches": len(results) * 2, "sourceFixtures": "UNCHANGED",
               "targetManifestSha256": digest(args.target_manifest), "patchedLibrarySha256": digest(patched),
               "observationPlanSha256": plan_sha256,
               "powerBackupOriginOrderDependent": dependent})
    return int(failed)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixtures", type=Path, required=True)
    parser.add_argument("--target-manifest", type=Path, required=True)
    parser.add_argument("--java16", "--java8", dest="java8", type=Path, required=True,
                        help="Java 16 executable for the native 1.17 server and probe compiler")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--port", type=int, choices=(25574, *range(25584, 25588)), required=True)
    parser.add_argument("--sources", nargs="+", choices=("original110", "maintained112"), default=["original110", "maintained112"])
    parser.add_argument("--orders", nargs="+", choices=historical.ORDERS, default=list(historical.ORDERS))
    parser.add_argument("--probe-only", action="store_true")
    raise SystemExit(run(parser.parse_args()))
