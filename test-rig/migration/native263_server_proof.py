"""Bind actual Forge-only two-boot server sequences to current sources and binaries."""

import argparse
import hashlib
from pathlib import Path
import sys
from zipfile import ZipFile

from matrix import digest, write_json
import native263 as native
import native263_engine_proof as primary
import native263_platform_diagnostics as diagnostics

SERVER_JAR_SHA = "0448282A6F32A30F8D44A6B9BB44E86566EBCADE2DCC246425FE0AB7A46F03EA"
OFFICIAL_RECEIPT_SHA = "236FDED442585F843AC95E7A1F7658CDACA31FA7FCEA0939137C7A99331E44AE"
TRANSPORT_SOURCES = {
    "netty-transport-classes-kqueue-4.2.16.Final-sources.jar": "0396FFC3B900D8D371173F7542D720A8FD9347F20981FD25195C228CEDA08D5B",
    "netty-transport-classes-epoll-4.2.16.Final-sources.jar": "2AE39F0D953CDBFA9C9CB0FA7C8EF5EE6008E1D1912783E7CC0C8AFC22E06819",
}
SOURCES = Path("D:/PowerAdvantage/PA263/PowerAdvantage/test-rig/.gradle/mavenizer/repo/net/minecraftforge/forge/26.3-66.0.0/forge-26.3-66.0.0-sources.jar")


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def captures(root):
    root = Path(root)
    document = native.read_json(root / "receipt.json")
    require(document.get("status") == "PASS_FORGE_ONLY_TWO_COLD_BOOTS_DIAGNOSTICS_CAPTURED"
            and document.get("forge") == "26.3-66.0.0" and document.get("minecraftDataVersion") == 5023,
            "Missing actual native66 two-cold-boot control")
    for key in ("noBM", "noOS", "noPower", "noHarness", "seedUnchanged", "stagedCodeUnchanged", "sameStoppedWorldRestarted"):
        require(document.get(key) is True, "Unproven Forge-only control: " + key)
    rows = {}
    for phase in ("fresh", "restart"):
        boot = document["phases"][phase]
        require(boot.get("exitCode") == 0 and all(boot.get(key) is True for key in (
            "ownedStop", "allOwnedProcessesExited", "sessionLockReleased", "portReleased", "shutdownErrorFree")),
            "Missing actual owned cold-stop proof")
        for name in ("console.log", "latest.log", "debug.log"):
            path = root / phase / name
            native.validate_hash(path, boot["logFiles"][name]["sha256"])
            text = path.read_text(encoding="utf-8-sig")
            native.require_complete_boot(text, stop_sent=True, returncode=0)
            rows[phase, name] = diagnostics.blocks(text)
    return rows


def capture_fixture(root, output):
    root, output = Path(root), Path(output)
    require(not output.exists(), "Do not overwrite a reviewed platform fixture")
    native.validate_hash(root / "attempt-04/receipt.json", OFFICIAL_RECEIPT_SHA)
    sequences, controls = [], {}
    for renderer, folder in (("official", "attempt-04"), ("plain", "attempt-05-plain")):
        directory = root / folder
        rows = captures(directory)
        controls[renderer] = dict(receiptSha256=digest(directory / "receipt.json"),
                                  logging="Official config" if renderer == "official" else "Only the two native263 plain stack renderers")
        for name in ("console.log", "latest.log", "debug.log"):
            for kind, count in diagnostics.COUNTS.items():
                # The official extended renderer itself faults for transport traces.
                # Those faults stay rejected, and are not a transport-waiver fixture.
                if renderer == "official" and kind == "transport":
                    continue
                first = [row for _, row in diagnostics.selected(rows["fresh", name], kind)]
                second = [row for _, row in diagnostics.selected(rows["restart", name], kind)]
                require(first == second, "Actual cold boots differ in " + renderer + "/" + name + "/" + kind)
                require(len(first) == (count if kind == "host" or name == "debug.log" else 0),
                        "Unexpected actual native platform sequence count")
                if first and not any(entry["kind"] == kind and entry["blocks"] == first for entry in sequences):
                    sequences.append(dict(kind=kind, renderer=renderer, appender=name, blocks=first))
    write_json(output, dict(engineProtocol="NATIVE66", forge="66.0.0", minecraft="26.3",
                            controls=controls, serverJarSha256=SERVER_JAR_SHA, sequences=sequences,
                            clientQualification=False, gameplayQualification=False))
    return digest(output)


