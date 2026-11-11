"""Negative controls against read-only real woven exports; mutations are new .class fixtures only."""

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT.parent / "migration"))
from matrix import digest, fingerprint, write_json
import native12111 as native
import native12111_order_probe as probe


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("build-receipt", "runtime", "exports", "java", "output"):
        parser.add_argument("--" + name, type=lambda value: Path(value).resolve(), required=True)
    args = parser.parse_args()
    build = native.read_json(args.build_receipt)
    variant = build["variants"]["power-low"]
    native.validate_hash(Path(variant["jar"]), variant["sha256"])
    args.output.mkdir(parents=True, exist_ok=False)
    before = fingerprint(args.exports)
    production_before = fingerprint(args.runtime / "mods")
    utility = args.output / "test-classes"
    utility.mkdir()
    cp = os.pathsep.join([variant["jar"], *build["classpath"]])
    command = [str(args.java.with_name("javac.exe" if os.name == "nt" else "javac")), "-proc:none", "--release", "21",
               "-cp", cp, "-d", str(utility), str(ROOT / "test/WovenNegativeControl.java")]
    compiled = subprocess.run(command, env=native.native_launch_environment(), capture_output=True, text=True)
    (args.output / "javac.log").write_text(compiled.stdout + compiled.stderr)
    if compiled.returncode:
        raise AssertionError("Negative fixture utility compilation failed")
    results = {"unmodified": probe.audit_command(args.java, build, variant, args.runtime, args.exports, args.output / "baseline.json")["status"]}
    for mutation in ("body", "constant-body", "duplicate-handler", "priority", "early-return", "missing-head",
                     "missing-return", "bypass-head", "worldgen-body", "worldgen-true", "worldgen-extra-cast"):
        folder = args.output / mutation
        exports = folder / "export"
        shutil.copytree(args.exports, exports)
        relative = ("net/minecraft/server/level/WorldGenRegion.class" if mutation.startswith("worldgen-")
                    else "net/minecraft/world/level/chunk/storage/SimpleRegionStorage.class")
        original = args.exports / relative
        mutant = folder / "mutant" / relative
        changed = subprocess.run([str(args.java), "-cp", os.pathsep.join([str(utility), cp]),
                                  "zone.moddev.orderprobe.WovenNegativeControl", str(original), str(mutant), mutation],
                                 env=native.native_launch_environment(), capture_output=True, text=True)
        (folder / "fixture.log").write_text(changed.stdout + changed.stderr)
        if changed.returncode or not mutant.is_file():
            raise AssertionError("Fixture mutation did not execute: " + mutation)
        # Only a newly created derivative export is replaced, never a live export or jar.
        shutil.copy2(mutant, exports / relative)
        try:
            probe.audit_command(args.java, build, variant, args.runtime, exports, folder / "audit.json")
        except AssertionError:
            log = (folder / "audit.log").read_text()
            if "java.lang.AssertionError: ORDER_PROBE:" not in log:
                raise AssertionError("Mutation failed for an unrelated reason: " + mutation)
            results[mutation] = "REJECTED"
        else:
            raise AssertionError("Woven mutation was accepted: " + mutation)
    if before != fingerprint(args.exports) or production_before != fingerprint(args.runtime / "mods"):
        raise AssertionError("Negative controls changed actual exports or production jars")
    receipt = dict(status="PASS", scope=probe.SCOPE, qualificationAllowed=False, controls=results,
                   originalExportsUnchanged=True, allRuntimeJarsUnchanged=True,
                   buildReceiptSha256=digest(args.build_receipt), sourceSha256=digest(Path(__file__)),
                   fixtureUtilitySha256=digest(ROOT / "test/WovenNegativeControl.java"))
    write_json(args.output / "negative-controls.json", receipt)
    print(json.dumps(receipt, separators=(",", ":")))


if __name__ == "__main__":
    main()
