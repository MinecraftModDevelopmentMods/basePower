import copy
import json
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import native12111_oil_processing as processing


def fluid(name, amount):
    return dict(FluidName=name, Amount=amount) if amount else {"Empty": 1}


def snapshot(phase):
    cases = {}
    for f in processing.fixtures():
        held, partial = phase in ("held", "intake"), phase == "partial"
        drain = dict(Tank=fluid(f["fluid"], 0))
        if f["kind"] == "still":
            raw, product = (1000, 0) if held else (700, 150) if partial else (0, 500)
            machine = dict(InputTank=fluid(f["fluid"], raw), Tank=fluid("basepower:refined_oil", product),
                           Items=[dict(id="minecraft:coal", count=1, Slot=0)] if held else [],
                           burnTime=0 if held else 1000, totalBurnTime=0 if held else 1600)
        else:
            machine = dict(FuelTank=fluid(f["fluid"], 1000 if held else 900),
                           WaterTank=fluid("minecraft:water", 64 if phase == "held" else 0))
            if not held:
                machine["BurnTimeTotal"] = 500
            if partial:
                machine["BurnTime"] = 350
        rows = []
        for role, block in dict(source="minecraft:air", drain="basepower:fluid_drain", pipe="basepower:fluid_pipe",
                                machine=f["block"], gate="minecraft:redstone_block" if phase == "held" or
                                (phase == "intake" and f["kind"] == "still") else "minecraft:air").items():
            row = dict(pos=f[role], loaded=True, block=block)
            if role in ("drain", "machine"):
                row.update(tileIdentity=len(rows), nbt=json.dumps(drain if role == "drain" else machine))
            if role == "machine" and f["kind"] == "boiler":
                row["energy"] = dict(steam=0 if held else 80 if partial else 74.375)
            rows.append(row)
        cases[f["id"]] = rows
    return dict(minecraft=processing.native.MINECRAFT, forge=processing.native.FORGE,
                station="lifecycle", worldTime=160, cases=cases)


