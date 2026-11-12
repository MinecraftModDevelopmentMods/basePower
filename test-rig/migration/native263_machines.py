"""Native66 harvest and lifecycle transport; retain historical machine oracles."""

import traceback
from types import SimpleNamespace

import lifecycle
from matrix import digest, write_json
from native117_actor_io import bind
from native117_identity import fixture_ids
from native117_lifecycle import NativeLifecycleRun, authorize_lifecycle, exercise
import native1201_machines
import native263 as native
from native263_networks import (MAX_TICKET_DISTANCE, Server, arguments, compatibility_view,
                                native_boot, native_inventory_item, prepare_inputs, validate_snapshot)


class LifecycleRun(NativeLifecycleRun):
    minecraft = native.MINECRAFT
    max_ticket_distance = MAX_TICKET_DISTANCE
    require_boot = staticmethod(native_boot)

    def __init__(self, *args):
        super().__init__(*args)
        self.pids = []

    def open_server(self, label):
        server = Server(self.runtime, self.java, self.launcher, label)
        self.pids.append(server.process.pid)
        return server

    def inventory(self, pos, slot, item, count):
        coordinates = " ".join(map(str, pos))
        self.command(f"item replace block {coordinates} container.{slot} with {native_inventory_item(item)} {count}")

    def _raw_probe(self, operation, label):
        return validate_snapshot(super()._raw_probe(operation, label))

    def probe(self, operation, label):
        return compatibility_view(super().probe(operation, label))


harvest = bind(native1201_machines.harvest, native1201=SimpleNamespace(
    Server=Server, MINECRAFT=native.MINECRAFT, LAUNCHER=native.LAUNCHER))


def run(args):
    inputs = prepare_inputs(args)
    if args.preflight_only:
        return dict(status="READY_NOT_RUN", qualificationAllowed=False, **inputs.receipt())
    runtime = inputs.output
    receipt = runtime / f"native263-{args.campaign}-results.json"
    report = dict(status="RUNNING", campaign=args.campaign, scope="FINITE_HARVEST_AND_PHYSICAL_MACHINE_LIFECYCLE",
                  **inputs.receipt())

    def progress(value):
        report["observations"] = value
        write_json(receipt, report)

    runner, staged = None, False
    try:
        native.stage(runtime, *inputs.target, args.port)
        staged = True
        write_json(receipt, report)
        if args.campaign == "harvest":
            result = harvest(runtime, inputs.java, native.LAUNCHER, {}, progress)
        else:
            definition = fixture_ids(lifecycle.fixtures())
            fixture = runtime / "lifecycle-fixture.json"
            write_json(fixture, definition)
            report["fixtureSha256"] = digest(fixture)
            authorize_lifecycle(runtime)
            runner = LifecycleRun(runtime, inputs.java, native.LAUNCHER, definition)
            result = exercise(runner, "fresh263", progress)
        report.update(status=result["status"], observations=result)
    except BaseException as failure:
        report.update(status="FAIL", failure=repr(failure), traceback=traceback.format_exc())
    finally:
        try:
            if runner is not None:
                runner.close()
                report["processIds"] = runner.pids
                report["processesClosed"] = runner.server is None
            inputs.verify(staged)
            report.update(seedUnchanged=True, inputsUnchanged=True, oraclesUnchanged=True)
        except BaseException as failure:
            report.update(status="FAIL", custodyError=repr(failure), custodyTraceback=traceback.format_exc())
        if runtime.is_dir():
            write_json(receipt, report)
    return report


def main(argv=None):
    parser = arguments(__doc__)
    parser.add_argument("--campaign", choices=("harvest", "lifecycle"), required=True)
    report = run(parser.parse_args(argv))
    print(report["status"], flush=True)
    return int(report["status"] not in ("PASS", "READY_NOT_RUN"))


if __name__ == "__main__":
    raise SystemExit(main())
