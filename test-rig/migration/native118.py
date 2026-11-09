"""Run the 1.18.2 destination with exact, disposable server inputs."""

import json
import os
import re
from pathlib import Path

from matrix import digest, stage as stage_server, write_json
from native117 import jar_identity, require_disjoint, runtime_error_lines
from native117_code import NativeLaunch, code_files


MINECRAFT = "1.18.2"
FORGE = "40.3.0"
FORGE_VERSION = f"{MINECRAFT}-{FORGE}"
MCP = "20220404.173914"
RIG = Path(__file__).resolve().parents[1]
LAUNCHER = f"libraries/net/minecraftforge/forge/{FORGE_VERSION}/" + (
    "win_args.txt" if os.name == "nt" else "unix_args.txt")
INVENTORY = "native118-code-inventory.json"
NETTY_PROBES = {
    "direct buffer constructor: unavailable": "java.lang.UnsupportedOperationException: Reflective setAccessible(true) disabled",
    "jdk.internal.misc.Unsafe.allocateUninitializedArray(int): unavailable": (
        "java.lang.IllegalAccessException: class io.netty.util.internal.PlatformDependent0$6"),
}


def log_findings(text):
    """Separate Netty's DEBUG capability probes from runtime exceptions."""
    failures, diagnostics = [], []
    current_probe = None
    for line in text.splitlines():
        header = re.fullmatch(r"\[[^\]]+\] \[[^\]]+/DEBUG\] "
                              r"\[io\.netty\.util\.internal\.PlatformDependent0/\]: (.*)", line)
        if header and header[1] in NETTY_PROBES:
            current_probe = header[1]
        elif current_probe in NETTY_PROBES and line.startswith(NETTY_PROBES[current_probe]):
            diagnostics.append({"probe": current_probe, "detail": line})
            current_probe = "frames"
        elif current_probe == "frames" and line.startswith("\tat "):
            continue
        else:
            current_probe = None
            failures.extend(runtime_error_lines(line))
    return failures, diagnostics


def require_clean_log(path):
    failures, diagnostics = log_findings(path.read_text(encoding="utf-8", errors="replace"))
    if failures:
        raise AssertionError(f"Native runtime errors in {path}: {failures[:5]}")
    return diagnostics


def require_boot_identity(path):
    text = path.read_text(encoding="utf-8", errors="replace")
    for marker in (f"Starting minecraft server version {MINECRAFT}",
                   f"Forge mod loading, version {FORGE}, for MC {MINECRAFT} with MCP {MCP}", "Done ("):
        if marker not in text:
            raise AssertionError("Missing native 1.18.2 startup witness: " + marker)


def require_native_boot(path):
    require_clean_log(path)
    require_boot_identity(path)


def seal(seed, receipt):
    if receipt.exists() or not (seed / LAUNCHER).is_file():
        raise ValueError("Supply an installed 1.18.2 server and a new inventory path")
    require_disjoint(receipt, seed)
    write_json(receipt, {"forge": FORGE_VERSION, "files": code_files(seed)})


def read_target(profile, seed, inventory, inventory_hash):
    seed = seed.resolve()
    data = json.loads(profile.read_text(encoding="utf-8"))
    if (data.get("minecraftVersion") != MINECRAFT or data.get("forge", {}).get("version") != FORGE_VERSION
            or data.get("mode") != "packaged" or data.get("portStatus") != "PORTED_UNQUALIFIED"
            or data.get("qualificationStatus") != "UNQUALIFIED"):
        raise ValueError("Use a pinned, unqualified Forge 40.3.0 destination profile")
    if digest(inventory) != inventory_hash.upper():
        raise AssertionError("Forge server inventory hash changed")
    code = json.loads(inventory.read_text(encoding="utf-8"))
    if code.get("forge") != FORGE_VERSION or code_files(seed) != code.get("files"):
        raise AssertionError("Forge server does not match its sealed code")
    entries = {}
    for mod in data["mods"]:
        jar = (RIG / mod["path"]).resolve()
        identity = mod["id"]
        if (identity in entries or not mod["version"].endswith(".118021")
                or mod["file"] != jar.name or "deobf" in jar.name
                or not re.fullmatch("[A-Fa-f0-9]{64}", mod["sha256"])):
            raise ValueError(f"Invalid packaged 1.18.2 mod pin: {identity}")
        if digest(jar) != mod["sha256"].upper():
            raise AssertionError(f"Pinned mod changed: {jar}")
        jar_identity(jar, identity, mod["version"])
        entries[identity] = dict(mod, path=jar)
    if not {"basepower", "basesteam", "baseelectric", "orespawn", "advantageworkstest"} <= entries.keys():
        raise ValueError("The combined test requires the three base mods, OreSpawn and Advantage Works")
    return seed, code, entries


def stage(runtime, target, port, *, normal=False):
    seed, code, entries = target
    for protected in (seed, *(entry["path"] for entry in entries.values())):
        require_disjoint(runtime, protected)
    expected = {entry["file"]: entry["sha256"].upper() for entry in entries.values()}
    if {entry["file"]: digest(entry["path"]) for entry in entries.values()} != expected:
        raise AssertionError("Pinned mod jar changed before staging")
    launcher = stage_server(runtime, (seed, LAUNCHER, [entry["path"] for entry in entries.values()]))
    staged = json.loads((runtime / "input-jars.json").read_text(encoding="utf-8"))
    if {entry["file"]: entry["sha256"] for entry in staged} != expected:
        raise AssertionError("Pinned mod jar changed during staging")
    write_json(runtime / INVENTORY, code)
    path = runtime / "server.properties"
    overrides = {"level-type": "default" if normal else "advantageworkstest:test_flat",
                 "generator-settings": "", "server-port": str(port), "generate-structures": "false",
                 "simulation-distance": "4", "allow-nether": "true"}
    lines = [line for line in path.read_text().splitlines() if line.partition("=")[0] not in overrides]
    path.write_text("\n".join(lines) + "\n" + "".join(f"{k}={v}\n" for k, v in overrides.items()))
    verify_runtime(runtime)
    return launcher


def verify_runtime(runtime):
    code = json.loads((runtime / INVENTORY).read_text(encoding="utf-8"))
    if code_files(runtime) != code["files"]:
        raise AssertionError("Staged server code changed")
    entries = json.loads((runtime / "input-jars.json").read_text(encoding="utf-8"))
    if {f.name: digest(f) for f in (runtime / "mods").glob("*.jar")} != {
            item["file"]: item["sha256"] for item in entries}:
        raise AssertionError("Staged mod jars changed")


class Server(NativeLaunch):
    forge_version = FORGE_VERSION

    def __init__(self, runtime, java, launcher, label):
        if launcher != LAUNCHER:
            raise ValueError("Wrong server launch target")
        verify_runtime(runtime)
        super().__init__(runtime, java, launcher, label)

    def validate_native_log(self, path):
        diagnostics = require_clean_log(path)
        if diagnostics:
            write_json(path.with_suffix(".platform-diagnostics.json"), diagnostics)

    def close(self):
        try:
            super().close()
            require_boot_identity(self.native_runtime / f"console-{self.native_label}.log")
        finally:
            verify_runtime(self.native_runtime)
