"""Generate new Overworld actor fixtures with the actual old source serializers."""

import argparse
from collections import Counter
import json
from pathlib import Path
import re
import socket

from actors import boot, phase
from matrix import WORLD, copy_fixture, digest, fingerprint, write_json
from native117 import output_path, require_disjoint


LAUNCHERS = {"1.10.2": "forge-1.10.2-12.18.3.2511-universal.jar",
             "1.12.2": "forge-1.12.2-14.23.5.2859.jar"}
SOURCE_ERRORS = re.compile(r"/(?:ERROR|FATAL)\]|\b(?:[\w$]+\.)+[\w$]+(?:Exception|Error)\b")


def diagnostics(path):
    return Counter(re.sub(r"^\[\d\d:\d\d:\d\d\]\s*", "", line)
                   for line in path.read_text(encoding="utf-8", errors="replace").splitlines()
                   if SOURCE_ERRORS.search(line))


def run(args):
    output = output_path(args.output)
    approved = json.loads(args.inventory.read_text(encoding="utf-8"))
    sources = {}
    for proof in approved:
        source = Path(proof["source"]).resolve()
        name = source.name
        if name in sources or name not in {"original110", "maintained112"}:
            raise AssertionError("Unexpected or duplicate actor source: " + name)
        require_disjoint(output, source)
        if fingerprint(source) != proof["sourceFingerprint"]:
            raise AssertionError("Protected actor seed differs: " + str(source))
        version = "1.10.2" if name == "original110" else "1.12.2"
        jars = sorted((source / "mods").glob("*.jar"))
        launcher = LAUNCHERS[version]
        if not (source / launcher).is_file() or not any(j.name.startswith("AdvantageMigrationProbe-") for j in jars):
            raise AssertionError("Missing real old Forge launcher or actor probe: " + name)
        sources[name] = (proof, (source, launcher, jars))
    if set(sources) != {"original110", "maintained112"}:
        raise AssertionError("Supply both old serializer fixtures")
    with socket.socket() as port:
        port.bind(("127.0.0.1", args.port))
    output.mkdir()
    report = dict(status="RUNNING", scope="NEW_OVERWORLD_ACTOR_FIXTURES_NOT_ARCHIVE_RECOVERY",
                  inventorySha256=digest(args.inventory), fixtures={})
    try:
        for name, (proof, profile) in sources.items():
            source = profile[0]
            runtime = output / name
            try:
                launcher = boot(runtime, profile, args.java8, args.port)
                copy_fixture(source, runtime)
                state = phase(runtime, args.java8, launcher, False, "source", "build")
                new_messages = diagnostics(runtime / "console-source.log") - diagnostics(source / "console-source.log")
                if new_messages:
                    raise AssertionError("Unexpected old source diagnostics: " + repr(dict(new_messages)))
                if (set(state["entities"]) != {"cart", "dropped-musket", "dropped-oil", "owned-wolf", "hydroturbine"}
                        or len(state["file"].get("Inventory", [])) < 14 or not state["file"].get("EnderItems")):
                    raise AssertionError("Old source did not serialize the complete actor witness set")
                hashes = fingerprint(runtime / WORLD)
                write_json(runtime / "fixture-hashes.json", hashes)
                report["fixtures"][name] = dict(source=str(source), sourceWorldUnchanged=True,
                    producedWorldFingerprint=hashes, actorStateSha256=digest(runtime / "semantic-source.json"),
                    oldSourceDiagnostics=dict(diagnostics(runtime / "console-source.log")))
                print(name + ": new Overworld actor fixture serialized and stopped", flush=True)
            finally:
                if fingerprint(source) != proof["sourceFingerprint"]:
                    raise AssertionError("Protected seed changed: " + str(source))
        report["status"] = "PASS"
    except Exception as error:
        report.update(status="FAIL", error=repr(error))
        raise
    finally:
        write_json(output / "new-actor-source-receipt.json", report)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inventory", type=Path, required=True, help="Exact source fingerprints from the retained campaign")
    parser.add_argument("--java8", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--port", type=int, required=True)
    run(parser.parse_args())
