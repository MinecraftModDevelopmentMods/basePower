"""Real stopped-storage fixtures and fail-closed migration adapter contracts, no JVM launch."""

import base64
import copy
import gzip
import io
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch
import uuid
import zlib

import nbtlib as n

from matrix import WORLD, digest, fingerprint, write_json
from native117_actor_io import bind
import native262_upgrade as upgrade


def encoded(tag):
    stream = io.BytesIO()
    n.File(tag).write(stream)
    return stream.getvalue()


def region(tag, cx=-1, cz=-2):
    payload = b"\x02" + zlib.compress(encoded(tag))
    record = len(payload).to_bytes(4, "big") + payload
    sectors = (len(record) + 4095) // 4096
    header = bytearray(8192)
    slot = (cx & 31) + ((cz & 31) << 5)
    header[slot * 4:slot * 4 + 4] = ((2 << 8) | sectors).to_bytes(4, "big")
    return bytes(header) + record.ljust(sectors * 4096, b"\0")


def reject_operation(*args):
    raise AssertionError("Required ordinary operation executed and failed")


class Native262UpgradeTest(unittest.TestCase):
    def test_native2612_source_retains_complete_component_payloads(self):
        value = dict(id="baseelectric:lithium_battery", count=1,
                     components={"minecraft:custom_data": {"charge": 517, "Private": [1, 2, 3]},
                                 "minecraft:enchantments": {"basesteam:musket_damage": 2}})
        before = copy.deepcopy(value)
        self.assertEqual(4790, upgrade.SOURCE_VERSIONS["26.1.2"])
        self.assertEqual(value, upgrade.expected_components(value, source_version="26.1.2"))
        self.assertEqual(before, value)
        for broken in (dict(value, Count=1), dict(value, count=True), dict(value, count=0)):
            with self.assertRaises(AssertionError):
                upgrade.expected_components(broken, source_version="26.1.2")

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.runtime = self.root / "runtime"
        self.world = self.runtime / WORLD
        self.world.mkdir(parents=True)
        self.identity = uuid.UUID("12345678-1234-5678-9abc-def012345678")

    def save(self, path, tag):
        path.parent.mkdir(parents=True, exist_ok=True)
        n.File(tag, gzipped=True).save(path)

    def words(self, identity=None):
        value = (identity or self.identity).int
        return n.IntArray([((value >> shift) & 0xffffffff) -
                           (0x100000000 if (value >> shift) & 0x80000000 else 0) for shift in (96, 64, 32, 0)])

    def storage(self):
        self.save(self.world / "level.dat", n.Compound(Data=n.Compound(DataVersion=n.Int(4903))))
        self.save(upgrade.layout.shared_file(self.world, "minecraft:world_gen_settings"),
                  n.Compound(DataVersion=n.Int(4903), data=n.Compound(seed=n.Long(-17),
                             unknown=n.Compound(retain=n.ByteArray([1, -2, 3])))))
        tile = n.Compound(id=n.String("minecraft:chest"), x=n.Int(-1), y=n.Int(64), z=n.Int(-17),
                          Items=n.List[n.Compound]([]), components=n.Compound(), Unknown=n.Long(718))
        chunk = n.Compound(DataVersion=n.Int(4903), xPos=n.Int(-1), zPos=n.Int(-2),
                           sections=n.List[n.Compound]([n.Compound(Y=n.Byte(4), block_states=n.Compound(
                               palette=n.List[n.Compound]([n.Compound(Name=n.String("minecraft:chest"))])))]),
                           block_entities=n.List[n.Compound]([copy.deepcopy(tile)]))
        path = upgrade.layout.region_file(self.world, "minecraft:overworld", -1, -2)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(region(chunk))
        row = dict(name="chest", x=-1, y=64, z=-17, actualName="minecraft:chest", properties={},
                   nbtBase64=base64.b64encode(gzip.compress(encoded(tile))).decode())
        return path, chunk, row

    def test_native_stopped_capture_reads_only_relocated_region_and_shared_worldgen(self):
        path, chunk, row = self.storage()
        (self.world / "region").mkdir()
        (self.world / "region" / path.name).write_bytes(b"stale legacy decoy")
        original = fingerprint(self.world)
        receipt = upgrade.capture(self.runtime, "converted", [row])
        self.assertEqual("PASS", receipt["status"])
        self.assertEqual(4903, receipt["nativeDataVersion"])
        self.assertEqual([1, -2, 3], receipt["worldGenSettings"]["data"]["unknown"]["retain"])
        self.assertEqual("NO_SINGLEPLAYER_OWNER", receipt["playerAuthority"]["status"])
        self.assertEqual(original, fingerprint(self.world))
        self.assertEqual(path.read_bytes(), (self.runtime / "native262-save/converted" / path.name).read_bytes())

    def test_raw_chunk_mutations_cannot_be_hidden_by_live_projection(self):
        path, baseline, row = self.storage()
        for index, mutation in enumerate(("version", "version-type", "coordinate", "coordinate-type", "historical",
                                           "block", "tile", "extra-tile-field", "duplicate-tile", "duplicate-section", "packed")):
            chunk = copy.deepcopy(baseline)
            if mutation == "version": chunk["DataVersion"] = n.Int(4671)
            elif mutation == "version-type": chunk["DataVersion"] = n.Long(4903)
            elif mutation == "coordinate": chunk["xPos"] = n.Int(0)
            elif mutation == "coordinate-type": chunk["xPos"] = n.String("-1")
            elif mutation == "historical": chunk["Level"] = n.Compound()
            elif mutation == "block": chunk["sections"][0]["block_states"]["palette"][0]["Name"] = n.String("minecraft:air")
            elif mutation == "tile": chunk["block_entities"][0]["Unknown"] = n.Long(719)
            elif mutation == "extra-tile-field": chunk["block_entities"][0]["Unexpected"] = n.Int(1)
            elif mutation == "duplicate-tile": chunk["block_entities"].append(copy.deepcopy(chunk["block_entities"][0]))
            elif mutation == "duplicate-section": chunk["sections"].append(copy.deepcopy(chunk["sections"][0]))
            elif mutation == "packed": chunk["block_entities"][0]["keepPacked"] = n.Byte(1)
            path.write_bytes(region(chunk))
            with self.subTest(mutation=mutation), self.assertRaises((AssertionError, ValueError)):
                upgrade.capture(self.runtime, "mutant-" + str(index), [row])

    def test_only_reviewed_zero_keep_packed_storage_field_is_projected(self):
        path, chunk, row = self.storage()
        chunk["block_entities"][0]["keepPacked"] = n.Byte(0)
        path.write_bytes(region(chunk))
        self.assertEqual("PASS", upgrade.capture(self.runtime, "zero-packed", [row])["status"])

    def test_empty_duplicate_and_misaddressed_live_witnesses_cannot_pass(self):
        _, _, row = self.storage()
        for index, rows in enumerate(([], [row, row], [{**row, "x": True}], [{**row, "x": 16}])):
            with self.subTest(rows=rows), self.assertRaises((AssertionError, ValueError)):
                upgrade.capture(self.runtime, "invalid-" + str(index), rows)

    def test_archive_damage_and_missing_native_path_do_not_use_legacy_fallback(self):
        path, chunk, row = self.storage()
        native = path.read_bytes()
        legacy = self.world / "region" / path.name
        legacy.parent.mkdir()
        legacy.write_bytes(native)
        for index, raw in enumerate((b"", native[:-1], b"invalid", bytes(8192))):
            path.write_bytes(raw)
            with self.subTest(raw=len(raw)), self.assertRaises((ValueError, AssertionError)):
                upgrade.capture(self.runtime, "damaged-" + str(index), [row])
        path.unlink()
        with self.assertRaisesRegex(ValueError, "Missing"):
            upgrade.capture(self.runtime, "missing", [row])

    def test_host_uuid_owns_native_player_path_not_legacy_or_embedded_decoys(self):
        self.storage()
        self.save(self.world / "level.dat", n.Compound(Data=n.Compound(DataVersion=n.Int(4903), singleplayer_uuid=self.words())))
        player = n.Compound(DataVersion=n.Int(4903), UUID=self.words(), Unknown=n.Long(88))
        path = upgrade.layout.player_file(self.world, self.identity)
        self.save(path, player)
        self.save(self.world / "playerdata" / path.name, n.Compound(UUID=self.words(uuid.uuid4())))
        result = upgrade.player_authority(self.world)
        self.assertEqual("NATIVE_PLAYER", result["status"])
        self.assertEqual("players/data/" + path.name, result["file"].replace("\\", "/"))
        self.assertFalse(result["playerConversionQualified"])
        player["DataVersion"] = n.Int(4671)
        self.save(path, player)
        self.assertEqual("RETAINED_UNOPENED_SOURCE_PLAYER", upgrade.player_authority(self.world)["status"])
        path.unlink()
        with self.assertRaises(FileNotFoundError):
            upgrade.player_authority(self.world)

    def test_host_uuid_version_and_embedded_conflicts_fail_closed(self):
        self.storage()
        self.save(self.world / "level.dat", n.Compound(Data=n.Compound(DataVersion=n.Int(4903), singleplayer_uuid=self.words())))
        path = upgrade.layout.player_file(self.world, self.identity)
        for change in (dict(UUID=self.words(uuid.uuid4())), dict(DataVersion=n.Int(4904)),
                       dict(DataVersion=n.String("4903")), dict(DataVersion=n.Int(0))):
            tag = n.Compound(DataVersion=n.Int(4903), UUID=self.words(), **{})
            tag.update(change)
            self.save(path, tag)
            with self.subTest(change=change), self.assertRaises(AssertionError):
                upgrade.player_authority(self.world)
        self.save(self.world / "level.dat", n.Compound(Data=n.Compound(DataVersion=n.Int(4903),
                  singleplayer_uuid=self.words(), Player=n.Compound(UUID=self.words()))))
        with self.assertRaises(ValueError):
            upgrade.player_authority(self.world)

    def test_turbine_native_entity_region_preserves_uuid_parent_and_position(self):
        self.storage()
        actor = n.Compound(id=n.String("baseelectric:hydroturbine"), UUID=self.words(), parentPos=n.IntArray([-1, 64, -17]),
                           Pos=n.List[n.Double]([-0.5, 64.0, -16.5]))
        tag = n.Compound(DataVersion=n.Int(4903), Position=n.IntArray([-1, -2]), Entities=n.List[n.Compound]([actor]))
        path = upgrade.layout.region_file(self.world, "minecraft:overworld", -1, -2, "entities")
        path.parent.mkdir(parents=True)
        path.write_bytes(region(tag))
        result = upgrade.turbine(self.world, [-1, 64, -17])
        self.assertEqual([-1, 64, -17], result["parent"])
        self.assertEqual([-0.5, 64.0, -16.5], result["position"])
        for version in (4671, 4791):
            tag["DataVersion"] = n.Int(version)
            path.write_bytes(region(tag))
            with self.assertRaises((AssertionError, ValueError)):
                upgrade.turbine(self.world, [-1, 64, -17])

    def source_receipt(self, version="1.21.11", data_version=4671, route=None):
        source = self.root / "source"
        source.mkdir(exist_ok=True)
        write_json(source / "migration-restarted.json", [])
        receipt = dict(status="PASS", minecraft=version, sourceUnchanged=True, phases={"restarted": dict(
            status="PASS", snapshotSha256=digest(source / "migration-restarted.json"), serialized=dict(nativeDataVersion=data_version))})
        path = source / "receipt.json"
        write_json(path, receipt)
        return SimpleNamespace(source=source, legacy_reference=None, source_receipt_name=path.name,
                               receipt_sha256=digest(path), source_route=route or version), receipt

    def test_each_retained_intermediate_and_12111_route_requires_exact_sealed_version(self):
        for version, data_version in upgrade.SOURCE_VERSIONS.items():
            args, _ = self.source_receipt(version, data_version)
            self.assertEqual(version, upgrade.read_source(args)[4])
        self.assertEqual(("original110", "released110", "maintained112", "released112"), upgrade.LEGACY_ROUTES)

    def test_source_receipt_cannot_lie_about_version_status_route_or_snapshot(self):
        for version, dv, route in (("1.21.11", 3955, "1.21.11"), (None, 4671, "1.21.11"),
                                    ({}, 4671, "1.21.11"), ("1.21.11", "4671", "1.21.11"),
                                    ("1.21.11", 4671, "1.21.1"), ("1.21.11", 4671, "original110")):
            args, _ = self.source_receipt(version, dv, route)
            with self.subTest(version=version, dv=dv, route=route), self.assertRaises(AssertionError):
                upgrade.read_source(args)
        args, receipt = self.source_receipt()
        receipt["status"] = "FAIL"
        write_json(args.source / "receipt.json", receipt)
        args.receipt_sha256 = digest(args.source / "receipt.json")
        with self.assertRaisesRegex(AssertionError, "passing"):
            upgrade.read_source(args)
        args, _ = self.source_receipt()
        (args.source / "migration-restarted.json").write_text("[1]")
        with self.assertRaisesRegex(AssertionError, "checksum"):
            upgrade.read_source(args)

    def test_raw_historical_routes_require_independent_exact_source_fingerprint(self):
        for route in upgrade.LEGACY_ROUTES:
            args, receipt = self.source_receipt("1.20.1", 3465, route)
            original = self.root / route
            original.mkdir()
            (original / "immutable").write_bytes(b"retained historic input")
            self.save(original / WORLD / "level.dat", n.Compound(Data=n.Compound(DataVersion=n.Int(upgrade.LEGACY_SOURCE_VERSIONS[route]))))
            receipt.update(source=str(original), sourceFingerprint=fingerprint(original))
            write_json(args.source / "receipt.json", receipt)
            args.legacy_reference, args.source = args.source, original
            args.receipt_sha256 = digest(args.legacy_reference / "receipt.json")
            self.assertEqual("1.20.1", upgrade.read_source(args)[4])
            (original / "immutable").write_bytes(b"changed")
            with self.subTest(route=route), self.assertRaisesRegex(AssertionError, "no longer matches"):
                upgrade.read_source(args)

    def test_pre_1201_intermediates_can_use_their_sealed_1201_bridge_without_mislabeling_format(self):
        for route, version in upgrade.SOURCE_VERSIONS.items():
            if version >= 3465:
                continue
            args, receipt = self.source_receipt("1.20.1", 3465, route)
            original = self.root / ("bridge-" + route)
            self.save(original / WORLD / "level.dat", n.Compound(Data=n.Compound(DataVersion=n.Int(version))))
            receipt.update(source=str(original), sourceFingerprint=fingerprint(original))
            write_json(args.source / "receipt.json", receipt)
            args.legacy_reference, args.source = args.source, original
            args.receipt_sha256 = digest(args.legacy_reference / "receipt.json")
            self.assertEqual("1.20.1", upgrade.read_source(args)[4])
            args.source_route = "1.21.11"
            with self.assertRaisesRegex(AssertionError, "Historical bridge"):
                upgrade.read_source(args)

    def test_expected_components_preserve_legacy_adapters_and_native_opaque_payloads(self):
        legacy = dict(id="basesteam:musket", Count=1, tag=dict(Damage=7, loaded=1,
                      Enchantments=[dict(id="basesteam:powderless", lvl=1)]))
        for version, data_version in upgrade.SOURCE_VERSIONS.items():
            if data_version < 3465 or version == "1.20.1":
                self.assertEqual(upgrade.previous.components(legacy), upgrade.expected_components(legacy, source_version=version))
        native = dict(id="basesteam:musket", count=1, components={"minecraft:damage": 7,
                      "minecraft:enchantments": {"basesteam:powderless": 1},
                      "other:opaque": {"id": "not:an_item", "count": False, "components": {"levels": "opaque"}}})
        original = copy.deepcopy(native)
        adapted = upgrade.expected_components(native, source_version="1.21.11")
        self.assertEqual(original, adapted)
        adapted["components"]["other:opaque"]["count"] = 3
        self.assertEqual(original, native)

    def test_native_expected_component_types_and_wrappers_are_not_coerced(self):
        for version in (None, {}, True, "26.2"):
            with self.assertRaises(AssertionError):
                upgrade.expected_components({}, source_version=version)
        base = dict(id="basesteam:musket", count=1, components={"minecraft:enchantments": {"basesteam:powderless": 1}})
        for field, value in (("count", True), ("count", 0), ("count", "1"), ("id", "bad id"), ("components", None), ("Count", 1)):
            with self.subTest(field=field, value=value), self.assertRaises(AssertionError):
                upgrade.expected_components({**base, field: value}, source_version="1.21.11")
        for levels in ({"levels": {}}, {"basesteam:powderless": True}, {"basesteam:powderless": 256}, {7: 1}, []):
            with self.subTest(levels=levels), self.assertRaises(AssertionError):
                upgrade.expected_components({**base, "components": {"minecraft:enchantments": levels}}, source_version="1.21.11")

    def test_native_observation_extra_unknown_wear_enchantment_and_count_changes_stay_failures(self):
        tag = {"Items": [dict(id="basesteam:musket", count=1, components={"minecraft:damage": 7,
                           "minecraft:enchantments": {"basesteam:powderless": 1}, "other:opaque": {"retain": [1, 2]}})], "components": {}}
        row = dict(name="chest", x=1, y=2, z=3, actualName="minecraft:chest", properties={}, nbtBase64="fixture")
        for change in (None, "wear", "enchantment", "count", "opaque", "extra", "missing"):
            observed = copy.deepcopy(tag)
            stack = observed["Items"][0]
            if change == "wear": stack["components"]["minecraft:damage"] = 8
            elif change == "enchantment": stack["components"]["minecraft:enchantments"]["basesteam:powderless"] = 2
            elif change == "count": stack["count"] = 2
            elif change == "opaque": stack["components"]["other:opaque"]["retain"].append(3)
            elif change == "extra": stack["components"]["other:added"] = 9
            elif change == "missing": del stack["components"]["other:opaque"]
            with patch.object(upgrade.previous, "parse_nbt", side_effect=[tag, observed]):
                actual = upgrade.comparison([row], [row], modern=True, source_version="1.21.11")
            self.assertEqual("PASS" if change is None else "FAIL", actual["status"])
        with patch.object(upgrade.previous, "parse_nbt", side_effect=[tag, tag]):
            self.assertEqual("PASS", upgrade.comparison([row], [row], modern=False)["status"])

    def test_stopped_protocol_freezes_before_snapshot_flushes_then_closes(self):
        events = []
        server = Mock()
        server.send.side_effect = lambda command: events.append(command)
        server.snapshot.side_effect = lambda label: (events.append("snapshot:" + label), write_json(self.runtime / ("migration-" + label + ".json"), []))
        server.close.side_effect = lambda: events.append("close")
        core = SimpleNamespace(Server=Mock(return_value=server), LAUNCHER="official-args")
        with patch.object(upgrade, "engine", return_value=core):
            self.assertEqual([], upgrade.with_server(self.runtime, "java", "converted", lambda run: events.append("action")))
        self.assertEqual(["action", "tick freeze", "snapshot:converted", "save-all flush", "close"], events)
        self.assertEqual([("The game is frozen", 30), ("Saved the game", 30)], [call.args for call in server.wait.call_args_list])

    def test_action_and_stop_failures_both_remain_visible_and_no_snapshot_passes(self):
        server = Mock()
        server.close.side_effect = AssertionError("stop log failure")
        core = SimpleNamespace(Server=Mock(return_value=server), LAUNCHER="official-args")
        original = AssertionError("action failed")
        with patch.object(upgrade, "engine", return_value=core), self.assertRaisesRegex(AssertionError, "action failed") as failure:
            upgrade.with_server(self.runtime, "java", "converted", Mock(side_effect=original))
        self.assertIn("stop log failure", failure.exception.__notes__[0])
        server.snapshot.assert_not_called()
        server.close.assert_called_once()

    def test_operation_readiness_uses_target_identity_and_all_distinct_chunks(self):
        server = Mock()
        ready = Mock(return_value={"status": "PASS"})
        load = bind(upgrade.load_operating_chunks, await_ready=ready)
        self.assertEqual(2, len(load(server, self.runtime, {"a": (-1, 64, 32), "b": (16, 64, -1), "same": (31, 5, -8)})))
        self.assertTrue(all(call.kwargs["minecraft"] == "26.2" for call in ready.call_args_list))
        self.assertEqual(["forceload add -16 32", "forceload add 16 -16"], [call.args[0] for call in server.send.call_args_list])
        ready.side_effect = AssertionError("not ticking")
        with self.assertRaisesRegex(AssertionError, "not ticking"):
            load(server, self.runtime, {"a": (0, 5, 0)})

    def test_historical_world_copy_is_exact_and_only_loader_config_collision_is_explicit(self):
        source = self.root / "copy-source"
        (source / WORLD).mkdir(parents=True)
        (source / WORLD / "retain").write_bytes(b"immutable")
        (source / "config").mkdir()
        (source / "config/fml.toml").write_text("old-loader")
        (source / "config/mod.cfg").write_text("old-mod")
        target = self.root / "copy-target"
        (target / "config").mkdir(parents=True)
        (target / "config/fml.toml").write_text("native-loader")
        before = fingerprint(source)
        upgrade.copy_source(source, target)
        self.assertEqual(before, fingerprint(source))
        self.assertEqual(fingerprint(source / WORLD), fingerprint(target / WORLD))
        receipt = upgrade.read_json(target / "historical-config-custody.json")
        self.assertEqual("NATIVE_TARGET_LOADER", receipt["fml.toml"]["authority"])
        self.assertEqual("native-loader", (target / "config/fml.toml").read_text())
        bad = self.root / "bad-copy"
        (bad / "config").mkdir(parents=True)
        (bad / "config/mod.cfg").write_text("unreviewed")
        with self.assertRaisesRegex(AssertionError, "collision"):
            upgrade.copy_source(source, bad)

    def test_staging_failure_retains_fail_receipt_and_oracle_input_hashes_without_launch(self):
        args, _ = self.source_receipt()
        args.profile, args.inventory = self.root / "profile.json", self.root / "inventory.json"
        write_json(args.profile, {})
        write_json(args.inventory, {})
        args.seed = self.root / "seed"
        args.seed.mkdir()
        args.java = self.root / "java.exe"
        args.java.write_bytes(b"unit fake, never executed")
        args.output, args.port, args.inventory_sha256 = self.root / "failed-output", 26510, digest(args.inventory)
        mods = {}
        for name in upgrade.MOD_IDS:
            jar = self.root / (name + ".jar")
            jar.write_bytes(name.encode())
            mods[name] = "unit", jar, digest(jar)
        core = SimpleNamespace(read_family_target=Mock(return_value=(args.seed, {}, mods)),
                               java25=Mock(return_value=(args.java, "unit")),
                               stage=Mock(side_effect=AssertionError("owned staging failed")), verify_runtime=Mock())
        with patch.object(upgrade, "engine", return_value=core), patch.object(upgrade, "output_path", return_value=args.output), \
                self.assertRaisesRegex(AssertionError, "owned staging failed"):
            upgrade.run(args)
        receipt = upgrade.read_json(args.output / "native262-upgrade-results.json")
        self.assertEqual("FAIL", receipt["status"])
        self.assertIn("owned staging failed", receipt["traceback"])
        self.assertTrue(receipt["sourceUnchanged"])
        self.assertTrue(receipt["inputsUnchanged"])
        self.assertTrue(receipt["referenceUnchanged"])
        self.assertTrue(any(path.endswith("native12111_upgrade.py") for path in receipt["inputHashes"]))
        self.assertTrue(all(str(path) in receipt["inputHashes"] for _, path, _ in mods.values()))

    def test_hook_only_scope_never_invents_operation_pass_and_ordinary_operation_remains_required(self):
        args, prior = self.source_receipt()
        rows = [dict(name="hydroGenerator", x=1, y=5, z=1, actualName="baseelectric:hydrogenerator", properties={})]
        write_json(args.source / "migration-restarted.json", rows)
        write_json(args.source / "migration-fixture.json", rows)
        prior["phases"]["restarted"].update(snapshotSha256=digest(args.source / "migration-restarted.json"),
                                           turbine=dict(id="baseelectric:hydroturbine", uuid=[1, 2]))
        write_json(args.source / "receipt.json", prior)
        args.receipt_sha256 = digest(args.source / "receipt.json")
        (args.source / WORLD).mkdir()
        args.profile, args.inventory = self.root / "profile.json", self.root / "inventory.json"
        write_json(args.profile, {})
        write_json(args.inventory, {})
        args.seed = self.root / "seed"
        args.seed.mkdir()
        args.java = self.root / "java.exe"
        args.java.write_bytes(b"unit fake, never executed")
        args.port, args.inventory_sha256 = 26560, digest(args.inventory)
        mods = {}
        for name in upgrade.MOD_IDS:
            path = self.root / (name + ".jar")
            path.write_bytes(name.encode())
            mods[name] = "unit", path, digest(path)

        def stage(runtime, *values):
            runtime.mkdir()
            return {}

        def snapshot(runtime, java, label, action, **options):
            write_json(runtime / ("migration-" + label + ".json"), rows)
            return rows

        core = SimpleNamespace(read_family_target=Mock(return_value=(args.seed, {}, mods)),
                               java25=Mock(return_value=(args.java, "unit")), stage=stage, verify_runtime=Mock())
        for hook_only in (True, False):
            args.output = self.root / ("hook-only" if hook_only else "ordinary")
            with patch.object(upgrade, "engine", return_value=core), patch.object(upgrade, "output_path", return_value=args.output), \
                    patch.object(upgrade, "with_server", side_effect=snapshot) as boots, \
                    patch.object(upgrade, "capture", return_value=dict(status="PASS", worldGenSettings={"seed": 17})), \
                    patch.object(upgrade, "turbine", return_value=prior["phases"]["restarted"]["turbine"]), \
                    patch.object(upgrade.previous, "operate", reject_operation):
                if hook_only:
                    self.assertEqual(0, upgrade.run(args, hook_only=True))
                else:
                    with self.assertRaisesRegex(AssertionError, "Required ordinary operation"):
                        upgrade.run(args)
                self.assertEqual(2, boots.call_count)
            result = upgrade.read_json(args.output / "native262-upgrade-results.json")
            self.assertNotIn("operation", result["phases"])
            if hook_only:
                self.assertEqual("PASS", result["status"])
                self.assertEqual(upgrade.HOOK_SCOPE, result["scope"])
                self.assertIs(False, result["qualificationAllowed"])
                self.assertEqual("NOT_RUN", result["operationNotRun"]["status"])
            else:
                self.assertEqual("FAIL", result["status"])
                self.assertEqual(upgrade.SCOPE, result["scope"])
                self.assertNotIn("operationNotRun", result)

    def test_hook_scope_rejects_implicit_truthy_mode_values(self):
        for value in ("true", 1, None, []):
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, "explicitly boolean"):
                upgrade.run(None, hook_only=value)


if __name__ == "__main__":
    unittest.main()
