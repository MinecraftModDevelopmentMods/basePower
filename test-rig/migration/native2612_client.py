"""Pinned native64 real-world client launch and strict retained resource evidence.

Actual-world oil/reload and human gameplay remain distinct, mandatory scopes.
"""

import hashlib
import json
import math
import os
import re
from pathlib import Path
import shlex
import shutil
import struct
import subprocess
import tomllib
import xml.etree.ElementTree as ET
from zipfile import ZipFile
import zlib

from matrix import digest, fingerprint, write_json
from native117 import output_path, require_disjoint
import native2612 as native
import native2612_layout as layout
import native2612_client_diagnostics as client_diagnostics
from native2612_resources import FAMILY, arguments, artifact, preflight, require


ASSET_INDEX = "30"
ASSET_INDEX_SHA1 = "49107CF4E5F961B086AC4F06F3C2C56142B023DC"
VIEWPORTS = ((320, 240, 1), (426, 240, 2), (640, 360, 1))
VM_ARGS = ("-Djava.net.preferIPv6Addresses=system", "-XX:+UseCompactObjectHeaders", "--add-opens",
           "java.base/java.lang.invoke=ALL-UNNAMED", "-Dforge.enableGameTest=true",
           "-Dforge.logging.markers=REGISTRIES", "-Dforge.logging.console.level=info")
RESULT = "native2612-client-results.json"
CLIENT_WORLD = "AdvantageClientResources"
CLIENT_USERNAME = "AdvantageProbe"
FIXTURE_PROTOCOL = "NATIVE64_SEALED_EXPERIMENTAL_FIXTURE_SKIP_V2"
FIXTURE_SCOPE = "COMPLETED_NATIVE64_BROAD_PACKAGED_CURRENT_COPY"


def verify_world_source(world, receipt, profile_sha256, mods):
    require(receipt["status"] == "PASS" and receipt["inputsUnchanged"] is True
            and receipt["seedUnchanged"] is True and receipt["profileSha256"] == profile_sha256.upper()
            and receipt["jars"] == {key: row[2] for key, row in mods.items()},
            "Client world lacks a completed exact-family source receipt")
    require(receipt["minecraft"] == native.MINECRAFT and receipt["forge"] == native.FORGE
            and receipt["scope"] == "PACKAGED_WALKABLE_WORKS_AND_TWELVE_DRILL_WITNESSES",
            "Client source is not the approved native broad fixture")
    completed = receipt["profiles"]["packaged-current"]
    require(completed["status"] == "PASS" and completed["profile"] == "packaged-current"
            and all(completed["phases"][phase]["status"] == "PASS" for phase in (
                "first", "initial", "operation", "persistence", "restarted"))
            and completed["expectedMods"] == {"minecraft": native.MINECRAFT, "forge": native.FORGE,
                **{key: row[0] for key, row in mods.items()}}, "Client broad source is incomplete or has a different family")
    data = layout.read_level(world)
    require(str(data["Version"]["Name"]) == native.MINECRAFT, "Client source world is not native26")
    root = layout._load(Path(world) / "level.dat")
    versions = {str(row["ModId"]): str(row["ModVersion"]) for row in root["fml"]["LoadingModList"]}
    require(versions == {"minecraft": native.MINECRAFT, "forge": native.FORGE,
                         **{key: row[0] for key, row in mods.items()}}, "Client world mod identities differ")
    return fingerprint(world)


def validate_username(name):
    require(isinstance(name, str) and re.fullmatch(r"[A-Za-z0-9_]{1,16}", name) is not None,
            "Native offline probe name must contain 1..16 ASCII letters, digits or underscores")
    return name


def words(value):
    return [word[1:-1] if word.startswith('"') and word.endswith('"') else word
            for word in shlex.split(value, posix=False)]


def mod_ids(path):
    with ZipFile(path) as jar:
        if "META-INF/mods.toml" not in jar.namelist():
            return set()
        metadata = tomllib.loads(jar.read("META-INF/mods.toml").decode("utf-8"))
        return {row["modId"] for row in metadata.get("mods", [])}


def record(path):
    path = Path(path).resolve(strict=True)
    require(path.is_file() and path.stat().st_nlink == 1, "Client code needs independent files")
    for part in (path, *path.parents):
        require(not part.is_symlink() and not getattr(part, "is_junction", lambda: False)(), "Aliased client code")
    return dict(path=str(path), sha256=digest(path))


def inspect_launch(path):
    launch = record(path)
    tree = ET.parse(path).getroot()
    attrs = {}
    for row in tree.findall("stringAttribute"):
        require(row.attrib["key"] not in attrs, "Duplicate Eclipse launch attribute")
        attrs[row.attrib["key"]] = row.attrib["value"]
    require(attrs["org.eclipse.jdt.launching.MAIN_TYPE"] == "net.minecraftforge.launcher.Main",
            "Unexpected client entrypoint")
    vm = words(attrs["org.eclipse.jdt.launching.VM_ARGUMENTS"])
    require(tuple(vm) == VM_ARGS, "Unreviewed JVM options, agent, or altered client classpath")
    program = words(attrs["org.eclipse.jdt.launching.PROGRAM_ARGUMENTS"])
    require(len(program) == 19 and program[:3] == ["--main", "net.minecraftforge.bootstrap.ForgeBootstrap", "--cache"]
            and program[4] == "--metadata" and program[6:16] == ["--", "--gameDir", ".", "--launchTarget",
                "forge_userdev_client", "--version", "MOD_DEV", "--assetIndex", ASSET_INDEX, "--assetsDir"]
            and program[17:] == ["--mixin", "basepower.mixins.json"], "Unreviewed native client program arguments")
    environment = {}
    maps = [row for row in tree.findall("mapAttribute") if row.attrib["key"] == "org.eclipse.debug.core.environmentVariables"]
    require(len(maps) == 1, "Expected one explicit Eclipse environment")
    for row in maps[0].findall("mapEntry"):
        require(row.attrib["key"] not in environment, "Duplicate client environment")
        environment[row.attrib["key"]] = row.attrib["value"]
    expected_env = dict(MC_VERSION=native.MINECRAFT, FORGE_VERSION=native.FORGE, FORGE_GROUP="net.minecraftforge",
                        MCP_MAPPINGS="official_26.1.2", MCP_VERSION=native.MCP, target="fmluserdevclient")
    require({key: value for key, value in environment.items() if key != "MOD_CLASSES"} == expected_env,
            "Wrong SDK environment or unreviewed injection")
    # MOD_CLASSES and IDE/JRE entries are not executed. Construct a jar-only classpath below.
    environment["MOD_CLASSES"] = ""
    lists = [row for row in tree.findall("listAttribute") if row.attrib["key"] == "org.eclipse.jdt.launching.CLASSPATH"]
    require(len(lists) == 1, "Expected one explicit client classpath")
    archives, skipped, ide = [], [], []
    for row in lists[0].findall("listEntry"):
        value = ET.fromstring(row.attrib["value"]).attrib
        if "externalArchive" not in value:
            require(value in ({"projectName": "PowerAdvantage", "type": "1", "path": "5"},
                              {"containerPath": "org.eclipse.jdt.launching.JRE_CONTAINER/org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType/JavaSE-25",
                               "type": "4", "path": "5"}), "Unreviewed IDE classpath output")
            ide.append(value)
            continue
        archive = Path(value["externalArchive"]).resolve(strict=True)
        require(archive.suffix == ".jar", "Loose client code is forbidden")
        identities = mod_ids(archive)
        if identities & FAMILY:
            require(len(identities) == 1, "Ambiguous source candidate archive")
            skipped.append(str(archive))
        else:
            require(identities <= {"forge", "minecraft"}, "Unexpected mod on SDK classpath")
            archives.append(record(archive))
    require(len(ide) == len({tuple(sorted(row.items())) for row in ide}) == 2
            and len({row["path"] for row in archives}) == len(archives), "Duplicate/missing SDK classpath entries")
    require(sum(Path(row["path"]).name == "forge-26.1.2-64.1.5.jar" for row in archives) == 1,
            "Missing exact mapped Forge64 runtime")
    require(sum(Path(row["path"]).name == "client-extra-26.1.2-20260409.101008.jar" for row in archives) == 1,
            "Missing exact native vanilla client resources")
    require(not any("forge-1." in Path(row["path"]).name or "client-extra-1." in Path(row["path"]).name for row in archives),
            "Historical native archive in client classpath")
    metadata = record(program[5])
    with ZipFile(metadata["path"]) as jar:
        version = json.loads(jar.read("minecraft/version.json"))
    require(version["id"] == native.MINECRAFT and version["javaVersion"]["majorVersion"] == 25
            and version["assetIndex"]["id"] == ASSET_INDEX
            and version["assetIndex"]["sha1"].upper() == ASSET_INDEX_SHA1, "Wrong actual native SDK manifest")
    index = Path(program[16]) / "indexes" / (ASSET_INDEX + ".json")
    require(hashlib.sha1(index.read_bytes()).hexdigest().upper() == ASSET_INDEX_SHA1, "Wrong native client asset index")
    return dict(format=1, minecraft=native.MINECRAFT, forge=native.FORGE, mcp=native.MCP,
                javaSha256=native.JAVA_SHA, launch=launch, archives=archives, metadata=metadata,
                assetIndex=record(index), program=program, vm=vm, environment=environment,
                excludedMutableCandidatePaths=skipped)


