"""Exact target SDK binding; platform diagnostic exceptions remain unapproved."""

from pathlib import Path

from native262_client_diagnostics import sdk_payload_sha
from native263_platform_diagnostics import classify, COMMON_JAR_SHA, OSHI_JAR_SHA

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
