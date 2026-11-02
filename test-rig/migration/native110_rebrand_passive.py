"""Independent authentic Cyano 355-witness passive upgrade, or separate synthetic serializers.

Neither mode edits original files. The passive observer has no NBT load/repair path.
Synthetic detached-player/item cases are never original-player migration evidence.
"""
import argparse
import base64
from datetime import datetime, timezone
import gzip
import io
from pathlib import Path
import shutil
import socket
import traceback

import nbtlib

import legacy_connected_oracle as legacy
from native110 import output_path, require_disjoint
from native110_code import INVENTORY, Server, verify_code
from native110_lifecycle import read_json, require_native_boot, verify_staged
from native110_rebrand_connected import ORIGINAL_JARS, NAMESPACES, binary_nbt, bind_target, identity_nbt, target_id
from rig_transport import WORLD, copy_fixture, digest, fingerprint, stage, write_json

AUTHENTIC_SCOPE = "AUTHENTIC_ORIGINAL_RELEASE_110_PASSIVE_355"
SYNTHETIC_SCOPE = "SYNTHETIC_NATIVE_SERIALIZERS_NOT_ORIGINAL_PLAYER_OR_ENTITY_PROOF"
PROFILE_SHA = "6A6A7C57D674CCD30656098B1D1F3EC3C0AD95B49927511B039BF0F93CF3FE09"
SOURCE_FILES = {
    "fixture-hashes.json": "2C9E521E6E813D8822A8B882B0D81D210B8646D11BC978D845621AC124273E41",
    "migration-fixture.json": "FF1B1E6D0977E12577B1FCABDB5A2410EEBE016DCEB52E1B5004DE99BAB3AD01",
    "migration-source.json": "C490A49CDFBCA43E01F1179CA035773BB36628727831E84149205EF5260664BA",
    "input-jars.json": "62E42341F646C0C52C512B74E1F99DAFA7FA0A8AE9BE63DD4D76B989B70202B8",
}
SOURCE_JARS = {**ORIGINAL_JARS, "AdvantageMigrationProbe-110-1.0.0.jar":
               "79157E31FFAF5D66CCDC8030EB7B8C37D0E0D33FD6A601D119F59904A461AAD4"}
SYNTHETIC_CASES = {"synthetic/player-inventory-ender-armor-offhand", "synthetic/dropped-enchanted-item",
                   "synthetic/nested-container-stack"}
RETAINED_ENCHANTMENTS = {"steamadvantage:" + name for name in
                         ("rapid_reload", "powderless", "high_explosive", "recoil")}


def saved_enchantments(world):
    level = nbtlib.load(world / "level.dat")
    entries = level["FML"]["Registries"]["minecraft:enchantments"]["ids"]
    return registry([(int(row["V"]), str(row["K"])) for row in entries])


def registry(rows):
    result = {}
    names = set()
    for numeric_id, name in rows:
        if type(numeric_id) is not int or numeric_id < 0 or numeric_id in result or name in names:
            raise AssertionError("Missing/duplicate/malformed enchantment registry identity")
        result[numeric_id] = name
        names.add(name)
    if not result: raise AssertionError("No genuine native enchantment registry")
    return result


def canonical_nbt(row, enchantments, expected):
    tag = identity_nbt(binary_nbt(row), NAMESPACES if expected else {})
    if expected and row["actualName"] == "steamadvantage:steam_drill" and "Facing" not in tag:
        meta = row["metadata"]
        facings = ("down", "up", "north", "south", "west", "east")
        if type(meta) is not int or not 0 <= meta < len(facings) or row["properties"].get("facing") != facings[meta]:
            raise AssertionError("Original Steam drill metadata and facing disagree")
        tag["Facing"] = nbtlib.Byte(meta)
    result = legacy.saved_tile_nbt(tag)
    for field in ("ForgeData", "ForgeCaps"):
        if result.get(field) == {}: result.pop(field)

    def visit(value):
        if isinstance(value, dict):
            for key, child in value.items():
                if key in ("ench", "StoredEnchantments", "Enchantments"):
                    if not isinstance(child, list) or len(child) != 3 or child[:2] != ["List", 10]:
                        raise AssertionError("Malformed typed enchantment list")
                    for entry in child[2]:
                        kind, numeric_id = entry["id"]
                        if kind not in ("Short", "Int") or type(numeric_id) is not int or numeric_id not in enchantments:
                            raise AssertionError("Unresolved native enchantment ID")
                        name = enchantments[numeric_id]
                        entry["id"] = [kind, target_id(name) if expected and name not in RETAINED_ENCHANTMENTS else name]
                visit(child)
        elif isinstance(value, list):
            for child in value: visit(child)
    visit(result)
    return result


