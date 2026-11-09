"""Commission and restart all 21 Works stations with the pinned 1.18.2 jars."""

import argparse
from pathlib import Path
import shutil
import traceback

from matrix import digest, write_json
from native117 import output_path, require_disjoint
from native117_broad import campaign_document, read_json, run_profile
import native118


def freeze_mods(entries, destination):
    destination.mkdir()
    frozen = {}
    for mod, entry in entries.items():
        original = entry["path"]
        jar = destination / entry["file"]
        shutil.copy2(original, jar)
        if digest(jar) != entry["sha256"].upper() or digest(original) != entry["sha256"].upper():
            raise AssertionError("A pinned mod changed while freezing broad test inputs")
        frozen[mod] = dict(entry, path=jar, originPath=str(original))
    return frozen


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("profile", "seed", "inventory", "output", "java17"):
        parser.add_argument("--" + name, required=True, type=Path)
    parser.add_argument("--inventory-sha256", required=True)
    parser.add_argument("--port", type=int, default=25642)
    parser.add_argument("--run-seconds", type=int, default=8)
    args = parser.parse_args()
    if not 1 <= args.run_seconds <= 60 or not 1024 <= args.port < 65535:
        parser.error("Bounded run duration and unprivileged consecutive ports required")
    output = output_path(args.output)
    target = native118.read_target(args.profile, args.seed, args.inventory, args.inventory_sha256)
    if "basemetals" not in target[2]:
        raise ValueError("Use the complete profile; the decoupled profile is derived from it")
    for path in (args.profile, args.seed, args.inventory, *(item["path"] for item in target[2].values())):
        require_disjoint(output, path)
    definition = native118.RIG / "src/main/resources/assets/advantageworkstest/works/works-1.13.2.json"
    stations = read_json(definition)["stations"]
    if len(stations) != 21:
        raise AssertionError("The complete 21-bay specification is required")
    output.mkdir(parents=True)
    manifest = output / "target118.json"
    seed, code, entries = target
    entries = freeze_mods(entries, output / "pinned-inputs")
    target = seed, code, entries
    write_json(manifest, {"target118": {
        "seed": str(seed), "launcher": native118.LAUNCHER,
        "launcher_sha256": digest(seed / native118.LAUNCHER), "serverCode": code,
        "jars": [{**item, "path": str(item["path"])} for item in entries.values()],
    }})
    runner = output / "runner-source.py"
    shutil.copy2(Path(__file__), runner)
    proof = dict(profileSha256=digest(args.profile), inventorySha256=digest(args.inventory),
                 stationDefinitionSha256=digest(definition), runnerSha256=digest(runner),
                 targetManifest=str(manifest), targetManifestSha256=digest(manifest),
                 minecraft=native118.MINECRAFT)
    names = ("packaged-current", "packaged-decoupled")
    results = {}
    report = output / "native118-broad-results.json"
    for index, name in enumerate(names):
        write_json(report, campaign_document(names, results, proof, finished=False))
        try:
            results[name] = run_profile(args, None, output, name, args.port + index, stations, native118_target=target)
        except Exception:
            results[name] = dict(status="FAIL", profile=name, error=traceback.format_exc())
        print(name, results[name]["status"], flush=True)
    final = campaign_document(names, results, proof, finished=True)
    write_json(report, final)
    return int(final["status"] != "PASS")


if __name__ == "__main__":
    raise SystemExit(main())
