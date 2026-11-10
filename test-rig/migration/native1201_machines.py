"""Exercise 1.20.1 machines using the historical lifecycle and harvest oracles."""

import argparse
from pathlib import Path
import traceback

import lifecycle
from matrix import digest, fingerprint, write_json
from native117 import jar_identity, output_path, require_disjoint
from native117_harvest import HARVEST_CHUNKS, mark_runtime, require_checks, require_isolation
from native117_identity import fixture_ids
from native117_lifecycle import NativeLifecycleRun, authorize_lifecycle, exercise, read_json
from native117_oil import NativeOilRun, exercise as exercise_oil, fixtures as oil_fixtures
from native117_readiness import await_ready
import native1201

MINERALOGY = {
    "id": "mineralogy",
    "version": "6.1.4.120011",
    "sourceRef": "3826585128a53dbbd94e05e025ddb32a693ae428",
    "file": "Mineralogy-6.1.4.120011.jar",
    "sha256": "1172C7FF424BCE10E3EAC7A790353CAB35E5A5F06EBB019B31BED05B90ED8A5F",
}


class LifecycleRun(NativeLifecycleRun):
    minecraft = native1201.MINECRAFT
    max_ticket_distance = 12
    require_boot = staticmethod(native1201.require_native_boot)

    def open_server(self, label):
        return native1201.Server(self.runtime, self.java, self.launcher, label)


class OilRun(NativeOilRun):
    expected_launcher = native1201.LAUNCHER
    require_boot = staticmethod(native1201.require_native_boot)

    def open_server(self, label):
        return native1201.Server(self.runtime, self.java, self.launcher, label)


def harvest(runtime, java, launcher, report, progress):
    mark_runtime(runtime)
    for label in ("exercise", "restored"):
        server = native1201.Server(runtime, java, launcher, label)
        try:
            server.send("forceload add 1198 1022 1202 1026")
            ready = await_ready(server, runtime, HARVEST_CHUNKS, "harvest-" + label,
                                minecraft=native1201.MINECRAFT)
            server.send("advharvest " + label)
            server.wait("ADVHARVEST COMPLETE " + label, 120)
            document = read_json(runtime / "advantage-works-results" / f"native-harvest-{label}.json")
            if (type(document.get("worldTime")) is not int
                    or document["worldTime"] < ready["completedWorldTime"]):
                raise AssertionError("Harvest preceded native chunk/entity readiness")
            report[label] = {"observation": document, "readiness": ready}
            require_checks(document, label, minecraft=native1201.MINECRAFT)
            require_isolation(document)
            server.send("save-all flush")
            server.wait("Saved the game", 30)
        finally:
            server.close()
        progress(report)
    return dict(report, status="PASS")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("profile", "seed", "inventory", "output", "java17"):
        parser.add_argument("--" + name, required=True, type=Path)
    parser.add_argument("--inventory-sha256", required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--campaign", choices=("lifecycle", "harvest", "oil"), required=True)
    parser.add_argument("--mineralogy-jar", type=Path, help="Include the pinned Mineralogy build in the oil test")
    args = parser.parse_args()
    target = native1201.read_target(args.profile, args.seed, args.inventory, args.inventory_sha256)
    if args.mineralogy_jar:
        jar = args.mineralogy_jar.resolve()
        if args.campaign != "oil" or jar.name != MINERALOGY["file"] or digest(jar) != MINERALOGY["sha256"]:
            raise ValueError("Mineralogy oil test requires the pinned player jar")
        jar_identity(jar, MINERALOGY["id"], MINERALOGY["version"])
        target[2]["mineralogy"] = dict(MINERALOGY, path=jar)
    output = output_path(args.output)
    for path in (args.profile, args.seed, args.inventory, *(item["path"] for item in target[2].values())):
        require_disjoint(output, path)
    helpers = [Path(__file__), Path(lifecycle.__file__),
               Path(__file__).with_name(f"native117_{args.campaign}.py")]
    helper_hashes = {path.name: digest(path) for path in helpers}
    seed_hashes = fingerprint(target[0])
    launcher = native1201.stage(output, target, args.port)
    path = output / f"native1201-{args.campaign}-results.json"
    report = dict(status="RUNNING", minecraft=native1201.MINECRAFT, forge=native1201.FORGE,
                  profileSha256=digest(args.profile), inventorySha256=digest(args.inventory),
                  javaSha256=digest(args.java17), helperSha256=helper_hashes)

    def progress(values):
        report["observations"] = values
        write_json(path, report)

    runner = None
    try:
        if args.campaign == "harvest":
            result = harvest(output, args.java17, launcher, {}, progress)
        elif args.campaign == "oil":
            pins = {"jars": list(target[2].values())}
            definition = oil_fixtures(pins, mineralogy_version=MINERALOGY["version"],
                                      mineralogy_source=MINERALOGY["sourceRef"])
            write_json(output / "lifecycle-fixture.json", definition)
            authorize_lifecycle(output)
            runner = OilRun(output, args.java17, launcher, definition, pins, seed_hashes)
            exercise_oil(runner, 8, progress)
            result = dict(report["observations"], laneCount=len(definition), status="PASS")
        else:
            definition = fixture_ids(lifecycle.fixtures())
            write_json(output / "lifecycle-fixture.json", definition)
            authorize_lifecycle(output)
            runner = LifecycleRun(output, args.java17, launcher, definition)
            result = exercise(runner, "fresh1201", progress)
        report.update(status=result["status"], observations=result)
    except Exception as error:
        report.update(status="FAIL", failure=repr(error), traceback=traceback.format_exc())
        raise
    finally:
        try:
            if runner is not None:
                runner.close()
            native1201.verify_runtime(output)
            report["seedUnchanged"] = fingerprint(target[0]) == seed_hashes
            report["helpersUnchanged"] = {path.name: digest(path) for path in helpers} == helper_hashes
            if not report["seedUnchanged"] or not report["helpersUnchanged"]:
                raise AssertionError("Protected server seed or test code changed during the test")
        except Exception as error:
            report.update(status="FAIL", auditError=repr(error))
            raise
        finally:
            write_json(path, report)
    return int(report["status"] != "PASS")


if __name__ == "__main__":
    raise SystemExit(main())
