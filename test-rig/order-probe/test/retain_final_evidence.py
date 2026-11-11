"""Add read-only per-body hashes and javap views to the completed instrumented proof."""

import argparse
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT.parent / "migration"))
from matrix import digest, fingerprint, write_json
import native12111 as native


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("build-receipt", "order-receipt", "java", "output"):
        parser.add_argument("--" + name, type=lambda value: Path(value).resolve(), required=True)
    args = parser.parse_args()
    build, proof = native.read_json(args.build_receipt), native.read_json(args.order_receipt)
    if proof["status"] != "PASS" or proof["qualificationAllowed"] is not False:
        raise AssertionError("Completed instrumented proof required")
    args.output.mkdir(parents=True, exist_ok=False)
    classes = args.output / "test-classes"
    classes.mkdir()
    cp = os.pathsep.join([build["variants"]["power-low"]["jar"], *build["classpath"]])
    compiled = subprocess.run([str(args.java.with_name("javac.exe" if os.name == "nt" else "javac")), "-proc:none", "--release", "21",
                               "-cp", cp, "-d", str(classes), str(ROOT / "test/BodyEvidenceDump.java")],
                              env=native.native_launch_environment(), capture_output=True, text=True)
    (args.output / "javac.log").write_text(compiled.stdout + compiled.stderr)
    if compiled.returncode:
        raise AssertionError("Body evidence tool compilation failed")
    receipts = {}
    for mode, campaign in proof["modes"].items():
        variant = build["variants"][mode]
        cp = os.pathsep.join([str(classes), variant["jar"], *build["classpath"]])
        for boot in campaign["observations"]:
            runtime = Path(boot["runtime"])
            exports = runtime / "order-evidence" / boot["label"] / "export"
            before = fingerprint(exports)
            output = args.output / mode / boot["label"]
            output.mkdir(parents=True)
            result = subprocess.run([str(args.java), "-cp", cp, "zone.moddev.orderprobe.BodyEvidenceDump", variant["pins"],
                                     str(runtime), str(exports), str(output / "handler-bodies.json")],
                                    env=native.native_launch_environment(), capture_output=True, text=True)
            (output / "body-audit.log").write_text(result.stdout + result.stderr)
            if result.returncode:
                raise AssertionError("Per-body evidence mismatch")
            for name in ("SimpleRegionStorage", "LevelStorageSource", "WorldGenRegion"):
                matches = list(exports.rglob(name + ".class"))
                if len(matches) != 1:
                    raise AssertionError("Missing/duplicate export")
                dumped = subprocess.run([str(args.java.with_name("javap.exe" if os.name == "nt" else "javap")), "-p", "-c", str(matches[0])],
                                         env=native.native_launch_environment(), capture_output=True, text=True)
                if dumped.returncode:
                    raise AssertionError("Raw bytecode view failed")
                (output / (name + ".javap.txt")).write_text(dumped.stdout + dumped.stderr, encoding="utf-8")
            if before != fingerprint(exports):
                raise AssertionError("Actual exports changed during read-only evidence retention")
            receipts[mode + "/" + boot["label"]] = dict(bodyEvidenceSha256=digest(output / "handler-bodies.json"), actualExportsUnchanged=True,
                                                       files=fingerprint(output))
    write_json(args.output / "evidence-index.json", dict(status="PASS", scope="INSTRUMENTED_ORDER_TEST_ONLY", qualificationAllowed=False,
                                                        orderReceiptSha256=digest(args.order_receipt), boots=receipts))
    print("Retained all six boots: nine handler hashes and three raw bytecode views per boot")


if __name__ == "__main__":
    main()
