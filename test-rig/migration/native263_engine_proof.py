"""Native66 source/codec/control contract stage, never a packaged-server qualification."""

import argparse
from pathlib import Path
from zipfile import ZipFile
import hashlib

import native263 as native
from matrix import digest, write_json

SDK_SOURCE_SHA = "4571742629CD247753B109B272C8D6BBC583E6D3D5B2E79FA7DEC1EA09D25F8E"
SOURCE_PINS = {
    "net/minecraft/SharedConstants.java": "3DD2370A3EA115D95EB552936DEA8EA2B4D30D747C2B6D3AA0B27D7EC7E2724A",
    "net/minecraft/nbt/NbtUtils.java": "1572E9C03DE3D09127A40003C7F7242EBB6B4B2D3D16936707D8045CC9A4E201",
    "net/minecraft/world/level/block/state/BlockState.java": "F5DF62C9FDADC05D873AE7877A420B01A1CFF7E0401012463F9F66E790B2B33B",
    "net/minecraft/world/level/block/state/StateHolder.java": "CDF8C83BFC8A98A8E7BBE349C99F169ED4D9AEBCF30725F18E88AC7C582804D8",
    "net/minecraft/SystemReport.java": "0AEA369284B196322E5A84F8CFD4C72837E29F2D70CBC6C6E4CC070AD11CEB52",
}
PRIMARY_ARCHIVES = {
    "netty-common-4.2.16.Final-sources.jar": "117DC753E803A626D07BAE2A2638755C8762B71118FDA0CE8B476F887A7B1449",
    "oshi-core-6.9.0-sources.jar": "8C18534D5FED8FFB2A584762F8A606E68F67C511DDF71910F37001CA8CE73B1F",
}


def verify_sources(path):
    native.validate_hash(path, SDK_SOURCE_SHA)
    with ZipFile(path) as archive:
        for name, checksum in SOURCE_PINS.items():
            if archive.namelist().count(name) != 1 or hashlib.sha256(archive.read(name)).hexdigest().upper() != checksum:
                raise AssertionError("Native66 primary source changed: " + name)
    return SOURCE_PINS.copy()


def contract_stage(sources, controls, primary_sources, output):
    verify_sources(sources)
    files = [Path(sources), Path(__file__), Path(native.__file__)]
    primary_entries = {}
    for name, checksum in PRIMARY_ARCHIVES.items():
        path = Path(primary_sources) / name
        native.validate_hash(path, checksum)
        files.append(path)
        with ZipFile(path) as archive:
            selected = ("io/netty/util/internal/CleanerJava9.java", "io/netty/util/internal/CleanerJava24Linker.java",
                        "io/netty/util/internal/CleanerJava25.java", "io/netty/util/internal/PlatformDependent.java",
                        "io/netty/util/internal/PlatformDependent0.java") if name.startswith("netty") else (
                        "oshi/SystemInfo.java", "oshi/software/os/windows/WindowsOperatingSystem.java",
                        "oshi/driver/windows/registry/HkeyPerformanceDataUtil.java",
                        "oshi/driver/windows/registry/ProcessPerformanceData.java")
            primary_entries[name] = {entry: hashlib.sha256(archive.read(entry)).hexdigest().upper() for entry in selected}
    controls = Path(controls)
    inputs = native.read_json(controls / "inputs.json")
    for row in inputs["sources"]:
        native.validate_hash(row["path"], row["sha256"])
    binaries = {}
    for row in inputs["archives"]:
        native.validate_hash(row["path"], row["sha256"])
        name = Path(row["path"]).name
        if name in binaries:
            raise AssertionError("Ambiguous platform binary: " + name)
        binaries[name] = row
    sdk = binaries["forge-26.3-66.0.0.jar"]
    with ZipFile(sdk["path"]) as archive:
        raw = archive.read("net/minecraft/SharedConstants.class")
        if raw[:4] != b"\xca\xfe\xba\xbe" or int.from_bytes(raw[6:8], "big") != 69:
            raise AssertionError("Actual target SDK class is not Java25/class69")
    for main, receipt, marker in (
            ("NativeSystemReportPlatformControl", "receipt.json", "NATIVE_SYSTEM_REPORT_CONTROL_DONE"),
            ("NativeLeashKnotPlatformControl", "contract.json", "LEASH_KNOT_CONTROL_DONE"),
            ("NativeWolfPlatformControl", "control-runtime.json", "WOLF_CONTROL_DONE")):
        status = native.read_json(controls / (main + "-exit.json"))
        document = native.read_json(controls / main / receipt)
        if (status["exitCode"] != 0 or document["engineProtocol"] != "NATIVE66"
                or document["minecraftVersion"] != native.MINECRAFT
                or document["engineSourcesSha256"] != SDK_SOURCE_SHA
                or marker not in (controls / (main + ".log")).read_text(encoding="utf-8-sig")):
            raise AssertionError("Actual target control did not complete: " + main)
    if (native.read_json(controls / "Netty-exit.json")["exitCode"] != 0
            or "NETTY_JAVA25_CONTROL_PASS" not in (controls / "Netty.log").read_text(encoding="utf-8-sig")):
        raise AssertionError("Actual new Netty binary cleaner control failed")
    files.extend(path for path in controls.rglob("*") if path.is_file())
    output = Path(output)
    if output.exists():
        raise FileExistsError(output)
    result = dict(status="WORLDLESS_NATIVE66_CONTROLS_READY_NOT_SERVER_QUALIFICATION", engineProtocol="NATIVE66",
                  qualificationAllowed=False, minecraft=native.MINECRAFT, forge=native.FORGE,
                  primarySdkSources=SOURCE_PINS, primaryLibrarySources=primary_entries, actualSdkClassMajor=69,
                  platformBinaries={name: row for name, row in binaries.items() if name.startswith(("forge-", "netty-", "oshi-"))},
                  proofFiles={str(path.resolve()): digest(path) for path in files},
                  pending=["ACTUAL_PACKAGED_FAMILY_PINS", "OFFICIAL_SERVER_SEED", "ORDERED_NATIVE_SERVER_COLDSTOP",
                           "EXACT_SERVER_AND_CLIENT_LOG_SEQUENCES", "PACKAGED_GAMEPLAY_AND_CLIENT_QUALIFICATION"])
    write_json(output, result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk-sources", type=Path, required=True)
    parser.add_argument("--controls", type=Path, required=True)
    parser.add_argument("--primary-sources", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    print(contract_stage(args.sdk_sources, args.controls, args.primary_sources, args.output)["status"])


if __name__ == "__main__":
    main()
