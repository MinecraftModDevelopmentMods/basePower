"""Run native harvest, lifecycle and cross-mod oil checks without altering the old oracles."""

import argparse
from pathlib import Path
import traceback

import lifecycle
from matrix import digest, fingerprint, write_json
from native117 import output_path, require_disjoint
from native117_actor_io import bind
from native117_identity import fixture_ids
from native117_lifecycle import NativeLifecycleRun, authorize_lifecycle, exercise
from native117_oil import NativeOilRun, exercise as exercise_oil, fixtures as oil_fixtures
from native12111_networks import compatibility_view, native_inventory_item
import native1201_machines
import native12111 as native


MINERALOGY = dict(id="mineralogy", version="6.1.4.121111",
                  sourceRef="d27b800100213c8166a24a7196adf22d05a99bab",
                  file="Mineralogy-6.1.4.121111.jar",
                  sha256="2ADE7BEE0ACD5B1463752B3D14A01E27D3FFA5448E9407BA8F41DFAFBCA0D954")


def oil_pins(entries):
    pins = {"jars": [dict(id=key, version=value[0], path=str(value[1]), sha256=value[2])
                     for key, value in entries.items()]}
    for pin in pins["jars"]:
        if pin["id"] == "mineralogy":
            if pin["version"] != MINERALOGY["version"] or pin["sha256"].upper() != MINERALOGY["sha256"]:
                raise ValueError("Wrong Mineralogy oil input")
            pin["sourceRef"] = MINERALOGY["sourceRef"]
    return pins


def native_boot(path):
    native.require_clean_log(path)
    content = path.read_text(encoding="utf-8")
    for marker in (f"Starting minecraft server version {native.MINECRAFT}",
                   f"Forge mod loading, version {native.FORGE}, for MC {native.MINECRAFT} with MCP {native.MCP}"):
        if marker not in content:
            raise AssertionError("Missing native machine startup proof: " + marker)


class LifecycleRun(NativeLifecycleRun):
    minecraft = native.MINECRAFT
    max_ticket_distance = 11
    require_boot = staticmethod(native_boot)

    def open_server(self, label):
        native.audit_staged(self.runtime)
        return native.Server(self.runtime, self.java, self.launcher, label)

    def inventory(self, pos, slot, item, count):
        coordinates = " ".join(map(str, pos))
        self.command(f"item replace block {coordinates} container.{slot} with {native_inventory_item(item)} {count}")

    def probe(self, operation, label):
        return compatibility_view(super().probe(operation, label))


class OilRun(NativeOilRun):
    expected_launcher = native.LAUNCHER
    max_ticket_distance = 11
    require_boot = staticmethod(native_boot)

    def open_server(self, label):
        native.audit_staged(self.runtime)
        return native.Server(self.runtime, self.java, self.launcher, label)


harvest = bind(native1201_machines.harvest, native1201=native)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("profile", "seed", "inventory", "java", "output"):
        parser.add_argument("--" + name, required=True, type=lambda value: Path(value).resolve())
    parser.add_argument("--inventory-sha256", required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--campaign", choices=("harvest", "oil", "lifecycle"), required=True)
    args = parser.parse_args()
    target = native.read_family_target(args.profile, args.seed, args.inventory, args.inventory_sha256)
    java, version = native.java21(args.java)
    output = output_path(args.output)
    for path in (args.profile, args.seed, args.inventory, java, *(entry[1] for entry in target[2].values())):
        require_disjoint(output, path)
    seed_before = fingerprint(args.seed)
    expected = native.stage(output, *target, args.port)
    receipt = output / f"native12111-{args.campaign}-results.json"
    report = dict(status="RUNNING", minecraft=native.MINECRAFT, forge=native.FORGE, jars=expected,
                  profileSha256=digest(args.profile), inventorySha256=digest(args.inventory),
                  javaSha256=digest(java), javaVersion=version)

    def progress(observations):
        report["observations"] = observations
        write_json(receipt, report)

    runner = None
    try:
        if args.campaign == "harvest":
            result = harvest(output, java, native.LAUNCHER, {}, progress)
        elif args.campaign == "oil":
            pins = oil_pins(target[2])
            definition = oil_fixtures(pins, mineralogy_version=MINERALOGY["version"],
                                      mineralogy_source=MINERALOGY["sourceRef"])
            write_json(output / "lifecycle-fixture.json", definition)
            authorize_lifecycle(output)
            runner = OilRun(output, java, native.LAUNCHER, definition, pins, seed_before)
            exercise_oil(runner, 8, progress)
            result = dict(report["observations"], laneCount=len(definition), status="PASS")
        else:
            definition = fixture_ids(lifecycle.fixtures())
            write_json(output / "lifecycle-fixture.json", definition)
            authorize_lifecycle(output)
            runner = LifecycleRun(output, java, native.LAUNCHER, definition)
            result = exercise(runner, "fresh1211", progress)
        report.update(status=result["status"], observations=result)
    except BaseException as failure:
        report.update(status="FAIL", failure=repr(failure), traceback=traceback.format_exc())
        raise
    finally:
        try:
            if runner is not None:
                runner.close()
            native.verify_runtime(output, target[1], expected)
            report["seedUnchanged"] = fingerprint(args.seed) == seed_before
            if not report["seedUnchanged"]:
                raise AssertionError("Protected server seed changed")
        except BaseException as failure:
            report.update(status="FAIL", custodyError=repr(failure))
            raise
        finally:
            write_json(receipt, report)
    return int(report["status"] != "PASS")


if __name__ == "__main__":
    raise SystemExit(main())