def seal_client_code(launch, output):
    output = Path(output).resolve()
    require_disjoint(output, Path(launch).resolve(strict=True))
    document = inspect_launch(launch)
    with output.open("x", encoding="utf-8") as stream:
        json.dump(document, stream, indent=2, sort_keys=True)
        stream.write("\n")
    return document


def png_record(root, name, dimensions):
    path = Path(root) / name
    row = record(path)
    require(Path(row["path"]).is_relative_to(Path(root).resolve()), "Image escaped client runtime")
    require(path.stat().st_size <= 64 * 1024 * 1024, "Oversized native image")
    data = path.read_bytes()
    header = data[:24]
    require(header[:8] == b"\x89PNG\r\n\x1a\n" and header[12:16] == b"IHDR"
            and len(header) == 24 and struct.unpack(">II", header[16:24]) == dimensions,
            "Missing/malformed native image: " + name)
    offset, chunks, compressed, channels = 8, [], bytearray(), None
    while offset < len(data):
        require(offset + 12 <= len(data), "Truncated PNG chunk")
        length = struct.unpack(">I", data[offset:offset + 4])[0]
        end = offset + 12 + length
        require(end <= len(data), "Unbounded PNG chunk")
        kind, content = data[offset + 4:offset + 8], data[offset + 8:end - 4]
        require(zlib.crc32(kind + content) & 0xFFFFFFFF == struct.unpack(">I", data[end - 4:end])[0], "PNG CRC mismatch")
        chunks.append(kind)
        if kind == b"IHDR":
            require(len(content) == 13 and content[8] == 8 and content[9] in (2, 6)
                    and content[10:] == b"\0\0\0", "Unexpected native PNG encoding")
            channels = 4 if content[9] == 6 else 3
        elif kind == b"IDAT":
            compressed.extend(content)
        elif kind == b"IEND":
            require(length == 0 and end == len(data), "Trailing PNG data")
        offset = end
    require(chunks[0] == b"IHDR" and chunks.count(b"IHDR") == 1 and chunks[-1] == b"IEND"
            and chunks.count(b"IEND") == 1 and compressed, "Incomplete native PNG")
    stride = dimensions[0] * channels + 1
    decoder = zlib.decompressobj()
    raw = decoder.decompress(compressed, stride * dimensions[1] + 1)
    require(len(raw) == stride * dimensions[1] and decoder.eof and not decoder.unused_data
            and not decoder.unconsumed_tail and all(raw[row * stride] <= 4 for row in range(dimensions[1])),
            "Malformed or oversized native PNG pixel stream")
    return dict(file=name, sha256=row["sha256"], width=dimensions[0], height=dimensions[1])


def read_frame(root, frame):
    width, height = frame["width"], frame["height"]
    require(type(width) is int and type(height) is int and 1 <= width <= 4096 and 1 <= height <= 4096,
            "Invalid native framebuffer dimensions")
    rgba = artifact(root, frame["rgba"])
    require(rgba.stat().st_size == width * height * 4, "Truncated native framebuffer")
    png = artifact(root, frame["png"])
    png_record(root, str(png.relative_to(Path(root).resolve())), (width, height))
    state = native.read_json(artifact(root, frame["native"]))
    fills = state.get("fills", [])
    require(state["protocol"] == "NATIVE64_GUI_FRAMEBUFFER_FENCED_V1"
            and (state["width"], state["height"]) == (width, height)
            and state["renderer"] == "net.minecraft.client.gui.render.GuiRenderer"
            and state["extractor"] in ("net.minecraft.client.gui.GuiGraphicsExtractor",
                "zone.moddev.mc.advantageworks.client.MachineGuiLayoutProbe$RecordingScreen$1")
            and all(state[key] is True for key in ("renderSubmitted", "fenceCompleted", "screenshotCallbackCompleted"))
            and isinstance(state["texts"], list) and isinstance(state["blits"], list) and isinstance(fills, list)
            and 0 < len(state["blits"]) + len(fills) <= 10000,
            "No actual native extraction/submission/fenced screenshot evidence")
    for blit in state["blits"]:
        require(all(type(blit[key]) is int for key in ("x0", "y0", "x1", "y1", "color", "textureViewIdentity"))
                and blit["textureViewIdentity"] != 0 and isinstance(blit["pipeline"], str) and blit["pipeline"]
                and len(blit["pose"]) == 6 and all(type(value) in (int, float) and math.isfinite(value)
                    for value in [*blit["pose"], *(blit[key] for key in ("u0", "v0", "u1", "v1"))]),
                "Malformed/empty native blit extraction")
    for fill in fills:
        require(all(type(fill[key]) is int for key in ("x0", "y0", "x1", "y1", "col1", "col2"))
                and isinstance(fill["pipeline"], str) and fill["pipeline"] and len(fill["pose"]) == 6
                and all(type(value) in (int, float) and math.isfinite(value) for value in fill["pose"]),
                "Malformed native color extraction")
    return width, height, rgba.read_bytes(), state