def compare_rows(before, after, before_enchantments, after_enchantments):
    def indexed(rows, enchantments, expected):
        if len(rows) != 355: raise AssertionError("All 355 authentic witnesses are mandatory")
        result = {}
        for row in rows:
            pos = tuple(row[k] for k in ("x", "y", "z"))
            if any(type(n) is not int for n in pos) or pos in result: raise AssertionError("Duplicate/invalid witness position")
            value = {key: row[key] for key in ("name", "metadata", "actualName", "properties")}
            if type(value["metadata"]) is not int: raise AssertionError("Invalid metadata type")
            if expected: value["actualName"] = target_id(value["actualName"])
            if "nbt" in row and "nbtBase64" not in row: raise AssertionError("Missing authoritative binary NBT")
            if "nbtBase64" in row: value["nbt"] = canonical_nbt(row, enchantments, expected)
            result[pos] = value
        return result
    left = indexed(before, before_enchantments, True)
    right = indexed(after, after_enchantments, False)
    differences = [dict(position=pos, expected=left.get(pos), actual=right.get(pos))
                   for pos in sorted(left.keys() | right.keys()) if left.get(pos) != right.get(pos)]
    if differences: raise AssertionError("Passive registry/meta/typed-NBT differences: " + str(differences[:5]))
    return dict(status="PASS", witnesses=355, tiles=sum("nbtBase64" in row for row in before),
                comparison="Exact metadata, properties and typed NBT; expected namespace fields only; numeric enchantments resolved against each genuine registry")


def coverage(source, rows):
    stacks, enchantments = [], []
    def visit(tag):
        if isinstance(tag, nbtlib.Compound):
            if "Count" in tag and isinstance(tag.get("id"), nbtlib.String): stacks.append(tag)
            for key, child in tag.items():
                if key in ("ench", "StoredEnchantments", "Enchantments"): enchantments.extend(child)
                visit(child)
        elif isinstance(tag, nbtlib.List):
            for child in tag: visit(child)
    for row in rows:
        if "nbtBase64" in row: visit(binary_nbt(row))
    world = source / WORLD
    return dict(blockStateWitnesses=len(rows), tileWitnesses=sum("nbtBase64" in r for r in rows),
        distinctBlockNames=len({r["name"] for r in rows}), inventoryStacks=len(stacks),
        inventoryItems=sum(int(s["Count"]) for s in stacks), distinctItemNames=len({str(s["id"]) for s in stacks}),
        enchantmentEntries=len(enchantments), playerFiles=len(list((world / "playerdata").glob("*.dat"))),
        levelPlayer="Player" in nbtlib.load(world / "level.dat").get("Data", {}), declaredEntityWitnesses=0,
        gaps=["No authentic saved player, ender inventory or armor/offhand fixture",
              "No declared actor fixture; independent read-only scan found no saved entities",
              "Synthetic detached serializers are not player-login, entity-spawn or cross-restart player acceptance"])


def bind_fixture(source, source_profile):
    source, source_profile = source.resolve(), source_profile.resolve()
    if digest(source_profile) != PROFILE_SHA: raise AssertionError("Not the sealed original-Cyano profile manifest")
    for name, checksum in SOURCE_FILES.items():
        if digest(source / name) != checksum: raise AssertionError("Changed authentic source receipt: " + name)
    profile = read_json(source_profile)["original110"]
    if {Path(j["path"]).name: j["sha256"].upper() for j in profile["jars"]} != SOURCE_JARS:
        raise AssertionError("Source profile does not describe the original released jars and original passive probe")
    if {p.name: digest(p) for p in (source / "mods").glob("*.jar")} != SOURCE_JARS:
        raise AssertionError("Archived source mod bytes differ")
    if fingerprint(source / WORLD) != read_json(source / "fixture-hashes.json"):
        raise AssertionError("Protected authentic source world changed")
    rows = read_json(source / "migration-source.json")
    if len(rows) != 355 or len(read_json(source / "migration-fixture.json")) != 355:
        raise AssertionError("Not the complete original 355-witness fixture")
    return rows, saved_enchantments(source / WORLD)


