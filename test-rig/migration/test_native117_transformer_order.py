"""Strict observer negative controls; synthetic fixtures here are unit data, not runtime proof."""

import unittest
import copy
import json
from pathlib import Path
import tempfile
from unittest.mock import patch

import transformer_order as historical
import native117_audit_order as audit
import native117_transformer_order as native
import native117_observation_plan as plans
from matrix import digest, write_json
from native117_code import INVENTORY, code_files, verify_code


class NativeTransformerOrderAdapterTest(unittest.TestCase):
    def test_audit_uses_native_actor_decoder_before_strict_replay(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for name, value in {
                "results.json": {"original110/natural": {}},
                "source-fingerprints.json": {},
                "probe-build.json": {"patched_sha256": "hash"},
                "summary.json": {"patchedLibrarySha256": "hash"},
            }.items():
                (root / name).write_text(json.dumps(value))
            mixed = root / "original110-mixed-input"
            mixed.mkdir()
            (mixed / "semantic-source.json").write_text("{}")
            with patch.object(audit, "digest", return_value="hash"), \
                 patch.object(historical, "assert_staged"), \
                 patch.object(audit, "capture_actors", create=True,
                              side_effect=RuntimeError("native decoder reached")) as capture, \
                 patch.object(audit.actors, "capture",
                              side_effect=AssertionError("historical decoder bypasses native UUIDs")):
                with self.assertRaisesRegex(RuntimeError, "native decoder reached"):
                    audit.replay(root)
                runtime = root / "original110-natural"
                capture.assert_called_once_with(runtime, "converted", runtime / "ordering-evidence/converted")

    def test_packaged_platform_is_not_a_dev_cache_or_historical_guess(self):
        self.assertIsNot(historical.order_evidence.__code__, native.native_order_evidence.__code__)
        self.assertEqual("net/minecraft/world/level/storage/LevelStorageSource", native.SUFFIXES["world_info"][0])
        self.assertEqual("net/minecraft/world/level/chunk/storage/ChunkStorage", native.SUFFIXES["chunk_status"][0])
        self.assertEqual("net/minecraft/server/level/WorldGenRegion", native.SUFFIXES["worldgen_guard"][0])
        self.assertEqual("coremods-5.0.1.jar", native.COREMODS.name)
        self.assertEqual(native.COREMODS.as_posix(), plans.COREMODS)
        self.assertEqual(("asm-9.1.jar", "asm-tree-9.1.jar", "modlauncher-9.0.7.jar", "gson-2.8.0.jar"),
                         tuple(path.rsplit("/", 1)[1] for path in native.BRIDGE_LIBRARIES))
        self.assertEqual("native117-transformer-order-probe", plans.BRIDGE_SOURCE.parent.name)
        self.assertNotEqual(native.GUARD_METHODS["bm"], native.GUARD_METHODS["pa"])


def hook(mod, name, target, descriptor):
    return dict(method="nativeMethod" + target, index=0, owner=native.OWNERS[mod], name=name, descriptor=descriptor)


class NativeOrderEvidenceNegativeControls(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        names = [mod + "_legacy_" + suffix for suffix in native.SUFFIXES for mod in ("basemetals", "poweradvantage")]
        names += [native.ENTITY_NAME]
        self.events = [dict(event="registration", bridge="native117-v1", mode="bm-first", natural=names[:], forced=names[:])]
        for suffix, (class_name, _) in native.SUFFIXES.items():
            first, final = [], []
            for mod in ("bm", "pa"):
                if suffix == "world_info":
                    calls = [hook(mod, "prepareLegacyWorld", native.WORLD_METHOD, "(Ljava/io/File;)V")]
                elif suffix == "chunk_status":
                    calls = [hook(mod, "prepareLegacyChunk", native.CHUNK_METHOD, "(" + native.NBT + ")V"),
                             hook(mod, "finalizeLegacyChunk", native.CHUNK_METHOD, native.ENTITY_METHOD)]
                    if mod == "pa":
                        calls += [hook(mod, "prepareLegacyEntities", native.CHUNK_METHOD, "(" + native.NBT + ")V"),
                                  hook(mod, "copyChunkForMigration", "(Lnet/minecraft/world/level/ChunkPos;)" + native.NBT,
                                       native.ENTITY_METHOD)]
                else:
                    call = ("(Lnet/minecraft/server/level/WorldGenRegion;Lnet/minecraft/core/BlockPos;)Z"
                            if mod == "bm" else native.GUARD_METHODS["bm"])
                    calls = [hook(mod, "shouldBlockWorldgenWrite", native.GUARD_METHODS[mod], call)]
                if mod == "bm":
                    first = calls
                else:
                    # Prefixes prepend, return hooks append, and the two guards occupy different methods.
                    final = [entry for entry in calls if entry["name"] != "finalizeLegacyChunk"] + first
                    final += [entry for entry in calls if entry["name"] == "finalizeLegacyChunk"]
                    if suffix == "chunk_status":
                        final = [calls[0], first[0], calls[2], calls[3], first[1], calls[1]]
            self.events += [self.event(class_name, "basemetals_legacy_" + suffix, [], first),
                            self.event(class_name, "poweradvantage_legacy_" + suffix, first, final)]
        entity_hooks = [hook("pa", name, native.ENTITY_METHOD, native.ENTITY_METHOD)
                        for name in ("prepareEntityChunkForMigration", "finalizeLegacyChunk")]
        self.events.append(self.event(native.ENTITY_CLASS, native.ENTITY_NAME, [], entity_hooks))

    @staticmethod
    def event(class_name, name, before, after):
        return dict(event="application", **{"class": class_name}, transformer=name,
                    delegate="net.minecraftforge.coremod.transformer.CoreModClassTransformer",
                    labels=["basemetals" if name.startswith("basemetals_") else "poweradvantage", name],
                    before=copy.deepcopy(before), after=copy.deepcopy(after))

    def save(self):
        for index, event in enumerate(self.events, 1):
            event["sequence"] = index
            if event["event"] != "application":
                continue
            for phase in ("before", "after"):
                path = self.root / "transformer-order-bytecode" / (str(index) + "-" + phase + ".class")
                path.parent.mkdir(exist_ok=True)
                # Deliberately JSON unit data. Only the mocked decoder accepts these bytes.
                write_json(path, {"class": event["class"], "hooks": event[phase]})
                event[phase + "Dump"] = path.relative_to(self.root).as_posix()
                event[phase + "Sha256"] = digest(path)
        (self.root / "transformer-order-events.jsonl").write_text("\n".join(json.dumps(event) for event in self.events))

    def prove(self, mode="bm-first"):
        self.save()
        with patch.object(native, "inspection_command", return_value=["unit-decoder-only"]), \
             patch.object(native, "inspect_dump", side_effect=lambda command, path: json.loads(path.read_text())):
            return native.native_order_evidence(self.root, mode)

    def test_true_pairs_and_unpaired_entity_storage_are_distinguished(self):
        proof = self.prove()
        self.assertEqual("PASS", proof["status"])
        self.assertFalse(proof["classes"]["separate_entity_storage"]["paired"])
        self.assertEqual([native.ENTITY_NAME], proof["classes"]["separate_entity_storage"]["application_order"])
        guard = proof["classes"]["worldgen_guard"]["emitted_hook_order"]["shouldBlockWorldgenWrite"]
        self.assertEqual([["bm"], ["pa"]], sorted(guard.values()))

    def test_natural_order_uses_the_observed_natural_registration(self):
        self.events[0]["mode"] = "natural"
        self.assertEqual("PASS", self.prove("natural")["status"])

    def test_reverse_order_has_reversed_prefixes_and_appended_returns(self):
        self.events[0]["mode"] = "pa-first"
        for index, suffix in enumerate(native.SUFFIXES):
            bm_index = index * 2
            names = self.events[0]["forced"]
            names[bm_index], names[bm_index + 1] = names[bm_index + 1], names[bm_index]
            offset = bm_index + 1
            bm, pa = self.events[offset:offset + 2]
            all_hooks = copy.deepcopy(pa["after"])
            pa["before"] = []
            pa["after"] = [entry for entry in all_hooks if native.hook_owner(entry) == "pa"]
            bm["before"] = copy.deepcopy(pa["after"])
            if suffix == "world_info":
                bm["after"] = list(reversed(all_hooks))
            elif suffix == "chunk_status":
                def entry(mod, name):
                    return next(value for value in all_hooks if native.hook_owner(value) == mod and value["name"] == name)
                bm["after"] = [entry("bm", "prepareLegacyChunk"), entry("pa", "prepareLegacyChunk"),
                    entry("pa", "prepareLegacyEntities"), entry("pa", "copyChunkForMigration"),
                    entry("pa", "finalizeLegacyChunk"), entry("bm", "finalizeLegacyChunk")]
            else:
                bm["after"] = all_hooks
            self.events[offset:offset + 2] = [pa, bm]
        self.assertEqual("PASS", self.prove("pa-first")["status"])

    def test_registration_alone_fails(self):
        self.events = self.events[:1]
        with self.assertRaisesRegex(AssertionError, "class applications"):
            self.prove()

    def test_wrong_application_order_fails(self):
        self.events[1], self.events[2] = self.events[2], self.events[1]
        with self.assertRaisesRegex(AssertionError, "application order"):
            self.prove()

    def test_wrong_forced_order_fails(self):
        self.events[0]["forced"] = list(reversed(self.events[0]["forced"]))
        with self.assertRaisesRegex(AssertionError, "requested real pairs"):
            self.prove()

    def test_missing_injection_fails(self):
        self.events[1]["after"] = []
        self.events[2]["before"] = []
        with self.assertRaisesRegex(AssertionError, "injection"):
            self.prove()

    def test_duplicate_callback_fails(self):
        self.events.append(copy.deepcopy(self.events[1]))
        with self.assertRaisesRegex(AssertionError, "application order"):
            self.prove()

    def test_wrong_emitted_order_fails(self):
        self.events[2]["after"].reverse()
        with self.assertRaisesRegex(AssertionError, "emitted hook order"):
            self.prove()

    def test_disconnected_nodes_fail(self):
        self.events[2]["before"] = []
        with self.assertRaisesRegex(AssertionError, "same evolving class"):
            self.prove()

    def test_duplicate_injection_fails(self):
        self.events[1]["after"].append(copy.deepcopy(self.events[1]["after"][0]))
        self.events[2]["before"] = copy.deepcopy(self.events[1]["after"])
        with self.assertRaisesRegex(AssertionError, "injection"):
            self.prove()

    def test_entity_storage_is_required(self):
        self.events.pop()
        with self.assertRaisesRegex(AssertionError, "class applications"):
            self.prove()

    def test_entity_hook_noop_fails(self):
        self.events[-1]["after"] = []
        with self.assertRaisesRegex(AssertionError, "injection"):
            self.prove()

    def test_invented_entity_partner_fails(self):
        self.events[-1]["after"].append(hook("bm", "prepareEntityChunkForMigration", native.ENTITY_METHOD, native.ENTITY_METHOD))
        with self.assertRaisesRegex(AssertionError, "Invented Base Metals"):
            self.prove()

    def test_missing_real_delegate_identity_fails(self):
        self.events[1]["delegate"] = "SyntheticTransformer"
        with self.assertRaisesRegex(AssertionError, "real CoreMods"):
            self.prove()

    def test_wrong_native_guard_method_fails(self):
        self.events[5]["after"][0]["method"] = "wrong" + native.GUARD_METHODS["pa"]
        self.events[6]["before"] = copy.deepcopy(self.events[5]["after"])
        with self.assertRaisesRegex(AssertionError, "descriptor or target"):
            self.prove()

    def test_wrong_nbt_descriptor_fails(self):
        self.events[-1]["after"][0]["descriptor"] = "()V"
        with self.assertRaisesRegex(AssertionError, "descriptor or target"):
            self.prove()

    def test_entity_preparation_cannot_jump_ahead_of_either_chunk_hook(self):
        hooks = self.events[4]["after"]
        entity = next(entry for entry in hooks if entry["name"] == "prepareLegacyEntities")
        hooks.remove(entity)
        hooks.insert(0, entity)
        with self.assertRaisesRegex(AssertionError, "must follow both chunk"):
            self.prove()

    def test_json_claims_cannot_replace_native_bytecode_or_custody(self):
        self.save()
        with self.assertRaises(FileNotFoundError):
            native.native_order_evidence(self.root, "bm-first")
        with patch.object(native, "inspection_command", return_value=["unit-decoder-only"]), \
             patch.object(native, "inspect_dump", return_value={"class": native.ENTITY_CLASS, "hooks": []}):
            with self.assertRaisesRegex(AssertionError, "actual bytecode"):
                native.native_order_evidence(self.root, "bm-first")

    def test_changed_bytecode_fails(self):
        self.save()
        (self.root / self.events[1]["afterDump"]).write_bytes(b"changed")
        with patch.object(native, "inspection_command", return_value=["unit-decoder-only"]), \
             patch.object(native, "inspect_dump", side_effect=lambda command, path: json.loads(path.read_text())):
            with self.assertRaisesRegex(AssertionError, "hash changed"):
                native.native_order_evidence(self.root, "bm-first")

    def test_no_registration_sequence_replay(self):
        self.save()
        self.events[-1]["sequence"] = 1
        (self.root / "transformer-order-events.jsonl").write_text("\n".join(json.dumps(event) for event in self.events))
        with self.assertRaisesRegex(AssertionError, "unordered"):
            native.native_order_evidence(self.root, "bm-first")


class NativeObservationPlanControls(unittest.TestCase):
    def setUp(self):
        import zipfile
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.seed = self.root / "seed"
        original = self.seed / plans.COREMODS
        original.parent.mkdir(parents=True)
        self.observer = self.root / "observer.jar"
        with zipfile.ZipFile(original, "w") as archive:
            archive.writestr(historical.ENGINE, b"original-engine-unit-fixture")
            archive.writestr("unrelated", b"unaltered")
        with zipfile.ZipFile(self.observer, "w") as archive:
            archive.writestr(historical.ENGINE, b"observed-engine-unit-fixture")
            archive.writestr("unrelated", b"unaltered")
            for name in ("TransformerOrderBridge.class", "TransformerOrderBridge$Observed.class"):
                archive.writestr("qualification/order/" + name, b"unit-fixture-not-runtime-proof")
        self.dependency = self.seed / "libraries/asm-unit.jar"
        self.dependency.write_bytes(b"unit-dependency")
        write_json(self.seed / INVENTORY, {"files": code_files(self.seed), "format": 1, "minecraft": "1.17.1", "forge": "37.1.1"})
        self.baseline = digest(self.seed / INVENTORY)
        self.proof = self.root / "build.json"
        write_json(self.proof, dict(historical.validate_patch(original, self.observer),
            sourceSha256=digest(plans.BRIDGE_SOURCE), dependencies={str(self.dependency): digest(self.dependency)}))
        self.plan = self.root / "plan.json"
        write_json(self.plan, plans.make_plan(self.seed, self.baseline, self.observer, digest(self.observer),
                                            self.proof, digest(self.proof)))
        self.sha = digest(self.plan)

    def test_observer_replaces_only_coremods_with_original_gate_negative_control(self):
        runtime = self.root / "runtime"
        proof = plans.stage_probe(runtime, self.plan, self.sha)
        self.assertEqual("PASS_CODE_CUSTODY_ONLY_NO_MC", proof["status"])
        self.assertEqual("REJECTED_AS_EXPECTED", proof["baselineGateBeforeReseal"])
        self.assertEqual([plans.COREMODS], proof["changedPaths"])
        self.assertEqual(self.baseline, digest(self.seed / INVENTORY))
        verify_code(self.seed)
        verify_code(runtime)
        self.assertEqual(proof, plans.audit_runtime(runtime, self.plan, self.sha))

    def test_added_code_after_reseal_fails(self):
        runtime = self.root / "runtime"
        plans.stage_probe(runtime, self.plan, self.sha)
        (runtime / "rogue.jar").write_bytes(b"unlisted-code")
        with self.assertRaisesRegex(AssertionError, "exact sealed inventory"):
            plans.audit_runtime(runtime, self.plan, self.sha)

    def test_changed_original_seed_fails(self):
        self.dependency.write_bytes(b"changed-dependency")
        with self.assertRaisesRegex(AssertionError, "exact sealed inventory"):
            plans.load_plan(self.plan, self.sha)

    def test_changed_observer_fails(self):
        self.observer.write_bytes(b"changed-observer")
        with self.assertRaisesRegex(AssertionError, "pinned observation input"):
            plans.load_plan(self.plan, self.sha)

    def test_protected_seed_cannot_be_a_staging_target(self):
        with self.assertRaisesRegex(ValueError, "overlaps a protected input"):
            plans.stage_probe(self.seed / "runtime", self.plan, self.sha)

    def test_wrong_runtime_metadata_and_unknown_inventory_keys_fail(self):
        inventory = plans.read(self.seed / INVENTORY)
        for key, value in (("minecraft", "1.16.5"), ("forge", "37.1.2"), ("format", True), ("unexpected", "ignored")):
            changed = copy.deepcopy(inventory)
            changed[key] = value
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, "exact nonempty code inventory"):
                plans.validate_inventory(changed)


if __name__ == "__main__":
    unittest.main()