def verify_projection_calibration(root, calibration):
    require(calibration["protocol"] == "NATIVE64_GUI_PROJECTION_PIXEL_RULER_V1" and calibration["status"] == "PASS",
            "Native projection calibration did not complete")
    cases = calibration["cases"]
    require(len(cases) == 2 and {row["scale"] for row in cases} == {1, 2}, "Missing projection scale control")
    checked = 0
    for row in cases:
        scale = row["scale"]
        require((row["logicalWidth"], row["logicalHeight"]) == (64, 48)
                and row["exactPixelsChecked"] == 64 * 48 * scale * scale, "Wrong projection control dimensions")
        for key, shift in (("actual", 0), ("shiftedRejected", 1)):
            frame = read_frame(root, row[key])
            require(frame[:2] == (64 * scale, 48 * scale) and len(frame[3].get("fills", [])) == 3,
                    "Calibration lacks actual native rectangle extraction")
            expected = bytearray()
            for y in range(48 * scale):
                for x in range(64 * scale):
                    u, v = x // scale, y // scale
                    color = 0xFF37A95C if 7 + shift <= u < 23 + shift and 9 <= v < 25 else (
                        0xFF3158D5 if (u, v) == (2, 2) else 0xFFDD7341 if (u, v) == (63, 47) else 0xFF101820)
                    expected.extend((color >> 16 & 255, color >> 8 & 255, color & 255, 255))
            require(frame[2] == expected, "Native projection ruler pixels are compressed/clipped/shifted")
        require(row["actual"]["rgba"]["sha256"] != row["shiftedRejected"]["rgba"]["sha256"],
                "Shifted projection negative control was accepted")
        checked += row["exactPixelsChecked"]
    return dict(status="PASS", physicalPixels=checked, shiftedGpuControls=2)


def source_references(document, mods):
    requested = set()

    def collect(value):
        if isinstance(value, dict):
            if set(value) == {"resource", "sha256", "width", "height"}:
                requested.add(value["resource"])
            for child in value.values():
                collect(child)
        elif isinstance(value, list):
            for child in value:
                collect(child)

    collect(document)
    require(requested, "Missing source artwork provenance")
    wanted = {"assets/" + key.replace(":", "/", 1): key for key in requested}
    found = {}
    for _, path, _ in mods.values():
        with ZipFile(path) as jar:
            for name in set(jar.namelist()) & wanted.keys():
                key = wanted[name]
                require(key not in found, "Ambiguous GUI source artwork provider: " + key)
                found[key] = hashlib.sha256(jar.read(name)).hexdigest().upper()
    require(set(found) == requested, "GUI source artwork is absent from the frozen family")
    return found


def verify_producer(document):
    require(document.get("status") == "PASS", "Native client producer failed: " + json.dumps(
        {key: document[key] for key in ("status", "error", "cause", "loginReadiness", "scope_results") if key in document},
        sort_keys=True))


def fixture_policy(runtime, profile, profile_sha, source_receipt, source_world):
    native.validate_hash(profile, profile_sha)
    document = native.read_json(profile)
    require(document["minecraftVersion"] == native.MINECRAFT and document["forge"]["version"] == native.FORGE_VERSION,
            "Experimental warning action requires the exact native target")
    mods = {row["id"]: (row["version"], Path(row["path"]), row["sha256"]) for row in document["mods"]}
    require(len(document["mods"]) == len(mods) == 6 and set(mods) == FAMILY, "Experimental warning policy needs the ordinary six-jar family")
    require(Path(source_world).resolve() == Path(source_receipt).resolve().parent / "packaged-current/AdvantageWorksMigration",
            "Experimental warning action is not approved for this source-world route")
    verify_world_source(source_world, native.read_json(source_receipt), profile_sha, mods)
    copy = Path(runtime) / "saves" / CLIENT_WORLD
    level_sha = digest(Path(source_world) / "level.dat")
    require(level_sha == digest(copy / "level.dat"),
            "Experimental warning action requires the exact approved stopped source/copy")
    return dict(protocol=FIXTURE_PROTOCOL, eligibility=FIXTURE_SCOPE, profileSha256=profile_sha.upper(), profilePath=str(Path(profile).resolve()),
                sourceReceiptSha256=digest(source_receipt), copiedWorld=str(copy.resolve()), copiedLevelSha256=level_sha,
                sourceLedgerSha256=digest(Path(runtime) / "client-world-source-files.json"))


def verify_fixture_action(root, action):
    require(action["protocol"] == FIXTURE_PROTOCOL, "Unknown native fixture action protocol")
    if action["status"] == "NOT_OBSERVED":
        require(type(action["actionCount"]) is int and action["actionCount"] == 0, "Unreported native fixture action")
        return dict(status="NOT_OBSERVED", actionCount=0)
    require(action["status"] == "NATIVE_SKIP_BUTTON_RETURNED" and action["onPressReturned"] is True
            and type(action["actionCount"]) is int and action["actionCount"] == 1
            and action["screenClass"] == "net.minecraft.client.gui.screens.BackupConfirmScreen"
            and action["titleKey"] == "selectWorld.backupQuestion.experimental"
            and action["buttonKey"] == "selectWorld.backupJoinSkipButton"
            and (action["key"], action["scancode"], action["modifiers"]) == (257, 0, 0)
            and action["fenceCompleted"] is True,
            "Unapproved/repeated native confirmation action")
    policy = native.read_json(Path(root) / "client-fixture-policy.json")
    input_doc = native.read_json(Path(root) / "client-input.json")
    require(policy["protocol"] == FIXTURE_PROTOCOL and policy["eligibility"] == FIXTURE_SCOPE
            and action["profileSha256"] == policy["profileSha256"] == input_doc["profileSha256"]
            and action["sourceReceiptSha256"] == policy["sourceReceiptSha256"] == digest(Path(input_doc["sourceWorldReceipt"]))
            and digest(Path(policy["profilePath"])) == policy["profileSha256"], "Native warning action has different sealed inputs")
    require(digest(Path(root) / "client-fixture-policy.json") == action["policySha256"]
            and Path(action["copiedWorld"]) == Path(root).resolve() / "saves" / CLIENT_WORLD,
            "Native fixture action lost its sealed copied-world binding")
    before = native.read_json(Path(root) / "login-readiness/experimental-before-action.json")
    require(before["status"] == "RECORDED_BEFORE_NATIVE_ACTION" and before["actionCount"] == 0
            and {key: value for key, value in before.items() if key not in ("status", "actionCount")}
                == {key: value for key, value in action.items() if key not in ("status", "actionCount", "onPressReturned")},
            "Native fixture action lacks its immutable pre-action record")
    readiness = action["readinessBeforeAction"]
    require(readiness["overlayClass"] == "none" and all(readiness[key] is False for key in (
        "levelPresent", "playerPresent", "integratedServer", "singleplayerServerPresent", "connectionPresent")),
        "Native fixture action ran outside the unconnected pre-server screen")
    image = png_record(root, action["screenshot"], (action["width"], action["height"]))
    require(image["sha256"] == action["screenshotSha256"] and action["fenceCompleted"] is True,
            "Native warning frame changed or was not fenced before action")
    require(action["copiedLevelCustody"]["initialSha256"] == policy["copiedLevelSha256"]
            and action["copiedLevelCustody"]["exactTypedNbtTransform"] in (
                "UNCHANGED_TYPED_ROOT", "ONLY_FML_LOWERCASE_EMPTY_BIOME_INDEX_REMOVED"), "Unapproved copied-level transformation")
    return dict(status="PASS", actionCount=1, screenshot=image)


