"""Exact native66 server-only platform sequences; client exceptions remain closed."""

import hashlib
import json
from pathlib import Path
import re

COMMON_JAR_SHA = "9825EE68A0DC4CD2B53E2F532502401B2211BAD9B77E8B04882D9E64487283FF"
SOURCES_JAR_SHA = "117DC753E803A626D07BAE2A2638755C8762B71118FDA0CE8B476F887A7B1449"
OSHI_JAR_SHA = "B29BA9BCE041E6EA822203D5B5A692660C87551241510EBC76EEDFFE684844C7"
HOST_CONTROL_SHA = "BE5A0BA2C5935CBBAB27873745B36BB68A54CCD2A7171DB0D74B69DA661A92D0"
CONTROL_STATUS = "ACTUAL_FORGE_ONLY_TWO_BOOT_SERVER_SEQUENCES_REVIEWED_CLIENT_CLOSED"
SERVER_FIXTURE = Path(__file__).with_name("fixtures") / "native263-server-platform-sequences.json"
SERVER_FIXTURE_SHA = "B001EA4A92104D1EAC390AADC390D8CAAC86BABC1B6A1DF296086739C74CDDCB"
HOST_FIXTURE_SHA = HOST_FILE_FIXTURE_SHA = NETTY_FIXTURE_SHA = SERVER_FIXTURE_SHA
HEADER = re.compile(r"^\[[^\]]+\] \[([^\]]+)/([A-Z]+)\] \[([^\]]+)\]: (.*)$")
ANSI = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")
HOST_LOGGERS = {"os.dr.wi.re.HkeyPerformanceDataUtil/", "oshi.driver.windows.registry.HkeyPerformanceDataUtil/",
                "os.ut.pl.wi.PerfCounterQuery/", "oshi.util.platform.windows.PerfCounterQuery/",
                "minecraft/SystemReport", "net.minecraft.SystemReport/"}
CLEANERS = {"io.netty.util.internal.CleanerJava9/", "io.netty.util.internal.CleanerJava24Linker/",
            "io.netty.util.internal.CleanerJava25/", "io.ne.ut.in.CleanerJava9/",
            "io.ne.ut.in.CleanerJava24Linker/", "io.ne.ut.in.CleanerJava25/"}
COUNTS = {"host": 5, "netty": 3, "transport": 2, "unsafe": 1}
TRANSPORTS = {"io.netty.channel.kqueue.KQueue/", "io.netty.channel.epoll.Epoll/"}


def strict(text, fallback=None):
    # Do not invoke a historical fallback: those classifiers contain old-engine waivers.
    failures = []
    for line in text.splitlines():
        if re.search(r"/(?:ERROR|FATAL)\]|\b(?:[A-Za-z_$][\w$]*\.)*[A-Za-z_$][\w$]*(?:Exception|Error)\b|"
                     r"^\s+at |^\s*\.\.\. \d+ more|Server empty for \d+ seconds, pausing|"
                     r"Unable to write to stream|Unable to invoke factory method|"
                     r"KQueue support is not available|Epoll support is not available|"
                     r"Unsafe.*unavailable|Perflib|PerfOS|performance counter", line, re.IGNORECASE):
            failures.append(line)
    return failures, []


def blocks(text):
    result = []
    for raw in text.splitlines():
        line = ANSI.sub("", raw)
        match = HEADER.fullmatch(line)
        if match:
            result.append(dict(thread=match[1], level=match[2], logger=match[3], message=match[4], body=[]))
        elif result and "body" in result[-1]:
            if line.strip() and line.strip() != ">":
                result[-1]["body"].append(line)
        else:
            result.append(dict(preamble=line))
    return result


def selected(rows, kind):
    names = {"host": HOST_LOGGERS, "netty": CLEANERS, "transport": TRANSPORTS,
             "unsafe": {"io.netty.util.internal.PlatformDependent0/"}}[kind]
    return [(index, row) for index, row in enumerate(rows) if row.get("logger") in names
            and (kind != "host" or row.get("level") != "DEBUG")
            and (kind != "unsafe" or row.get("message", "").startswith("sun.misc.Unsafe:"))]


def server_fixture():
    raw = SERVER_FIXTURE.read_bytes()
    if hashlib.sha256(raw).hexdigest().upper() != SERVER_FIXTURE_SHA:
        raise AssertionError("Reviewed native66 server platform fixture changed")
    result = json.loads(raw)
    if result.get("engineProtocol") != "NATIVE66" or result.get("forge") != "66.0.0":
        raise AssertionError("Wrong native66 server diagnostic fixture protocol")
    return result


def classify(text, fallback=None):
    # Historical fallbacks are deliberately not invoked; every unselected block uses strict().
    rows = blocks(text)
    fixture = server_fixture()
    accepted = {}
    for kind, count in COUNTS.items():
        found = selected(rows, kind)
        if not found:
            continue
        actual = [row for _, row in found]
        matches = [entry for entry in fixture["sequences"] if entry["kind"] == kind and entry["blocks"] == actual]
        indices = [index for index, _ in found]
        if (len(actual) != count or not matches
                or kind == "netty" and indices != list(range(indices[0], indices[0] + count))):
            errors, notes = strict(text)
            errors.append("Unproven native66 server " + kind + " sequence; exact complete captured blocks required")
            return errors, notes
        for index, row in found:
            accepted[index] = dict(classification="EXACT_INDEPENDENT_NATIVE66_SERVER_" + kind.upper(),
                                   fixtureSha256=SERVER_FIXTURE_SHA,
                                   scope="Host diagnostics only; not gameplay or client qualification", rawBlock=row)
    failures, diagnostics = [], []
    for index, row in enumerate(rows):
        if index in accepted:
            diagnostics.append(accepted[index])
        else:
            text = row["preamble"] if "preamble" in row else (
                f"[time] [{row['thread']}/{row['level']}] [{row['logger']}]: {row['message']}\n" + "\n".join(row["body"]))
            failures.extend(strict(text)[0])
    return failures, diagnostics


def require_controls():
    return server_fixture()


def require_client_controls():
    raise ValueError("Native66 client diagnostic sequences pending; server and worldless controls alone cannot waive client errors")
