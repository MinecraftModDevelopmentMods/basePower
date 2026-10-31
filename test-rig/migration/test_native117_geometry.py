"""Engine geometry and independent historical-oracle preservation contracts."""

import ast
import copy
import hashlib
import json
from pathlib import Path
import unittest
import tempfile
import zipfile

from matrix import digest
from native117 import RIG
from native117_geometry import (ENGINE_SOURCES, FORGE_VERSION, MCP_VERSION, MINECRAFT_VERSION,
                                SOURCE_ARCHIVE_SHA256, SOURCE_SHA256, require_geometry,
                                verify_engine_sources)
from native117_campaign import ACCEPTED_POWER114, REQUIRED, require_routes, require_source114_receipt
from native117_harvest import EXPECTED, require_checks, mark_runtime


class GeometryTests(unittest.TestCase):
    def test_native_engine_source_pin_is_not_the_historical_engine_relabelled(self):
        self.assertEqual((MINECRAFT_VERSION, FORGE_VERSION, MCP_VERSION),
                         ("1.17.1", "37.1.1", "20210706.113038"))
        self.assertEqual(SOURCE_ARCHIVE_SHA256,
                         "ACB95ACEBB65CE2EE034E887F324F6CC18C7C628D0CF97358F4D2F64572AAA0B")
        self.assertEqual(SOURCE_SHA256,
                         "5BB6FDF6E625CB86C5A7975F686DB89306729C0517D39350BFD88BD77C5EBBD4")
        self.assertEqual(ENGINE_SOURCES["net/minecraft/server/level/DistanceManager.java"], SOURCE_SHA256)
        self.assertEqual(len(ENGINE_SOURCES), 6)
        self.assertEqual(hashlib.sha256(json.dumps(ENGINE_SOURCES, sort_keys=True,
                                                  separators=(",", ":")).encode()).hexdigest().upper(),
                         "EC4EC6A1DAE0006C11EA42311B046E8AF169172343350311871ECBC73FA80E45")
        self.assertNotEqual(SOURCE_SHA256,
                            "A1C8999617D792843C879C04E49F8658C299E5A423B4F44468F8F70961A68104")

    def test_unverified_source_archive_cannot_become_native_proof_by_renaming(self):
        with tempfile.TemporaryDirectory() as folder:
            archive = Path(folder) / "forge-1.17.1-37.1.1-injected-sources.jar"
            with zipfile.ZipFile(archive, "w") as sources:
                for name in ENGINE_SOURCES:
                    sources.writestr(name, "unverified or historical source")
            with self.assertRaisesRegex(AssertionError, "source archive differs"):
                verify_engine_sources(archive)

    def sample(self):
        return dict(ticketGeometry=dict(fullLevel=33, entityTickingLevel=31, maxDistance=11,
                                        lastHolderLevel=44, holderRadius=13, observedForcedLevels=[31]))

    def test_real_forced_ticket_level_and_generation_halo_are_both_required(self):
        require_geometry(self.sample(), forced=True)
        for key in ("fullLevel", "entityTickingLevel", "maxDistance", "lastHolderLevel", "holderRadius"):
            value = self.sample()
            value["ticketGeometry"][key] += 1
            with self.subTest(key=key), self.assertRaises(AssertionError):
                require_geometry(value)

    def test_empty_tickets_only_allowed_after_release(self):
        value = self.sample()
        value["ticketGeometry"]["observedForcedLevels"] = []
        require_geometry(value)
        with self.assertRaises(AssertionError):
            require_geometry(value, forced=True)

    def test_missing_or_historical_assumptions_cannot_count_as_live_geometry(self):
        with self.assertRaises(AssertionError):
            require_geometry({})
        value = self.sample()
        value["ticketGeometry"]["observedForcedLevels"] = [30, 31]
        with self.assertRaises(AssertionError):
            require_geometry(value)


