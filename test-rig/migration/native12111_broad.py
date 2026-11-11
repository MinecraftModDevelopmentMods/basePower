"""Commission all 21 bays and prove twelve native drill witnesses across restart."""

import argparse
from pathlib import Path
from types import SimpleNamespace
import traceback

from matrix import digest, fingerprint, write_json
from native117 import output_path, require_disjoint
from native117_broad import DRILL_BAYS, campaign_document, drill_cases, run_profile
from native117_readiness import await_ready
import native12111 as native


class Adapter:
    Server = native.Server
    MINECRAFT, FORGE, FLAT_SETTINGS = native.MINECRAFT, native.FORGE, native.FLAT_SETTINGS
    verify_runtime = staticmethod(native.audit_staged)
    require_clean_log = staticmethod(native.require_clean_log)

    @staticmethod
    def stage(runtime, target, port):
        seed, inventory, entries = target
        native.stage(runtime, seed, inventory, {
            key: (item["version"], item["path"], item["sha256"]) for key, item in entries.items()
        }, port)
        return native.LAUNCHER

    @staticmethod
    def drill_tag(marker):
        return ('{components:{"minecraft:custom_data":{AdvBayWitness:' + marker
                + '}},Items:[{Slot:0b,id:"minecraft:diamond",count:7,'
                  'components:{"minecraft:custom_data":{AdvBayStock:' + marker + '}}}]}')

    @staticmethod
    def drill_data(row):
        data = native.compat_tile_data(row)
        if "AdvBayWitness" in data.get("ForgeData", {}):
            raise AssertionError("Broad witness must come from the native tile component")
        marker = data.get("components", {}).get("minecraft:custom_data", {}).get("AdvBayWitness")
        if marker is not None:
            data["ForgeData"] = {"AdvBayWitness": marker}
        return data

    @staticmethod
    def drill_tickets(server, runtime, stations, *, add):
        for station in stations:
            if station["id"] not in DRILL_BAYS:
                continue
            cells = [case[cell] for case in drill_cases(station) for cell in ("origin", "rear")]
            bounds = (min(x for x, _, _ in cells), min(z for _, _, z in cells),
                      max(x for x, _, _ in cells), max(z for _, _, z in cells))
            verb = "add" if add else "remove"
            server.send(f"forceload {verb} " + " ".join(map(str, bounds)))
            if add:
                await_ready(server, runtime, tuple(value >> 4 for value in bounds),
                            f"broad-seed-{station['id']}", minecraft=native.MINECRAFT)
            else:
                server.wait("Unmarked", 30)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ("profile", "seed", "inventory", "java", "output"):
        parser.add_argument("--" + key, type=lambda value: Path(value).resolve(), required=True)
    parser.add_argument("--inventory-sha256", required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--run-seconds", type=int, default=8)
    parser.add_argument("--mode", choices=("both", "packaged-current", "packaged-decoupled"), default="both")
    args = parser.parse_args()
    if not 1 <= args.run_seconds <= 60 or not 1024 <= args.port < 65535:
        parser.error("Use a bounded duration and consecutive unprivileged localhost ports")
    output = output_path(args.output)
    seed, inventory, mods = native.read_family_target(args.profile, args.seed, args.inventory, args.inventory_sha256)
    if "basemetals" not in mods:
        raise ValueError("The shared profile is the source of both shared and decoupled pins")
    java, version = native.java21(args.java)
    for path in (args.profile, seed, args.inventory, java, *(item[1] for item in mods.values())):
        require_disjoint(output, path)
    protected = fingerprint(seed)
    entries = {key: dict(id=key, version=value[0], path=value[1], file=value[1].name, sha256=value[2])
               for key, value in mods.items()}
    spec = native.RIG / "src/main/resources/assets/advantageworkstest/works/works-1.13.2.json"
    stations = native.read_json(spec)["stations"]
    if len(stations) != 21:
        raise AssertionError("The complete 21-bay definition is required")
    names = ("packaged-current", "packaged-decoupled") if args.mode == "both" else (args.mode,)
    proof = dict(qualificationStatus=native.EXPLORATORY, minecraft=native.MINECRAFT, forge=native.FORGE,
                 profileSha256=digest(args.profile), inventorySha256=digest(args.inventory),
                 stationDefinitionSha256=digest(spec), javaVersion=version, javaSha256=digest(java),
                 jars={key: item["sha256"] for key, item in entries.items()})
    receipt = output / "native12111-broad-results.json"
    results = {}
    for index, name in enumerate(names):
        write_json(receipt, campaign_document(names, results, proof, finished=False))
        try:
            results[name] = run_profile(SimpleNamespace(java17=java, run_seconds=args.run_seconds), None,
                                        output, name, args.port + index, stations,
                                        native118_target=(seed, inventory, entries), native_adapter=Adapter)
        except Exception:
            results[name] = dict(status="FAIL", profile=name, error=traceback.format_exc())
        try:
            expected = {item["file"]: item["sha256"] for key, item in entries.items()
                        if name != "packaged-decoupled" or key != "basemetals"}
            native.verify_runtime(output / name, inventory, expected)
        except Exception:
            results[name].update(status="FAIL", custodyError=traceback.format_exc())
        print(name, results[name]["status"], flush=True)
    final = campaign_document(names, results, proof, finished=True)
    final["seedUnchanged"] = fingerprint(seed) == protected
    if not final["seedUnchanged"]:
        final["status"] = "FAIL"
    write_json(receipt, final)
    return int(final["status"] != "PASS")


if __name__ == "__main__":
    raise SystemExit(main())
