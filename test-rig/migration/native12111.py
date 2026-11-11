"""Run the Forge 61 Power/Base Metals interop witnesses in a fresh disposable world."""

import argparse
import base64
import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import traceback
import xml.etree.ElementTree as ET
from zipfile import ZipFile

import nbtlib

from matrix import digest, fingerprint, stage as stage_server, write_json
from native117 import jar_identity, output_path, require_disjoint
from native117_code import NativeLaunch, code_files
from native117_interop import mark_runtime, require_checks, require_probe_order
from native117_readiness import await_ready
from native1201 import FLAT_SETTINGS, log_findings as previous_log_findings


RIG = Path(__file__).resolve().parents[1]
MINECRAFT = "1.21.11"
FORGE = "61.1.0"
MCP = "20251223.124241"
FORGE_VERSION = f"{MINECRAFT}-{FORGE}"
SHIM = f"forge-{FORGE_VERSION}-shim.jar"
SHIM_SHA = "09008DB9881E35E62669865F3B7420741EDB1A52BC59C67A5FA03D24C6C8E6E3"
LAUNCHER = f"libraries/net/minecraftforge/forge/{FORGE_VERSION}/" + (
    "win_args.txt" if os.name == "nt" else "unix_args.txt")
INVENTORY = "native12111-code-inventory.json"
EXPLORATORY = "EXPLORATORY_UNQUALIFIED"
BASE_PROFILE = RIG / "profiles/native12111/power-interop.json"


def log_findings(text):
    """Classify only exact DEBUG capability probes reproduced by unmodded Forge."""
    header = re.compile(r"^\[[^\]]+\] \[[^\]]+/([A-Z]+)\] \[([^\]]+)/\]: (.*)$")
    blocks = []
    for line in text.splitlines():
        if header.fullmatch(line) or not blocks:
            blocks.append([line])
        else:
            blocks[-1].append(line)
    failures, diagnostics = [], []
    probes = {
        ("io.netty.channel.kqueue.KQueue", "KQueue support is not available"):
            "Caused by: java.lang.IllegalStateException: Only supported on OSX/BSD",
        ("io.netty.channel.epoll.Epoll", "Epoll support is not available"):
            "Caused by: java.lang.IllegalStateException: Only supported on Linux",
    }
    unsafe = "java.lang.IllegalAccessException: symbolic reference class is not accessible: class jdk.internal.misc.Unsafe, from class io.netty.util.internal.PlatformDependent0 (module io.netty.common)"
    for block in blocks:
        if any(re.search(r"Server empty for \d+ seconds, pausing", line) for line in block):
            failures.extend(line for line in block if "Server empty for " in line)
            continue
        match = header.fullmatch(block[0])
        body = [line for line in block[1:] if line.strip()]
        reason = probes.get((match[2], match[3])) if match and match[1] == "DEBUG" else None
        expected = set()
        if reason:
            expected = {"java.lang.ExceptionInInitializerError", reason}
        elif match and match[1] == "DEBUG" and (match[2], match[3]) == (
                "io.netty.util.internal.PlatformDependent0", "jdk.internal.misc.Unsafe.allocateUninitializedArray(int): unavailable"):
            expected = {unsafe}
        details = {line for line in body if not re.fullmatch(r"\tat .*|\t\.\.\. [0-9]+ more", line)}
        if expected and details == expected and not any("zone.moddev.mc." in line for line in body):
            diagnostics.append(dict(probe=match[3], logger=match[2], level="DEBUG", stack=body,
                                    classification="Native Forge/Netty platform capability fallback"))
        else:
            errors, platform = previous_log_findings("\n".join(block))
            failures.extend(errors)
            diagnostics.extend(platform)
    return failures, diagnostics


def require_clean_log(path):
    failures, diagnostics = log_findings(path.read_text(encoding="utf-8", errors="replace"))
    if failures:
        raise AssertionError(f"Native runtime errors in {path}: {failures[:5]}")
    return diagnostics


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
            raise AssertionError(f"Expected a native 1.21.11 stack in checkpoint: {stack}")
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
    result = subprocess.run([str(java), "-version"], env=native_launch_environment(),
                            capture_output=True, text=True, check=True, timeout=30)
    version = (result.stderr or result.stdout).strip()
    if not re.search(r'version "21(?:\.|")', version):
        raise ValueError(f"Forge 61 test requires Java 21: {version}")
    return java, version


