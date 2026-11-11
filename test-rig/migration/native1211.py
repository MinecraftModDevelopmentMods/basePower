"""Run the Forge 52 Power/Base Metals interop witnesses in a fresh disposable world."""

import argparse
import base64
import gzip
import io
import json
import os
from pathlib import Path
import re
import subprocess
import traceback
from zipfile import ZipFile

import nbtlib

from matrix import digest, fingerprint, stage as stage_server, write_json
from native117 import jar_identity, output_path, require_disjoint
from native117_code import NativeLaunch, code_files
from native117_interop import mark_runtime, require_checks, require_probe_order
from native117_readiness import await_ready
from native1201 import FLAT_SETTINGS, require_clean_log


RIG = Path(__file__).resolve().parents[1]
MINECRAFT = "1.21.1"
FORGE = "52.1.0"
MCP = "20240808.132146"
FORGE_VERSION = f"{MINECRAFT}-{FORGE}"
SHIM = f"forge-{FORGE_VERSION}-shim.jar"
SHIM_SHA = "F7B706C79896728723DA0E4E8C5A6ECDB6A617AD4DF9D7EA1DDC6367187BC291"
LAUNCHER = f"libraries/net/minecraftforge/forge/{FORGE_VERSION}/" + (
    "win_args.txt" if os.name == "nt" else "unix_args.txt")
INVENTORY = "native1211-code-inventory.json"
EXPLORATORY = "EXPLORATORY_UNQUALIFIED"
BASE_PROFILE = RIG / "profiles/native1211/power-interop.json"


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def compat_tile_nbt(row):
    if "nbtBase64" in row:
        data = nbtlib.File.parse(io.BytesIO(gzip.decompress(base64.b64decode(row["nbtBase64"], validate=True))))
    else:
        data = nbtlib.parse_nbt(row.get("nbt", "{}"))
    # Project component data for existing inventory oracles without discarding
    # the original component maps that their full-stack comparisons also use.
    for stack in data.get("Items", []):
        count = stack.get("count")
        if not isinstance(count, (nbtlib.Byte, nbtlib.Short, nbtlib.Int, nbtlib.Long)) or "Count" in stack or "tag" in stack:
            raise AssertionError(f"Expected a native 1.21.1 stack in checkpoint: {stack}")
        stack["Count"] = count
        custom = stack.get("components", {}).get("minecraft:custom_data")
        if custom is not None:
            stack["tag"] = custom
    return data


def compat_tile_data(row):
    return compat_tile_nbt(row).unpack()


def parse_endurance_snapshot(document):
    return {tuple(row["pos"]): {**row, "data": compat_tile_data(row)} for row in document["snapshot"]}


def audit_staged(runtime):
    expected = {entry["file"]: entry["sha256"] for entry in read_json(runtime / "input-jars.json")}
    verify_runtime(runtime, read_json(runtime / INVENTORY), expected)


def validate_hash(path, expected):
    if not re.fullmatch(r"[A-Fa-f0-9]{64}", expected) or digest(path) != expected.upper():
        raise AssertionError(f"Wrong or missing artifact hash: {path}")


def java21(executable):
    java = executable.resolve(strict=True)
    result = subprocess.run([str(java), "-version"], capture_output=True, text=True, check=True, timeout=30)
    version = (result.stderr or result.stdout).strip()
    if not re.search(r'version "21(?:\.|")', version):
        raise ValueError(f"Forge 52 test requires Java 21: {version}")
    return java, version


def inspect_seed(seed):
    validate_hash(seed / SHIM, SHIM_SHA)
    command = (seed / LAUNCHER).read_text(encoding="utf-8").split()
    if command != ["-Djava.net.preferIPv6Addresses=system", "-jar", SHIM]:
        raise AssertionError("Forge 52 launcher is not the official shim command")
    return {"forge": FORGE_VERSION, "files": code_files(seed)}


def seal(seed, inventory):
    require_disjoint(inventory, seed)
    if inventory.exists() or not inventory.is_relative_to(RIG / "build"):
        raise ValueError("Inventory must be a new path under test-rig/build")
    write_json(inventory, inspect_seed(seed))


