"""Run the original partial-network, bridge and native interop oracles on Forge 47."""

import argparse
from pathlib import Path
import traceback

from matrix import digest, fingerprint, write_json
from native117 import output_path, require_disjoint
import native117_extended
from native117_geometry import FORCED_LEVEL, FULL_LEVEL
from native117_holder_networks import bridge_fixtures, extended_fixtures, linear_fixtures
from native117_interop import mark_runtime, require_checks, require_probe_order
from native117_lifecycle import authorize_lifecycle, read_json
from native117_partial import exercise_bridge, exercise_partial
from native117_readiness import await_ready
from native117_ticket_networks import NativeTicketExtendedRun
import native1201
from native1201_machines import LifecycleRun


class PartialRun(NativeTicketExtendedRun, LifecycleRun):
    pass


HOLDER_RADIUS = FULL_LEVEL + PartialRun.max_ticket_distance - FORCED_LEVEL


def interop(runtime, launcher, java, progress):
    mark_runtime(runtime)
    observations = {}
    for phase in ("exercise", "restored"):
        server = native1201.Server(runtime, java, launcher, phase)
        try:
            server.send("forceload add 1023 1023 1100 1030")
            ready = await_ready(server, runtime, (63, 63, 68, 64), "interop-" + phase,
                                minecraft=native1201.MINECRAFT)
            server.send("advinterop " + phase)
            server.wait("ADVINTEROP COMPLETE " + phase, 120)
            report = read_json(runtime / "advantage-works-results" / f"native-interop-{phase}.json")
            observations[phase] = dict(observation=report, readiness=ready)
            require_probe_order(report, ready)
            require_checks(report, phase)
            server.send("save-all flush")
            server.wait("Saved the game", 30)
        finally:
            server.close()
        progress(observations)
    return dict(observations, status="PASS")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("profile", "seed", "inventory", "output", "java17"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--inventory-sha256", required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--campaign", choices=("partial", "bridge", "extended", "interop"), required=True)
    args = parser.parse_args()
    target = native1201.read_target(args.profile, args.seed, args.inventory, args.inventory_sha256)
    runtime = output_path(args.output)
    for path in (args.profile, args.seed, args.inventory, *(item["path"] for item in target[2].values())):
        require_disjoint(runtime, path)
    protected = fingerprint(target[0])
    launcher = native1201.stage(runtime, target, args.port)
    report = dict(status="RUNNING", minecraft=native1201.MINECRAFT, forge=native1201.FORGE,
                  profileSha256=digest(args.profile), inventorySha256=digest(args.inventory),
                  javaSha256=digest(args.java17), runnerSha256=digest(Path(__file__)))
    receipt = runtime / f"native1201-{args.campaign}-results.json"

    def progress(value):
        report["observations"] = value
        write_json(receipt, report)

    runner = None
    try:
        if args.campaign == "interop":
            result = interop(runtime, launcher, args.java17, progress)
        else:
            layout, exercise = {
                "partial": (linear_fixtures, exercise_partial),
                "bridge": (bridge_fixtures, exercise_bridge),
                "extended": (extended_fixtures, native117_extended.exercise),
            }[args.campaign]
            definition = layout(HOLDER_RADIUS)
            write_json(runtime / "lifecycle-fixture.json", definition)
            authorize_lifecycle(runtime)
            runner = PartialRun(runtime, args.java17, launcher, definition)
            result = exercise(runner, progress)
        report.update(status=result["status"], observations=result)
    except Exception as error:
        report.update(status="FAIL", failure=repr(error), traceback=traceback.format_exc())
        raise
    finally:
        try:
            if runner is not None:
                runner.close()
            native1201.verify_runtime(runtime)
            report["seedUnchanged"] = fingerprint(target[0]) == protected
            if not report["seedUnchanged"]:
                raise AssertionError("Protected server seed changed during the test")
        except Exception as error:
            report.update(status="FAIL", auditError=repr(error))
            raise
        finally:
            write_json(receipt, report)
    return int(report["status"] != "PASS")


if __name__ == "__main__":
    raise SystemExit(main())
