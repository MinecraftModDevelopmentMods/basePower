import base64
import copy
import gzip
import io
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import nbtlib as nbt

import native2612_actor_transformer_order as target


def expected_state():
    stack = dict(id="basesteam:musket", count=1, components={
        "minecraft:damage": 5, "minecraft:enchantments": dict.fromkeys(target.ENCHANTMENTS.values(), 1),
        "minecraft:custom_data": {"Nested": {"OwnerNote": "keep-this-verbatim"},
                                  "MigrationWitness": "steamadvantage:musket"}})
    return dict(entities={"cart": {"Items": [dict(Slot=1, **copy.deepcopy(stack))]},
                          "dropped-musket": {"Item": stack}})


def source_level():
    entries = [nbt.Compound(K=nbt.String(value.replace("basesteam:", "steamadvantage:")), V=nbt.Int(key))
               for key, value in target.ENCHANTMENTS.items()]
    return nbt.File({"Data": nbt.Compound(DataVersion=nbt.Int(512)),
                     "FML": nbt.Compound(Registries=nbt.Compound({
                         "minecraft:enchantments": nbt.Compound(ids=nbt.List[nbt.Compound](entries))}))})


def source_entity(witness):
    stack = nbt.Compound(id=nbt.String("steamadvantage:musket"), Count=nbt.Byte(1),
        tag=nbt.Compound(ench=nbt.List[nbt.Compound]([
            nbt.Compound(id=nbt.Short(key), lvl=nbt.Short(1)) for key in (11, 13, 10, 12)])))
    if witness == "cart":
        stack["Slot"] = nbt.Byte(1)
        return nbt.File({"Items": nbt.List[nbt.Compound]([stack])})
    return nbt.File({"Item": stack})


def row(witness, value=None):
    stream = io.BytesIO()
    (value if value is not None else source_entity(witness)).write(stream)
    return dict(witness=witness, nbtBase64=base64.b64encode(gzip.compress(stream.getvalue())).decode("ascii"))


def source_record():
    return dict(entities=[row(witness) for witness in target.WITNESSES])


def context():
    return target.source_context(source_level(), source_record(), expected_state())


class SourceContextTest(unittest.TestCase):
    def test_original_numeric_ids_and_saved_names_define_expected_not_target(self):
        result = context()
        self.assertFalse(result["targetObservationsUsed"])
        self.assertEqual([11, 13, 10, 12], result["witnesses"]["cart"]["numericIds"])
        self.assertEqual(expected_state()["entities"]["dropped-musket"]["Item"],
                         result["witnesses"]["dropped-musket"]["expectedStack"])

    def test_native_or_wrongly_typed_source_version_rejects(self):
        for version in (nbt.Int(4790), nbt.Short(512), nbt.String("512")):
            with self.subTest(version=version):
                level = source_level()
                level["Data"]["DataVersion"] = version
                with self.assertRaisesRegex(AssertionError, "raw original110"):
                    target.source_context(level, source_record(), expected_state())

    def test_saved_registry_missing_collision_duplicate_or_typed_id_rejects(self):
        for mutation in ("missing", "collision", "duplicate", "type"):
            with self.subTest(mutation=mutation):
                level = source_level()
                entries = level["FML"]["Registries"]["minecraft:enchantments"]["ids"]
                if mutation == "missing": entries.pop()
                elif mutation == "collision": entries[0]["K"] = nbt.String("minecraft:binding_curse")
                elif mutation == "duplicate": entries.append(copy.deepcopy(entries[0]))
                else: entries[0]["V"] = nbt.Short(10)
                with self.assertRaises(AssertionError):
                    target.source_context(level, source_record(), expected_state())

    def test_raw_enchantment_mutations_reject_for_both_entity_witnesses(self):
        for witness in target.WITNESSES:
            for mutation in ("string-id", "int-id", "level", "duplicate", "missing"):
                with self.subTest(witness=witness, mutation=mutation):
                    value = source_entity(witness)
                    entries = target.item(value, witness, native_format=False)["tag"]["ench"]
                    if mutation == "string-id": entries[0]["id"] = nbt.String("basesteam:powderless")
                    elif mutation == "int-id": entries[0]["id"] = nbt.Int(11)
                    elif mutation == "level": entries[0]["lvl"] = nbt.Short(2)
                    elif mutation == "duplicate": entries[0]["id"] = nbt.Short(13)
                    else: entries.pop()
                    record = source_record()
                    record["entities"] = [row(witness, value) if item["witness"] == witness else item
                                          for item in record["entities"]]
                    with self.assertRaises(AssertionError):
                        target.source_context(source_level(), record, expected_state())

    def test_missing_or_duplicate_source_entities_reject(self):
        for values in ([], [row("cart"), row("cart"), row("dropped-musket")]):
            with self.assertRaisesRegex(AssertionError, "source witness"):
                target.source_context(source_level(), dict(entities=values), expected_state())

    def test_historical_oracle_cannot_derive_curse_substitution_from_target(self):
        changed = expected_state()
        changed["entities"]["cart"]["Items"][0]["components"]["minecraft:enchantments"] = {"minecraft:binding_curse": 1}
        with self.assertRaisesRegex(AssertionError, "Historical oracle"):
            target.source_context(source_level(), source_record(), changed)

    def test_preflight_requires_receipt_and_entire_original_fingerprint_before_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "original"
            (source / target.WORLD).mkdir(parents=True)
            source_level().save(source / target.WORLD / "level.dat", gzipped=True)
            target.write_json(source / "actors-source.json", source_record())
            receipt = root / "approved.json"
            target.write_json(receipt, dict(source=str(source), sourceFingerprint=target.fingerprint(source)))
            args = SimpleNamespace(fixture=source, fixture_receipt=receipt,
                                   fixture_receipt_sha256=target.digest(receipt), output=root / "never-created")
            with patch.object(target.actors, "baseline", return_value=(expected_state(), "source")):
                self.assertEqual(512, target.preflight(args)["context"]["sourceDataVersion"])
                (source / "unapproved.txt").write_text("drift", encoding="ascii")
                with self.assertRaisesRegex(AssertionError, "approved independent receipt"):
                    target.preflight(args)
            self.assertFalse(args.output.exists())
            args.fixture_receipt_sha256 = "0" * 64
            with self.assertRaises(AssertionError): target.preflight(args)


