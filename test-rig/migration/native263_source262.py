"""Explicit, read-only Native65/26.2 source protocol; never a 26.1.2 alias."""

import copy
import argparse
import hashlib
import json
from pathlib import Path
import shutil
from zipfile import ZipFile

import nbtlib

from matrix import WORLD, digest, fingerprint, write_json
import native262_layout as source_layout

MINECRAFT, FORGE, DATA_VERSION, ENGINE_PROTOCOL = "26.2", "65.1.0", 4903, "NATIVE65"
SERVER_JAR_SHA = "2DDA94C321F928934AE7E3B8DB45AAE0FD994A9FA7BBDCDDC0AF1F9170A581DA"


def engine_proof(path):
    if digest(path) != SERVER_JAR_SHA:
        raise AssertionError("Source engine must be the actual sealed Forge65.1.0 server jar")
    with ZipFile(path) as archive:
        if archive.namelist().count("version.json") != 1:
            raise AssertionError("Ambiguous actual source engine metadata")
        raw = archive.read("version.json")
    version = json.loads(raw)
    if (version.get("id") != MINECRAFT or type(version.get("world_version")) is not int
            or version["world_version"] != DATA_VERSION or version.get("java_version") != 25):
        raise AssertionError("Actual Native65 metadata differs from the explicit 26.2 source protocol")
    return dict(engineProtocol=ENGINE_PROTOCOL, minecraft=MINECRAFT, forge=FORGE, dataVersion=DATA_VERSION,
                serverJarSha256=SERVER_JAR_SHA, versionJsonSha256=hashlib.sha256(raw).hexdigest().upper(),
                actualVersion=version)


def level(world):
    result = source_layout.read_level(world)
    if "Player" in result:
        raise AssertionError("26.2 requires native singleplayer_uuid authority, not embedded Player")
    return result


def player(world, identity):
    level(world)
    return source_layout.read_player(world, identity)


def components(value):
    # The shared modern comparison receives the literal source version, not an older schema alias.
    from native263_upgrade import expected_components
    return expected_components(copy.deepcopy(value), source_version=MINECRAFT)


def source_protocol(runtime):
    data = level(Path(runtime) / WORLD)
    return dict(engineProtocol=ENGINE_PROTOCOL, minecraft=MINECRAFT, forge=FORGE,
                dataVersion=int(data["DataVersion"]), playerStorage="players/data",
                dimensionStorage="dimensions/<namespace>/<path>")


def freeze(source, receipt, checksum, output, kind, engine):
    import native263
    source, receipt, output = map(Path, (source, receipt, output))
    source = source.resolve(strict=True)
    if output.exists() or output.resolve().is_relative_to(source):
        raise ValueError("Freeze requires a new independent source-fixture directory")
    native263.validate_hash(receipt, checksum)
    approval = native263.read_json(receipt)
    if (approval.get("status") not in ("PASS", "PASS_WITH_NATIVE_DIFFERENCE")
            or approval.get("minecraft") != MINECRAFT
            or approval.get("profileSha256") != "A98D4F9B54A0AE47392D9A3CF81335F5E228F718C7D322DF9838A94AD27D9736"
            or not all(approval.get(key) is True for key in ("sourceUnchanged", "seedUnchanged", "inputsUnchanged"))):
        raise AssertionError("Only the accepted immutable native262 profile03 source may be frozen")
    protocol = source_protocol(source)
    native263.require_released_session_lock(source)
    before = fingerprint(source)
    output.mkdir(parents=True)
    shutil.copytree(source / WORLD, output / WORLD)
    if (source / "config").is_dir():
        shutil.copytree(source / "config", output / "config")
    for path in source.iterdir():
        if path.is_file() and path.suffix in (".json", ".log"):
            shutil.copyfile(path, output / path.name)
    shutil.copyfile(receipt, output / receipt.name)
    if fingerprint(source) != before or fingerprint(source / WORLD) != fingerprint(output / WORLD):
        raise AssertionError("Stopped native262 source changed during ordinary fixture copying")
    result = dict(status="SEALED_STOPPED_NATIVE262_SOURCE_NOT_TARGET_QUALIFICATION", qualificationAllowed=False,
                  kind=kind, protocol=protocol, engine=engine_proof(engine), originalSource=str(source),
                  originalReceiptSha256=checksum.upper(), originalReceiptStatus=approval["status"],
                  source=str(output.resolve()), sourceFingerprint=fingerprint(output),
                  originalSourceUnchanged=True, ordinaryWorldCopyExact=True)
    seal = output.parent / (output.name + "-source262-fixture.json")
    if seal.exists():
        raise FileExistsError(seal)
    write_json(seal, result)
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--receipt", type=Path, required=True)
    parser.add_argument("--receipt-sha256", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--kind", choices=("machines", "actors", "dimensions"), required=True)
    parser.add_argument("--server-jar", type=Path, required=True)
    args = parser.parse_args()
    print(freeze(args.source, args.receipt, args.receipt_sha256, args.output, args.kind, args.server_jar)["status"])