def read_mods(args):
    data = read_json(args.profile)
    if (data.get("mode"), data.get("minecraftVersion"), data.get("forgeVersion"),
            data.get("qualificationStatus")) != ("packaged", MINECRAFT, FORGE, EXPLORATORY):
        raise ValueError("Use the exploratory Forge 52 packaged profile")
    mods = {
        "basepower": ("2.4.14.121011", args.power, args.power_sha256),
        "advantageworkstest": ("1.1.5.121011", args.harness, args.harness_sha256),
    }
    for entry in data["mods"]:
        identity = entry["id"]
        if identity in mods:
            raise ValueError(f"Duplicate mod identity: {identity}")
        mods[identity] = (entry["version"], (RIG / entry["path"]).resolve(), entry["sha256"])
    if set(mods) != {"basepower", "advantageworkstest", "basemetals", "orespawn"}:
        raise ValueError("Power interop requires exactly Power, Base Metals, OreSpawn, and the harness")
    for identity, (version, path, sha) in mods.items():
        if not version.endswith(".121011") or not path.name.endswith(f"-{version}.jar"):
            raise ValueError(f"Expected an ordinary Forge 52 jar: {path}")
        validate_hash(path, sha)
        jar_identity(path, identity, version)
    verify_compile_pin(args.harness, args.power_sha256)
    return mods


def verify_compile_pin(harness, power_sha256):
    with ZipFile(harness) as jar:
        manifest = dict(line.split(": ", 1) for line in
                        jar.read("META-INF/MANIFEST.MF").decode().replace("\r\n ", "").splitlines()
                        if ": " in line)
    if (manifest.get("Built-On") != FORGE_VERSION
            or manifest.get("Power-Input-Status") != EXPLORATORY
            or manifest.get("Power-Input-SHA256") != power_sha256.upper()):
        raise AssertionError("Harness compile pin and staged ordinary Power jar differ")


def read_family_target(profile, seed, inventory_file, inventory_sha256):
    validate_hash(inventory_file, inventory_sha256)
    inventory = read_json(inventory_file)
    if inspect_seed(seed) != inventory:
        raise AssertionError("Official Forge server differs from its sealed code inventory")
    data = read_json(profile)
    if (data.get("mode"), data.get("minecraftVersion"), data.get("forge", {}).get("version"),
            data.get("qualificationStatus")) != ("packaged", MINECRAFT, FORGE_VERSION, EXPLORATORY):
        raise ValueError("Expected a materialized Forge 52 packaged profile")
    mods = {}
    for item in data["mods"]:
        identity = item["id"]
        path = (RIG / item["path"]).resolve(strict=True)
        version = item["version"]
        if identity in mods or not version.endswith(".121011") or not path.name.endswith(f"-{version}.jar") or path.name != item["file"]:
            raise ValueError(f"Invalid ordinary jar pin: {identity}")
        validate_hash(path, item["sha256"])
        jar_identity(path, identity, version)
        mods[identity] = (version, path, item["sha256"])
    if not {"basepower", "advantageworkstest", "basesteam", "baseelectric", "orespawn"} <= mods.keys():
        raise ValueError("A full family profile must pin Power, Steam, Electric, OreSpawn and Advantage Works")
    verify_compile_pin(mods["advantageworkstest"][1], mods["basepower"][2])
    return seed, inventory, mods


def materialize_profile(args):
    runtime_profile = args.output.resolve()
    if not runtime_profile.is_relative_to(RIG / "build") or runtime_profile.exists():
        raise ValueError("Write the pinned profile to a new file under test-rig/build")
    mods = read_mods(args)
    if args.name == "power-interop":
        base = {"name": args.name, "levelType": "minecraft:flat", "buildWorks": False, "lifecycleStations": []}
        origin = args.profile
        required = set(mods)
    else:
        if not re.fullmatch(r"[a-z0-9-]+", args.name):
            raise ValueError("Invalid profile name")
        origin = RIG / "profiles/native1201" / (args.name + ".json")
        base = read_json(origin)
        template_version = base.get("minecraftVersion", base.get("forge", {}).get("version", "").split("-")[0])
        if (base.get("name") != args.name or template_version != "1.20.1"
                or base.get("forge", {}).get("version") != "1.20.1-47.4.10"):
            raise ValueError("Profile template must be a named native1201 profile")
        required = {entry["id"] for entry in base["mods"]} | {"basepower", "orespawn"}
        for identity, path, sha in (("basesteam", args.steam, args.steam_sha256),
                                    ("baseelectric", args.electric, args.electric_sha256),
                                    ("mineralogy", args.mineralogy, args.mineralogy_sha256)):
            if identity not in required:
                continue
            if path is None or sha is None:
                raise ValueError(f"Need an ordinary 1.21.1 jar and exact SHA-256 for {identity}")
            version = path.stem.rsplit("-", 1)[-1]
            if not version.endswith(".121011"):
                raise ValueError(f"Wrong Minecraft/loader target for {identity}: {version}")
            validate_hash(path, sha)
            jar_identity(path, identity, version)
            mods[identity] = (version, path, sha)
    for legacy in ("powerDeobfSha256", "sourceCommit"):
        base.pop(legacy, None)
    base.update(mode="packaged", runtimeBase="build/works-native1211", minecraftVersion=MINECRAFT,
                forge={"version": FORGE_VERSION}, portStatus="PORTED_UNQUALIFIED",
                qualificationStatus=EXPLORATORY, sourceVersion=mods["basepower"][0],
                sourceProfileSha256=digest(origin), mods=[
                    dict(id=key, version=version, path=str(path.resolve()), file=path.name, sha256=sha.upper())
                    for key, (version, path, sha) in mods.items() if key in required
                ])
    if {entry["id"] for entry in base["mods"]} != required:
        raise ValueError("Profile template references unpinned mods")
    write_json(runtime_profile, base)


