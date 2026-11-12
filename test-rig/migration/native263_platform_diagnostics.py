"""Native66 has no approved platform exceptions until independent target controls run."""

import re

COMMON_JAR_SHA = "9825EE68A0DC4CD2B53E2F532502401B2211BAD9B77E8B04882D9E64487283FF"
SOURCES_JAR_SHA = "117DC753E803A626D07BAE2A2638755C8762B71118FDA0CE8B476F887A7B1449"
OSHI_JAR_SHA = "B29BA9BCE041E6EA822203D5B5A692660C87551241510EBC76EEDFFE684844C7"
HOST_CONTROL_SHA = "BE5A0BA2C5935CBBAB27873745B36BB68A54CCD2A7171DB0D74B69DA661A92D0"
CONTROL_STATUS = "WORLDLESS_REPRODUCED_EXACT_SERVER_AND_CLIENT_SEQUENCES_PENDING"


def classify(text, fallback=None):
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


def require_controls():
    raise ValueError("Native66 exact server/client diagnostic sequences pending; worldless controls alone cannot waive runtime errors")
