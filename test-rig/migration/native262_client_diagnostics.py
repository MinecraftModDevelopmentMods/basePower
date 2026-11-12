"""Bind the Forge 65 client inputs without inheriting earlier log exemptions."""

import hashlib
import json
import re
from pathlib import Path
from zipfile import ZipFile

from matrix import digest
from native262_platform_diagnostics import HOST_CONTROL_SHA, OSHI_JAR_SHA

SDK_NAME = "forge-26.2-65.1.0.jar"
SDK_PAYLOAD_SHA = "3E10711C65C69662E1AEFE92F07A9B85BB1EF92DEDD429CF856935D6CA81768C"
BINARIES = {
    SDK_NAME: SDK_PAYLOAD_SHA,
    "netty-common-4.2.15.Final.jar": "78206AA7F6D197CAA926291408C01889B6B910CA0F74017D3FCBDACCF9562959",
    "netty-buffer-4.2.15.Final.jar": "1361FD9C9BA85B9831CF54A1B2E45DDC3CE34A768931726C099D3F5EF0EFE4A3",
    "datafixerupper-10.0.21.jar": "3547CB1DA8993FB7EBD42494D0E78997D2CFBE90C421F5EFD022863E6909941E",
    "oshi-core-6.9.0.jar": OSHI_JAR_SHA,
}
SOURCES = {
    SDK_NAME: "D06D76658C43DC91D8EB5BF0C1A519E261B84AAC17940E64E7050449D9DA86D9",
    "netty-common-4.2.15.Final.jar": "DE720A128534BA1072B354C863D671AB1999900A8FDDAADC4A68EE74C2E33FD6",
    "netty-buffer-4.2.15.Final.jar": "13259B636C4A91C0F34A8536D621178FBA302F9BA61F3E4AAEC3A17CA291DC2A",
}


def sdk_payload_sha(path):
    with ZipFile(path) as archive:
        entries = [entry for entry in archive.infolist() if not entry.is_dir()]
        if len({entry.filename for entry in entries}) != len(entries):
            raise AssertionError("Ambiguous duplicate SDK payload")
        payloads = {entry.filename: hashlib.sha256(archive.read(entry)).hexdigest().upper() for entry in entries}
    return hashlib.sha256(json.dumps(payloads, sort_keys=True, separators=(",", ":")).encode("ascii")).hexdigest().upper()


def verify_sdk_payload(path):
    if sdk_payload_sha(path) != SDK_PAYLOAD_SHA:
        raise AssertionError("Client SDK payload differs from the sealed target")


def verify_bindings(code):
    found = [row for row in code["archives"] if Path(row["path"]).name in BINARIES]
    if len(found) != len(BINARIES) or {Path(row["path"]).name for row in found} != set(BINARIES):
        raise AssertionError("Missing/duplicate exact client dependencies")
    for row in found:
        path = Path(row["path"])
        if digest(path) != row["sha256"]:
            raise AssertionError("Client dependency bytes differ from their sealed input")
        if path.name == SDK_NAME:
            verify_sdk_payload(path)
        elif row["sha256"] != BINARIES[path.name]:
            raise AssertionError("Client dependency bytes differ from the target")
    records = [dict(row) for row in found]
    for binary, source_sha in SOURCES.items():
        path = Path(next(row["path"] for row in found if Path(row["path"]).name == binary))
        name = path.stem + "-sources.jar"
        candidates = [path.with_name(name)] if binary == SDK_NAME else list(path.parent.parent.glob("*/" + name))
        if len(candidates) != 1 or digest(candidates[0]) != source_sha:
            raise AssertionError("Exact client primary-source archive absent/changed")
        records.append(dict(path=str(candidates[0].resolve()), sha256=source_sha))
    return records


FIXTURE = Path(__file__).with_name("fixtures") / "native262-client-platform-sequences.json"
FIXTURE_SHA = "2DF3B239D7A566E96A9DB04E958B574359F947E067E842F3F94824FB104440D5"
HEADER = re.compile(r"^\[[^\]]+\] \[([^\]]+)/([A-Z]+)\] \[([^\]]+)\]: (.*)$")
ANSI = re.compile(r"\x1b\[[0-9;]*m")


def variants():
    if digest(FIXTURE) != FIXTURE_SHA:
        raise AssertionError("Reviewed native client platform sequences changed")
    return json.loads(FIXTURE.read_text(encoding="utf-8"))["variants"]


def classify(text, fallback):
    blocks = []
    for line in ANSI.sub("", text).splitlines():
        if HEADER.fullmatch(line) or not blocks:
            blocks.append([line])
        else:
            blocks[-1].append(line)
    expected = variants()
    names = {row["logger"] for rows in expected.values() for row in rows}
    selected = []
    for index, block in enumerate(blocks):
        header = HEADER.fullmatch(block[0])
        if not header or header[3] not in names:
            continue
        if "HkeyPerformanceDataUtil" in header[3] and header[2] == "DEBUG":
            continue
        if "SystemReport" in header[3] and not header[4].startswith("Failed to get system info for Process "):
            continue
        selected.append((index, dict(thread=header[1], level=header[2], logger=header[3], message=header[4],
                                     body=[line for line in block[1:] if line.strip() and line.strip() != ">"])))
    if not selected:
        return fallback(text)
    actual = [row for _, row in selected]
    match = next((key for key, rows in expected.items() if actual == rows), None)
    if match is None:
        failures, diagnostics = fallback(text)
        failures.append("Unproven native262 client platform sequence; exact independent-control-bound messages and stacks required")
        return failures, diagnostics
    accepted = {index for index, _ in selected}
    diagnostics, remaining = [], []
    for index, block in enumerate(blocks):
        if index in accepted:
            diagnostics.append(dict(classification="INDEPENDENTLY_PROVEN_NATIVE_CLIENT_PLATFORM_DIAGNOSTIC",
                                    fixtureSha256=FIXTURE_SHA, hostControlReceiptSha256=HOST_CONTROL_SHA,
                                    appender=match, rawBlock=block))
        else:
            remaining.extend(block)
    failures, other = fallback("\n".join(remaining))
    diagnostics.extend(other)
    return failures, diagnostics
