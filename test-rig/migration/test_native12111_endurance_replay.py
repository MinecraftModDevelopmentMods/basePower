"""Physical native61 region fixtures and fail-closed frozen replay controls."""

import base64
import copy
import gzip
import io
import json
from pathlib import Path
import tempfile
import unittest
import zlib

import nbtlib as n

import native12111_endurance_replay as replay
from native12111_endurance import measure_batch
from native117_identity import target_id
from semantic_machine_audit import BATCH_MACHINES


def encoded(root):
    stream = io.BytesIO()
    n.File(root).write(stream)
    return stream.getvalue()


def stack():
    return n.Compound(Slot=n.Byte(0), id=n.String("baseelectric:lead_acid_battery"), count=n.Int(1),
                      components=n.Compound({"minecraft:custom_data": n.Compound(energy=n.Float(100))}))


def tile():
    return n.Compound(id=n.String("basepower:test"), x=n.Int(1), y=n.Int(1), z=n.Int(1),
                      components=n.Compound({"minecraft:custom_data": n.Compound(witness=n.Int(7))}),
                      Items=n.List[n.Compound]([stack()]), Energy=n.IntArray([1065353216]))


def chunk():
    saved = tile()
    saved["keepPacked"] = n.Byte(0)
    return n.Compound(DataVersion=n.Int(4671), xPos=n.Int(0), zPos=n.Int(0),
                      sections=n.List[n.Compound]([n.Compound(Y=n.Byte(0), block_states=n.Compound(
                          palette=n.List[n.Compound]([n.Compound(Name=n.String("basepower:test"))])))]),
                      block_entities=n.List[n.Compound]([saved]))


def payload(root, compression=2):
    raw = encoded(root)
    data = {1: gzip.compress, 2: zlib.compress, 3: lambda v: v}[compression](raw)
    return (len(data) + 1).to_bytes(4, "big") + bytes([compression]) + data


def region(data):
    count = (len(data) + 4095) // 4096
    header = bytearray(8192)
    header[:4] = ((2 << 8) | count).to_bytes(4, "big")
    return bytes(header) + data + bytes(count * 4096 - len(data))


def document(value=None):
    return dict(snapshot=[dict(pos=[1, 1, 1], block="basepower:test", state="Block{basepower:test}",
                               nbt=(tile() if value is None else value).snbt())])


def put_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value), encoding="utf-8")


def stopped_fixture(root):
    world = root / replay.WORLD
    (world / "region").mkdir(parents=True)
    (world / "level.dat").write_bytes(gzip.compress(encoded(n.Compound(Data=n.Compound(DataVersion=n.Int(4671))))))
    (world / "region/r.0.0.mca").write_bytes(region(payload(chunk())))
    (world / "session.lock").write_bytes(b"abc")
    (root / "console-endurance-restart.log").write_text("Stopping server\nAll dimensions are saved\n", encoding="utf-8")
    for station in replay.endurance.CHECKED:
        put_json(root / "advantage-works-results" / f"{station}-checkpoint-restart-resumed.json", document())
    return world


def complete_report():
    return dict(status="PASS", minecraft="1.21.11", forge="61.1.0", duration_seconds=1800,
                interval_seconds=60, seedUnchanged=True, retained_unchanged=True,
                checked_stations=replay.endurance.CHECKED, active_stations=replay.endurance.ACTIVE,
                profiles={mode: dict(status="PASS", restart="PASS", resumed_processing="PASS",
                                     endurance_batches=30, verified_checkpoints=33,
                                     game_ticks=36000, elapsed_seconds=1801) for mode in replay.MODES})


