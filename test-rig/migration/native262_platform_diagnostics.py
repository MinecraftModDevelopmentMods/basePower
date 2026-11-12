"""Exact, independently reproduced Forge 65 host diagnostics; no exception wildcard."""

import hashlib
import json
from pathlib import Path
import re

COMMON_JAR_SHA = "78206AA7F6D197CAA926291408C01889B6B910CA0F74017D3FCBDACCF9562959"
SOURCES_JAR_SHA = "DE720A128534BA1072B354C863D671AB1999900A8FDDAADC4A68EE74C2E33FD6"
HOST_FIXTURE = Path(__file__).with_name("fixtures") / "native262-server-host-perflib.json"
HOST_FIXTURE_SHA = "894104959BCED7C967A1916B1AEFAE74A02C42648F3A492E95901E1987F98D2B"
HOST_FILE_FIXTURE = HOST_FIXTURE.with_name("native262-file-host-perflib.json")
HOST_FILE_FIXTURE_SHA = "7F59E0B7AA327AFA437F7503EBBA4586C5C33D6177A2E0F6775EDB69A8601840"
HOST_CONTROL_SHA = "9D2BCB6B5780D2DCD56CF2618DD7CB6F49038E3C6D1D2C94E2D6E3DE57575B94"
SYSTEM_REPORT_SOURCE_SHA = "C663E1109AD7C2D1869A74FA07FD74A4D95D8C131F57533A4CB0471988C14CC5"
OSHI_JAR_SHA = "B29BA9BCE041E6EA822203D5B5A692660C87551241510EBC76EEDFFE684844C7"
NETTY_FIXTURE = HOST_FIXTURE.with_name("native262-server-netty-java25.json")
NETTY_FIXTURE_SHA = "D22CD0A0B883C88BE66B42E3AD37EF914DFB8BEBF5CEC17893A09799F47770E3"
HEADER = re.compile(r"^\[[^\]]+\] \[([^\]]+)/([A-Z]+)\] \[([^\]]+)\]: (.*)$")
ANSI = re.compile(r"\x1b\[[0-9;]*m")


def host_fixture(file_appender=False):
    path, checksum = (HOST_FILE_FIXTURE, HOST_FILE_FIXTURE_SHA) if file_appender else (HOST_FIXTURE, HOST_FIXTURE_SHA)
    raw = path.read_bytes()
    if hashlib.sha256(raw).hexdigest().upper() != checksum:
        raise AssertionError("Reviewed native host-diagnostic fixture changed")
    return json.loads(raw)["blocks"]


def netty_fixture():
    raw = NETTY_FIXTURE.read_bytes()
    if hashlib.sha256(raw).hexdigest().upper() != NETTY_FIXTURE_SHA:
        raise AssertionError("Reviewed native Netty fixture changed")
    return json.loads(raw)["blocks"]


def classify_netty(text, fallback):
    blocks = []
    for line in text.splitlines():
        if HEADER.fullmatch(line) or not blocks:
            blocks.append([line])
        else:
            blocks[-1].append(line)
    selected = []
    names = {row["logger"] for row in netty_fixture()}
    for index, block in enumerate(blocks):
        header = HEADER.fullmatch(block[0])
        if header and header[3] in names:
            selected.append((index, dict(thread=header[1], level=header[2], logger=header[3], message=header[4],
                                         body=[line for line in block[1:] if line.strip() and line.strip() != ">"])))
    if not selected:
        return fallback(text)
    if ([row for _, row in selected] != netty_fixture()
            or [index for index, _ in selected] != list(range(selected[0][0], selected[0][0] + 3))):
        failures, diagnostics = fallback(text)
        failures.append("Unproven native262 Netty sequence; exact DEBUG probes and successful Java25 cleaner required")
        return failures, diagnostics
    accepted = {index for index, _ in selected[:2]}
    failures, diagnostics = [], []
    for index, block in enumerate(blocks):
        if index in accepted:
            diagnostics.append(dict(classification="SOURCE_AND_BINARY_PROVEN_NATIVE_NETTY_JAVA25_FALLBACK",
                                    dependencySha256=COMMON_JAR_SHA, sourceSha256=SOURCES_JAR_SHA,
                                    fixtureSha256=NETTY_FIXTURE_SHA, rawBlock=block))
        else:
            errors, other = fallback("\n".join(block))
            failures.extend(errors)
            diagnostics.extend(other)
    return failures, diagnostics


def classify(text, fallback):
    blocks = []
    for raw in text.splitlines():
        line = ANSI.sub("", raw)
        if HEADER.fullmatch(line) or not blocks:
            blocks.append([line])
        else:
            blocks[-1].append(line)
    selected = []
    for index, block in enumerate(blocks):
        header = HEADER.fullmatch(block[0])
        if header and (header[3] in ("os.dr.wi.re.HkeyPerformanceDataUtil/", "oshi.driver.windows.registry.HkeyPerformanceDataUtil/")
                       and header[2] != "DEBUG" or
                       header[3] in ("minecraft/SystemReport", "net.minecraft.SystemReport/")
                       and header[4].startswith("Failed to get system info for Process ")):
            selected.append((index, dict(thread=header[1], level=header[2], logger=header[3], message=header[4],
                                         body=[line for line in block[1:] if line.strip() and line.strip() != ">"])))
    if not selected:
        return classify_netty(text, fallback)
    file_appender = selected[0][1]["logger"] == "oshi.driver.windows.registry.HkeyPerformanceDataUtil/"
    if [row for _, row in selected] != host_fixture(file_appender):
        failures, diagnostics = fallback(text)
        failures.append("Unproven native262 host-diagnostic sequence; complete exact primary-source-bound stacks required")
        return failures, diagnostics
    accepted = {index for index, _ in selected}
    failures, diagnostics = [], []
    remaining = []
    for index, block in enumerate(blocks):
        if index in accepted:
            diagnostics.append(dict(classification="INDEPENDENTLY_REPRODUCED_NATIVE_WINDOWS_PERFLIB_FAILURE",
                                    scope="Host process statistics unavailable; not a gameplay assertion",
                                    controlReceiptSha256=HOST_CONTROL_SHA,
                                    fixtureSha256=HOST_FILE_FIXTURE_SHA if file_appender else HOST_FIXTURE_SHA,
                                    primarySourceSha256=SYSTEM_REPORT_SOURCE_SHA, dependencySha256=OSHI_JAR_SHA,
                                    rawBlock=block))
        else:
            remaining.extend(block)
    errors, other = classify_netty("\n".join(remaining), fallback)
    failures.extend(errors)
    diagnostics.extend(other)
    return failures, diagnostics
