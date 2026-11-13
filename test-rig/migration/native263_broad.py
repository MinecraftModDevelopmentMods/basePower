"""Native26 walkable works: retain the 21-bay and twelve-drill operation oracles."""

import argparse
from pathlib import Path
import sys
from types import SimpleNamespace
import traceback

from matrix import digest, fingerprint, write_json
from native117 import output_path, require_disjoint
from native117_actor_io import bind
from native117_broad import campaign_document, run_profile
import native12111_broad as previous
import native263 as native


RECEIPT = "native263-broad-results.json"


def supported_mode(value):
    if value != "packaged-current":
        raise argparse.ArgumentTypeError(
            f"{value!r} is unsupported for Minecraft 26.3: BaseMetals is mandatory. "
            "Use packaged-current with the complete pinned family profile.")
    return value


class Adapter:
    Server = native.Server
    MINECRAFT, FORGE, FLAT_SETTINGS = native.MINECRAFT, native.FORGE, native.FLAT_SETTINGS
    verify_runtime = staticmethod(native.audit_staged)
    require_clean_log = staticmethod(native.require_clean_log)
    drill_tag = staticmethod(previous.Adapter.drill_tag)
    drill_tickets = staticmethod(bind(previous.Adapter.drill_tickets, native=native))

    @staticmethod
    def stage(runtime, target, port):
        seed, inventory, entries = target
        native.stage(runtime, seed, inventory, {
            key: (item["version"], item["path"], item["sha256"]) for key, item in entries.items()
        }, port)
        return native.LAUNCHER

    @staticmethod
    def drill_data(row):
        data = native.compat_tile_data(row)
        if "AdvBayWitness" in data.get("ForgeData", {}):
            raise AssertionError("Broad witness must come from the native tile component")
        marker = data.get("components", {}).get("minecraft:custom_data", {}).get("AdvBayWitness")
        if marker is not None:
            data["ForgeData"] = {"AdvBayWitness": marker}
        return data


def oracle_closure():
    directory = Path(__file__).resolve().parent
    return {Path(module.__file__).resolve(): digest(Path(module.__file__)) for module in tuple(sys.modules.values())
            if getattr(module, "__file__", None) and Path(module.__file__).suffix == ".py"
            and Path(module.__file__).resolve().parent == directory
            and not Path(module.__file__).name.startswith("test_")}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ("profile", "seed", "inventory", "java", "output"):
        parser.add_argument("--" + key, type=lambda value: Path(value).resolve(), required=True)
    parser.add_argument("--profile-sha256", required=True)
    parser.add_argument("--inventory-sha256", required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--run-seconds", type=int, default=8)
    parser.add_argument("--mode", type=supported_mode, default="packaged-current", metavar="packaged-current")
    args = parser.parse_args(argv)
    if not 1 <= args.run_seconds <= 60 or not 1024 <= args.port < 65535:
        parser.error("Use a bounded duration and an unprivileged localhost port")
    native.validate_hash(args.profile, args.profile_sha256)
    seed, inventory, mods = native.read_family_target(args.profile, args.seed, args.inventory, args.inventory_sha256)
    if set(mods) != set(native.VERSIONS):
        parser.error("BaseMetals and the complete six-jar family are mandatory for Minecraft 26.3")
    output = output_path(args.output)
    java, version = native.java25(args.java)
    for path in (args.profile, seed, args.inventory, java, *(item[1] for item in mods.values())):
        require_disjoint(output, path)
    seed_before = fingerprint(seed)
    entries = {key: dict(id=key, version=value[0], path=value[1], file=value[1].name, sha256=value[2])
               for key, value in mods.items()}
    spec = native.RIG / "src/main/resources/assets/advantageworkstest/works/works-1.13.2.json"
    stations = native.read_json(spec)["stations"]
    if len(stations) != 21 or len({station["id"] for station in stations}) != 21:
        raise AssertionError("The complete 21-bay definition is required")
    protected = oracle_closure()
    protected.update({path: digest(path) for path in (args.profile, args.inventory, java, spec,
                                                    *(value[1] for value in mods.values()))})
    proof = dict(engineProtocol=native.ENGINE_PROTOCOL, qualificationStatus="EXPLORATORY_UNQUALIFIED", minecraft=native.MINECRAFT, forge=native.FORGE,
                 scope="PACKAGED_WALKABLE_WORKS_AND_TWELVE_DRILL_WITNESSES",
                 profileSha256=digest(args.profile), inventorySha256=digest(args.inventory),
                 stationDefinitionSha256=digest(spec), javaVersion=version, javaSha256=digest(java),
                 inputHashes={str(path): sha for path, sha in protected.items()},
                 jars={key: item["sha256"] for key, item in entries.items()})
    receipt = output / RECEIPT
    names = (args.mode,)
    results = {}
    write_json(receipt, campaign_document(names, results, proof, finished=False))
    try:
        result = run_profile(SimpleNamespace(java17=java, run_seconds=args.run_seconds), None,
                             output, args.mode, args.port, stations,
                             native118_target=(seed, inventory, entries), native_adapter=Adapter)
        results[args.mode] = result
    except Exception:
        results[args.mode] = dict(status="FAIL", profile=args.mode, error=traceback.format_exc())
    try:
        native.verify_runtime(output / args.mode, inventory,
                              {item["file"]: item["sha256"] for item in entries.values()})
    except Exception:
        results[args.mode].update(status="FAIL", custodyError=traceback.format_exc())
    final = campaign_document(names, results, proof, finished=True)
    final["seedUnchanged"] = fingerprint(seed) == seed_before
    final["inputsUnchanged"] = all(path.is_file() and digest(path) == sha for path, sha in protected.items())
    if not final["seedUnchanged"] or not final["inputsUnchanged"]:
        final["status"] = "FAIL"
    write_json(receipt, final)
    print(args.mode, final["status"], flush=True)
    return int(final["status"] != "PASS")


if __name__ == "__main__":
    raise SystemExit(main())