class NativeWitnessTest(unittest.TestCase):
    def test_all_fourteen_derived_controls_reject_without_altering_baseline(self):
        actual = expected_state()
        before = copy.deepcopy(actual)
        result = target.negative_controls(context(), actual)
        self.assertEqual(before, actual)
        self.assertEqual(14, list(result["controls"].values()).count("REJECTED"))
        self.assertIs(False, result["sourceOrWorldFilesMutated"])
        self.assertIs(False, result["qualificationAllowed"])

    def test_false_native_types_cannot_equal_expected_numbers(self):
        for witness in target.WITNESSES:
            for location, value in (("count", True), ("count", 1.0), ("wear", 5.0),
                                    ("level", True), ("level", "1"), ("slot", True)):
                if location == "slot" and witness != "cart": continue
                with self.subTest(witness=witness, location=location, value=value):
                    actual = expected_state()
                    stack = target.item(actual["entities"][witness], witness, native_format=True)
                    if location == "count": stack["count"] = value
                    elif location == "wear": stack["components"]["minecraft:damage"] = value
                    elif location == "slot": stack["Slot"] = value
                    else: stack["components"]["minecraft:enchantments"]["basesteam:recoil"] = value
                    with self.assertRaises(AssertionError): target.assert_witnesses(context(), actual)

    def test_missing_duplicate_slot_and_renamed_native_musket_reject(self):
        for mutation in ("missing", "duplicate", "slot", "namespace"):
            actual = expected_state()
            values = actual["entities"]["cart"]["Items"]
            if mutation == "missing": values.clear()
            elif mutation == "duplicate": values.append(copy.deepcopy(values[0]))
            elif mutation == "slot": values[0]["Slot"] = 2
            else: values[0]["id"] = "steamadvantage:musket"
            with self.assertRaises(AssertionError): target.assert_witnesses(context(), actual)

    def test_complete_unknown_custom_payload_is_part_of_exact_oracle(self):
        actual = expected_state()
        del actual["entities"]["dropped-musket"]["Item"]["components"]["minecraft:custom_data"]["Nested"]
        with self.assertRaises(AssertionError): target.assert_witnesses(context(), actual)

    def test_stopped_audit_cannot_accept_empty_failed_or_boolean_count(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "receipt.json"
            for status, count in (("FAIL", 2), ("PASS", 0), ("PASS", 1), ("PASS", True), ("PASS", "2")):
                target.write_json(path, dict(status=status, matchedEntities=count))
                with self.assertRaises(AssertionError): target.raw_receipt(path)
            target.write_json(path, dict(status="PASS", matchedEntities=2))
            self.assertEqual(target.digest(path), target.raw_receipt(path)["sha256"])

    def test_hook_only_is_not_actor_proof(self):
        with self.assertRaisesRegex(ValueError, "all three"):
            target.run_actor_campaign(SimpleNamespace(), server_type=object, stage_function=None, hook_only=True)

    def test_actor_facade_binding_does_not_mutate_sealed_order_globals(self):
        original, modes = target.order.upgrade, target.order.MODES
        facade = SimpleNamespace()
        campaign = target.bind(target.order.run, upgrade=facade, MODES=tuple(reversed(modes)))
        self.assertIs(facade, campaign.__globals__["upgrade"])
        self.assertIs(original, target.order.upgrade)
        self.assertEqual(modes, target.order.MODES)
        self.assertEqual(21, len(target.order.GUARD_CONTROLS) + len(target.order.WOVEN_CONTROLS))
        self.assertEqual(("converted", "adapter", "restarted", "runtime", "runtime-restart"), target.PHASES)
        self.assertEqual(("conversion", "restart", "runtime-restart"), target.STOP_PHASES)


class CampaignContractTest(unittest.TestCase):
    def exercise(self, root, mutation=None):
        source = root / "source"
        source.mkdir()
        profile = root / "profile.json"
        profile.write_text("{}", encoding="ascii")
        args = SimpleNamespace(fixture=source, profile=profile, seed=root / "seed", inventory=root / "inventory",
                               inventory_sha256="0" * 64, java=root / "java.exe", output=root / "campaign", port=26560)
        approved = dict(context=context(), sourceFingerprint=target.fingerprint(source))

        def route(actual_source, runtime, actual_target, values, **hooks):
            self.assertEqual(source, actual_source)
            self.assertEqual("actors", values.kind)
            self.assertEqual(args.profile, values.target_manifest)
            self.assertEqual("26.1.2", hooks["minecraft"])
            self.assertIsNotNone(hooks["stop_audit"])
            runtime.mkdir()
            if mutation == "stop": raise RuntimeError("owned stop/raw audit failure")
            for label in target.PHASES:
                actual = expected_state()
                if mutation == "curse" and label == "converted":
                    actual["entities"]["cart"]["Items"][0]["components"]["minecraft:enchantments"] = {"minecraft:binding_curse": 1}
                if mutation != "missing-phase" or label != "runtime-restart":
                    target.write_json(runtime / ("semantic-" + label + ".json"), actual)
            for label in target.STOP_PHASES:
                path = runtime / "stopped-entity-evidence" / label / "receipt.json"
                path.parent.mkdir(parents=True)
                target.write_json(path, dict(status="FAIL" if mutation == "raw" else "PASS", matchedEntities=2))
            return {} if mutation == "empty-checks" else {"conversion": dict(status="PASS"), "runtime-restart": dict(status="PASS")}

        with patch.object(target, "preflight", return_value=approved), \
             patch.object(target.native, "read_family_target", return_value=(args.seed, {}, {})), \
             patch.object(target.native, "java25", return_value=(args.java, "25")), \
             patch.object(target.actors, "run_route", side_effect=route):
            if mutation:
                with self.assertRaises((AssertionError, RuntimeError)):
                    target.run_actor_campaign(args, server_type=object, stage_function=None)
            else:
                self.assertEqual(0, target.run_actor_campaign(args, server_type=object, stage_function=None))
        return target.upgrade.read_json(args.output / "native2612-actor-order-campaign.json")

    def test_complete_actor_campaign_requires_all_phases_raw_audits_and_controls(self):
        with tempfile.TemporaryDirectory() as directory:
            result = self.exercise(Path(directory))
        self.assertEqual("PASS", result["status"])
        self.assertEqual(set(target.PHASES), set(result["witnessChecks"]))
        self.assertEqual(set(target.STOP_PHASES), set(result["rawAudits"]))
        self.assertEqual(14, list(result["entityControls"]["controls"].values()).count("REJECTED"))
        self.assertIs(False, result["qualificationAllowed"])
        modes = dict.fromkeys(target.order.MODES, result)
        self.assertTrue(target.all_orders_proven(dict(status="PASS"), modes))
        for mutation in ("missing-mode", "failed-mode", "missing-phase", "failed-raw", "missing-control", "custody"):
            changed = copy.deepcopy(modes)
            first = changed[target.order.MODES[0]]
            if mutation == "missing-mode": del changed[target.order.MODES[0]]
            elif mutation == "failed-mode": first["status"] = "FAIL"
            elif mutation == "missing-phase": del first["witnessChecks"]["converted"]
            elif mutation == "failed-raw": first["rawAudits"]["conversion"]["status"] = "FAIL"
            elif mutation == "missing-control": del first["entityControls"]["controls"]["cart/curse"]
            else: first["sourceUnchanged"] = False
            self.assertFalse(target.all_orders_proven(dict(status="PASS"), changed), mutation)
        self.assertFalse(target.all_orders_proven(dict(status="FAIL"), modes))

    def test_curse_missing_phase_empty_checks_and_failed_stop_are_retained_failures(self):
        for mutation in ("curse", "missing-phase", "empty-checks", "raw", "stop"):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as directory:
                result = self.exercise(Path(directory), mutation)
                self.assertEqual("FAIL", result["status"])
                self.assertTrue(result["sourceUnchanged"])
                self.assertEqual("NOT_RUN", result["entityControls"]["status"])
                if mutation == "stop": self.assertIn("stop/raw audit failure", result["error"])
                if mutation == "curse": self.assertEqual("FAIL", result["witnessChecks"]["converted"]["status"])


if __name__ == "__main__":
    unittest.main()