def inspect_seed(seed):
    validate_hash(seed / SHIM, SHIM_SHA)
    command = (seed / LAUNCHER).read_text(encoding="utf-8").split()
    if command != ["-Djava.net.preferIPv6Addresses=system", "-jar", SHIM]:
        raise AssertionError("Forge 61 launcher is not the official shim command")
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
        raise ValueError("Use the exploratory Forge 61 packaged profile")
    mods = {
        "basepower": ("2.4.15.121111", args.power, args.power_sha256),
        "advantageworkstest": ("1.1.5.121111", args.harness, args.harness_sha256),
    }
    for entry in data["mods"]:
        identity = entry["id"]
        if identity in mods:
            raise ValueError(f"Duplicate mod identity: {identity}")
        mods[identity] = (entry["version"], (RIG / entry["path"]).resolve(), entry["sha256"])
    if set(mods) != {"basepower", "advantageworkstest", "basemetals", "orespawn"}:
        raise ValueError("Power interop requires exactly Power, Base Metals, OreSpawn, and the harness")
    for identity, (version, path, sha) in mods.items():
        if not version.endswith(".121111") or not path.name.endswith(f"-{version}.jar"):
            raise ValueError(f"Expected an ordinary Forge 61 jar: {path}")
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
        raise ValueError("Expected a materialized Forge 61 packaged profile")
    mods = {}
    for item in data["mods"]:
        identity = item["id"]
        path = (RIG / item["path"]).resolve(strict=True)
        version = item["version"]
        if identity in mods or not version.endswith(".121111") or not path.name.endswith(f"-{version}.jar") or path.name != item["file"]:
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
                raise ValueError(f"Need an ordinary 1.21.11 jar and exact SHA-256 for {identity}")
            version = path.stem.rsplit("-", 1)[-1]
            if not version.endswith(".121111"):
                raise ValueError(f"Wrong Minecraft/loader target for {identity}: {version}")
            validate_hash(path, sha)
            jar_identity(path, identity, version)
            mods[identity] = (version, path, sha)
    for legacy in ("powerDeobfSha256", "sourceCommit"):
        base.pop(legacy, None)
    base.update(mode="packaged", runtimeBase="build/works-native12111", minecraftVersion=MINECRAFT,
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
        "pause-when-empty-seconds": "-1",
    }
    existing = [line for line in settings.read_text().splitlines() if line.partition("=")[0] not in overrides]
    settings.write_text("\n".join(existing + [f"{key}={value}" for key, value in overrides.items()]) + "\n")
    config = runtime / "config/fml.toml"
    config.parent.mkdir()
    config.write_text("versionCheck = false\n", encoding="utf-8")
    mark_runtime(runtime)
    verify_runtime(runtime, inventory, expected)
    return expected


def native_launch_environment(runtime=None, label=None, *, diagnostic_environment=None, diagnostic_environment_sha256=None):
    """No inherited JVM options; sealed, runtime-bound mixin export is diagnostic only."""
    environment = os.environ.copy()
    for variable in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
        if environment.get(variable, "").strip():
            raise ValueError(f"Forge 61 cannot inherit JVM options or agents from {variable}")
    if diagnostic_environment is None and diagnostic_environment_sha256 is None:
        return environment
    if diagnostic_environment is None or diagnostic_environment_sha256 is None:
        raise ValueError("Diagnostic environment requires an explicit manifest and SHA-256 together")
    if not isinstance(diagnostic_environment_sha256, str) or not re.fullmatch(r"[A-Fa-f0-9]{64}", diagnostic_environment_sha256):
        raise ValueError("Diagnostic environment requires an exact SHA-256 pin")
    if not isinstance(label, str) or not re.fullmatch(r"[A-Za-z0-9_.-]{1,100}", label) or label in (".", ".."):
        raise ValueError("Unsafe diagnostic launch label")
    if runtime is None:
        raise ValueError("Diagnostic environment requires an explicit runtime")
    runtime = Path(runtime).resolve(strict=True)
    manifest = Path(diagnostic_environment).resolve(strict=True)
    if not runtime.is_dir() or not manifest.is_file() or manifest.stat().st_size > 16384:
        raise ValueError("Diagnostic manifest must be a bounded file for an existing runtime")
    raw = manifest.read_bytes()
    if len(raw) > 16384 or hashlib.sha256(raw).hexdigest().upper() != diagnostic_environment_sha256.upper():
        raise ValueError("Diagnostic environment SHA-256 mismatch")

    def unique_fields(pairs):
        document = {}
        for key, value in pairs:
            if key in document:
                raise ValueError(f"Duplicate diagnostic manifest field: {key}")
            document[key] = value
        return document

    document = json.loads(raw, object_pairs_hook=unique_fields)
    if (not isinstance(document, dict)
            or set(document) != {"format", "purpose", "runtime", "label", "properties"}
            or type(document["format"]) is not int or document["format"] != 1
            or document["purpose"] != "NATIVE61_MIXIN_EXPORT_DIAGNOSTIC"
            or document["runtime"] != str(runtime) or document["label"] != label):
        raise ValueError("Diagnostic manifest must bind this native61 runtime, label and purpose")
    properties = document["properties"]
    allowed = {"mixin.debug.export", "mixin.debug.export.decompile", "mixin.debug.export.filter"}
    if (not isinstance(properties, dict) or set(properties) - allowed
            or properties.get("mixin.debug.export") is not True):
        raise ValueError("Only an explicit mixin-export diagnostic may inject JVM properties")
    options = ["-Dmixin.debug.export=true"]
    if "mixin.debug.export.decompile" in properties:
        decompile = properties["mixin.debug.export.decompile"]
        if type(decompile) is not bool:
            raise ValueError("Mixin export decompile must be a boolean")
        options.append("-Dmixin.debug.export.decompile=" + str(decompile).lower())
    if "mixin.debug.export.filter" in properties:
        pattern = properties["mixin.debug.export.filter"]
        if not isinstance(pattern, str) or not re.fullmatch(r"[A-Za-z0-9_.$*?]{1,256}", pattern):
            raise ValueError("Unsafe mixin export filter")
        options.append("-Dmixin.debug.export.filter=" + pattern)
    injected = {"JAVA_TOOL_OPTIONS": " ".join(options)}
    receipt = runtime / f"native61-launch-environment-{label}.json"
    with receipt.open("x", encoding="utf-8") as output:
        json.dump(dict(status="DIAGNOSTIC_NOT_QUALIFIED", qualificationAllowed=False,
                       manifestSha256=diagnostic_environment_sha256.upper(),
                       manifest=document, environment=injected), output, indent=2, sort_keys=True)
        output.write("\n")
    environment.update(injected)
    return environment