def world_sample_pixel(matrix, camera, point, width, height):
    offset = [point[index] - camera[index] for index in range(3)] + [1.0]
    clip = [sum(matrix[column * 4 + row] * offset[column] for column in range(4)) for row in range(4)]
    require(clip[3] > 0 and all(math.isfinite(value) for value in clip), "World oil sample is behind the native camera")
    return [int((clip[0] / clip[3] * .5 + .5) * width), int((.5 - clip[1] / clip[3] * .5) * height)]


def verify_world_oil(root, report):
    require(report["protocol"] == "NATIVE64_LEVEL_RENDERER_OIL_RELOAD_V1" and report["status"] == "PASS"
            and report["scope"] == "ACTUAL_LEVEL_RENDERER_NOT_GUI_OR_SYNTHETIC_WORLD" and report["mipmapLevel"] == 4,
            "Actual-world oil/reload did not complete")
    frames = report["frames"]
    require(set(frames) == {"original", "empty", "wrong_water", "restored", "after_reload"}, "Missing world oil control/reload frame")
    pixels, dimensions = {}, None
    for name, frame in frames.items():
        width, height = frame["width"], frame["height"]
        require(type(width) is int and type(height) is int and 320 <= width <= 4096 and 240 <= height <= 2160,
                "Invalid world framebuffer dimensions")
        if dimensions is None:
            dimensions = (width, height)
        require(dimensions == (width, height) and frame["renderer"] == "net.minecraft.client.renderer.LevelRenderer"
                and frame["dimension"] == "minecraft:overworld" and frame["fenceCompleted"] is True
                and frame["hudHidden"] is True and type(frame["renderedSections"]) is int and frame["renderedSections"] > 0,
                "Missing actual native world renderer/compiled-section/fence evidence")
        raw = artifact(root, frame["rgba"])
        require(raw.stat().st_size == width * height * 4, "Truncated world framebuffer")
        pixels[name] = raw.read_bytes()
        png = artifact(root, frame["png"])
        png_record(root, str(png.relative_to(Path(root).resolve())), (width, height))
        require(len(frame["camera"]) == 3 and all(type(value) in (int, float) and math.isfinite(value) for value in frame["camera"])
                and abs(frame["camera"][0] - 512.5) < .001 and abs(frame["camera"][2] - 527) < .001
                and 14 <= frame["camera"][1] <= 16 and len(frame["viewRotationProjection"]) == 16
                and all(type(value) in (int, float) and math.isfinite(value) for value in frame["viewRotationProjection"]), "Uncontrolled native oil camera")
        require(len(frame["pools"]) == 3, "Missing world control pool")
        for index, pool in enumerate(frame["pools"]):
            expected = "minecraft:water" if index == 2 or name == "wrong_water" else "minecraft:empty" if name == "empty" else (
                "basepower:crude_oil" if index == 0 else "basepower:refined_oil")
            require(pool["pool"] == ("crude-oil", "refined-oil", "water-control")[index] and pool["fluid"] == expected
                    and pool["compiledAndVisible"] is True and len(pool["samples"]) == 16, "Incorrect actual-world fluid/control state")
            for sample, (x, z) in zip(pool["samples"], ((x, z) for x in range(4) for z in range(4))):
                require(len(sample) == 5 and sample[:3] == [(504, 510, 516)[index] + .75 + x * .75, 6 + 8 / 9, 510 + .75 + z * .75]
                        and all(type(value) is int for value in sample[3:]) and 2 <= sample[3] < width - 2 and 2 <= sample[4] < height - 2,
                        "World oil samples escaped the exact native pool geometry/frame")
                require(sample[3:] == world_sample_pixel(frame["viewRotationProjection"], frame["camera"], sample[:3], width, height),
                        "World oil pixel does not project from the retained native camera/pool point")
    checks = []
    for actual in ("original", "after_reload"):
        require(frames[actual]["camera"] == frames["empty"]["camera"] == frames["wrong_water"]["camera"]
                and frames[actual]["viewRotationProjection"] == frames["empty"]["viewRotationProjection"]
                    == frames["wrong_water"]["viewRotationProjection"], "World camera/projection moved between negative controls")
        for index, pool in enumerate(frames[actual]["pools"]):
            row = dict(actual=actual, pool=pool["pool"], samples=144)
            for control in ("empty", "wrong_water"):
                changed = 0
                for sample in pool["samples"]:
                    for dy in range(-1, 2):
                        for dx in range(-1, 2):
                            at = ((sample[4] + dy) * dimensions[0] + sample[3] + dx) * 4
                            distance = sum(abs(a - b) for a, b in zip(pixels[actual][at:at + 3], pixels[control][at:at + 3]))
                            changed += distance > (32 if index == 2 else 64)
                require(changed <= 14 if index == 2 else changed >= 72, "Native world oil blank/wrong-fluid control was accepted")
                row[control + "Different"] = changed
            checks.append(row)
    require(checks == report["pixelControls"], "World pixel-control counts differ from retained raw RGBA")
    for key in ("modelsBeforeReload", "modelsAfterReload"):
        require(len(report[key]) == 2, "Missing actual-world oil model")
        for index, model in enumerate(report[key]):
            name = ("crude_oil", "refined_oil")[index]
            require(model["fluid"] == "basepower:" + name and model["layer"] == "TRANSLUCENT"
                    and model["stillSprite"] == f"basepower:blocks/{name}_still"
                    and model["flowingSprite"] == f"basepower:blocks/{name}_flow", "Actual-world oil used the wrong native model/sprites")
            if key == "modelsAfterReload":
                require(all(model[field] != report["modelsBeforeReload"][index][field] for field in ("stillIdentity", "flowingIdentity")),
                        "Actual-world oil retained its pre-reload atlas sprites")
    geometry = report["nativeGeometryAfterReload"]
    require(len(geometry["fluid_rendering"]) == 6 and all(row["correct_sprite"] is True and row["vertices"] >= 4
            for row in geometry["fluid_rendering"]), "Native post-reload fluid geometry is incomplete or stale")
    return dict(status="PASS", frames=5, oilNegativeComparisons=8, waterControlComparisons=4)