def seal(root, controls, libraries, output):
    root, controls, libraries, output = map(Path, (root, controls, libraries, output))
    require(not output.exists(), "Do not overwrite an engine proof")
    primary.verify_sources(SOURCES)
    diagnostics.require_controls()
    files = {SOURCES, diagnostics.SERVER_FIXTURE}
    loaded = native.read_json(controls / "inputs.json")
    for row in loaded["sources"] + loaded["archives"]:
        native.validate_hash(row["path"], row["sha256"])
        files.add(Path(row["path"]))
    native.validate_hash(controls / "NativeSystemReportPlatformControl/receipt.json", diagnostics.HOST_CONTROL_SHA)
    source_entries = {}
    for name, checksum in {**primary.PRIMARY_ARCHIVES, **TRANSPORT_SOURCES}.items():
        path = libraries / name
        native.validate_hash(path, checksum)
        files.add(path)
        with ZipFile(path) as archive:
            names = (["io/netty/channel/kqueue/KQueue.java", "io/netty/channel/kqueue/Native.java"] if "kqueue" in name
                     else ["io/netty/channel/epoll/Epoll.java", "io/netty/channel/epoll/Native.java"] if "epoll" in name
                     else ["io/netty/util/internal/PlatformDependent0.java"] if name.startswith("netty")
                     else ["oshi/util/platform/windows/PerfCounterQuery.java"])
            source_entries[name] = {key: hashlib.sha256(archive.read(key)).hexdigest().upper() for key in names}
    actual_inventory = native.read_json(root / "attempt-04/input-code-inventory.json")
    expected_binaries = {Path(name).name: row["sha256"] for name, row in actual_inventory["files"].items()}
    mandatory = {"netty-common-4.2.16.Final.jar", "oshi-core-6.9.0.jar",
                 "netty-transport-classes-kqueue-4.2.16.Final.jar", "netty-transport-classes-epoll-4.2.16.Final.jar"}
    matched = set()
    for row in loaded["archives"]:
        name = Path(row["path"]).name
        if name in expected_binaries:
            require(row["sha256"] == expected_binaries[name], "Actual control/server platform binary differs: " + name)
            matched.add(name)
    require(mandatory <= matched, "Missing actual native transport/OSHI binary reproduction")
    transport = root.parent / "harness/netty-transport-02-plain"
    command = native.read_json(transport / "command.json")
    require(native.read_json(transport / "exit.json")["exitCode"] == 0, "Independent transport control failed")
    native.validate_hash(native.RIG / "platform-controls/NativeNettyTransportPlatformControl.java", command["sourceSha256"])
    observation = native.read_json(transport / "result/receipt.json")
    for name, platform in (("KQueue", "OSX/BSD"), ("Epoll", "Linux")):
        row = observation["transports"][name]
        require(row["available"] is False and row["cause"] == "java.lang.ExceptionInInitializerError"
                and row["rootCause"] == "java.lang.IllegalStateException: Only supported on " + platform
                and row["archiveSha256"] == expected_binaries[Path(row["archive"]).name],
                "Independent native Windows transport reproduction differs")
    files.add(native.RIG / "platform-controls/NativeNettyTransportPlatformControl.java")
    files.update(path for path in transport.rglob("*") if path.is_file())
    actual_classes = {}
    for folder in ("attempt-04", "attempt-05-plain"):
        captures(root / folder)
        archive_path = root / folder / "runtime/libraries/net/minecraftforge/forge/26.3-66.0.0/forge-26.3-66.0.0-server.jar"
        native.validate_hash(archive_path, SERVER_JAR_SHA)
        files.add(archive_path)
        with ZipFile(archive_path) as archive:
            classes = {key: hashlib.sha256(archive.read(key)).hexdigest().upper() for key in (
                "net/minecraft/SharedConstants.class", "net/minecraft/SystemReport.class", "net/minecraft/server/MinecraftServer.class")}
        require(not actual_classes or actual_classes == classes, "Actual server native class bytes differ")
        actual_classes = classes
        for path in (root / folder).rglob("*"):
            if path.is_file() and "runtime" not in path.relative_to(root / folder).parts:
                files.add(path)
        for phase in ("fresh", "restart"):
            for name in ("console.log", "latest.log", "debug.log"):
                path = root / folder / phase / name
                errors, notes = diagnostics.classify(path.read_text(encoding="utf-8-sig"))
                if folder == "attempt-04" and name == "console.log":
                    require(any("AppenderLoggingException" in error for error in errors),
                            "The retained official extended-renderer faults must remain rejected")
                else:
                    require(not errors and len(notes) == ((9 if folder == "attempt-04" else 11) if name == "debug.log" else 5),
                            "Current classifier does not exactly accept the actual native-only capture: " + str(path))
    files.update(path for path in controls.rglob("*") if path.is_file())
    source = {Path(module.__file__).resolve() for module in tuple(sys.modules.values())
              if getattr(module, "__file__", None) and Path(module.__file__).suffix == ".py"
              and Path(module.__file__).resolve().parent == Path(__file__).resolve().parent
              and not Path(module.__file__).name.startswith("test_")}
    proof = dict(status="STRICT_CLASSIFIER_AND_ORDERED_COLDSTOP_READY", engineProtocol="NATIVE66",
                 scope="INDEPENDENT_FORGE_ONLY_SERVER_DIAGNOSTICS_NOT_MACHINE_OPERATION_OR_CLIENT",
                 qualificationAllowed=False, clientClassification="CLOSED", debugNotSuppressed=True,
                 noJvmCompatibilityFlagsAdded=True, harnessPlainRendererActuallyReproduced=True,
                 officialExtendedRendererFaultsRemainRejected=True,
                 coreSha256=digest(Path(native.__file__)), classifierSha256=digest(Path(diagnostics.__file__)),
                 actualPackagedServerClassSha256=actual_classes, primarySdkSources=primary.SOURCE_PINS,
                 primaryPlatformSources=source_entries,
                 sourceProvenance="Official SDK source archive and actual packaged class bytes pinned separately; SDK recompile class bytes are not asserted identical",
                 proofFiles={str(path.resolve()): digest(path) for path in sorted(files)},
                 sourceClosure=[dict(path=str(path), sha256=digest(path)) for path in sorted(source)])
    write_json(output, proof)
    return proof


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("capture", "seal"))
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--controls", type=Path)
    parser.add_argument("--primary-sources", type=Path)
    args = parser.parse_args()
    print(capture_fixture(args.root, args.output) if args.command == "capture" else
          seal(args.root, args.controls, args.primary_sources, args.output)["status"])