class HarvestTests(unittest.TestCase):
    def test_disposable_harvest_runtime_has_both_required_markers(self):
        with tempfile.TemporaryDirectory() as folder:
            mark_runtime(Path(folder))
            self.assertEqual({p.name for p in Path(folder).iterdir()},
                             {"advantage-native-harvest.allow", "advantage-works.allow"})

    def sample(self):
        rows = []
        for name, (level, allowed, silk, minimum, maximum, trials) in EXPECTED.items():
            rows.append(dict(id=name, status="PASS", historicalHarvestLevel=level, harvestAllowed=allowed,
                             silkTouch=silk, trials=trials, actualDropCounts=[minimum] * trials,
                             actualSolid=name.startswith(("sulfur/", "lithium/", "wire/")),
                             fullCollision=not name.startswith("wire/"),
                             connectionMask=int(name.split("/")[1]) if name.startswith(("wire/", "scaffold/")) else -1))
        rows.append(dict(id="solar/native-solidity", status="PASS", actualSolid=True))
        return dict(label="exercise", minecraft="1.17.1", status="PASS", checks=rows)

    def test_complete_native_survival_witness_set(self):
        require_checks(self.sample(), "exercise")
        self.assertEqual(len(EXPECTED), 270)

    def test_json_presence_or_pass_label_without_real_samples_is_not_enough(self):
        value = self.sample()
        value["checks"][0]["actualDropCounts"] = []
        with self.assertRaises(AssertionError):
            require_checks(value, "exercise")

    def test_failure_tool_drop_silk_drop_quantity_and_level_remain_strict(self):
        for name, field, bad in (("lithium/wood-fails", "harvestAllowed", True),
                                 ("lithium/stone", "historicalHarvestLevel", 0),
                                 ("sulfur/silk", "actualDropCounts", [2]),
                                 ("sulfur/wood", "actualDropCounts", [5] * 8),
                                 ("sulfur/hand-fails", "actualDropCounts", [1])):
            value = self.sample()
            next(row for row in value["checks"] if row["id"] == name)[field] = bad
            with self.subTest(name=name), self.assertRaises(AssertionError):
                require_checks(value, "exercise")

    def test_missing_or_duplicated_witnesses_fail(self):
        value = self.sample()
        value["checks"][-1] = copy.deepcopy(value["checks"][0])
        with self.assertRaises(AssertionError):
            require_checks(value, "exercise")

    def test_all_wire_scaffold_states_and_legacy_solar_solidness_are_strict(self):
        for name, field, bad in (("wire/63/hand", "connectionMask", 0),
                                 ("wire/0/hand", "actualSolid", False),
                                 ("wire/31/silk", "fullCollision", True),
                                 ("scaffold/31/silk", "actualSolid", True),
                                 ("scaffold/0/hand", "fullCollision", False),
                                 ("solar/native-solidity", "actualSolid", False)):
            value = self.sample()
            next(row for row in value["checks"] if row["id"] == name)[field] = bad
            with self.subTest(name=name), self.assertRaises(AssertionError):
                require_checks(value, "exercise")