def source_observer(source):
    path = source / "mods" / "AdvantageMigrationProbe-110-1.0.0.jar"
    if digest(path) != SOURCE_JARS[path.name]:
        raise AssertionError("The exact authentic passive source observer is required")
    return path


def validate_passive(document):
    if (document.get("scope") != AUTHENTIC_SCOPE or document.get("held") is not True
            or any(type(document.get(k)) is not int or document[k] != 0
                   for k in ("constructionCallsThisProcess", "requestedTicks", "advancedTicks"))
            or type(document.get("frozenTileObjects")) is not int or document["frozenTileObjects"] < 1):
        raise AssertionError("Missing frozen, nonconstructing passive native observation")
    return registry([(r["numericId"], r["name"]) for r in document["enchantments"]])


def decoded(value):
    return nbtlib.File.parse(io.BytesIO(gzip.decompress(base64.b64decode(value, validate=True))))


def validate_nested(document):
    if (document.get("scope") != SYNTHETIC_SCOPE or document.get("status") != "PASS"
            or type(document.get("worldInsertions")) is not int or document["worldInsertions"] != 0
            or type(document.get("playerFileWrites")) is not int or document["playerFileWrites"] != 0):
        raise AssertionError("Not a complete isolated synthetic native serializer regression")
    cases = document.get("cases", [])
    if len(cases) != 3 or {r["id"] for r in cases} != SYNTHETIC_CASES:
        raise AssertionError("All three separate synthetic cases are required")
    for row in cases:
        if row.get("status") != "PASS" or row.get("nativeSemantics") is not True:
            raise AssertionError("Native serializer semantics failed")
        expected, actual = decoded(row["expectedNbtBase64"]), decoded(row["actualNbtBase64"])
        if legacy.typed_nbt(expected) != legacy.typed_nbt(actual):
            raise AssertionError("Synthetic typed native readback changed: " + row["id"])
        if not decoded(row["inputNbtBase64"]): raise AssertionError("Missing declared synthetic legacy input")
    return dict(status="PASS", cases=3, scope=SYNTHETIC_SCOPE)


class PassiveServer(Server):
    def send(self, command):
        if command not in ("stop", "save-all flush") and not command.startswith(("advpassive snapshot ", "advnested run ")):
            raise AssertionError("Forbidden passive runtime command: " + command)
        super().send(command)

    def close(self):
        try: return super().close()
        finally: require_native_boot(Path(self.log.name))


