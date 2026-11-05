"""Open a copy of a legacy Advantage 1.12 save and verify its MMDLib crusher IDs."""

import argparse
from pathlib import Path
import shutil
import traceback

import nbtlib

from native112 import output_path, pinned_profile, require_disjoint
from native112_code import verify_code
from native112_lifecycle import read_json, require_native_boot, verify_staged
from native112_rebrand import ALIASES, canonical, command, configure
from rig_transport import WORLD, Server, digest, fingerprint, stage, write_json

REGISTRY = "mmdlib:crusher_registry"


def registry(world):
    entries = nbtlib.load(world / "level.dat")["FML"]["Registries"][REGISTRY]
    return {str(row["K"]): int(row["V"]) for row in entries["ids"]}


def run(args):
    source = args.world.resolve(strict=True)
    config = args.config.resolve(strict=True)
    manifest = args.manifest.resolve(strict=True)
    output = output_path(args.output)
    profile = pinned_profile(manifest, "target112")
    for path in (source, config, manifest, profile[0], *profile[2]):
        require_disjoint(output, path)
    source_world = fingerprint(source)
    source_config = fingerprint(config)
    expected = {canonical(name): number for name, number in registry(source).items()
                if name.split(":", 1)[0] in ALIASES}
    if not expected:
        raise AssertionError("Source has no old Advantage recipes in " + REGISTRY)
    output.mkdir()
    runtime = output / "runtime"
    report = dict(status="RUNNING", manifestSha256=digest(manifest), expected=expected, phases={})
    try:
        launcher = stage(runtime, profile)
        configure(runtime, args.port)
        shutil.copytree(source, runtime / WORLD)
        shutil.copytree(config, runtime / "config")
        if fingerprint(runtime / WORLD) != source_world or fingerprint(runtime / "config") != source_config:
            raise AssertionError("Fixture changed while copying")
        for phase in ("first", "restart"):
            verify_code(runtime, read_json(manifest)["target112"]["serverCode"])
            verify_staged(runtime, profile)
            server = Server(runtime, args.java8, launcher, phase, query_result="cancel")
            try:
                command(server, "save-all flush", "Saved the world")
            finally:
                server.close()
            log = runtime / f"console-{phase}.log"
            require_native_boot(log)
            saved = registry(runtime / WORLD)
            observed = {name: saved.get(name) for name in expected}
            report["phases"][phase] = dict(observed=observed, logSha256=digest(log))
            if observed != expected:
                raise AssertionError("Saved crusher IDs differ: " + str(observed))
            if any(name.split(":", 1)[0] in ALIASES for name in saved):
                raise AssertionError("Save still uses old Advantage crusher IDs")
        report["status"] = "PASS"
    except Exception as error:
        report.update(status="FAIL", failure=str(error), traceback=traceback.format_exc())
    finally:
        report["sourceUnchanged"] = source_world == fingerprint(source) and source_config == fingerprint(config)
        if not report["sourceUnchanged"]:
            report["status"] = "FAIL"
        write_json(output / "crusher-migration-results.json", report)
    print(report["status"] + ": " + str(output / "crusher-migration-results.json"))
    return int(report["status"] != "PASS")


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    for option in ("world", "config", "manifest", "java8", "output"):
        cli.add_argument("--" + option, type=Path, required=True)
    cli.add_argument("--port", type=int, required=True)
    raise SystemExit(run(cli.parse_args()))