def verify_runtime(runtime, inventory, expected):
    if inspect_seed(runtime) != inventory:
        raise AssertionError("Staged Forge code changed")
    if {p.name: digest(p) for p in (runtime / "mods").glob("*.jar")} != expected:
        raise AssertionError("Staged mod jars changed")


def stage(runtime, seed, inventory, mods, port):
    stage_server(runtime, (seed, LAUNCHER, [mod[1] for mod in mods.values()]))
    expected = {path.name: sha.upper() for _, path, sha in mods.values()}
    write_json(runtime / INVENTORY, inventory)
    settings = runtime / "server.properties"
    overrides = {
        "level-type": "minecraft:flat", "generator-settings": json.dumps(FLAT_SETTINGS, separators=(",", ":")),
        "server-port": port, "generate-structures": "false", "simulation-distance": 4, "allow-nether": "true",
    }
    existing = [line for line in settings.read_text().splitlines() if line.partition("=")[0] not in overrides]
    settings.write_text("\n".join(existing + [f"{key}={value}" for key, value in overrides.items()]) + "\n")
    config = runtime / "config/fml.toml"
    config.parent.mkdir()
    config.write_text("versionCheck = false\n", encoding="utf-8")
    mark_runtime(runtime)
    verify_runtime(runtime, inventory, expected)
    return expected


class Server(NativeLaunch):
    forge_version = FORGE_VERSION

    def validate_native_log(self, path):
        diagnostics = require_clean_log(path)
        if diagnostics:
            write_json(path.with_suffix(".platform-diagnostics.json"), diagnostics)

    def close(self):
        super().close()
        console = (self.native_runtime / f"console-{self.native_label}.log").read_text(encoding="utf-8")
        for marker in (f"Starting minecraft server version {MINECRAFT}",
                       f"Forge mod loading, version {FORGE}, for MC {MINECRAFT} with MCP {MCP}", "Done ("):
            if marker not in console:
                raise AssertionError(f"Missing Forge 52 startup witness: {marker}")


def require_shared_crushing(document):
    checks = document.get("sharedChecks", [])
    if len(checks) != 1 or checks[0].get("id") != "basemetals/native-power-crushing" or checks[0].get("status") != "PASS":
        raise AssertionError(f"Missing or failed Base Metals shared crushing witness: {checks}")


def require_oil_buckets(document, readiness):
    require_probe_order(document, readiness)
    expected = {f"{oil}/{check}" for oil in ("crude_oil", "refined_oil")
                for check in ("world-roundtrip", "machine-roundtrip", "machine-below-bucket",
                              "machine-insufficient-room", "machine-full")}
    checks = document.get("checks", [])
    if document.get("gameMode") != "survival" or len(checks) != len(expected) or {row["id"] for row in checks} != expected:
        raise AssertionError("Incomplete survival oil-bucket witness set")
    failures = [row for row in checks if row.get("status") != "PASS"]
    if failures:
        raise AssertionError(f"Survival oil bucket failures: {[(row['id'], row.get('failure')) for row in failures]}")