def run(args):
    output = output_path(args.output)
    source, source_profile = args.source_fixture.resolve(), args.source_profile.resolve()
    original, original_enchantments = bind_fixture(source, source_profile)
    target, pins = bind_target(args.target_manifest)
    observer = source_observer(source)
    runtime_profile = target[:2] + (target[2] + [observer],)
    for protected in (source, source_profile, args.target_manifest, target[0], *target[2]): require_disjoint(output, protected)
    with socket.socket() as listener: listener.bind(("127.0.0.1", args.port))
    source_before = fingerprint(source)
    helpers = [Path(__file__), *[Path(__file__).with_name(name) for name in
        ("native110_rebrand_connected.py", "native110_connected.py", "legacy_connected_oracle.py", "semantic_machine_audit.py",
         "native110.py", "native110_code.py", "native110_lifecycle.py", "native110_diagnostics.py", "rig_transport.py")]]
    report = dict(status="RUNNING", mode=args.mode, scope=AUTHENTIC_SCOPE if args.mode == "passive-source355" else SYNTHETIC_SCOPE,
        source=str(source), sourceProfile=str(source_profile), sourceProfileSha256=digest(source_profile),
        targetManifest=str(args.target_manifest.resolve()), targetManifestSha256=digest(args.target_manifest),
        sourceBefore=source_before, sourceFilesSha256=SOURCE_FILES, coverage=coverage(source, original), phases={},
        sourceObserver=dict(path=str(observer), sha256=digest(observer), modid="advantagemigrationprobe", version="1.0.0",
                            scope="Exact original fixture dependency; initialization freezes tiles only; legacy build/snapshot commands forbidden"),
        protocolSha256={str(p): digest(p) for p in helpers}, startedAtUtc=datetime.now(timezone.utc).isoformat())
    receipt = output / ("native110-rebrand-passive-results.json" if args.mode == "passive-source355" else "native110-rebrand-synthetic-nested-results.json")
    server = None
    try:
        launcher = stage(output, runtime_profile)
        copy_fixture(source, output)
        (output / "advantage-passive-migration.allow").touch()
        if args.mode == "synthetic-nested":
            (output / "advantage-nested-identity.allow").touch()
            write_json(output / "source-enchantments.json", {name: numeric_id for numeric_id, name in original_enchantments.items()})
        props = output / "server.properties"
        props.write_text(props.read_text().replace("server-port=25584", "server-port=" + str(args.port)))
        for path in helpers:
            destination = output / "protocol-sources" / path.name
            destination.parent.mkdir(exist_ok=True)
            shutil.copy2(path, destination)
        write_json(output / INVENTORY, pins["serverCode"])
        write_json(receipt, report)
        before, before_enchantments = original, original_enchantments
        for phase in ("converted", "restarted"):
            bind_fixture(source, source_profile)
            if digest(args.target_manifest) != report["targetManifestSha256"]: raise AssertionError("Target manifest changed")
            if (output / "migration-operate.allow").exists() or (output / "advantage-machine-operation.allow").exists():
                raise AssertionError("Passive world acquired operating authority")
            verify_staged(output, runtime_profile)
            verify_code(output, pins["serverCode"])
            server = PassiveServer(output, args.java8, launcher, phase)
            if args.mode == "passive-source355":
                server.send("advpassive snapshot " + phase)
                server.wait("ADVPASSIVE COMPLETE " + phase, 180)
                observed = read_json(output / ("passive-" + phase + ".json"))
                after_enchantments = validate_passive(observed)
                report["phases"][phase] = compare_rows(before, observed["snapshot"], before_enchantments, after_enchantments)
                before, before_enchantments = observed["snapshot"], after_enchantments
            else:
                server.send("advnested run " + phase)
                server.wait("ADVNESTED COMPLETE " + phase, 180)
                report["phases"][phase] = validate_nested(read_json(output / ("nested-identity-" + phase + ".json")))
            server.send("save-all flush")
            server.wait("Saved the world", 120)
            server.close(); server = None
            verify_staged(output, runtime_profile)
            if phase == "converted": shutil.copytree(output / WORLD, output / "pre-restart-save")
            write_json(receipt, report)
        report["status"] = "PASS"
    except BaseException as error:
        report.update(status="FAIL", failure=str(error), traceback=traceback.format_exc())
    finally:
        if server:
            try: server.close()
            except BaseException as error: report.update(status="FAIL", shutdownError=str(error))
        report["sourceUnchanged"] = fingerprint(source) == source_before
        report["protocolUnchanged"] = report["protocolSha256"] == {str(p): digest(p) for p in helpers}
        report["manifestUnchanged"] = digest(args.target_manifest) == report["targetManifestSha256"]
        try: bind_fixture(source, source_profile)
        except BaseException as error: report.update(status="FAIL", sourceBindingError=str(error))
        if not all(report[k] for k in ("sourceUnchanged", "protocolUnchanged", "manifestUnchanged")): report["status"] = "FAIL"
        output.mkdir(parents=True, exist_ok=True)
        report["completedAtUtc"] = datetime.now(timezone.utc).isoformat()
        report["rawSha256"] = {str(p.relative_to(output)): digest(p) for p in output.rglob("*") if p.is_file() and p != receipt}
        write_json(receipt, report)
    return int(report["status"] != "PASS")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("passive-source355", "synthetic-nested"), default="passive-source355")
    parser.add_argument("--source-fixture", type=Path, required=True)
    parser.add_argument("--source-profile", type=Path, required=True)
    parser.add_argument("--target-manifest", type=Path, required=True)
    parser.add_argument("--java8", type=Path, required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True)
    return run(parser.parse_args())


if __name__ == "__main__": raise SystemExit(main())
