"""Prove MMDLib hammer and both live crushers share reloadable recipe decisions."""

import argparse
from pathlib import Path
import traceback

from matrix import WORLD, digest, fingerprint, write_json
from native117 import output_path, require_disjoint
import native1211neo as native


def require_result(document, label, count):
    if (document.get("status"), document.get("label"), document.get("expectedCount"),
            document.get("measuredTicks")) != ("PASS", label, count, 400):
        raise AssertionError(f"Incomplete or failed crushing phase: {document.get('failure', document)}")
    hammer = document.get("hammer", {})
    if hammer.get("item") != "basemetals:iron_crackhammer":
        raise AssertionError("Shared crushing must use the published Base Metals hammer")
    if (hammer.get("outputCount"), hammer.get("inputRemaining"), hammer.get("durabilityDebit")) != (
            count, int(count == 0), int(count != 0)):
        raise AssertionError("Missing finite hammer consumption/durability witness")
    machines = {row["machine"]: row for row in document.get("machines", ())}
    if set(machines) != {"steam", "electricity"}:
        raise AssertionError("Both naturally ticked crusher witnesses are required")
    for name, work in (("steam", 125), ("electricity", 200)):
        row = machines[name]
        if (row.get("inputConsumed"), row.get("outputCount"), row.get("workTicks")) != (
                int(count != 0), count, work if count else 0):
            raise AssertionError(f"Incorrect bounded machine job: {name}: {row}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("profile", "seed", "inventory", "output", "java"):
        parser.add_argument("--" + name, required=True, type=lambda value: Path(value).resolve())
    parser.add_argument("--inventory-sha256", required=True)
    parser.add_argument("--port", required=True, type=int)
    args = parser.parse_args()
    target = native.read_family_target(args.profile, args.seed, args.inventory, args.inventory_sha256)
    java, version = native.java21(args.java)
    runtime = output_path(args.output)
    for path in (args.profile, args.seed, args.inventory, java, *(row[1] for row in target[2].values())):
        require_disjoint(runtime, path)
    before = fingerprint(args.seed)
    expected = native.stage(runtime, *target, args.port)
    (runtime / "migration-operate.allow").touch()
    report = dict(status="RUNNING", loader="neoforge", minecraft=native.MINECRAFT, neoforge=native.NEOFORGE,
                  profileSha256=digest(args.profile), inventorySha256=digest(args.inventory),
                  jars=expected, javaSha256=digest(java), javaVersion=version, phases={})
    receipt = runtime / "native1211neo-crushing-results.json"
    write_json(receipt, report)
    pack = runtime / WORLD / "datapacks/crushing-override"
    recipe = pack / "data/basepower/recipe/crushing_potato_starch.json"
    write_json(pack / "pack.mcmeta", {"pack": {"pack_format": 48, "description": "Bounded shared crushing override"}})
    server = None

    def run(label, count):
        server.send(f"advcrushing {label} {count}")
        server.wait("ADVCRUSHING COMPLETE " + label, 120)
        document = native.read_json(runtime / "advantage-works-results" / f"crushing-interop-{label}.json")
        report["phases"][label] = document
        write_json(receipt, report)
        require_result(document, label, count)
        server.send("save-all flush")
        server.wait("Saved the game", 30)

    def reload():
        server.send("reload")
        server.wait("Reloading!", 60)
        server.wait(" advancements", 120)

    try:
        server = native.Server(runtime, java, native.LAUNCHER, "baseline")
        run("baseline", 1)
        overridden = dict(type="mmdlib:crushing", ingredient={"item": "minecraft:potato"},
                          result={"id": "basepower:starch", "count": 3,
                                  "components": {"minecraft:custom_data": {"CrushingOverrideWitness": "native1211neo"}}})
        write_json(recipe, overridden)
        reload()
        run("override", 3)
        server.close()
        server = native.Server(runtime, java, native.LAUNCHER, "override-restart")
        run("override-restart", 3)
        write_json(recipe, {**overridden, "neoforge:conditions": [{"type": "neoforge:false"}]})
        reload()
        run("removed", 0)
        server.close()
        server = native.Server(runtime, java, native.LAUNCHER, "removed-restart")
        run("removed-restart", 0)
        recipe.unlink()
        reload()
        run("restored", 1)
        server.close()
        server = None
        if len(report["phases"]) != 6:
            raise AssertionError("Incomplete shared crushing campaign")
        report["status"] = "PASS"
    except BaseException as failure:
        report.update(status="FAIL", failure=repr(failure), traceback=traceback.format_exc())
        raise
    finally:
        try:
            if server is not None:
                server.close()
            native.verify_runtime(runtime, target[1], expected)
            report["seedUnchanged"] = fingerprint(args.seed) == before
            if not report["seedUnchanged"]:
                raise AssertionError("Protected server seed changed")
        except BaseException as failure:
            report.update(status="FAIL", auditError=repr(failure))
            raise
        finally:
            write_json(receipt, report)
    return int(report["status"] != "PASS")


if __name__ == "__main__":
    raise SystemExit(main())