class Server(NativeLaunch):
    forge_version = FORGE_VERSION

    def __init__(self, runtime, java, launcher, label, *, diagnostic_environment=None, diagnostic_environment_sha256=None):
        self.diagnostic_environment = diagnostic_environment
        self.diagnostic_environment_sha256 = diagnostic_environment_sha256
        super().__init__(runtime, java, launcher, label)

    def launch_environment(self):
        return native_launch_environment(self.native_runtime, self.native_label,
                                        diagnostic_environment=self.diagnostic_environment,
                                        diagnostic_environment_sha256=self.diagnostic_environment_sha256)

    @staticmethod
    def unexpected_line(line):
        return re.search(r"Server empty for \d+ seconds, pausing", line) is not None

    def logging_configuration(self, logging_jar):
        with ZipFile(logging_jar) as jar:
            original = jar.read("log4j2.xml")
        tree = ET.fromstring(original)
        changed = 0
        for layout in tree.iter("PatternLayout"):
            pattern = layout.get("pattern", "")
            if "%xEx" in pattern:
                changed += pattern.count("%xEx")
                layout.set("pattern", pattern.replace("%xEx", "%ex"))
        if changed != 2:
            raise AssertionError("Forge 61 logging changed; review the platform formatting adapter")
        # Plain stack traces retain every exception and logger threshold without
        # initializing unavailable OSX/Linux Netty classes to render version labels.
        content = ET.tostring(tree, encoding="utf-8", xml_declaration=True)
        config = self.native_runtime / "forge61-log4j2-plain-stacktraces.xml"
        if config.exists():
            if config.read_bytes() != content:
                raise AssertionError("The native logging configuration changed during the test")
        else:
            with config.open("xb") as output:
                output.write(content)
            write_json(self.native_runtime / "forge61-logging-custody.json", dict(
                sourceJarSha256=digest(logging_jar), configSha256=digest(config),
                modification="Two extended stack-trace renderers replaced by plain stack traces; all levels and filters retained"))
        return config.resolve().as_uri()

    def send(self, command):
        if not command.startswith("gamerule "):
            return super().send(command)
        words = command.split()
        if len(words) not in (2, 3):
            raise ValueError("Expected a single gamerule query or assignment")
        aliases = {"doMobSpawning": "spawn_mobs", "randomTickSpeed": "random_tick_speed",
                   "doWeatherCycle": "advance_weather", "doDaylightCycle": "advance_time",
                   "doTileDrops": "block_drops", "doEntityDrops": "entity_drops",
                   "keepInventory": "keep_inventory", "mobGriefing": "mob_griefing"}
        rule = aliases.get(words[1], words[1])
        if not re.fullmatch(r"(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+", rule):
            raise ValueError("Unknown legacy gamerule; add an explicit native mapping: " + words[1])
        if ":" not in rule:
            rule = "minecraft:" + rule
        super().send(" ".join(["gamerule", rule, *words[2:]]))
        # The command must take effect, not merely be written to stdin.
        shown = rule.removeprefix("minecraft:")
        token = f"Gamerule {shown} is now set to: {words[2]}" if len(words) == 3 else f"Gamerule {shown} is currently set to: "
        self.wait(token, 15)

    def validate_native_log(self, path):
        diagnostics = require_clean_log(path)
        if diagnostics:
            write_json(path.with_suffix(".platform-diagnostics.json"), diagnostics)

    def close(self):
        super().close()
        logging_jar = self.native_runtime / f"libraries/net/minecraftforge/fmlloader/{FORGE_VERSION}/fmlloader-{FORGE_VERSION}.jar"
        self.logging_configuration(logging_jar)
        console = (self.native_runtime / f"console-{self.native_label}.log").read_text(encoding="utf-8")
        for marker in (f"Starting minecraft server version {MINECRAFT}",
                       f"Forge mod loading, version {FORGE}, for MC {MINECRAFT} with MCP {MCP}", "Done ("):
            if marker not in console:
                raise AssertionError(f"Missing Forge 61 startup witness: {marker}")


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
    receipt = runtime / "native12111-interop-results.json"
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
