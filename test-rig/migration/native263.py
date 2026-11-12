"""Forge 66.1.0 server custody and process control; historical engines stay unchanged."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import subprocess
from zipfile import ZipFile

from matrix import digest, stage as stage_server, write_json
from native117 import jar_identity, require_disjoint
from native117_code import NativeLaunch, code_files
from native117_interop import mark_runtime
from native1201 import FLAT_SETTINGS
from native12111 import (compat_tile_nbt, compat_tile_data, parse_endurance_snapshot,
                         log_findings as previous_log_findings)
from native263_platform_diagnostics import COMMON_JAR_SHA, classify as classify_platform_log


RIG = Path(__file__).resolve().parents[1]
ENGINE_PROTOCOL = "NATIVE66"
MINECRAFT = "26.3"
FORGE = "66.0.0"
MCP = "20260918.230105"
FORGE_VERSION = f"{MINECRAFT}-{FORGE}"
SHIM = f"forge-{FORGE_VERSION}-shim.jar"
SHIM_SHA = "5D4687093B4E34ABF1254034B0DA3BCA48F225569343809F906C586B0685BAB1"
INSTALLER_SHA = "C6173EEA56AED0B947722F314542196E28A35285844961D517C7FB571492BBE3"
JAVA_SHA = "D5F1B9E03E298EBFB04D2326ECC1E2AF3E6817F9B4D7F9F151FE856D56975650"
JAVA_VERSION = "25.0.3+9"
LOGGING_JAR_SHA = "041CDCC0BCF4207410D06D8F17EECCE0097FBC19A4263E6E619171A22D20340F"
LOGGING_XML_SHA = "9680BAF9F936C8CD009CD3A8D75253B476FCA9C316F8D5B5C51700053BEAE440"
LAUNCHER = f"libraries/net/minecraftforge/forge/{FORGE_VERSION}/" + (
    "win_args.txt" if os.name == "nt" else "unix_args.txt")
INVENTORY = "native263-code-inventory.json"
EXPLORATORY = "EXPLORATORY_UNQUALIFIED"
VERSIONS = {"basepower": "2.4.18.2603001", "advantageworkstest": "1.1.8.2603001",
            "basesteam": "2.2.19.2603001", "baseelectric": "2.2.19.2603001",
            "basemetals": "3.1.2.2603001", "orespawn": "4.1.1.2603001"}


def read_json(path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("Duplicate JSON field: " + key)
            result[key] = value
        return result
    path = Path(path)
    if path.stat().st_size > 16 * 1024 * 1024:
        raise ValueError("Unbounded input document: " + str(path))
    return json.loads(path.read_text(encoding="utf-8-sig"), object_pairs_hook=unique)


def validate_hash(path, expected):
    if not isinstance(expected, str) or not re.fullmatch(r"[A-Fa-f0-9]{64}", expected):
        raise ValueError("An exact SHA-256 pin is required: " + str(path))
    if digest(Path(path)) != expected.upper():
        raise AssertionError("Artifact SHA-256 mismatch: " + str(path))


def native_launch_environment(runtime=None, label=None, *, diagnostic_environment=None,
                              diagnostic_environment_sha256=None):
    environment = os.environ.copy()
    for key in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH"):
        if environment.get(key, "").strip():
            raise ValueError("Forge 66 cannot inherit launch options from " + key)
    if diagnostic_environment is None and diagnostic_environment_sha256 is None:
        return environment
    if diagnostic_environment is None or diagnostic_environment_sha256 is None:
        raise ValueError("Diagnostic manifest and exact SHA-256 must be supplied together")
    if runtime is None or not isinstance(label, str) or not re.fullmatch(r"[A-Za-z0-9_-][A-Za-z0-9_.-]{0,99}", label):
        raise ValueError("Diagnostic launch needs a bounded runtime and label")
    runtime = Path(runtime).resolve(strict=True)
    path = Path(diagnostic_environment).resolve(strict=True)
    if path.stat().st_size > 16384:
        raise ValueError("Unbounded diagnostic manifest")
    validate_hash(path, diagnostic_environment_sha256)
    document = read_json(path)
    if (set(document) != {"format", "purpose", "runtime", "label", "properties"}
            or type(document["format"]) is not int or document["format"] != 1
            or document["purpose"] != "NATIVE66_MIXIN_EXPORT_DIAGNOSTIC"
            or document["runtime"] != str(runtime) or document["label"] != label):
        raise ValueError("Diagnostic manifest is not bound to this native66 launch")
    properties = document["properties"]
    allowed = {"mixin.debug.export", "mixin.debug.export.decompile", "mixin.debug.export.filter"}
    if (not isinstance(properties, dict) or set(properties) - allowed
            or properties.get("mixin.debug.export") is not True):
        raise ValueError("Only explicitly sealed mixin export properties are permitted")
    options = ["-Dmixin.debug.export=true"]
    if "mixin.debug.export.decompile" in properties:
        value = properties["mixin.debug.export.decompile"]
        if type(value) is not bool:
            raise ValueError("Mixin decompile must be boolean")
        options.append("-Dmixin.debug.export.decompile=" + str(value).lower())
    if "mixin.debug.export.filter" in properties:
        value = properties["mixin.debug.export.filter"]
        if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_.$*?]{1,256}", value):
            raise ValueError("Unsafe mixin export filter")
        options.append("-Dmixin.debug.export.filter=" + value)
    injected = {"JAVA_TOOL_OPTIONS": " ".join(options)}
    with (runtime / f"native66-launch-environment-{label}.json").open("x", encoding="utf-8") as output:
        json.dump(dict(status="DIAGNOSTIC_NOT_QUALIFIED", qualificationAllowed=False,
                       manifestSha256=diagnostic_environment_sha256.upper(), manifest=document,
                       environment=injected), output, indent=2, sort_keys=True)
    environment.update(injected)
    return environment


def java25(executable):
    java = Path(executable).resolve(strict=True)
    validate_hash(java, JAVA_SHA)
    result = subprocess.run([str(java), "-XX:+UseCompactObjectHeaders", "-version"],
                            env=native_launch_environment(), capture_output=True, text=True,
                            check=True, timeout=30)
    version = (result.stderr or result.stdout).strip()
    if not re.search(r'version "25\.0\.3"', version) or "25.0.3+9" not in version:
        raise ValueError("Forge 66 requires the pinned Java 25.0.3+9: " + version)
    return java, version


def require_free_port(port):
    if type(port) is not int or not 1024 <= port <= 65535:
        raise ValueError("Supply a nonprivileged TCP port")
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        if hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
            probe.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        try:
            probe.bind(("127.0.0.1", port))
        except OSError as failure:
            raise ValueError(f"TCP port {port} is occupied; no process will be terminated") from failure


def inspect_seed(seed):
    seed = Path(seed).resolve(strict=True)
    for path in [seed, *(seed / "libraries").rglob("*"), *seed.glob("*.jar")]:
        if path.is_symlink() or path.is_junction():
            raise ValueError("Server code may not use filesystem aliases: " + str(path))
        if path.is_file() and path.stat().st_nlink != 1:
            raise ValueError("Server code may not share writable hard links: " + str(path))
    validate_hash(seed / SHIM, SHIM_SHA)
    validate_hash(seed / "libraries/io/netty/netty-common/4.2.16.Final/netty-common-4.2.16.Final.jar", COMMON_JAR_SHA)
    command = (seed / LAUNCHER).read_text(encoding="utf-8").split()
    if command != ["-Djava.net.preferIPv6Addresses=system", "-XX:+UseCompactObjectHeaders",
                   "-XX:StackShadowPages=32", "-jar", SHIM]:
        raise AssertionError("Forge 66 launcher is not the selected official shim command")
    files = code_files(seed)
    if set(seed.glob("*.jar")) != {seed / SHIM}:
        raise AssertionError("Unexpected root server code")
    return dict(engineProtocol=ENGINE_PROTOCOL, forge=FORGE_VERSION, javaVersion=JAVA_VERSION, javaSha256=JAVA_SHA,
                installerSha256=INSTALLER_SHA, files=files)


def seal(seed, inventory):
    seed, inventory = Path(seed).resolve(strict=True), Path(inventory).resolve()
    require_disjoint(inventory, seed)
    if inventory.exists():
        raise FileExistsError(inventory)
    allowed = {SHIM, "libraries"}
    if {path.name for path in seed.iterdir()} != allowed:
        raise ValueError("Seal only a fresh code-only SDK, never a configured server/world")
    result = inspect_seed(seed)
    inventory.parent.mkdir(parents=True, exist_ok=True)
    with inventory.open("x", encoding="utf-8") as output:
        json.dump(result, output, indent=2, sort_keys=True)
        output.write("\n")
    return result


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
    seed = Path(seed).resolve(strict=True)
    validate_hash(inventory_file, inventory_sha256)
    inventory = read_json(inventory_file)
    if inspect_seed(seed) != inventory:
        raise AssertionError("Selected Forge 66 server differs from its sealed inventory")
    document = read_json(profile)
    if (document.get("mode"), document.get("minecraftVersion"), document.get("forge", {}).get("version"),
            document.get("qualificationStatus")) != ("packaged", MINECRAFT, FORGE_VERSION, EXPLORATORY):
        raise ValueError("Expected an explicitly materialized Forge 66 packaged profile")
    mods = {}
    for item in document["mods"]:
        identity, version = item["id"], item["version"]
        path = (RIG / item["path"]).resolve(strict=True)
        if identity in mods or version != VERSIONS.get(identity) or path.name != item["file"] or not path.name.endswith(f"-{version}.jar"):
            raise ValueError("Invalid native263 ordinary mod pin: " + identity)
        validate_hash(path, item["sha256"])
        jar_identity(path, identity, version)
        mods[identity] = version, path, item["sha256"].upper()
    if set(mods) != set(VERSIONS):
        raise ValueError("Full family qualification requires all six exact native263 inputs")
    verify_compile_pin(mods["advantageworkstest"][1], mods["basepower"][2])
    return seed, inventory, mods


def verify_runtime(runtime, inventory, expected):
    runtime = Path(runtime)
    if inspect_seed(runtime) != inventory:
        raise AssertionError("Staged Forge 66 code changed")
    mods = runtime / "mods"
    if any(not path.is_file() or path.is_symlink() or path.stat().st_nlink != 1 for path in mods.iterdir()):
        raise AssertionError("Unexpected aliased or nested mod input")
    if {path.name: digest(path) for path in mods.iterdir()} != expected:
        raise AssertionError("Staged mod jars changed or unexpected files were added")


def audit_staged(runtime):
    runtime = Path(runtime)
    expected = {item["file"]: item["sha256"] for item in read_json(runtime / "input-jars.json")}
    verify_runtime(runtime, read_json(runtime / INVENTORY), expected)


def stage(runtime, seed, inventory, mods, port):
    runtime = Path(runtime).resolve()
    native_launch_environment()
    if set(mods) != set(VERSIONS) or any(mods[key][0] != version for key, version in VERSIONS.items()):
        raise ValueError("Staging requires all six actual native263 family inputs")
    if len({mod[1].name.casefold() for mod in mods.values()}) != len(mods):
        raise ValueError("Mod filenames must be unique")
    require_free_port(port)
    if runtime.exists():
        raise FileExistsError(runtime)
    for path in [Path(seed), *(mod[1] for mod in mods.values())]:
        require_disjoint(runtime, Path(path).resolve(strict=True))
    if inspect_seed(seed) != inventory:
        raise AssertionError("Seed custody changed before staging")
    for _, path, sha in mods.values():
        validate_hash(path, sha)
    stage_server(runtime, (seed, LAUNCHER, [mod[1] for mod in mods.values()]))
    expected = {path.name: sha.upper() for _, path, sha in mods.values()}
    write_json(runtime / INVENTORY, inventory)
    configure_runtime(runtime, port)
    verify_runtime(runtime, inventory, expected)
    return expected


def configure_runtime(runtime, port, profile=None, recipe_profile=None):
    if recipe_profile is not None:
        raise ValueError("Recipe configuration needs an explicit reviewed native263 adapter")
    runtime = Path(runtime)
    profile = profile or {}
    overrides = {"level-type": profile.get("levelType", "minecraft:flat"),
                 "generator-settings": json.dumps(FLAT_SETTINGS, separators=(",", ":")),
                 "server-ip": "127.0.0.1", "server-port": port,
                 "generate-structures": "false", "simulation-distance": 4, "allow-nether": "true",
                 "pause-when-empty-seconds": "-1"}
    settings = runtime / "server.properties"
    old = [line for line in settings.read_text(encoding="utf-8").splitlines()
           if line.partition("=")[0] not in overrides]
    settings.write_text("\n".join(old + [f"{key}={value}" for key, value in overrides.items()]) + "\n", encoding="utf-8")
    config = runtime / "config/fml.toml"
    config.parent.mkdir(exist_ok=True)
    config.write_text("versionCheck = false\n", encoding="utf-8")
    mark_runtime(runtime)


def require_launch_settings(runtime):
    settings = {}
    for line in (Path(runtime) / "server.properties").read_text(encoding="utf-8").splitlines():
        if not line or line.startswith(("#", "!")):
            continue
        key, separator, value = line.partition("=")
        if not separator or key in settings:
            raise ValueError("Ambiguous server launch property: " + key)
        settings[key] = value
    if settings.get("pause-when-empty-seconds") != "-1" or settings.get("server-ip") != "127.0.0.1":
        raise ValueError("Native263 requires explicit loopback binding and disabled empty-server pausing")
    require_free_port(int(settings["server-port"]))


def plain_logging_bytes(original):
    if hashlib.sha256(original).hexdigest().upper() != LOGGING_XML_SHA or original.count(b"%xEx") != 2:
        raise AssertionError("Selected Forge 66 logging source changed; review the formatter adapter")
    # Byte preservation keeps every level, filter, marker and appender unchanged.
    return original.replace(b"%xEx", b"%ex")


def logging_configuration(runtime, logging_jar):
    runtime, logging_jar = Path(runtime), Path(logging_jar)
    validate_hash(logging_jar, LOGGING_JAR_SHA)
    with ZipFile(logging_jar) as jar:
        content = plain_logging_bytes(jar.read("log4j2.xml"))
    config = runtime / "forge66-log4j2-plain-stacktraces.xml"
    custody = runtime / "forge66-logging-custody.json"
    receipt = dict(sourceJarSha256=LOGGING_JAR_SHA, sourceXmlSha256=LOGGING_XML_SHA,
                   configSha256=hashlib.sha256(content).hexdigest().upper(),
                   modification="Exactly two %xEx renderers to %ex; all other source bytes retained")
    if config.exists() or custody.exists():
        if not config.is_file() or not custody.is_file() or config.read_bytes() != content or read_json(custody) != receipt:
            raise AssertionError("Forge 66 logging custody changed")
    else:
        with config.open("xb") as output:
            output.write(content)
        with custody.open("x", encoding="utf-8") as output:
            json.dump(receipt, output, indent=2, sort_keys=True)
    return config.resolve().as_uri()


def log_findings(text):
    failures, diagnostics = classify_platform_log(text, previous_log_findings)
    for line in text.splitlines():
        if re.search(r"Server empty for \d+ seconds, pausing|Unable to write to stream|Unable to invoke factory method", line):
            if line not in failures:
                failures.append(line)
    return failures, diagnostics


def require_clean_log(path):
    failures, diagnostics = log_findings(Path(path).read_text(encoding="utf-8", errors="replace"))
    if failures:
        raise AssertionError(f"Native66 runtime errors in {path}: {failures[:5]}")
    return diagnostics


def require_complete_boot(text, *, stop_sent, returncode):
    if returncode != 0 or not stop_sent:
        raise AssertionError("Native66 requires its owned stop command and a zero process exit")
    markers = (f"Forge mod loading, version {FORGE}, for MC {MINECRAFT} with MCP {MCP}",
               f"Starting minecraft server version {MINECRAFT}", "Done (", "Stopping server",
               "All dimensions are saved")
    cursor = 0
    for marker in markers:
        position = text.find(marker, cursor)
        if position < 0:
            raise AssertionError("Missing or out-of-order native66 startup / cold-stop witness: " + marker)
        cursor = position + len(marker)


class Server(NativeLaunch):
    forge_version = FORGE_VERSION

    def __init__(self, runtime, java, launcher, label, *, diagnostic_environment=None,
                 diagnostic_environment_sha256=None):
        runtime = Path(runtime).resolve(strict=True)
        if not re.fullmatch(r"[A-Za-z0-9_-][A-Za-z0-9_.-]{0,99}", label):
            raise ValueError("Unsafe launch label")
        if str(launcher).replace("\\", "/") != LAUNCHER:
            raise ValueError("Launch only the selected Forge 66 official argument file")
        java25(java)
        audit_staged(runtime)
        require_launch_settings(runtime)
        self.diagnostic_environment = diagnostic_environment
        self.diagnostic_environment_sha256 = diagnostic_environment_sha256
        self.stop_sent = False
        self.closed = False
        super().__init__(runtime, java, launcher, label)
        write_json(runtime / f"native66-process-{label}.json", dict(
            pid=self.process.pid, runtime=str(runtime), javaSha256=JAVA_SHA,
            javaVersion=JAVA_VERSION, forge=FORGE_VERSION, stdin="OWNED_PIPE",
            qualificationAllowed=diagnostic_environment is None))

    def launch_environment(self):
        return native_launch_environment(self.native_runtime, self.native_label,
                                        diagnostic_environment=self.diagnostic_environment,
                                        diagnostic_environment_sha256=self.diagnostic_environment_sha256)

    @staticmethod
    def unexpected_line(line):
        return re.search(r"Server empty for \d+ seconds, pausing", line) is not None

    def logging_configuration(self, logging_jar):
        return logging_configuration(self.native_runtime, logging_jar)

    def send(self, command):
        if not isinstance(command, str) or not command or "\n" in command or "\r" in command:
            raise ValueError("Expected one bounded server command")
        if command.startswith("gamerule "):
            words = command.split()
            if len(words) not in (2, 3):
                raise ValueError("Expected one gamerule query or assignment")
            aliases = {"doMobSpawning": "spawn_mobs", "randomTickSpeed": "random_tick_speed",
                       "doWeatherCycle": "advance_weather", "doDaylightCycle": "advance_time",
                       "doTileDrops": "block_drops", "doEntityDrops": "entity_drops",
                       "keepInventory": "keep_inventory", "mobGriefing": "mob_griefing"}
            rule = aliases.get(words[1], words[1])
            if not re.fullmatch(r"(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+", rule):
                raise ValueError("Unknown legacy gamerule: " + words[1])
            if ":" not in rule:
                rule = "minecraft:" + rule
            super().send(" ".join(["gamerule", rule, *words[2:]]))
            shown = rule.removeprefix("minecraft:")
            self.wait(f"Gamerule {shown} is now set to: {words[2]}" if len(words) == 3
                      else f"Gamerule {shown} is currently set to: ", 15)
            return
        super().send(command)
        if command == "stop":
            self.stop_sent = True

    def validate_native_log(self, path):
        diagnostics = require_clean_log(path)
        if diagnostics:
            write_json(path.with_suffix(".platform-diagnostics.json"), diagnostics)

    def close(self):
        if self.closed:
            audit_staged(self.native_runtime)
            return
        try:
            super().close()
            text = (self.native_runtime / f"console-{self.native_label}.log").read_text(encoding="utf-8")
            require_complete_boot(text, stop_sent=self.stop_sent, returncode=self.process.returncode)
            logging_jar = self.native_runtime / f"libraries/net/minecraftforge/fmlloader/{FORGE_VERSION}/fmlloader-{FORGE_VERSION}.jar"
            self.logging_configuration(logging_jar)
        finally:
            audit_staged(self.native_runtime)
            self.closed = self.process.poll() is not None


def launch(runtime, java, label="native66"):
    return Server(runtime, java, LAUNCHER, label)


def cold_stop(server):
    server.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    command = sub.add_parser("seal")
    command.add_argument("--seed", type=Path, required=True)
    command.add_argument("--inventory", type=Path, required=True)
    command = sub.add_parser("verify")
    command.add_argument("--profile", type=Path, required=True)
    command.add_argument("--seed", type=Path, required=True)
    command.add_argument("--inventory", type=Path, required=True)
    command.add_argument("--inventory-sha256", required=True)
    command.add_argument("--java", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "seal":
        seal(args.seed, args.inventory)
    else:
        java25(args.java)
        read_family_target(args.profile, args.seed, args.inventory, args.inventory_sha256)
        print("Native263 inputs verified; no world opened")


if __name__ == "__main__":
    main()
