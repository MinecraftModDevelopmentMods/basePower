"""Exact SDK binding and independently captured Native66 client diagnostic sequences."""

from pathlib import Path
import hashlib
import json
import re

from native262_client_diagnostics import sdk_payload_sha
from native263_platform_diagnostics import blocks, selected, strict as strict_platform, COMMON_JAR_SHA, OSHI_JAR_SHA

CLIENT_FIXTURE = Path(__file__).with_name("fixtures") / "native263-client-platform-sequences-02.json"
CLIENT_FIXTURE_SHA = "F2C58A447743A5CE79EDB24F2512255889F7210E3F12F2A5B34C2B222583148E"
CLIENT_COUNTS = {"host": 5, "netty": 3, "unsafe": 1}


def strict_client(text):
    failures, diagnostics = strict_platform(text)
    # The shared strict matcher covers subclasses but misses the bare base types.
    for line in text.splitlines():
        if re.search(r"\b(?:Exception|Error)\b", line) and line not in failures:
            failures.append(line)
    return failures, diagnostics


def client_fixture():
    raw = CLIENT_FIXTURE.read_bytes()
    if hashlib.sha256(raw).hexdigest().upper() != CLIENT_FIXTURE_SHA:
        raise AssertionError("Reviewed native66 client fixture changed")
    document = json.loads(raw)
    if (document.get("engineProtocol"), document.get("minecraft"), document.get("forge"), document.get("java"),
            document.get("sdkPayloadSha256"), document.get("legacyCounts"), document.get("familyArtifacts"), document.get("probeIncluded")) != (
            "NATIVE66", "26.3", "66.0.0", "25.0.3+9", SDK_PAYLOAD_SHA, CLIENT_COUNTS, [], False):
        raise AssertionError("Wrong native66 independent client control contract")
    if document.get("format") != 2 or any(entry.get("kind") not in CLIENT_COUNTS
            or type(entry.get("expectedBlockCount")) is not int or entry["expectedBlockCount"] <= 0
            or entry["expectedBlockCount"] != len(entry.get("blocks", [])) for entry in document["sequences"]):
        raise AssertionError("Native66 client fixture needs an explicit exact count for each captured complete sequence")
    return document


def classify(text, fallback=None):
    # Only these complete current-client sequences are accepted; no historical fallback runs.
    rows = blocks(text)
    fixture = client_fixture()
    accepted = {}
    for kind in CLIENT_COUNTS:
        found = selected(rows, kind)
        if not found:
            continue
        actual = [row for _, row in found]
        indices = [index for index, _ in found]
        matches = [entry for entry in fixture["sequences"] if entry["kind"] == kind and entry["blocks"] == actual]
        if (not matches or not any(entry["expectedBlockCount"] == len(actual) for entry in matches)
                or kind == "netty" and indices != list(range(indices[0], indices[0] + len(actual)))):
            failures, notes = strict_client(text)
            failures.append("Unproven native66 client " + kind + " sequence; exact complete captured blocks required")
            return failures, notes
        if kind == "unsafe" and not accepted:
            failures, notes = strict_client(text)
            failures.append("Shared Unsafe notice lacks an exact executing client host/Netty context")
            return failures, notes
        for index, row in found:
            accepted[index] = dict(classification="EXACT_INDEPENDENT_NATIVE66_CLIENT_" + kind.upper(),
                                   fixtureSha256=CLIENT_FIXTURE_SHA,
                                   scope="Host/Java25 library diagnostics only; not gameplay or family qualification", rawBlock=row)
    failures, notes = [], []
    for index, row in enumerate(rows):
        if index in accepted:
            notes.append(accepted[index])
        else:
            raw = row["preamble"] if "preamble" in row else (
                f'[native66] [{row["thread"]}/{row["level"]}] [{row["logger"]}]: {row["message"]}\n' + "\n".join(row["body"]))
            errors, _ = strict_client(raw)
            failures.extend(errors)
    return failures, notes

SDK_NAME = "forge-26.3-66.0.0.jar"
SDK_PAYLOAD_SHA = "F897BBC12BBB715702E7DF8A85BFAEDE5B65673D2C609A1BD856DE3BB046B6DC"
BINARIES = {
    SDK_NAME: SDK_PAYLOAD_SHA,
    "netty-common-4.2.16.Final.jar": COMMON_JAR_SHA,
    "netty-buffer-4.2.16.Final.jar": "CC36AE9FBD0B03FE755EB4EB4424CA53B59CFD297D7FD47D49E5B1059BEDED6C",
    "datafixerupper-10.0.21.jar": "3547CB1DA8993FB7EBD42494D0E78997D2CFBE90C421F5EFD022863E6909941E",
    "oshi-core-6.9.0.jar": OSHI_JAR_SHA,
}


def verify_sdk_payload(path):
    if SDK_PAYLOAD_SHA == "0" * 64 or sdk_payload_sha(path) != SDK_PAYLOAD_SHA:
        raise AssertionError("Client SDK payload differs from the sealed Native66 target")


def verify_bindings(code):
    found = [row for row in code["archives"] if Path(row["path"]).name in BINARIES]
    if len(found) != len(BINARIES) or {Path(row["path"]).name for row in found} != set(BINARIES):
        raise AssertionError("Missing/duplicate exact Native66 client dependencies")
    for row in found:
        name = Path(row["path"]).name
        if name == SDK_NAME:
            verify_sdk_payload(row["path"])
        elif row["sha256"] != BINARIES[name]:
            raise AssertionError("Native66 client binary differs: " + name)
    return found
