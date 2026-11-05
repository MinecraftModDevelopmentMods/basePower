"""Independently reconcile the native Steam endurance ledger and its saved endpoint."""

import argparse
import math
from pathlib import Path
import struct

import nbtlib

from native112_machine_operations import validate_restart
from native112_lifecycle import read_json
from rig_transport import digest
from steam_control_audit import require

TICKS = 36000
TICKS_PER_CRUSH = 125
STEAM_PER_TICK = 1.5
MAX_IDLE_PER_TICK = 9 / 16


def audit(runtime):
    names = ["native112-machine-operation-results.json", "advantage-works-results/machine-operation-measured.json",
             "machine-operation-fixture.json", "advantage-works-results/machine-inspection-restarted.json"]
    paths = [runtime / name for name in names]
    hashes = [digest(path) for path in paths]
    suite, measured, fixture, cold = map(read_json, paths)
    require(suite["status"] == "PASS" and suite["protocolUnchanged"]
            and digest(Path(suite["manifest"])) == suite["manifestSha256"],
            "Underlying native endurance qualification is not a pass")
    require(suite["group"] == "endurance" and measured["held"] and len(measured["cases"]) == 1,
            "Expected exactly one held, full-duration endurance case")
    require(validate_restart(fixture, cold)["status"] == "PASS", "Independent cold comparison failed")
    case = measured["cases"][0]
    require(case["id"] == "steam/lifecycle/network-endurance-30min" and case["status"] == "PASS",
            "Wrong or failed endurance witness")
    require(case["finishTick"] - case["startTick"] == case["lifecycle.measuredTicks"] == TICKS,
            "Not exactly 36,000 natural ticks")
    seconds = case["lifecycle.measuredWallSeconds"]
    require(math.isfinite(seconds) and seconds >= 1799, "Insufficient first-to-last wall time")
    require(case["lifecycle.wallStartMeasuredTick"] == 1 and case["lifecycle.wallEndMeasuredTick"] == TICKS,
            "The wall clock was not tied to the full measurement window")
    frames = case["lifecycle.checkpoints"]
    batches = case["lifecycle.completedBatches"]
    require([frame["tick"] for frame in frames] == list(range(0, TICKS + 1, 1000)), "Missing checkpoints")
    for frame in frames + batches:
        require(frame["inputs"] + frame["products"] == 320, "Lost or created items")
        require(len(frame["sourceSteam"]) == 8 and all(0 <= amount <= 10000 for amount in frame["sourceSteam"]),
                "Invalid finite steam stock")
        require(sum(frame["sourceSteam"]) + frame["machineSteam"] == frame["totalSteam"],
                "Incorrect steam ledger total")
    for ordinal, batch in enumerate(batches, 1):
        require(batch["products"] == batch["ordinal"] == ordinal and batch["progress"] == 0,
                "Invalid batch order")
        if ordinal > 1:
            require(batch["tick"] - batches[ordinal - 2]["tick"] == TICKS_PER_CRUSH,
                    "Processing stalled after warmup")
    before, after = frames[0], frames[-1]
    require(before["totalSteam"] == 80000 and before["inputs"] == 320 and before["products"] == 0,
            "Wrong starting stock")
    paid = after["products"] * TICKS_PER_CRUSH + after["progress"]
    idle = before["totalSteam"] - after["totalSteam"] - paid * STEAM_PER_TICK
    require(after["products"] == len(batches) == 287 and case["lifecycle.lostProgressTicks"] == 0,
            "Expected 287 completed batches with no lost progress")
    require(paid == case["lifecycle.paidWorkTicks"] and 0 <= idle <= TICKS * MAX_IDLE_PER_TICK
            and idle == case["lifecycle.idleSteamLoss"], "Steam accounting does not reconcile")
    raw = [(row["block"], nbtlib.parse_nbt(row["nbt"])) for row in case["after"] if "nbt" in row]
    steam = sum(struct.unpack(">f", struct.pack(">i", int(data["Energy"][0])))[0]
                for block, data in raw if block in ("basesteam:steam_crusher", "basesteam:steam_tank"))
    crusher, = (data for block, data in raw if block == "basesteam:steam_crusher")
    stacks = [entry for _, data in raw for entry in data.get("Items", ())]
    stocks = {id: sum(int(entry["Count"]) for entry in stacks if str(entry["id"]) == id)
              for id in ("minecraft:stone", "minecraft:cobblestone")}
    require(steam == after["totalSteam"] and int(crusher["progress"]) == after["progress"]
            and stocks == {"minecraft:stone": after["inputs"], "minecraft:cobblestone": after["products"]},
            "Ledger does not match actual end-of-run machine and chest NBT")
    require(hashes == [digest(path) for path in paths], "Evidence changed during the audit")
    return dict(status="PASS", ticks=TICKS, seconds=seconds, completed=after["products"], paidTicks=paid,
                remainingSteam=steam, idleSteamLoss=idle, manifestSha256=suite["manifestSha256"],
                inputSha256=dict(zip(names, hashes)))


if __name__ == "__main__":
    import json
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = audit(args.runtime)
    with args.output.open("x", encoding="utf-8") as stream:
        json.dump(result, stream, indent=2, sort_keys=True, allow_nan=False)
    print(json.dumps(result, indent=2, sort_keys=True, allow_nan=False))
