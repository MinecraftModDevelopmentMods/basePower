"""Native26 capability, locked automation, portable tank and survival oil-bucket checks."""

from pathlib import Path
import traceback

from matrix import digest, write_json
from native117_interop import require_checks, require_probe_order
from native117_readiness import await_ready
import native117_interop as contracts
import native12111 as previous
import native262 as native
from native262_resources import arguments, preflight


RECEIPT = "native262-interop-results.json"


def validate_observation(document, phase, readiness):
    require_probe_order(document, readiness)
    require_checks(document, phase)
    previous.require_shared_crushing(document)


def run(args):
    if not 1024 <= args.port < 65535:
        raise ValueError("Use an unprivileged localhost port")
    inputs = preflight(args, [Path(__file__), Path(previous.__file__), Path(contracts.__file__)])
    if args.preflight_only:
        return dict(status="READY_NOT_RUN", qualificationAllowed=False, **inputs.receipt())
    runtime = args.output.resolve()
    expected = native.stage(runtime, *inputs.target, args.port)
    (runtime / "advantage-native-oil-bucket.allow").touch()
    report = dict(status="RUNNING", scope="PACKAGED_NATIVE_INTERFACES_AND_CONTAINER_RESTART",
                  qualificationStatus="EXPLORATORY_UNQUALIFIED", phases={}, profileSha256=digest(args.profile),
                  inventorySha256=digest(args.inventory), jars=expected, **inputs.receipt())
    receipt = runtime / RECEIPT
    write_json(receipt, report)
    server = None
    try:
        for phase in ("exercise", "restored"):
            native.verify_runtime(runtime, inputs.target[1], expected)
            server = native.Server(runtime, inputs.java, native.LAUNCHER, phase)
            server.send("forceload add 1023 1023 1100 1030")
            ready = await_ready(server, runtime, (63, 63, 68, 64), f"interop-{phase}", minecraft=native.MINECRAFT)
            server.send(f"advinterop {phase}")
            server.wait(f"ADVINTEROP COMPLETE {phase}", 120)
            document = native.read_json(runtime / "advantage-works-results" / f"native-interop-{phase}.json")
            report["phases"][phase] = dict(readiness=ready, observation=document)
            validate_observation(document, phase, ready)
            if phase == "exercise":
                server.send("advoilbuckets")
                server.wait("ADVOILBUCKETS COMPLETE", 120)
                buckets = native.read_json(runtime / "advantage-works-results/native-oil-buckets.json")
                report["phases"][phase]["oilBuckets"] = buckets
                previous.require_oil_buckets(buckets, ready)
            server.send("save-all flush")
            server.wait("Saved the game", 30)
            server.close()
            server = None
            write_json(receipt, report)
        report["status"] = "PASS"
    except BaseException as failure:
        report.update(status="FAIL", failure=repr(failure), traceback=traceback.format_exc())
        raise
    finally:
        try:
            if server is not None:
                server.close()
            native.verify_runtime(runtime, inputs.target[1], expected)
            report["inputsUnchanged"] = inputs.unchanged()
        except BaseException as failure:
            report.update(status="FAIL", shutdownOrCustodyError=repr(failure))
            raise
        finally:
            write_json(receipt, report)
    return report


def main():
    args = arguments(__doc__).parse_args()
    result = run(args)
    print(result["status"], flush=True)
    return int(result["status"] not in ("PASS", "READY_NOT_RUN"))


if __name__ == "__main__":
    raise SystemExit(main())
