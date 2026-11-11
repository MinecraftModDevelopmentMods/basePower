"""Exercise Java production-pin guards using synthetic jars, never modified child jars."""

import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT.parent / "migration"))
from matrix import digest, write_json
import native12111 as native
from native12111_order_probe import MOD_IDS, SCOPE


def sha(data):
    return hashlib.sha256(data).hexdigest().upper()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("build-receipt", "java", "output"):
        parser.add_argument("--" + name, type=lambda value: Path(value).resolve(), required=True)
    args = parser.parse_args()
    build = native.read_json(args.build_receipt)
    variant = build["variants"]["power-low"]
    args.output.mkdir(parents=True, exist_ok=False)
    classes = args.output / "test-classes"
    classes.mkdir()
    cp = os.pathsep.join([variant["jar"], *build["classpath"]])
    compile_result = subprocess.run([str(args.java.with_name("javac.exe" if os.name == "nt" else "javac")), "-proc:none", "--release", "21",
                                     "-cp", cp, "-d", str(classes), str(ROOT / "test/WovenNegativeControl.java")],
                                    env=native.native_launch_environment(), capture_output=True, text=True)
    (args.output / "javac.log").write_text(compile_result.stdout + compile_result.stderr)
    if compile_result.returncode:
        raise AssertionError("Synthetic guard fixture compiler failed")
    bodies = {}
    for kind in ("no-priority", "explicit-priority", "no-annotation"):
        path = args.output / (kind + ".class")
        result = subprocess.run([str(args.java), "-cp", os.pathsep.join([str(classes), cp]),
                                  "zone.moddev.orderprobe.WovenNegativeControl", "fixture", str(path), kind],
                                 env=native.native_launch_environment(), capture_output=True, text=True)
        if result.returncode:
            raise AssertionError("Synthetic guard fixture generation failed: " + result.stderr)
        bodies[kind] = path.read_bytes()
    observations = {}
    for case in ("baseline", "jar-hash", "class-hash", "config-hash", "missing-config", "duplicate-config",
                 "not-required", "config-priority", "explicit-class-priority", "missing-mixin-annotation", "mixin-version"):
        folder = args.output / case
        mods = folder / "synthetic-mods"
        mods.mkdir(parents=True)
        pins = dict(format=1, mixinVersion="0.8.7", mods={}, configs={})
        raw_configs = {}
        for mod in sorted(MOD_IDS):
            files = {}
            if mod in ("basepower", "basemetals"):
                name = mod + ".mixins.json"
                config = dict(required=not (case == "not-required" and mod == "basepower"), package="fixture", mixins=["ProbeMixin"])
                if case == "config-priority" and mod == "basepower":
                    config["mixinPriority"] = 1234
                body_kind = ("explicit-priority" if case == "explicit-class-priority" else "no-annotation" if case == "missing-mixin-annotation" else "no-priority")
                body = bodies[body_kind if mod == "basepower" else "no-priority"]
                raw = json.dumps(config).encode()
                raw_configs[name] = raw
                if not (case == "missing-config" and mod == "basepower"):
                    files[name] = raw
                files["fixture/ProbeMixin.class"] = body
                pins["configs"][name] = dict(mod=mod, sha256=sha(raw), classes={"fixture.ProbeMixin": sha(body)})
            jar = mods / ("SYNTHETIC-" + mod + ".jar")
            with ZipFile(jar, "x") as archive:
                for name, data in files.items():
                    archive.writestr(name, data)
            pins["mods"][mod] = dict(file=jar.name, sha256=digest(jar))
        if case == "jar-hash":
            pins["mods"]["basepower"]["sha256"] = "0" * 64
        elif case == "class-hash":
            pins["configs"]["basepower.mixins.json"]["classes"]["fixture.ProbeMixin"] = "0" * 64
        elif case == "config-hash":
            pins["configs"]["basepower.mixins.json"]["sha256"] = "0" * 64
        elif case == "duplicate-config":
            with ZipFile(mods / "SYNTHETIC-extra.jar", "x") as archive:
                archive.writestr("basepower.mixins.json", raw_configs["basepower.mixins.json"])
        elif case == "mixin-version":
            pins["mixinVersion"] = "0.8.6"
        plan = folder / "pins.json"
        write_json(plan, pins)
        checked = subprocess.run([str(args.java), "-cp", cp, "zone.moddev.orderprobe.OrderProbeAudit", "guard", str(plan), str(mods)],
                                 env=native.native_launch_environment(), capture_output=True, text=True)
        (folder / "guard.log").write_text(checked.stdout + checked.stderr)
        if case == "baseline":
            if checked.returncode or "ORDER_PROBE_GUARD_PASS" not in checked.stdout:
                raise AssertionError("Unchanged synthetic guard baseline failed")
            observations[case] = "PASS"
        else:
            if checked.returncode == 0 or "java.lang.AssertionError: ORDER_PROBE:" not in checked.stderr:
                raise AssertionError("Guard mutation did not fail closed: " + case)
            observations[case] = "REJECTED"
    receipt = dict(status="PASS", scope=SCOPE, qualificationAllowed=False,
                   fixtureScope="Synthetic jars only; no production child jar copied or mutated", controls=observations,
                   buildReceiptSha256=digest(args.build_receipt), sourceSha256=digest(Path(__file__)))
    write_json(args.output / "guard-controls.json", receipt)
    print(json.dumps(receipt, separators=(",", ":")))


if __name__ == "__main__":
    main()