def interop(args):
    if not 1024 <= args.port <= 65535:
        raise ValueError("An unprivileged localhost port is required")
    runtime = output_path(args.output)
    for path in (args.profile, args.seed, args.inventory, args.java, args.harness, args.power):
        require_disjoint(runtime, path)
    validate_hash(args.inventory, args.inventory_sha256)
    inventory = read_json(args.inventory)
    if inspect_seed(args.seed) != inventory:
        raise AssertionError("Official Forge server differs from its sealed code inventory")
    mods = read_mods(args)
    for _, path, _ in mods.values():
        require_disjoint(runtime, path)
    java, java_version = java21(args.java)
    protected = fingerprint(args.seed)
    expected = stage(runtime, args.seed, inventory, mods, args.port)
    if args.oil_buckets:
        (runtime / "advantage-native-oil-bucket.allow").touch()
    receipt = runtime / "native1211-interop-results.json"
    report = dict(status="RUNNING", qualificationStatus=EXPLORATORY, phases={}, minecraft=MINECRAFT, forge=FORGE,
                  profileSha256=digest(args.profile), inventorySha256=digest(args.inventory),
                  javaSha256=digest(java), javaVersion=java_version, jars=expected)
    write_json(receipt, report)
    try:
        for phase in ("exercise", "restored"):
            verify_runtime(runtime, inventory, expected)
            server = Server(runtime, java, LAUNCHER, phase)
            try:
                server.send("forceload add 1023 1023 1100 1030")
                ready = await_ready(server, runtime, (63, 63, 68, 64), f"interop-{phase}", minecraft=MINECRAFT)
                server.send(f"advinterop {phase}")
                server.wait(f"ADVINTEROP COMPLETE {phase}", 120)
                document = read_json(runtime / "advantage-works-results" / f"native-interop-{phase}.json")
                report["phases"][phase] = dict(readiness=ready, observation=document)
                require_probe_order(document, ready)
                require_checks(document, phase)
                require_shared_crushing(document)
                if args.oil_buckets and phase == "exercise":
                    server.send("advoilbuckets")
                    server.wait("ADVOILBUCKETS COMPLETE", 120)
                    buckets = read_json(runtime / "advantage-works-results/native-oil-buckets.json")
                    report["phases"][phase]["oilBuckets"] = buckets
                    require_oil_buckets(buckets, ready)
                server.send("save-all flush")
                server.wait("Saved the game", 30)
            except BaseException as failure:
                try:
                    server.close()
                except BaseException as shutdown:
                    failure.add_note(f"Shutdown/log audit also failed: {shutdown!r}")
                raise
            else:
                server.close()
            write_json(receipt, report)
        report["status"] = "PASS"
    except BaseException as failure:
        report.update(status="FAIL", failure=repr(failure), traceback=traceback.format_exc())
        raise
    finally:
        try:
            verify_runtime(runtime, inventory, expected)
            report["seedUnchanged"] = fingerprint(args.seed) == protected
            if not report["seedUnchanged"]:
                raise AssertionError("Protected Forge seed changed during the test")
        except Exception as audit:
            report.update(status="FAIL", auditError=repr(audit))
        write_json(receipt, report)
    return int(report["status"] != "PASS")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for command in ("seal", "interop", "profile"):
        sub = commands.add_parser(command)
        if command in ("seal", "interop"):
            sub.add_argument("--seed", type=Path, required=True)
            sub.add_argument("--inventory", type=Path, required=True)
        if command in ("interop", "profile"):
            sub.add_argument("--profile", type=Path, default=BASE_PROFILE)
            for key in ("output", "power", "harness"):
                sub.add_argument("--" + key, type=Path, required=True)
            for key in ("power", "harness"):
                sub.add_argument("--" + key + "-sha256", required=True)
        if command == "interop":
            sub.add_argument("--java", type=Path, required=True)
            sub.add_argument("--inventory-sha256", required=True)
            sub.add_argument("--port", type=int, required=True)
            sub.add_argument("--oil-buckets", action="store_true")
        elif command == "profile":
            sub.add_argument("--name", required=True)
            for mod in ("steam", "electric", "mineralogy"):
                sub.add_argument("--" + mod, type=Path)
                sub.add_argument("--" + mod + "-sha256")
    args = parser.parse_args()
    if args.command == "profile":
        materialize_profile(args)
        return 0
    args.seed = args.seed.resolve(strict=True)
    args.inventory = args.inventory.resolve()
    if args.command == "seal":
        seal(args.seed, args.inventory)
        print(digest(args.inventory))
        return 0
    return interop(args)


if __name__ == "__main__":
    raise SystemExit(main())
