"""Finite native bucket/pipe/still/boiler processing over two cold restarts.

Complements, rather than replaces, native12111_machines --campaign oil.
Uses only the frozen harness's public lifecycle commands and vanilla tick gate.
"""

import argparse
from pathlib import Path
import shutil
import time
import traceback

import nbtlib

import lifecycle
from matrix import digest, fingerprint, write_json
from native117 import output_path, require_disjoint
from native117_lifecycle import authorize_lifecycle
from native117_oil import require_document
from native117_readiness import await_ready
import native12111 as native
from native12111_worldgen import MINERALOGY, require


def fixtures():
    result = []
    for namespace in ("basepower", "mineralogy"):
        for kind in ("still", "boiler"):
            x, z = 13312 + 64 * len(result), 2056
            source, drain, pipe = [x + 8, 81, z], [x + 8, 80, z], [x + 9, 80, z]
            machine, gate = [x + 10, 80, z], [x + 10, 81, z]
            result.append(dict(id=namespace + "-" + kind, min=[x, 72, 2048], max=[x + 31, 88, 2079],
                               source=source, drain=drain, pipe=pipe, machine=machine, gate=gate,
                               fluid=namespace + ":crude_oil", bucket=namespace + ":crude_oil_bucket",
                               kind=kind, block="basepower:still" if kind == "still" else "basesteam:steam_boiler_oil",
                               witnesses=[source, drain, pipe, machine, gate]))
    return result


def parse(entry):
    return nbtlib.parse_nbt(entry["nbt"]).unpack()


def tank(tag, key, fluid):
    value = tag[key]  # Missing is not an empty tank.
    require(isinstance(value, dict), "Malformed tank: " + key)
    if value == {"Empty": 1}:
        return 0
    require("Empty" not in value and value["FluidName"] == fluid,
            "Wrong or ambiguous fluid identity: " + repr(value))
    amount = value["Amount"]
    # Still recipes debit FluidStack.amount in place, retaining an explicit zero
    # until a load normalizes it. Identity and Amount must both remain present.
    require(type(amount) is int and 0 <= amount <= 4000, "Invalid tank amount: " + repr(value))
    return amount


def coal_count(tag):
    items = tag["Items"]
    require(isinstance(items, list) and len(items) <= 1, "Unexpected still inventory")
    if not items:
        return 0
    item = items[0]
    require(item["id"] == "minecraft:coal" and item["Slot"] == 0 and item["count"] == 1,
            "Still fuel item changed: " + repr(item))
    return 1


def inspect(snapshot, definition, phase):
    require_document(snapshot, definition)
    require(snapshot["minecraft"] == native.MINECRAFT and snapshot["forge"] == native.FORGE
            and snapshot["station"] == "lifecycle" and type(snapshot["worldTime"]) is int,
            "Wrong native processing snapshot")
    rows = {}
    for f in definition:
        entries = {role: lifecycle.at(snapshot, f["id"], f[role])
                   for role in ("source", "drain", "pipe", "machine", "gate")}
        require(all(e["loaded"] is True for e in entries.values()), "Unloaded processing witness")
        gated = phase == "held" or (phase == "intake" and f["kind"] == "still")
        expected = dict(source="minecraft:air", drain="basepower:fluid_drain", pipe="basepower:fluid_pipe",
                        machine=f["block"], gate="minecraft:redstone_block" if gated else "minecraft:air")
        require(all(entries[role]["block"] == block for role, block in expected.items()),
                "Processing geometry changed: " + f["id"])
        require("nbt" not in entries["pipe"], "Pipe unexpectedly stores fluid")
        drain, machine = parse(entries["drain"]), parse(entries["machine"])
        require(tank(drain, "Tank", f["fluid"]) == 0, "Crude oil stuck in drain")
        require(all(type(entries[role]["tileIdentity"]) is int for role in ("drain", "machine")),
                "Missing live block entity witness")
        if f["kind"] == "still":
            raw, product = tank(machine, "InputTank", f["fluid"]), tank(machine, "Tank", "basepower:refined_oil")
            require(raw + 2 * product == 1000, "Still violated exact 2:1 crude/product conservation")
            fuel = coal_count(machine)
            if phase in ("held", "intake"):
                require((raw, product, fuel, machine["burnTime"]) == (1000, 0, 1, 0),
                        "Redstone held still processed/spent finite resources")
            else:
                require(fuel == 0 and machine["totalBurnTime"] > 0, "Still did not consume its one real coal")
                require(0 < product < 500 if phase == "partial" else (raw, product) == (0, 500),
                        "Still failed finite processing checkpoint")
            rows[f["id"]] = dict(raw=raw, product=product, coal=fuel, burnTime=machine["burnTime"])
        else:
            raw, water = tank(machine, "FuelTank", f["fluid"]), tank(machine, "WaterTank", "minecraft:water")
            steam = entries["machine"]["energy"]["steam"]
            require(type(steam) in (int, float) and 0 <= steam <= 80, "Unexpected steam production")
            if phase in ("held", "intake"):
                require((raw, water, steam) == (1000, 64 if phase == "held" else 0, 0) and "BurnTime" not in machine
                        and "BurnTimeTotal" not in machine, "Held boiler spent fuel/water or produced energy")
                burn = 0
            else:
                require((raw, water, machine["BurnTimeTotal"]) == (900, 0, 500),
                        "Boiler must debit exactly one 100-mB crude aliquot and 64-mB water")
                if phase == "partial":
                    burn = machine["BurnTime"]
                    require(type(burn) is int and 0 < burn < 500 and steam == 64 * 1.25,
                            "Both crude providers must produce exactly 80 steam before idle decay")
                else:
                    require("BurnTime" not in machine, "Boiler has not completed its finite charge")
                    burn = 0
            rows[f["id"]] = dict(raw=raw, water=water, steam=steam, burnTime=burn)
    return rows