def require_source(source, references):
    require(source["resource"] in references and source["sha256"] == references[source["resource"]],
            "Source artwork does not match the frozen jar bytes")


def slot_mismatches(frame, proof, left, top, scale):
    width, height, data, _ = frame
    points, slots = proof["border_points"], proof["slots"]
    require(len(points) == 68 and {(x, y) for x, y, _ in points}
            == {(x, y) for x in range(18) for y in range(18) if x in (0, 17) or y in (0, 17)},
            "Incomplete independent slot-border pixel template")
    require(len(slots) >= 36 and len({tuple(pos) for pos in slots}) == len(slots), "Missing/duplicate slot extraction")
    require(proof["logical_texture_size"] == 256 and slots[:-36] == proof["expected_machine_slots"],
            "Machine slot/background geometry differs from GUI declaration")
    colors = proof["slot_border_colors"]
    require(proof["source_sampling"] == "PHYSICAL_PIXEL_CENTERS_ON_256_LOGICAL_UV_V1"
            and len(colors) == len(slots) and all(len(row) == 68 * scale * scale
                and all(type(value) is int for value in row) for row in colors),
            "Missing per-slot source artwork pixels")
    require(all(len(set(row)) >= 2 for row in colors), "Blank source slot-border crop")
    require(all(row == [value for _, _, value in points for _ in range(scale * scale)] for row in colors[-36:]), "Player inventory template changed")
    player = [[7 + i % 9 * 18, 157 + i // 9 * 18] for i in range(27)] + [[7 + i * 18, 215] for i in range(9)]
    require(slots[-36:] == player, "Player slot/background geometry changed")
    require(proof["checked_pixels"] == len(slots) * 68 * scale * scale, "Incomplete slot pixel coverage")
    different = 0
    for index, (slot_x, slot_y) in enumerate(slots):
        require(type(slot_x) is int and type(slot_y) is int, "Invalid extracted slot coordinate")
        for point_index, (x, y, _) in enumerate(points):
            for dy in range(scale):
                for dx in range(scale):
                    argb = colors[index][point_index * scale * scale + dy * scale + dx]
                    expected = bytes((argb >> 16 & 255, argb >> 8 & 255, argb & 255, argb >> 24 & 255))
                    px, py = (left + slot_x + x) * scale + dx, (top + slot_y + y) * scale + dy
                    require(0 <= px < width and 0 <= py < height, "Slot framebuffer bounds escaped")
                    at = (py * width + px) * 4
                    different += data[at:at + 4] != expected
    return different


def verify_machine_frame(root, proof, title, inventory, references):
    width, height, scale = proof["viewport"]
    require((width, height, scale) in VIEWPORTS and type(proof["fill"]) is int and proof["fill"] in range(3),
            "Unknown native GUI render case")
    left, top = (width - 176) // 2, (height - 240) // 2
    require((proof["left"], proof["top"]) == (left, top), "Native background is not centered/aligned")
    actual, expected, background = (read_frame(root, proof[key]) for key in ("actual", "expected", "background"))
    require(all(frame[:2] == (width * scale, height * scale) for frame in (actual, expected, background)),
            "Viewport/framebuffer size mismatch")
    for text in (title, inventory):
        signatures = []
        for frame in (actual, expected):
            matches = [row for row in frame[3]["texts"] if row["text"] == text]
            require(len(matches) == 1 and matches[0]["color"] == -1 and matches[0]["dropShadow"] is True,
                    "Missing/garbled native caption extraction")
            row = matches[0]
            signatures.append((row["x"], row["y"], row["pose"]))
        require(signatures[0] == signatures[1], "Native caption placement differs from independent labels")
    ink_total = 0
    for x0, y0, x1, y1 in ((4, top + 1, width - 4, top + 12), (left + 8, top + 140, left + 168, top + 150)):
        ink = 0
        for y in range(y0 * scale, y1 * scale):
            for x in range(x0 * scale, x1 * scale):
                at = (y * actual[0] + x) * 4
                if expected[2][at:at + 4] != background[2][at:at + 4]:
                    ink += 1
                    require(actual[2][at:at + 4] == expected[2][at:at + 4], "Caption framebuffer pixels are missing/garbled/clipped")
        require(ink >= 20 * scale * scale, "Blank expected caption or a missing-text control passed")
        ink_total += ink
    slots = proof["slot_pixels"]
    require_source(slots["inventory_source"], references)
    require_source(slots["machine_source"], references)
    require(slot_mismatches(actual, slots, left, top, scale) == 0, "Slot framebuffer pixels differ from source artwork")
    return dict(captionInkPixels=ink_total, slotPixels=slots["checked_pixels"])


def verify_tank_frames(root, row, references):
    scale = row["render_scale"]
    require(type(scale) is int and scale in (1, 2), "Unexpected tank pixel scale")
    actual, expected, empty = (read_frame(root, row[key]) for key in ("actual_frame", "expected_frame", "empty_frame"))
    require(all(frame[:2] == (176 * scale, 240 * scale) for frame in (actual, expected, empty)), "Wrong tank framebuffer dimensions")
    require_source(row["source_artwork"], references)
    require(actual[2] == expected[2] and row["whole_panel_different_pixels"] == 0,
            "Whole tank background/frame/slot pixels differ")
    if row["fill_rows"]:
        start = 81 - row["fill_rows"]

        def quads(frame):
            selected = [blit for blit in frame[3]["blits"] if blit.get("scissor") == [80, start, 16, row["fill_rows"]]]
            require(len(selected) == (row["fill_rows"] + 15) // 16
                    and {(blit["x0"], blit["y0"], blit["x1"], blit["y1"]) for blit in selected}
                        == {(80, y, 96, y + 16) for y in range(start, 81, 16)},
                    "Native fluid extraction stretched/cropped a tile instead of scissor clipping")
            signature = [(blit["u0"], blit["v0"], blit["u1"], blit["v1"], blit["color"], blit["textureViewIdentity"])
                         for blit in sorted(selected, key=lambda value: value["y0"])]
            require(all(value == signature[0] for value in signature), "Native fluid tiles changed sprite UV or tint")
            return signature

        require(quads(actual) == quads(expected), "Native fluid sprite UV/tint differs from independent reference")
        visible = sum(expected[2][at:at + 4] != empty[2][at:at + 4]
                      for y in range(21 * scale, 81 * scale) for x in range(80 * scale, 96 * scale)
                      for at in [(y * 176 * scale + x) * 4])
        require(visible >= row["fill_rows"] * 8 * scale * scale, "Blank reference fluid sprite")
        garbled = read_frame(root, row["garbled_frame"])
        require(garbled[:2] == actual[:2] and garbled[2] != expected[2] and empty[2] != expected[2]
                and row["blank_control_different_pixels"] > 0 and row["garbled_control_different_pixels"] > 0,
                "Blank/garbled tank control was accepted")


def freeze_native_frames(root):
    folder = Path(root) / "native-gui-evidence"
    if not folder.exists():
        return dict(status="NOT_CAPTURED", frames=[])
    frames, claimed = [], set()
    for path in sorted(folder.glob("frame-*-native.json")):
        state = native.read_json(path)
        stem = path.name.removesuffix("-native.json")
        frame = dict(width=state["width"], height=state["height"])
        for key, suffix in (("png", ".png"), ("rgba", ".rgba"), ("native", "-native.json")):
            file = folder / (stem + suffix)
            frame[key] = dict(file=str(file.relative_to(root)), sha256=digest(file))
            claimed.add(file.resolve())
        read_frame(root, frame)
        frames.append(frame)
    require(claimed == {path.resolve() for path in folder.iterdir()} and 0 < len(frames) <= 2000,
            "Native framebuffer freeze has partial/unknown artifacts")
    return dict(status="FROZEN_CAPTURE_EVIDENCE_NOT_MANUAL_ACCEPTANCE", frames=frames)


def verify_vanilla_resources(root, code):
    folder = Path(root) / "vanilla-resource-controls"
    if not folder.exists():
        return dict(status="NOT_CAPTURED", controls=[])
    paths = sorted(folder.iterdir())
    require(1 <= len(paths) <= 4 and [path.name for path in paths]
            == [f"control-{number:02d}.json" for number in range(1, len(paths) + 1)],
            "Incomplete/unexpected native vanilla resource controls")
    wanted = {"minecraft:textures/block/stone.png", "minecraft:atlases/blocks.json"}
    sources = {key: [] for key in wanted}
    vanilla = [archive for archive in code["archives"]
               if Path(archive["path"]).name == "client-extra-26.1.2-20260409.101008.jar"]
    require(len(vanilla) == 1, "Need the exact native vanilla client-resource archive")
    for archive in vanilla:
        with ZipFile(archive["path"]) as jar:
            for key in wanted:
                name = "assets/" + key.replace(":", "/", 1)
                if name in jar.namelist():
                    data = jar.read(name)
                    require(data, "Empty primary vanilla resource")
                    sources[key].append(dict(archive=archive, sha256=hashlib.sha256(data).hexdigest().upper(), bytes=len(data)))
    require(all(rows and len({(row["sha256"], row["bytes"]) for row in rows}) == 1 for rows in sources.values()),
            "Missing/ambiguous pinned SDK vanilla resource source")
    controls = []
    for path in paths:
        proof = record(path)
        document = native.read_json(path)
        require(document["protocol"] == "NATIVE64_VANILLA_RESOURCE_ROOTS_V1" and document["status"] == "PASS"
                and document["phase"] == "reload-preparation-before-atlas-upload", "Vanilla native root lookup failed")
        rows = document["resources"]
        require(len(rows) == 2 and {row["resource"] for row in rows} == wanted, "Missing/duplicate vanilla lookup control")
        for row in rows:
            expected = sources[row["resource"]][0]
            require(row["vanilla"]["present"] is True
                    and all(row["vanilla"][key] == expected[key] for key in ("sha256", "bytes")),
                    "Vanilla pack differs from pinned SDK resource bytes")
            merged = row["merged"]
            require(isinstance(merged, list) and merged and all(isinstance(value["pack"], str) and value["pack"] for value in merged)
                    and sum(all(value[key] == expected[key] for key in ("sha256", "bytes")) for value in merged) == 1,
                    "Merged resource manager lost/duplicated the actual vanilla resource")
            if row["resource"].endswith("stone.png"):
                require(len(merged) == 1, "Unexpected override of the vanilla stone control")
        controls.append(proof)
    return dict(status="PASS", scope="VANILLA_PACK_ROOT_LOOKUP_NOT_ATLAS_UPLOAD_OR_GRAPHICS", controls=controls, primarySources=sources)


def validate_result(document, root, labels, references):
    require((document["status"], document["minecraft"], document["forge"])
            == ("PASS", native.MINECRAFT, native.FORGE), "Not a completed native64 client result")
    login = document["login"]
    require(login["status"] == "PASS" and all(login[key] is True for key in (
                "realWorld", "integratedServer", "nativeComponentsBound", "loggingInEventObserved", "connectionConnected", "clientLoaded"))
            and login["syntheticComponentBinding"] is False and type(login["itemHolders"]) is int and login["itemHolders"] > 0,
            "Client item/GUI checks did not run after actual native login")
    calibration = verify_projection_calibration(root, document["projectionCalibration"])
    fixture_action = verify_fixture_action(root, document["experimentalFixtureGate"])
    world_oil = verify_world_oil(root, document["world_oil_visual"])
    require(set(document["advantage_mods"]) == {"basepower", "basesteam", "baseelectric"}
            and len(document["advantage_mods"]) == 3, "Incomplete/duplicate client family")
    require(document["mod_versions"] == {key: native.VERSIONS[key] for key in document["advantage_mods"]},
            "Wrong client candidate versions")
    require(all(type(document[key]) is int and document[key] > 0 for key in ("items", "block_states", "creative_entries"))
            and document["resource_errors"] == [] and document["primary_resource_status"] == "PASS", "Empty/failed resources or creative tab")
    scopes = document["scope_results"]
    require(set(scopes) == {"registered_resources", "tank_pixels", "machine_gui", "oil_renderer", "recipe_book", "child_geometry"}
            and all(row["status"] == "PASS" for row in scopes.values()), "Missing or failed client scope")
    fluids = document["fluid_rendering"]
    require(document["oil_bucket_identities"] is True and len(fluids) == 6
            and {row["fluid"] for row in fluids} == {"basepower:crude_oil", "basepower:flowing_crude_oil",
                "basepower:refined_oil", "basepower:flowing_refined_oil", "minecraft:water", "minecraft:lava"}
            and all(row["correct_sprite"] is True and type(row["vertices"]) is int and row["vertices"] >= 4 for row in fluids),
            "Incomplete worldless native fluid-model extraction")
    require(document["machine_gui_layout_status"] == document["machine_gui_text_status"] == "PASS"
            and document["machine_gui_framebuffer_spec"] == 3
            and document["machine_gui_text_spec"] == 2 and document["machine_gui_layout_count"] == 39
            and document["machine_gui_text_checks"] == 351 and document["machine_gui_negative_controls_rejected"] == 356,
            "Missing all39 GUI/351-caption/background negative controls")
    rows = document["machine_gui_layouts"]
    require(len(labels["machines"]) == len(rows) == 39 and {row["block"] for row in rows} == set(labels["machines"]),
            "Machine GUI identity inventory changed")
    images = []
    framebuffer_checks = []
    for row in rows:
        require(row["status"] == row["required_text"] == row["tooltip_and_inventory_hitbox"] == "PASS"
                and row["renders"] == row["blank_label_controls_rejected"] == 9
                and type(row["background_extraction_calls"]) is int and row["background_extraction_calls"] == 1
                and row["menu_title"] == row["expected_title"] == labels["machines"][row["block"]],
                "Missing caption, tooltip, native background-once, or layout case")
        proofs = row["framebuffer_cases"]
        require(len(proofs) == 9 and {(tuple(proof["viewport"]), proof["fill"]) for proof in proofs}
                == {(viewport, fill) for viewport in VIEWPORTS for fill in range(3)},
                "Missing/duplicate GUI framebuffer cases")
        for proof in proofs:
            framebuffer_checks.append(verify_machine_frame(root, proof, row["expected_title"], labels["inventory"], references))
        control = read_frame(root, row["garbled_background_control"])
        initial = next(proof for proof in proofs if proof["viewport"] == list(VIEWPORTS[0]) and proof["fill"] == 0)
        require(control[:2] == VIEWPORTS[0][:2] and slot_mismatches(control, initial["slot_pixels"],
                    initial["left"], initial["top"], 1) > 0, "Garbled background passed actual slot framebuffer replay")
        stem = row["block"].replace(":", "-")
        for width, height, scale in VIEWPORTS:
            for fill in range(3):
                images.append(png_record(root, f"machine-gui-layouts/{stem}-{width}x{height}-scale{scale}-fill{fill}.png",
                                         (width * scale, height * scale)))
        images.append(png_record(root, f"machine-gui-layouts/{stem}-tooltip.png", (320, 240)))
    gauges = document["tank_gauge_pixels"]
    require(document["tank_gauge_pixel_status"] == "PASS" and len(gauges) == 26, "Incomplete real gauge pixels")
    for tank in ("portable", "metal"):
        selected = [row for row in gauges if row["tank"] == tank]
        require(len(selected) == 13 and {(row["fluid"], row["fill_rows"]) for row in selected}
                == {("empty", 0)} | {(fluid, rows) for fluid in ("water", "lava", "crude-oil", "refined-oil") for rows in (15, 30, 60)},
                "Gauge omitted a fluid or partial tile height")
    for row in gauges:
        require(type(row["different_pixels"]) is int and row["different_pixels"] == 0, "Gauge pixel mismatch")
        require(row["render_scale"] == 2, "Final gauge evidence must exercise native GUI scale2")
        verify_tank_frames(root, row, references)
        images.append(png_record(root, f"tank-gauge-{row['tank']}-{row['fluid']}-{row['amount_mb']}.png", (352, 480)))
    require(document["recipe_book_grouping_status"] == "PASS", "Native recipe packets were not rendered")
    recipes = document["recipe_book_grouping"]
    require(len(recipes) == 3 and {row["packet"] for row in recipes}
            == {f"recipes-{mode}-sweep.bin" for mode in ("normal", "tech", "apocalyptic")}
            and all(row["status"] == "PASS" and all(type(row[key]) is int and row[key] > 0
                    for key in ("recipes", "displays", "cells", "item_tags")) for row in recipes), "Incomplete native recipe/tag decoding")
    geometry = document["child_renderer_geometry"]
    geometry_context = document["child_geometry_context"]
    require(geometry_context["stage"] == "GENUINELY_WORLDLESS_BEFORE_LOGIN" and all(geometry_context[key] is False
            for key in ("levelPresent", "playerPresent", "singleplayerServerPresent")), "Child geometry lost genuine worldless isolation")
    require(document["child_renderer_geometry_status"] == geometry["status"] == "PASS_WITH_MANUAL_GAPS"
            and geometry["passed_geometry_cases"] == 25 and type(geometry["manual_gaps"]) is int and geometry["manual_gaps"] == 1
            and len(geometry["cases"]) == 26, "Missing/waived native renderer geometry scope")
    require(sum(row["status"] == "PASS" for row in geometry["cases"]) == 25
            and len({row["case"] for row in geometry["cases"]}) == 26
            and [row["case"] for row in geometry["cases"] if row["status"] == "MANUAL_GAP_WORLD_REQUIRED"]
                == ["hydroturbine-registration"],
            "Manual geometry gap was promoted to PASS")
    require(document["recipe_registry_ids_match_actual_login"] is True, "Recipe packets lack actual login registry identity")
    return dict(status="PASS", scope="CONNECTED_CLIENT_GUI_RESOURCE_PROBE_NOT_MANUAL_ACCEPTANCE", images=images,
                projectionCalibration=calibration,
                experimentalFixtureAction=fixture_action,
                actualWorldOilAndReload=world_oil,
                framebufferChecks=framebuffer_checks, garbledBackgroundControls=39, blankAndGarbledTankControls=48,
                manualRequired=["hydroturbine-world-orientation-spin"])


def _run(args, owned):
    inputs = preflight(args, [Path(__file__), Path(client_diagnostics.__file__), args.launch, args.client_code, args.probe, args.recipe_exports,
                              args.client_world_receipt,
                              native.RIG / "client-resources/resources/gui-label-expectations.json"])
    native.validate_hash(args.client_code, args.client_code_sha256)
    code = native.read_json(args.client_code)
    diagnostic_bindings = client_diagnostics.verify_bindings(code)
    for row in diagnostic_bindings:
        inputs.hashes[row["path"]] = row["sha256"]
    require(code == inspect_launch(args.launch), "Client SDK differs from the independently sealed inventory")
    native.validate_hash(args.probe, args.probe_sha256)
    with ZipFile(args.probe) as jar:
        metadata = tomllib.loads(jar.read("META-INF/mods.toml").decode("utf-8"))
    require(metadata["loaderVersion"] == "[64,65)" and metadata["mods"][0]["modId"] == "advantageclientresources",
            "Wrong native client probe")
    deps = {row["modId"]: row["versionRange"] for row in metadata["dependencies"]["advantageclientresources"]}
    require(deps == {"basepower": "[2.4.16.2601021]", "forge": "[64.1.5]", "minecraft": "[26.1.2]"}, "Stale client probe dependencies")
    for row in [*code["archives"], code["metadata"], code["assetIndex"]]:
        inputs.hashes[row["path"]] = row["sha256"]
    source_world = args.client_world.resolve(strict=True)
    require_disjoint(args.output.resolve(), source_world)
    world_before = verify_world_source(source_world, native.read_json(args.client_world_receipt),
                                       args.profile_sha256, inputs.mods)
    if args.preflight_only:
        return dict(status="READY_NOT_RUN", qualificationAllowed=False, **inputs.receipt())
    runtime = output_path(args.output)
    runtime.mkdir(parents=True, exist_ok=False)
    owned["runtime"] = runtime
    shutil.copy2(Path(__file__), runtime / "native2612-client-source.py")
    (runtime / "mods").mkdir()
    expected = {}
    for key, (_, path, sha) in inputs.mods.items():
        shutil.copy2(path, runtime / "mods" / path.name)
        expected[path.name] = sha
    shutil.copy2(args.probe, runtime / "mods/advantageclientresources.jar")
    expected["advantageclientresources.jar"] = args.probe_sha256.upper()
    exports = native.read_json(args.recipe_exports)
    require(exports["status"] == "PASS" and exports["profileSha256"] == args.profile_sha256.upper()
            and exports["target_jars"] == inputs.receipt()["target_jars"], "Recipe exports lack this frozen family binding")
    fixture_root = Path(exports["recipe_fixture"]["directory"])
    from native2612_resources import verify_packet_exports
    packet_records = verify_packet_exports(fixture_root)
    destination = runtime / "recipe-book-fixtures"
    destination.mkdir()
    for row in packet_records:
        for path in (Path(row["packet"]), Path(row["packet"]).with_suffix(".json"), Path(row["tags"])):
            require_disjoint(runtime, path.resolve(strict=True))
            inputs.hashes[str(path.resolve())] = digest(path)
            shutil.copy2(path, destination / path.name)
    level = fixture_root / "AdvantageWorksMigration/level.dat"
    require_disjoint(runtime, level.resolve(strict=True))
    inputs.hashes[str(level.resolve())] = digest(level)
    shutil.copy2(level, destination / "server-registries.dat")
    shutil.copytree(source_world, runtime / "saves" / CLIENT_WORLD)
    write_json(runtime / "client-world-source-files.json", world_before)
    write_json(runtime / "client-fixture-policy.json", fixture_policy(runtime, args.profile, args.profile_sha256,
        args.client_world_receipt, source_world))
    (runtime / "options.txt").write_text("onboardAccessibility:false\n", encoding="utf-8")
    program = list(code["program"])
    program[3] = str(runtime / "bootstrap-cache")
    program += ["--quickPlaySingleplayer", CLIENT_WORLD, "--offlineDeveloperMode", "--username", validate_username(CLIENT_USERNAME)]
    logging_jar = [Path(row["path"]) for row in code["archives"] if Path(row["path"]).name == "fmlloader-26.1.2-64.1.5.jar"]
    require(len(logging_jar) == 1, "Missing exact native logging configuration source")
    logging = native.logging_configuration(runtime, logging_jar[0])
    environment = native.native_launch_environment()
    environment.update(code["environment"])
    command = [str(inputs.java), *code["vm"], "-Xmx2G", "-Dadvantageworks.clientResources=true",
               "-Dadvantageworks.requireExtensions=true", "-Dadvantageworks.machineGuiLayouts=true",
               "-Dadvantageworks.childRendererGeometry=true", "-Dlog4j.configurationFile=" + str(logging),
               "-cp", os.pathsep.join(row["path"] for row in code["archives"]), "net.minecraftforge.launcher.Main", *program]
    report = dict(status="RUNNING", **inputs.receipt(), profileSha256=args.profile_sha256.upper(),
                  clientCodeSha256=args.client_code_sha256.upper(), command=command, runtimeJars=expected,
                  sourceWorld=str(source_world), sourceWorldReceipt=str(args.client_world_receipt),
                  worldSourceLedgerSha256=digest(runtime / "client-world-source-files.json"))
    report["experimentalFixturePolicy"] = dict(file="client-fixture-policy.json", sha256=digest(runtime / "client-fixture-policy.json"))
    write_json(runtime / "client-input.json", report)
    process = None
    try:
        require(type(args.timeout) is int and 30 <= args.timeout <= 1800, "Bound client process timeout")
        with (runtime / "launcher.stdout.log").open("wb") as stdout, (runtime / "launcher.stderr.log").open("wb") as stderr:
            process = subprocess.Popen(command, cwd=runtime, env=environment, stdout=stdout, stderr=stderr,
                                       creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
                                       start_new_session=os.name != "nt")
            report["pid"] = process.pid
            require(process.wait(timeout=args.timeout) == 0, "Native client exited nonzero")
        labels = native.read_json(native.RIG / "client-resources/resources/gui-label-expectations.json")
        result = native.read_json(runtime / "client-resources.json")
        report["nativeProducer"] = result
        verify_producer(result)
        report["verification"] = validate_result(result, runtime, labels, source_references(result, inputs.mods))
        report["clientLogAudit"] = []
        for path in (runtime / "logs/debug.log", runtime / "logs/latest.log", runtime / "launcher.stdout.log", runtime / "launcher.stderr.log"):
            errors, diagnostics = client_diagnostics.classify(path.read_text(encoding="utf-8", errors="strict"), native.previous_log_findings)
            report["clientLogAudit"].append(dict(path=str(path), sha256=digest(path), errors=errors, diagnostics=diagnostics))
            require(not errors, "Native client log has unclassified failures: " + str(errors))
        report["clientLogPrimaryBindings"] = diagnostic_bindings
        report["status"] = "PASS"
    except BaseException as failure:
        report.update(status="FAIL", error=repr(failure))
        raise
    finally:
        try:
            if process is not None and process.poll() is None:
                if os.name == "nt":
                    subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"], check=True, capture_output=True)
                else:
                    import signal
                    os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=30)
            require({path.name: digest(path) for path in (runtime / "mods").iterdir()} == expected, "Client runtime jars changed")
            report["vanillaResourceControls"] = verify_vanilla_resources(runtime, code)
            report["nativeFramebufferFreeze"] = freeze_native_frames(runtime)
            if report["status"] == "PASS":
                require(report["vanillaResourceControls"]["status"] == "PASS", "Missing native vanilla resource-root control")
                require(len(report["nativeFramebufferFreeze"]["frames"]) >= 1207,
                        "Incomplete native GUI framebuffer inventory")
            report["sourceUnchanged"] = inputs.unchanged()
            require(fingerprint(source_world) == world_before, "Protected stopped client-source world changed")
            report["sourceWorldUnchanged"] = True
            if report["status"] == "PASS":
                stopped = runtime / "saves" / CLIENT_WORLD
                layout.read_level(stopped)
                report["stoppedClientLevelSha256"] = digest(stopped / "level.dat")
        except BaseException as failure:
            report.update(status="FAIL", custodyError=repr(failure))
            raise
        finally:
            write_json(runtime / RESULT, report)
    return report


def run(args):
    owned = {}
    try:
        return _run(args, owned)
    except BaseException as failure:
        if "runtime" in owned:
            runtime = owned["runtime"]
            failed = dict(status="FAIL", minecraft=native.MINECRAFT, forge=native.FORGE,
                          scope="CLIENT_PREPARATION_OR_LAUNCH", error=repr(failure))
            write_json(runtime / "runner-failure.json", failed)
            if not (runtime / RESULT).exists():
                write_json(runtime / RESULT, failed)
        raise


def main():
    parser = arguments(__doc__)
    for key in ("launch", "client-code", "probe", "recipe-exports", "client-world", "client-world-receipt"):
        parser.add_argument("--" + key, required=True, type=Path)
    for key in ("client-code-sha256", "probe-sha256"):
        parser.add_argument("--" + key, required=True)
    parser.add_argument("--timeout", type=int, default=600)
    print(run(parser.parse_args())["status"], flush=True)


if __name__ == "__main__":
    main()