def semantic_fixture():
    documents = {name: dict(station=name, snapshot=[]) for name in ("S-04", "E-03", "W-03", "W-04")}
    definitions = {name: dict(origin=[0, 0, 0]) for name in documents}

    def put(station, pos, block="minecraft:chest", items=(), **fields):
        value = n.Compound(Items=n.List[n.Compound]([
            n.Compound(Slot=n.Byte(slot), id=n.String(item), count=n.Int(count)) for slot, item, count in items
        ]), **fields)
        documents[station]["snapshot"].append(dict(pos=list(pos), block=target_id(block),
                                                   state="Block{" + target_id(block) + "}", nbt=value.snbt()))

    for station, pos, neighbours, block, product, _ in BATCH_MACHINES:
        put(station, pos, block, [(0, "minecraft:" + product, 4)])
        for neighbour in neighbours:
            put(station, neighbour)
    for x in (2, 3, 4, 5):
        put("W-04", (x, 1, 6), items=[(0, "minecraft:dirt", 16)] if x == 5 else [])
    for z, ghosts in ((9, [(1, "minecraft:dirt", 1), (2, "minecraft:cobblestone", 1), (3, "minecraft:coal", 1)]),
                      (15, []), (18, [(1, "minecraft:dirt", 1)])):
        put("W-04", (2, 1, z))
        put("W-04", (3, 1, z), items=ghosts)
        forward = [(0, "minecraft:apple", 8)] + ([(1, "minecraft:dirt", 8)] if z == 15 else [])
        put("W-04", (4, 1, z), items=forward)
        put("W-04", (3, 0, z), items=[] if z == 15 else [(0, "minecraft:dirt", 8)])
    for x in (4, 6, 8):
        tank = n.Compound(FluidName=n.String("basepower:refined_oil"), Amount=n.Int(1000)) if x == 8 else n.Compound(Empty=n.Byte(1))
        put("W-03", (x, 1, 8), Tank=tank, **({"InputTank": n.Compound(Empty=n.Byte(1))} if x == 6 else {}))
    return documents, definitions