def require_decay(before, after, elapsed, *, exact):
    require(type(elapsed) is int and elapsed >= 0, "Processing clock went backwards")
    for key, old in before.items():
        if key.endswith("-boiler"):
            new = after[key]
            idle = max(0, elapsed - old["burnTime"])
            expected = max(0, old["steam"] - idle * 0.0625)
            # Cold chunk loading may skip idle ticks, never create energy.
            require(abs(new["steam"] - expected) < 0.0001 if exact else expected <= new["steam"] <= old["steam"],
                    "Steam was created/lost beyond documented idle decay: " + key)


def require_same_machines(before, after, definition):
    for f in definition:
        for role in ("drain", "machine"):
            old, new = (lifecycle.at(snapshot, f["id"], f[role]) for snapshot in (before, after))
            require(old["tileIdentity"] == new["tileIdentity"], "Processing replaced a live machine")


class ProcessingRun(lifecycle.LifecycleRun):
    def __init__(self, *args):
        super().__init__(*args)
        self.pids = []

    def boot(self, label):
        native.audit_staged(self.runtime)
        self.server = native.Server(self.runtime, self.java, self.launcher, label)
        self.pids.append(self.server.process.pid)
        require(len(set(self.pids)) == len(self.pids), "Cold restart reused a process")
        self.server.send("gamerule doMobSpawning false")
        self.probe("load", label + "-loaded")
        for f in self.definition:
            bounds = [f["min"][0] >> 4, f["min"][2] >> 4, f["max"][0] >> 4, f["max"][2] >> 4]
            await_ready(self.server, self.runtime, bounds, label + "-" + f["id"], minecraft=native.MINECRAFT)
        self.command("tick freeze")
        self.server.wait("The game is frozen", 15)

    def step(self, ticks, label):
        start = self.probe("snapshot", label + "-before")["worldTime"]
        self.command("tick step " + str(ticks))
        deadline = time.monotonic() + ticks / 10 + 30
        attempt = 0
        while time.monotonic() < deadline:
            snapshot = self.probe("snapshot", f"{label}-step-{attempt:03}")
            elapsed = snapshot["worldTime"] - start
            require(0 <= elapsed <= ticks, "Vanilla frozen step exceeded requested ticks")
            if elapsed == ticks:
                return snapshot
            attempt += 1
            time.sleep(0.5)
        raise AssertionError("Vanilla finite tick step timed out")

    def build(self):
        for f in self.definition:
            self.command("fill " + " ".join(map(str, f["min"] + f["max"])) + " minecraft:air replace")
            for role, block in (("gate", "minecraft:redstone_block" if f["kind"] == "still" else "minecraft:air"),
                                ("drain", "basepower:fluid_drain"),
                                ("pipe", "basepower:fluid_pipe"), ("machine", f["block"])):
                self.block(f[role], block)
            x, y, z = f["source"]
            for dx, dz in ((-1, 0), (1, 0), (0, -1), (0, 1)):
                self.block([x + dx, y, z + dz], "minecraft:glass")
            pos = " ".join(map(str, f["machine"]))
            if f["kind"] == "still":
                self.command(f"item replace block {pos} container.0 with minecraft:coal 1")
        empty = self.probe("snapshot", "processing-empty")
        require_document(empty, self.definition)
        for f in self.definition:
            tag = parse(lifecycle.at(empty, f["id"], f["machine"]))
            require(tank(tag, "InputTank" if f["kind"] == "still" else "FuelTank", f["fluid"]) == 0,
                    "Processing fixture contains preexisting oil")
        # This is the only raw-oil injection, through actual native BucketItem use.
        self.probe("place-buckets", "processing-buckets")

    def charge_water_and_hold(self):
        # Redstone blocks native boiler pipe requests as well as new burns.
        # Load its finite crude without water first, then close the gate.
        for f in self.definition:
            if f["kind"] == "boiler":
                self.block(f["gate"], "minecraft:redstone_block")
                pos = " ".join(map(str, f["machine"]))
                self.command(f'data modify block {pos} WaterTank set value {{FluidName:"minecraft:water",Amount:64}}')

    def save_close(self):
        self.command("save-all flush")
        self.server.wait("Saved the game", 30)
        self.close()


