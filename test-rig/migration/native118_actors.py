"""Check retained player and dimension fixtures on the pinned Forge 40 server."""

import argparse
from functools import partial
from pathlib import Path
import socket
import traceback

from matrix import digest, fingerprint, write_json
from native117 import output_path, require_disjoint
from native117_actors import run_route
from native117_broad import read_json
from native117_stopped_entities import audit_stopped
import native118


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("fixture", "fixture-receipt", "profile", "seed", "inventory", "java17", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--fixture-receipt-sha256", required=True)
    parser.add_argument("--inventory-sha256", required=True)
    parser.add_argument("--kind", choices=("actors", "dimensions"), required=True)
    parser.add_argument("--port", type=int, required=True)
    args = parser.parse_args()
    output = output_path(args.output)
    source = args.fixture.resolve()
    for path in (source, args.fixture_receipt, args.profile, args.seed, args.inventory):
        require_disjoint(output, path)
    if digest(args.fixture_receipt) != args.fixture_receipt_sha256.upper():
        raise AssertionError("Historical fixture receipt has changed")
    approved = read_json(args.fixture_receipt)
    before = fingerprint(source)
    if Path(approved["source"]).resolve() != source or approved["sourceFingerprint"] != before:
        raise AssertionError("Historical fixture no longer matches its approved source")
    target = native118.read_target(args.profile, args.seed, args.inventory, args.inventory_sha256)
    with socket.socket() as port:
        port.bind(("127.0.0.1", args.port))
    output.mkdir()
    result = dict(status="RUNNING", kind=args.kind, source=str(source), realClientLogin="NOT_TESTED",
                  fixtureReceiptSha256=digest(args.fixture_receipt), profileSha256=digest(args.profile),
                  inventorySha256=digest(args.inventory), checks={})
    report = output / "native118-actors-results.json"
    write_json(report, result)
    try:
        args.target_manifest = args.profile
        result["checks"] = run_route(
            source, output / "server", target, args, stage=native118.stage, server_type=native118.Server,
            stop_audit=partial(audit_stopped, minecraft_version=native118.MINECRAFT, data_version=2975,
                               stopping_phase="ServerStoppingEvent"),
            check_log=native118.require_native_boot, minecraft=native118.MINECRAFT)
        result["status"] = "PASS" if all(c["status"] == "PASS" for c in result["checks"].values()) else "FAIL"
    except Exception:
        result.update(status="FAIL", error=traceback.format_exc())
        raise
    finally:
        result["sourceUnchanged"] = fingerprint(source) == before
        if not result["sourceUnchanged"]:
            result["status"] = "FAIL"
        write_json(report, result)
    return int(result["status"] != "PASS")


if __name__ == "__main__":
    raise SystemExit(main())