class PortContracts(unittest.TestCase):
    def validate_ledger(self, ledger):
        self.assertEqual(ledger["format"], 1)
        self.assertEqual(ledger["sourceCommit"], "c310d0ca63ad9a8408dd4bba75172afafa5c71b7")
        self.assertEqual(ledger["sourceTree"], "5d3893fc5316a5af20d2456d51cc020a7741f5b0")
        hashes = ledger["hashes"]
        excluded = ledger["excludedNativeAdaptations"]
        self.assertEqual(len(hashes), 298)
        self.assertEqual(len(excluded), 94)
        self.assertFalse(set(hashes) & set(excluded))
        self.assertEqual(ledger["baselineFileCount"], 392)
        inventory = dict(hashes)
        inventory.update({name: record["historicalSha256"] for name, record in excluded.items()})
        self.assertEqual(len(inventory), 392)
        canonical = lambda value: hashlib.sha256(json.dumps(value, sort_keys=True,
                                                           separators=(",", ":")).encode()).hexdigest().upper()
        self.assertEqual(ledger["baselineInventorySha256"],
                         "C606235169C963A2CEF5445E87A645A73228AE05A35D2EFB89D22ABD6E88B559")
        self.assertEqual(canonical(inventory), ledger["baselineInventorySha256"])
        self.assertEqual(ledger["excludedNativePathsSha256"],
                         "B0B86A9CFD2CF5065C155A44381AFE240CD5625C003A063E916CDE87918279BC")
        self.assertEqual(canonical(sorted(excluded)), ledger["excludedNativePathsSha256"])
        clients = {name: {key: record[key] for key in (
            "historicalSha256", "auditedNativeSha256", "portPreservationGuards", "preservationEvidence")}
            for name, record in excluded.items() if record["kind"] == "native-client-adaptation"}
        self.assertEqual(len(clients), 8)
        self.assertEqual(ledger["nativeClientAdaptationsSha256"],
                         "B7D9EC6E6146AE08A0838D187E52E353D836B327919B705026B84E9FB57098B7")
        self.assertEqual(canonical(clients), ledger["nativeClientAdaptationsSha256"])
        revisions = ledger['documentationRevisions']
        self.assertEqual({'client-resources/README.md'}, set(revisions))
        revision = revisions['client-resources/README.md']
        self.assertEqual(clients['client-resources/README.md']['auditedNativeSha256'], revision['previousAuditedSha256'])
        self.assertEqual('FA7900D9190957B07A915597404AA63027B2E0C950BA26C3ED0F2424FA651595', revision['nativeSha256'])
        self.assertIn('Human instructions only', revision['scope'])
        evidence = ledger["nativeClientPreservation"]
        self.assertEqual(evidence["baselineCommit"], ledger["sourceCommit"])
        self.assertEqual(evidence["receipt"], "sidecar-summary-20261031-050041-c7861af6.json")
        self.assertEqual(evidence["receiptSha256"],
                         "AEB5F04F1B9F0FF55C4BFA8F669630DEEB1844E046DF11039F9B6CD6F8C2CF7A")
        self.assertEqual(evidence["status"], "STATIC_SIDECAR_PASS")
        self.assertEqual((evidence["preservationChecks"], evidence["geometryContractChecks"],
                          evidence["mutationControlsRejected"]), (57, 78, 28))
        self.assertEqual(evidence["negativeControlReceipt"],
                         "sidecar-negative-controls/20261031-045932-0e28b9e5ba234e728a49c4537475dd59/results.json")
        self.assertEqual(evidence["negativeControlReceiptSha256"],
                         "2D530DE914D9534B15E70878867623AECA6A69ACD1203DE1BB0388F95AF98FE4")
        metadata = {"build.gradle", "gradle.properties", "src/main/resources/META-INF/mods.toml",
                    "src/main/resources/pack.mcmeta"}
        for name, record in excluded.items():
            self.assertGreater(len(record["reason"]), 30, name)
            if name in metadata:
                self.assertEqual(record["kind"], "native-target-metadata")
            elif name in clients:
                self.assertEqual(record["preservationEvidence"], "nativeClientPreservation")
                self.assertTrue(record["portPreservationGuards"], name)
                for guard in record["portPreservationGuards"]:
                    self.assertIn(guard, clients)
                    self.assertTrue(guard.endswith(".ps1"), guard)
            else:
                self.assertEqual(record["kind"], "native-java-adaptation")
                self.assertTrue(name.endswith(".java"), name)
                self.assertTrue(name.startswith(("src/main/java/", "src/test/java/", "src/runner/java/",
                                                 "client-resources/java/")), name)
        self.assertIn("migration/native116-historical-inputs.json", hashes)
        self.assertEqual(hashes["src/test/java/zone/moddev/mc/advantageworks/NativeHarvestIsolationTest.java"],
                         "94BF6761278EDA6A60F9A593652EC7A42D1F65A8186A4E104807EFDDB7559E65")
        self.assertEqual(hashes["client-resources/resources/gui-label-expectations.json"],
                         "EC0B66B926053E90C216D856B5F33C19D5B7E92DBC4C9E2E25812B401AEBAC0E")

    def test_native_client_adaptations_match_the_approved_preservation_inputs(self):
        ledger = json.loads((RIG / "migration/native117-historical-inputs.json").read_text())
        self.validate_ledger(ledger)
        for name, record in ledger["excludedNativeAdaptations"].items():
            if record["kind"] == "native-client-adaptation":
                with self.subTest(path=name):
                    revision = ledger['documentationRevisions'].get(name)
                    expected = revision['nativeSha256'] if revision else record['auditedNativeSha256']
                    self.assertEqual(digest(RIG / name), expected)

    def test_ledger_cannot_refingerprint_or_exempt_historical_oracles(self):
        ledger = json.loads((RIG / "migration/native117-historical-inputs.json").read_text())
        self.validate_ledger(ledger)
        path = "migration/extended_lifecycle.py"
        changed = copy.deepcopy(ledger)
        changed["hashes"][path] = "0" * 64
        with self.assertRaises(AssertionError):
            self.validate_ledger(changed)
        changed = copy.deepcopy(ledger)
        changed["hashes"].pop(path)
        with self.assertRaises(AssertionError):
            self.validate_ledger(changed)
        for field, value in (("auditedNativeSha256", "0" * 64), ("portPreservationGuards", [])):
            changed = copy.deepcopy(ledger)
            changed["excludedNativeAdaptations"]["client-resources/test-probe-contract.ps1"][field] = value
            with self.subTest(unapproved_client_change=field), self.assertRaises(AssertionError):
                self.validate_ledger(changed)
        for protected in (path, "profiles/dev-core.json",
                          "src/main/resources/assets/advantageworkstest/works/works-1.16.5.json",
                          "client-resources/resources/gui-label-expectations.json",
                          "src/test/java/zone/moddev/mc/advantageworks/NativeHarvestIsolationTest.java"):
            changed = copy.deepcopy(ledger)
            checksum = changed["hashes"].pop(protected)
            native_path = next(name for name in changed["excludedNativeAdaptations"] if name.endswith(".java"))
            native = changed["excludedNativeAdaptations"].pop(native_path)
            changed["hashes"][native_path] = native["historicalSha256"]
            changed["excludedNativeAdaptations"][protected] = dict(
                historicalSha256=checksum, kind="native-java-adaptation", reason="unapproved new exemption")
            self.assertEqual(len(changed["hashes"]), len(ledger["hashes"]))
            self.assertEqual(len(changed["excludedNativeAdaptations"]), len(ledger["excludedNativeAdaptations"]))
            with self.subTest(path=protected), self.assertRaises(AssertionError):
                self.validate_ledger(changed)

    def test_historical_inputs_match_accepted_baseline_with_original_correction_guards(self):
        ledger = json.loads((RIG / "migration/native117-historical-inputs.json").read_text())
        self.validate_ledger(ledger)
        historical = json.loads((RIG / "migration/native116-historical-inputs.json").read_text())
        self.assertEqual(historical["sourceCommit"], "5b8e73a78391960643420a09798a1ef8eb06f518")
        approval = json.loads((RIG / "migration/audited-semantic-corrections.json").read_text())
        self.assertEqual(approval["id"], "EXTENDED-ABSENCE-001")
        records = approval["corrections"]
        self.assertIn("migration/extended_lifecycle.py", records)
        self.assertLessEqual(set(records), {"migration/extended_lifecycle.py", "migration/test_native115_geometry.py"})

        def verify(data, expected, record):
            self.assertEqual(record["correctedSha256"], expected)
            self.assertEqual(hashlib.sha256(data).hexdigest().upper(), expected)
            tree = ast.parse(data.decode("utf-8-sig"))
            if record["kind"] == "semantic-oracle-correction":
                names = {"partial_result", "continuous_absence"}
                functions = {n.name: hashlib.sha256(ast.dump(n).encode()).hexdigest().upper()
                             for n in tree.body if isinstance(n, ast.FunctionDef) and n.name in names}
                self.assertEqual(functions, record["correctedFunctionAstSha256"])
                tree.body = [n for n in tree.body if not (isinstance(n, ast.FunctionDef) and n.name in names)]
            else:
                self.assertEqual(record["kind"], "historical-guard-reporting-correction")
                for node in tree.body:
                    if isinstance(node, ast.ClassDef) and node.name == "PortContracts":
                        node.body = [n for n in node.body if not (isinstance(n, ast.FunctionDef) and n.name in (
                            "test_historical_adapters_profiles_and_semantic_oracles_are_byte_identical",
                            "test_historical_inputs_remain_exact_except_audited_semantic_corrections"))]
                tree.body = [n for n in tree.body if not (isinstance(n, ast.Import)
                             and all(a.name in ("ast", "hashlib") for a in n.names))]
            self.assertEqual(hashlib.sha256(ast.dump(tree).encode()).hexdigest().upper(),
                             record["unchangedHistoricalAstSha256"])

        for relative, checksum in ledger["hashes"].items():
            with self.subTest(path=relative):
                self.assertEqual(digest(RIG / relative), checksum)
                if relative in records:
                    self.assertEqual(records[relative]["historicalSha256"], historical["hashes"][relative])
                    verify((RIG / relative).read_bytes(), checksum, records[relative])

        # Updating only a byte hash must not bless a lower threshold or unrelated oracle edit.
        source = (RIG / "migration/extended_lifecycle.py").read_bytes()
        for old, new in ((b">= 40", b">= 39"),
                         (b"four consecutive moves were not witnessed", b"three consecutive moves were not witnessed")):
            changed = source.replace(old, new, 1)
            self.assertNotEqual(changed, source)
            record = copy.deepcopy(records["migration/extended_lifecycle.py"])
            record["correctedSha256"] = hashlib.sha256(changed).hexdigest().upper()
            with self.subTest(unapproved_change=new), self.assertRaises(AssertionError):
                verify(changed, record["correctedSha256"], record)

    def test_all_thirteen_old_routes_and_comprehensive_native114_source_are_required(self):
        self.assertEqual(len(REQUIRED["frozen"]), 16)
        self.assertEqual(len(REQUIRED["connected"]), 20)
        self.assertIn("native115/full", REQUIRED["frozen"])
        self.assertIn("native116/full", REQUIRED["frozen"])
        self.assertEqual(5, len([identity for identity in REQUIRED["connected"] if identity.startswith("native115/")]))
        self.assertEqual(5, len([identity for identity in REQUIRED["connected"] if identity.startswith("native116/")]))
        document = dict(format=1, acceptedPower114=ACCEPTED_POWER114,
                        routes=[dict(kind=kind, id=name) for kind, names in REQUIRED.items() for name in names])
        require_routes(document)
        for kind in REQUIRED:
            value = copy.deepcopy(document)
            index = next(i for i, row in enumerate(value["routes"]) if row["kind"] == kind)
            value["routes"].pop(index)
            with self.subTest(kind=kind), self.assertRaises(AssertionError):
                require_routes(value)

    def test_accepted114_cannot_be_replaced_by_another_passing_legacy_case(self):
        document = dict(format=1, acceptedPower114=ACCEPTED_POWER114,
                        routes=[dict(kind=kind, id=name) for kind, names in REQUIRED.items() for name in names])
        victim = next(row for row in document["routes"] if row["kind"] == "connected" and row["id"].startswith("native114/"))
        victim["id"] = "legacy/original110"
        with self.assertRaises(AssertionError):
            require_routes(document)

    def test_freshly_fingerprinting_an_unverified_old_destination_is_not_approval(self):
        with self.assertRaisesRegex(AssertionError, "not approval"):
            require_source114_receipt(dict(id="native114/full", kind="frozen", sourceStatus="FRESH_HASH_ONLY"))


if __name__ == "__main__":
    unittest.main()