def run(args):
    require(1024 <= args.port <= 65535, "Use an unprivileged assigned port")
    native.validate_hash(args.profile, args.profile_sha256)
    target = native.read_family_target(args.profile, args.seed, args.inventory, args.inventory_sha256)
    mineralogy = target[2]["mineralogy"]
    require(mineralogy[0] == MINERALOGY["version"] and mineralogy[2].upper() == MINERALOGY["sha256"],
            "Processing requires the approved separately frozen public Mineralogy jar")
    java, version = native.java21(args.java)
    runtime = output_path(args.output)
    sources = [args.profile, args.inventory, java, Path(__file__).resolve(), *(v[1] for v in target[2].values())]
    for source in [args.seed, *sources]:
        require_disjoint(runtime, source)
    hashes = {str(path): digest(path) for path in sources}
    seed_before = fingerprint(args.seed)
    expected = native.stage(runtime, *target, args.port)
    shutil.copy2(Path(__file__), runtime / "adapter-source.py")
    definition = fixtures()
    write_json(runtime / "lifecycle-fixture.json", definition)
    authorize_lifecycle(runtime)
    runner = ProcessingRun(runtime, java, native.LAUNCHER, definition)
    receipt = runtime / "native12111-oil-processing-results.json"
    report = dict(status="RUNNING", qualificationStatus=native.EXPLORATORY, minecraft=native.MINECRAFT,
                  forge=native.FORGE, inputs=hashes, jars=expected, javaVersion=version,
                  mineralogySourceRef=MINERALOGY["sourceRef"], phases={}, fullProcessRestarts=0)

    def record(label, snapshot, phase):
        rows = inspect(snapshot, definition, phase)
        report["phases"][label] = dict(status="PASS", worldTime=snapshot["worldTime"], cases=rows)
        write_json(receipt, report)
        print(label + ": PASS " + str(rows), flush=True)
        return rows

    failed = False
    try:
        write_json(receipt, report)
        runner.boot("processing-initial")
        runner.build()
        intake = runner.step(160, "processing-intake")
        record("intake-no-water", intake, "intake")
        runner.charge_water_and_hold()
        first = runner.step(80, "processing-held")
        record("held", first, "held")
        require_same_machines(intake, first, definition)
        runner.save_close()

        runner.boot("processing-restart")
        report["fullProcessRestarts"] = 1
        restored = runner.probe("snapshot", "processing-restored-inputs")
        record("restored-inputs", restored, "held")
        for f in definition:
            runner.block(f["gate"], "minecraft:air")
        partial = runner.step(160, "processing-partial")
        early = record("partial", partial, "partial")
        completed = runner.step(440, "processing-complete")
        final = record("complete", completed, "complete")
        require_same_machines(restored, partial, definition)
        require_same_machines(partial, completed, definition)
        require_decay(early, final, completed["worldTime"] - partial["worldTime"], exact=True)
        runner.save_close()

        runner.boot("processing-second-restart")
        report["fullProcessRestarts"] = 2
        retained = runner.probe("snapshot", "processing-retained-products")
        later = record("retained-products", retained, "complete")
        require_decay(final, later, retained["worldTime"] - completed["worldTime"], exact=False)
        runner.save_close()
        report.update(status="PASS", processIds=runner.pids)
    except BaseException as error:
        failed = True
        report.update(status="FAIL", failure=repr(error), traceback=traceback.format_exc())
        raise
    finally:
        try:
            runner.close()
            native.verify_runtime(runtime, target[1], expected)
            report["seedUnchanged"] = fingerprint(args.seed) == seed_before
            require(report["seedUnchanged"], "Protected Forge seed changed")
            require(all(digest(Path(path)) == sha for path, sha in hashes.items()), "Frozen input changed")
        except BaseException as error:
            report.update(status="FAIL", custodyError=repr(error))
            if not failed:
                raise
        finally:
            write_json(receipt, report)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("profile", "seed", "inventory", "java", "output"):
        parser.add_argument("--" + name, required=True, type=lambda value: Path(value).resolve())
    for name in ("profile-sha256", "inventory-sha256"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--port", required=True, type=int)
    print(run(parser.parse_args())["status"], flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