class Native61ReplayTests(unittest.TestCase):
    def test_all_33_batch_outputs_recomputed_and_changed_product_fails(self):
        documents, definitions = semantic_fixture()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for label in [f"cycle-{i:03d}" for i in range(1, 31)] + ["before-restart", "after-restart", "restart-resumed"]:
                measured = measure_batch(documents, definitions)
                self.assertEqual(measured["status"], "PASS")
                put_json(root / f"semantic-batch-{label}.json", measured)
                replay.Forge61.verify_batch(root, label, documents, definitions)
            changed = copy.deepcopy(documents)
            value = n.parse_nbt(changed["E-03"]["snapshot"][0]["nbt"])
            value["Items"][0]["count"] = n.Int(3)
            changed["E-03"]["snapshot"][0]["nbt"] = value.snbt()
            with self.assertRaisesRegex(AssertionError, "replay disagrees"):
                replay.Forge61.verify_batch(root, "cycle-030", changed, definitions)

    def test_freeze_copies_real_stopped_files_and_seal_detects_change(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            run = root / "run"
            for mode in replay.MODES:
                stopped_fixture(run / mode)
                put_json(run / mode / "advantage-works-results/W-01-checkpoint-setup.json",
                         dict(recordedAtUtc="2026-11-11T18:00:00+00:00"))
            put_json(run / replay.RECEIPT, complete_report())
            source = root / "candidate.json"
            put_json(source, {"fixture": True})
            files = {str(path): replay.sha(path.read_bytes()) for path in replay.oracle_paths()}
            files[str(source)] = replay.sha(source.read_bytes())
            seal = root / "seal.json"
            put_json(seal, dict(format=1, purpose="NATIVE61_ENDURANCE_PRE_RUN_SEAL", files=files,
                                oracles=[str(path) for path in replay.oracle_paths()],
                                createdAtUtc="2026-11-11T17:59:00+00:00"))
            pin = replay.sha(seal.read_bytes())
            before = replay.tree(run, replay.Budget())
            frozen = root / "frozen"
            result = replay.freeze(run, seal, pin, replay.sha((run / replay.RECEIPT).read_bytes()), frozen, replay.Budget())
            self.assertEqual(result["status"], "PASS")
            self.assertEqual(replay.tree(frozen / "run", replay.Budget()), before)
            self.assertEqual(replay.tree(run, replay.Budget()), before)
            source.write_text("changed", encoding="utf-8")
            with self.assertRaisesRegex(AssertionError, "pin"):
                replay.verify_seal(seal, pin, replay.Budget())

    def test_late_seal_fails_closed_but_explicit_retrospective_cannot_claim_custody(self):
        with tempfile.TemporaryDirectory() as directory:
            run = Path(directory)
            for mode in replay.MODES:
                put_json(run / mode / "advantage-works-results/W-01-checkpoint-setup.json",
                         dict(recordedAtUtc="2026-11-11T18:28:07Z"))
            sealed = dict(purpose="NATIVE61_ENDURANCE_PRE_RUN_SEAL", createdAtUtc="2026-11-11T18:41:34+00:00")
            with self.assertRaisesRegex(AssertionError, "not made before"):
                replay.custody(sealed, run, replay.Budget())
            sealed["purpose"] = "NATIVE61_ENDURANCE_RETROSPECTIVE_SEAL"
            actual = replay.custody(sealed, run, replay.Budget())
            self.assertIs(actual["preRunCustody"], False)
            self.assertIn("No pre-run", actual["coverageGap"])
            sealed["createdAtUtc"] = "2026-11-11T18:20:00+00:00"
            self.assertIs(replay.custody(sealed, run, replay.Budget())["preRunCustody"], False)

    def test_raw_stopped_data_version_4671_and_components(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            stopped_fixture(root)
            before = replay.tree(root, replay.Budget())
            actual = replay.raw_final(root, replay.Budget())
            self.assertEqual(actual, dict(status="PASS", dataVersion=4671, comparedRows=16, comparedChunks=1))
            self.assertEqual(replay.tree(root, replay.Budget()), before)

    def test_raw_component_value_or_type_loss_rejected(self):
        for replacement in (n.Float(99), n.Int(100)):
            with self.subTest(replacement=replacement), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                world = stopped_fixture(root)
                raw = chunk()
                raw["block_entities"][0]["Items"][0]["components"]["minecraft:custom_data"]["energy"] = replacement
                (world / "region/r.0.0.mca").write_bytes(region(payload(raw)))
                with self.assertRaisesRegex(AssertionError, "component mismatch"):
                    replay.raw_final(root, replay.Budget())

    def test_raw_unknown_component_loss_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            world = stopped_fixture(root)
            raw = chunk()
            raw["block_entities"][0].pop("components")
            (world / "region/r.0.0.mca").write_bytes(region(payload(raw)))
            with self.assertRaisesRegex(AssertionError, "component mismatch"):
                replay.raw_final(root, replay.Budget())

    def test_old_or_wrongly_typed_data_version_rejected(self):
        for version in (n.Int(2730), n.Int(3465), n.Int(3955), n.Long(4671)):
            with self.subTest(version=version):
                root = chunk()
                root["DataVersion"] = version
                with self.assertRaisesRegex(AssertionError, "4671"):
                    replay.terrain(payload(root), (0, 0))

    def test_wrong_level_data_version_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            world = stopped_fixture(root)
            (world / "level.dat").write_bytes(gzip.compress(encoded(n.Compound(Data=n.Compound(DataVersion=n.Int(3465))))))
            with self.assertRaisesRegex(AssertionError, "level DataVersion"):
                replay.raw_final(root, replay.Budget())

    def test_compression_formats_and_coordinate_guard(self):
        for compression in (1, 2, 3):
            with self.subTest(compression=compression):
                sections, tiles = replay.terrain(payload(chunk(), compression), (0, 0))
                self.assertEqual(sections[0][0]["Name"], "basepower:test")
                self.assertEqual(tiles[1, 1, 1]["Items"][0]["count"], 1)
        with self.assertRaisesRegex(AssertionError, "4671"):
            replay.terrain(payload(chunk()), (1, 0))

    def test_duplicate_section_or_tile_rejected(self):
        for key in ("sections", "block_entities"):
            with self.subTest(key=key):
                root = chunk()
                root[key].append(copy.deepcopy(root[key][0]))
                with self.assertRaisesRegex(AssertionError, "Duplicate"):
                    replay.terrain(payload(root), (0, 0))

    def test_packed_missing_wrong_marker_rejected(self):
        for marker in (None, n.Byte(1), n.Int(0)):
            with self.subTest(marker=marker):
                root = chunk()
                if marker is None:
                    root["block_entities"][0].pop("keepPacked")
                else:
                    root["block_entities"][0]["keepPacked"] = marker
                with self.assertRaisesRegex(AssertionError, "unpacked"):
                    replay.terrain(payload(root), (0, 0))

    def test_all_native_stack_type_negative_controls(self):
        for key, value in (("count", n.Byte(1)), ("count", n.Int(0)), ("count", n.Int(65)),
                           ("Slot", n.Int(0)), ("id", n.String("not_namespaced")),
                           ("components", n.String("lost")), ("Count", n.Byte(1)), ("tag", n.Compound())):
            with self.subTest(key=key, value=value):
                root = tile()
                root["Items"][0][key] = value
                with self.assertRaisesRegex(AssertionError, "native count"):
                    replay.row_nbt({"nbt": root.snbt()})

    def test_duplicate_slots_and_snbt_keys_rejected(self):
        root = tile()
        root["Items"].append(copy.deepcopy(root["Items"][0]))
        with self.assertRaisesRegex(AssertionError, "duplicate slot"):
            replay.row_nbt({"nbt": root.snbt()})
        for text in ('{x:1,x:2}', '{outer:{"x":1,x:2}}'):
            with self.subTest(text=text), self.assertRaisesRegex(ValueError, "Duplicate SNBT"):
                replay.row_nbt({"nbt": text})

    def test_snbt_trailing_or_ambiguous_nbt_rejected(self):
        with self.assertRaises(ValueError):
            replay.row_nbt({"nbt": "{} trailing"})
        with self.assertRaisesRegex(AssertionError, "Ambiguous"):
            replay.row_nbt({"nbt": "{}", "nbtBase64": "AA=="})

    def test_binary_checkpoint_preserves_native_components(self):
        data = base64.b64encode(gzip.compress(encoded(tile()))).decode("ascii")
        self.assertEqual(replay.typed_nbt(replay.row_nbt({"nbtBase64": data})), replay.typed_nbt(tile()))

    def test_duplicate_checkpoint_position_rejected(self):
        value = document()
        value["snapshot"] *= 2
        with self.assertRaisesRegex(AssertionError, "Duplicate"):
            replay.snapshot(value)

    def test_compressed_bomb_trailing_and_external_chunks_rejected(self):
        for data, compression in ((zlib.compress(b"x" * (16 * 1024 * 1024 + 1)), 2),
                                   (gzip.compress(encoded(chunk())) + b"trailing", 1), (b"anything", 130)):
            with self.subTest(compression=compression), self.assertRaises(ValueError):
                replay._decompress(data, compression)

    def test_duplicate_binary_keys_or_trailing_nbt_rejected(self):
        binary = encoded(n.Compound(x=n.Int(1)))
        duplicate = binary[:-1] + binary[3:-1] + b"\x00"
        for data in (duplicate, binary + b"\x00"):
            with self.subTest(data=data), self.assertRaises(ValueError):
                replay._nbt(data)

    def test_overlapping_sector_allocation_rejected(self):
        data = bytearray(region(payload(chunk())))
        data[4:8] = data[:4]
        with self.assertRaisesRegex(ValueError, "Overlapping"):
            list(replay._payloads(bytes(data), 0, 0))

    def test_json_duplicates_nonfinite_and_size_bound(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "input.json"
            for text in ('{"status":"PASS","status":"FAIL"}', '{"value":NaN}'):
                path.write_text(text, encoding="utf-8")
                with self.assertRaises(ValueError):
                    replay.Budget().json(path)
            path.write_bytes(b"0123456789")
            with self.assertRaisesRegex(ValueError, "bound"):
                replay.Budget().read(path, 3)

    def test_deadline_and_total_byte_bound(self):
        budget = replay.Budget()
        budget.deadline = 0
        with self.assertRaises(TimeoutError):
            budget.check()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "one"
            path.write_bytes(b"x")
            budget = replay.Budget()
            budget.bytes = replay.MAX_TOTAL
            with self.assertRaisesRegex(ValueError, "bound"):
                budget.read(path)

    def test_new_output_disjoint_and_no_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            original = root / "original"
            replay.write_new(original, {"status": "HELD"})
            with self.assertRaises(FileExistsError):
                replay.write_new(original, {"status": "PASS"})
            self.assertEqual(json.loads(original.read_text()), {"status": "HELD"})
            with self.assertRaises(ValueError):
                replay.disjoint(root / "child", root)

    def test_held_smoke_running_fail_and_bool_duration_rejected(self):
        for status in ("HELD", "FAIL", "RUNNING", "SMOKE_PASS_NOT_ENDURANCE"):
            report = complete_report()
            report["status"] = status
            with self.subTest(status=status), self.assertRaisesRegex(AssertionError, "complete"):
                replay.complete(report)
        report = complete_report()
        report["duration_seconds"] = True
        with self.assertRaisesRegex(AssertionError, "complete"):
            replay.complete(report)
        report = complete_report()
        replay.complete(report)
        for key, value in (("game_ticks", 32399), ("game_ticks", True), ("verified_checkpoints", 32),
                           ("endurance_batches", 29), ("elapsed_seconds", 1799)):
            changed = copy.deepcopy(report)
            changed["profiles"][replay.MODES[0]][key] = value
            with self.subTest(key=key), self.assertRaisesRegex(AssertionError, "Incomplete"):
                replay.complete(changed)

    def test_external_pin_tampering_and_path_alias_rejected(self):
        replay.pin(b"frozen", replay.sha(b"frozen"))
        with self.assertRaisesRegex(AssertionError, "pin"):
            replay.pin(b"changed", replay.sha(b"frozen"))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "target").write_bytes(b"x")
            replay.os.link(root / "target", root / "alias")
            with self.assertRaisesRegex(ValueError, "Alias"):
                replay.tree(root, replay.Budget())

    def test_stop_markers_and_real_session_lock(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            stopped_fixture(root)
            before = (root / replay.WORLD / "session.lock").read_bytes()
            with replay.stopped(root, replay.Budget()):
                if replay.os.name == "nt":
                    with self.assertRaises(OSError):
                        with replay.stopped(root, replay.Budget()):
                            self.fail("Active lock admitted")
            self.assertEqual((root / replay.WORLD / "session.lock").read_bytes(), before)
            (root / "console-endurance-restart.log").write_text("Still running", encoding="utf-8")
            with self.assertRaisesRegex(AssertionError, "shutdown"):
                with replay.stopped(root, replay.Budget()):
                    self.fail("Running world admitted")

    def test_feed_acknowledgements_and_rebuild_negative_control(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for boot, count in zip(("endurance", "endurance-restart"), replay.expected_inventory_acknowledgements(30)):
                (root / f"console-{boot}.log").write_text("Replaced a slot at 1, 1, 1\n" * count, encoding="utf-8")
            self.assertEqual(replay.feed_audit(root, replay.Budget())["status"], "PASS")
            path = root / "console-endurance-restart.log"
            path.write_text(path.read_text() + "Advantage Works built", encoding="utf-8")
            with self.assertRaisesRegex(AssertionError, "rebuilt"):
                replay.feed_audit(root, replay.Budget())


if __name__ == "__main__":
    unittest.main()