class ProcessingOracleTest(unittest.TestCase):
    def test_four_isolated_bounded_actual_bucket_circuits(self):
        fixtures = processing.fixtures()
        self.assertEqual(4, len(fixtures))
        self.assertEqual({"basepower-still", "mineralogy-still", "basepower-boiler", "mineralogy-boiler"},
                         {f["id"] for f in fixtures})
        for f in fixtures:
            self.assertEqual(f["fluid"] + "_bucket", f["bucket"])
            self.assertEqual(5, len({tuple(pos) for pos in f["witnesses"]}))
            self.assertTrue(all(all(lo <= value <= hi for lo, value, hi in zip(f["min"], pos, f["max"]))
                                for pos in f["witnesses"]))
        self.assertEqual(4, len({f["min"][0] for f in fixtures}))

    def test_all_native_processing_checkpoints(self):
        for phase in ("intake", "held", "partial", "complete"):
            with self.subTest(phase=phase):
                self.assertEqual(4, len(processing.inspect(snapshot(phase), processing.fixtures(), phase)))

    def test_absent_or_malformed_tank_is_not_empty(self):
        for value in ({}, {"Empty": 0}, {"Empty": 1, "Amount": 1000},
                      {"FluidName": "basepower:crude_oil"}, {"FluidName": "basepower:crude_oil", "Amount": -1},
                      {"FluidName": "basepower:crude_oil", "Amount": True}):
            with self.subTest(value=value), self.assertRaises((AssertionError, KeyError)):
                processing.tank(dict(Tank=value), "Tank", "basepower:crude_oil")
        with self.assertRaises(KeyError):
            processing.tank({}, "Tank", "basepower:crude_oil")

    def test_explicit_zero_debited_stack_keeps_identity_but_missing_is_never_zero(self):
        self.assertEqual(0, processing.tank(dict(Tank=dict(FluidName="basepower:crude_oil", Amount=0)),
                                           "Tank", "basepower:crude_oil"))
        with self.assertRaises(AssertionError):
            processing.tank(dict(Tank=dict(FluidName="mineralogy:crude_oil", Amount=0)), "Tank", "basepower:crude_oil")
        document = snapshot("complete")
        row = document["cases"]["basepower-still"][3]
        tag = json.loads(row["nbt"])
        tag["InputTank"] = dict(FluidName="basepower:crude_oil", Amount=0)
        row["nbt"] = json.dumps(tag)
        processing.inspect(document, processing.fixtures(), "complete")

    def test_intake_has_no_boiler_redstone_or_water_and_water_charge_replaces_empty_flag(self):
        runner = processing.ProcessingRun(None, None, None, processing.fixtures())
        commands, probes = [], []
        empty = snapshot("held")
        for f in runner.definition:
            row = empty["cases"][f["id"]][3]
            tag = json.loads(row["nbt"])
            tag["InputTank" if f["kind"] == "still" else "FuelTank"] = {"Empty": 1}
            row["nbt"] = json.dumps(tag)
        runner.command = commands.append
        runner.probe = lambda operation, label: probes.append((operation, label)) or empty
        runner.build()
        self.assertEqual(1, sum(operation == "place-buckets" for operation, _ in probes))
        self.assertFalse(any("data " in command for command in commands))
        for f in runner.definition:
            gate = "setblock " + " ".join(map(str, f["gate"]))
            self.assertIn(gate + (" minecraft:air" if f["kind"] == "boiler" else " minecraft:redstone_block"), commands)
        commands.clear()
        runner.charge_water_and_hold()
        self.assertEqual(4, len(commands))
        self.assertEqual(2, sum(' WaterTank set value {FluidName:"minecraft:water",Amount:64}' in command
                                and command.startswith("data modify block ") for command in commands))
        self.assertFalse(any("data merge" in command for command in commands))

    def test_readiness_uses_native_chunk_coordinates_not_block_coordinates(self):
        runner = processing.ProcessingRun(Path("fixture-runtime"), None, None, processing.fixtures())
        runner.probe = lambda *args: None
        server = SimpleNamespace(process=SimpleNamespace(pid=42), send=lambda *args: None, wait=lambda *args: None)
        with patch.object(processing.native, "audit_staged"), patch.object(processing.native, "Server", return_value=server), \
                patch.object(processing, "await_ready") as ready:
            runner.boot("processing-initial")
        self.assertEqual(4, ready.call_count)
        for call, f in zip(ready.call_args_list, runner.definition):
            self.assertEqual([f["min"][0] >> 4, 128, f["max"][0] >> 4, 129], call.args[2])
            self.assertEqual(processing.native.MINECRAFT, call.kwargs["minecraft"])

    def test_forged_identity_or_quantity_cannot_pass(self):
        f = processing.fixtures()[0]
        for key, tank in (("InputTank", fluid("mineralogy:crude_oil", 700)),
                          ("InputTank", fluid(f["fluid"], 699)),
                          ("Tank", fluid("basepower:refined_oil", 151))):
            document = snapshot("partial")
            row = document["cases"][f["id"]][3]
            tag = json.loads(row["nbt"])
            tag[key] = tank
            row["nbt"] = json.dumps(tag)
            with self.subTest(key=key), self.assertRaises(AssertionError):
                processing.inspect(document, processing.fixtures(), "partial")

    def test_water_fuel_energy_and_hold_negative_controls(self):
        for phase, field, value in (("held", "FuelTank", fluid("mineralogy:crude_oil", 900)),
                                    ("partial", "WaterTank", fluid("minecraft:water", 1)),
                                    ("partial", "BurnTimeTotal", 499)):
            document = snapshot(phase)
            row = document["cases"]["mineralogy-boiler"][3]
            tag = json.loads(row["nbt"])
            tag[field] = value
            row["nbt"] = json.dumps(tag)
            with self.subTest(field=field), self.assertRaises(AssertionError):
                processing.inspect(document, processing.fixtures(), phase)
        document = snapshot("partial")
        document["cases"]["mineralogy-boiler"][3]["energy"]["steam"] = 79
        with self.assertRaises(AssertionError):
            processing.inspect(document, processing.fixtures(), "partial")

    def test_missing_duplicate_unloaded_or_replaced_witness_fails(self):
        for mutate in (lambda rows: rows.pop(), lambda rows: rows.append(copy.deepcopy(rows[0])),
                       lambda rows: rows[0].update(loaded=False),
                       lambda rows: rows[2].update(block="minecraft:air")):
            document = snapshot("held")
            mutate(document["cases"]["basepower-still"])
            with self.assertRaises(AssertionError):
                processing.inspect(document, processing.fixtures(), "held")
        before, after = snapshot("partial"), snapshot("complete")
        after["cases"]["basepower-still"][3]["tileIdentity"] = 999
        with self.assertRaises(AssertionError):
            processing.require_same_machines(before, after, processing.fixtures())

    def test_idle_decay_is_exact_live_and_bounded_across_cold_chunk_load(self):
        before = processing.inspect(snapshot("partial"), processing.fixtures(), "partial")
        after = processing.inspect(snapshot("complete"), processing.fixtures(), "complete")
        processing.require_decay(before, after, 440, exact=True)
        after["basepower-boiler"]["steam"] += 0.0625
        with self.assertRaises(AssertionError):
            processing.require_decay(before, after, 440, exact=True)
        processing.require_decay(before, after, 440, exact=False)
        after["basepower-boiler"]["steam"] = 80.0625
        with self.assertRaises(AssertionError):
            processing.require_decay(before, after, 440, exact=False)


if __name__ == "__main__":
    unittest.main()
