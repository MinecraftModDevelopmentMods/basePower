"""Run the retained twelve-case native death and respawn oracle on Forge 40."""

import argparse
from pathlib import Path

from matrix import digest
from native117 import output_path, require_disjoint
from native117_respawn import read_json, run
import native118


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("source-receipt", "profile", "seed", "inventory", "java17", "output"):
        parser.add_argument("--" + name, required=True, type=Path)
    parser.add_argument("--source-receipt-sha256", required=True)
    parser.add_argument("--inventory-sha256", required=True)
    parser.add_argument("--route", required=True)
    parser.add_argument("--port", required=True, type=int)
    args = parser.parse_args()
    output = output_path(args.output)
    for path in (args.source_receipt, args.profile, args.seed, args.inventory):
        require_disjoint(output, path)
    if digest(args.source_receipt) != args.source_receipt_sha256.upper():
        raise AssertionError("Respawn source receipt has changed")
    record = read_json(args.source_receipt)[args.route]
    source = Path(record["path"]).resolve(strict=True)
    if source.name != args.route or digest(source / "dimension-fixture.json") != record["baseFixtureSha256"]:
        raise AssertionError("Respawn fixture identity or definition differs from prior receipt")
    target = native118.read_target(args.profile, args.seed, args.inventory, args.inventory_sha256)
    args.fixtures, args.routes, args.target_manifest = source.parent, [source.name], args.profile
    return run(args, target=target, stage=native118.stage, server_type=native118.Server,
               check_log=native118.require_native_boot, approved_sources={source.name: record["fingerprint"]})


if __name__ == "__main__":
    raise SystemExit(main())
