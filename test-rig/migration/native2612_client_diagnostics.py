"""Exact integrated-server status-ping cleaner probe; dedicated-server policy is untouched."""

import hashlib
import json
from pathlib import Path
from zipfile import ZipFile

from matrix import digest
from native2612_platform_diagnostics import HEADER, LOGGERS, verify_sources


# Mavenizer's SDK ZIP metadata varies between clean builds. Two independent
# preparations match all 12,677 payloads; native dependency jars stay byte-bound.
SDK_NAME = "forge-26.1.2-64.1.5.jar"
SDK_PAYLOAD_SHA = "5C3984377983C8CDE452AB12DFFE0043FC0E3E1ECA0DE8276F833D0C0D2033BF"
BINARIES = {
    SDK_NAME: SDK_PAYLOAD_SHA,
    "netty-common-4.2.7.Final.jar": "2345BC0ED5843FA57AA49EBA67529485C3A1D420FCF0429324C8220C7A80E9A6",
    "netty-buffer-4.2.7.Final.jar": "B81613C8ED22B1CC39ECCF2A28BD0C1D4561FB2E5E542061617EF4CE1D228BF5",
    "datafixerupper-9.0.19.jar": "9653A39CF5C4853CC98628D902DCB23FFEB2755122ECC563BDE38C8F60C0B61E",
}
BODY_HASHES = (
    "4E8F6351774C4403FA25710142BA5F4D9BA4B930B8FD295D48015238E1B4019E",
    "08586307371AFC1E3D067ACB177498E72D16C1C9F0832293146FA8A8BEC4A47A",
    "E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855",
)


def sdk_payload_sha(path):
    with ZipFile(path) as archive:
        entries = [entry for entry in archive.infolist() if not entry.is_dir()]
        if len({entry.filename for entry in entries}) != len(entries):
            raise AssertionError("Ambiguous duplicate SDK payload")
        payloads = {entry.filename: hashlib.sha256(archive.read(entry)).hexdigest().upper() for entry in entries}
    return hashlib.sha256(json.dumps(payloads, sort_keys=True, separators=(",", ":")).encode("ascii")).hexdigest().upper()


def verify_sdk_payload(path):
    if sdk_payload_sha(path) != SDK_PAYLOAD_SHA:
        raise AssertionError("Client cleaner SDK payload differs from independent clean-build proof")


def verify_bindings(code):
    found = [row for row in code["archives"] if Path(row["path"]).name in BINARIES]
    if len(found) != 4 or {Path(row["path"]).name for row in found} != set(BINARIES):
        raise AssertionError("Missing/duplicate exact client cleaner initialization dependencies")
    for row in found:
        path = Path(row["path"])
        if digest(path) != row["sha256"]:
            raise AssertionError("Client cleaner dependency bytes differ from primary proof")
        if path.name == SDK_NAME:
            verify_sdk_payload(path)
        elif row["sha256"] != BINARIES[path.name]:
            raise AssertionError("Client cleaner dependency bytes differ from primary proof")
    records = [dict(row) for row in found]
    for binary, source_sha in (
            ("forge-26.1.2-64.1.5.jar", "9175F1245E5E2907EA3ECAB13DC40F9B8994E61759E75363F6FB14322335F013"),
            ("netty-common-4.2.7.Final.jar", "0B5D0B0498D009DDCFAF2B48A84F23F9380F82DAFD8B0061406BAF895349080C"),
            ("netty-buffer-4.2.7.Final.jar", "B2142CC082E763042E1A50649808DA1A29601F0E471F0CA4F8082CACC69950FA")):
        path = Path(next(row["path"] for row in found if Path(row["path"]).name == binary))
        name = path.stem + "-sources.jar"
        candidates = [path.with_name(name)] if binary.startswith("forge-") else list(path.parent.parent.glob("*/" + name))
        if len(candidates) != 1 or digest(candidates[0]) != source_sha:
            raise AssertionError("Exact client cleaner primary-source archive absent/changed")
        if binary.startswith("netty-common"):
            verify_sources(candidates[0])
        records.append(dict(path=str(candidates[0].resolve()), sha256=source_sha))
    return records


def classify(text, fallback):
    blocks = []
    for line in text.splitlines():
        if HEADER.fullmatch(line) or not blocks:
            blocks.append([line])
        else:
            blocks[-1].append(line)
    selected = [(index, HEADER.fullmatch(block[0])) for index, block in enumerate(blocks)
                if HEADER.fullmatch(block[0]) and HEADER.fullmatch(block[0])[3] in LOGGERS]
    if not selected:
        return fallback(text)
    valid = len(selected) == 3
    if valid:
        positions = [row[0] for row in selected]
        valid = positions == list(range(positions[0], positions[0] + 3))
        for slot, (index, header) in enumerate(selected):
            body = [line for line in blocks[index][1:] if line.strip()]
            sha = hashlib.sha256("\n".join(body).encode("utf-8")).hexdigest().upper()
            valid = valid and (header[1], header[2], header[3], header[4], len(body), sha) == (
                "Server thread", "DEBUG", LOGGERS[slot], "java.nio.ByteBuffer.cleaner(): " + (
                    "available" if slot == 2 else "unavailable"), 0 if slot == 2 else 22, BODY_HASHES[slot])
    if not valid:
        errors, diagnostics = fallback(text)
        errors.append("Unproven native64 integrated-server cleaner sequence: exact status-ping frames/order/DEBUG/native25 fallback required")
        return errors, diagnostics
    accepted = {index for index, _ in selected[:2]}
    errors, diagnostics = [], []
    for index, block in enumerate(blocks):
        if index in accepted:
            diagnostics.append(dict(classification="SOURCE_PROVEN_NATIVE64_STATUS_PING_JAVA25_CLEANER_FALLBACK",
                logger=HEADER.fullmatch(block[0])[3], level="DEBUG", rawBlock=block, binaryBindings=BINARIES,
                requiredSubsequentFallback="io.netty.util.internal.CleanerJava25 available"))
        else:
            failures, other = fallback("\n".join(block))
            errors.extend(failures); diagnostics.extend(other)
    return errors, diagnostics
