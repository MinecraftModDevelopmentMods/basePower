"""Source approval requires a recorded stopped-runtime receipt, never a fresh hash."""

from argparse import Namespace
from pathlib import Path
import copy
import json
import tempfile
import unittest
from unittest.mock import patch

from matrix import WORLD, digest, fingerprint, write_json
from native117 import RIG
from native117_campaign import ACCEPTED_POWER114, REQUIRED, generate114, require_source114_receipt, source_row
import native117_campaign as campaign
from native117_sources import SOURCE115
from native117_sources116 import ACCEPTED_POWER116, SOURCE116
import test_native117_sources as source_tests


class SourceReceiptTests(unittest.TestCase):
    def setUp(self):
        root = RIG / "build/port-evidence"
        root.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=root)
        self.root = Path(self.temporary.name)

    def tearDown(self):
        self.temporary.cleanup()

    def receipt(self):
        source = self.root / "disposable114"
        (source / WORLD).mkdir(parents=True)
        (source / WORLD / "level.dat").write_bytes(b"immutable stopped source witness")
        manifest = self.root / "accepted114.json"
        write_json(manifest, {})
        receipt_path = self.root / "receipt.json"
        receipt = dict(status="PASS", acceptedPower114=ACCEPTED_POWER114, sourceRuntime=str(source.resolve()),
                       runtimeFingerprint=fingerprint(source), worldFingerprint=fingerprint(source / WORLD),
                       target114Manifest=str(manifest), target114ManifestSha256=digest(manifest), blocks=355, tiles=332)
        write_json(receipt_path, receipt)
        row = dict(id="native114/full", kind="frozen", source=str(source), source114Receipt=str(receipt_path),
                   source114ReceiptSha256=digest(receipt_path), sourceStatus="GENERATED_DISPOSABLE_114_VERIFIED")
        return source, receipt_path, row

    def test_recorded_whole_runtime_and_world_fingerprints_are_both_verified(self):
        source, _, row = self.receipt()
        with patch("native117_campaign.native114.pinned_profile"):
            require_source114_receipt(row)
            (source / WORLD / "level.dat").write_bytes(b"changed")
            with self.assertRaisesRegex(AssertionError, "fingerprint changed"):
                require_source114_receipt(row)

    def test_counts_cannot_be_downgraded_even_with_a_new_receipt_digest(self):
        source, receipt_path, row = self.receipt()
        import json
        receipt = json.loads(receipt_path.read_text())
        receipt["tiles"] = 331
        write_json(receipt_path, receipt)
        row["source114ReceiptSha256"] = digest(receipt_path)
        with patch("native117_campaign.native114.pinned_profile"), self.assertRaisesRegex(AssertionError, "355-block/332-tile"):
            require_source114_receipt(row)

    def test_generated_source_metadata_is_disjoint_from_the_stopped_runtime(self):
        bundle = self.root / "generated"
        source = bundle / "native114-full/runtime"
        (source / WORLD).mkdir(parents=True)
        (source / WORLD / "level.dat").write_bytes(b"stopped world")
        write_json(source / "migration-restarted.json", [])
        write_json(source / "migration-fixture.json", {})
        manifest = self.root / "accepted114.json"
        write_json(manifest, {})
        before = fingerprint(source)
        with patch("native117_campaign.native117.pinned_profile"):
            with self.assertRaisesRegex(ValueError, "overlaps a protected input"):
                source_row(bundle, "frozen", "native114/full", source, manifest, "target114", "migration-restarted.json")
            row = source_row(bundle / "source-records", "frozen", "native114/full", source, manifest,
                             "target114", "migration-restarted.json")
        self.assertEqual(fingerprint(source), before)
        self.assertTrue(Path(row["sourceHashes"]).is_relative_to(bundle / "source-records"))
        self.assertEqual(digest(Path(row["sourceHashes"])), row["sourceHashesSha256"])

    def test_source_generation_does_not_launch_an_incomplete_comprehensive_fixture(self):
        source = self.root / "approved13"
        source.mkdir()
        write_json(source / "migration-restarted.json", [])
        manifest, hashes = self.root / "pins.json", self.root / "hashes.json"
        write_json(manifest, {})
        write_json(hashes, {})
        recipe = dict(source=str(source), sourceManifest=str(manifest), sourceManifestSha256=digest(manifest),
                      sourceHashes=str(hashes), sourceHashesSha256=digest(hashes), sourceKey="target113",
                      sourceSnapshot="migration-restarted.json", sourceSnapshotSha256=digest(source / "migration-restarted.json"))
        rows = []
        for kind, names in REQUIRED.items():
            for name in sorted(names):
                row = dict(kind=kind, id=name)
                if name.startswith("native114/"):
                    row.update(native114SourceRecipe=recipe, source114Manifest=str(manifest),
                               source114ManifestSha256=digest(manifest), sourceStatus="REQUIRES_DISPOSABLE_114_GENERATION")
                rows.append(row)
        # Put the comprehensive source first; no process is needed to reject it.
        rows.sort(key=lambda row: row["id"] != "native114/full")
        plan = self.root / "routes.json"
        write_json(plan, dict(format=1, acceptedPower114=ACCEPTED_POWER114, routes=rows))
        settings = Namespace(routes=plan, java8=self.root / "java.exe", output=self.root / "new", port=25595)
        before = fingerprint(source)
        with patch("native117_campaign.native114.pinned_profile", return_value=(self.root / "seed", "launcher", [])), \
                patch("native117_campaign.native114.run") as launch:
            with self.assertRaisesRegex(AssertionError, "355-block/332-tile"):
                generate114(settings)
            launch.assert_not_called()
        self.assertEqual(fingerprint(source), before)
        self.assertTrue((settings.output / "native114-full/source114-receipt.json").is_file())


class Generate115CampaignTests(unittest.TestCase):
    def setUp(self):
        self.fixture = source_tests.Native115SourceGeneratorTests()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.args = self.fixture.args
        recipes = {(row['kind'], row['id']): row for row in self.fixture.rows}
        rows = []
        for kind, identities in REQUIRED.items():
            for identity in sorted(identities - SOURCE115[kind] - SOURCE116[kind]):
                rows.append(copy.deepcopy(recipes.get((kind, identity), dict(id=identity, kind=kind))))
        self.plan_document = dict(format=1, acceptedPower114=ACCEPTED_POWER114, routes=rows)
        write_json(self.fixture.routes, self.plan_document)

    def fake_protocol(self, settings, kind):
        runtime = settings.output if kind == 'frozen' else settings.output / 'target115'
        self.fixture.frozen_result(runtime)
        if kind == 'connected':
            write_json(runtime / 'migration-restarted-worked.json', [])
        return 0, self.fixture.stops()

    def fake_result(self, kind, settings, stops):
        runtime = settings.output if kind == 'frozen' else settings.output / 'target115'
        proof = dict(stoppedCleanly=True, blocks=355, tiles=332, preservationStatus='PASS',
                     requestedTicks=[512, 512], advancedTicks=[512, 512])
        return runtime, 'migration-restarted.json' if kind == 'frozen' else 'restarted-worked', settings.source_key, proof

    def test_validate_only_does_not_create_any_output_or_launch(self):
        self.args.validate_only = True
        with self.fixture.pinned(), patch.object(campaign, 'run_source115') as launch:
            self.assertEqual(0, campaign.generate115(self.args))
            launch.assert_not_called()
        self.assertFalse(self.args.output.exists())

    def test_six_receipts_and_thirty_six_routes_keep_canonical_inputs_unchanged(self):
        original = fingerprint(self.fixture.root / 'protected fixtures')
        with self.fixture.pinned(), patch.object(campaign, 'run_source115', side_effect=self.fake_protocol) as run, \
                patch.object(campaign, 'source115_result', side_effect=self.fake_result):
            self.assertEqual(0, campaign.generate115(self.args))
            self.assertEqual(6, run.call_count)
            for call in run.call_args_list:
                self.assertEqual((25575, 512), (call.args[0].port, call.args[0].ticks))
                self.assertTrue(call.args[0].output.is_relative_to(self.args.output))
            bundle = json.loads((self.args.output / 'source115-receipts.json').read_text())
            document = json.loads((self.args.output / 'native117-upgrade-routes.json').read_text())
            self.assertEqual(('PASS', 6), (bundle['status'], len(bundle['receipts'])))
            campaign.require_routes(document)
            self.assertEqual(36, len(document['routes']))
            for row in document['routes']:
                campaign.require_source115_receipt(row)
            self.assertEqual('UNQUALIFIED', document['qualificationStatus'])
        self.assertEqual(original, fingerprint(self.fixture.root / 'protected fixtures'))
        self.assertEqual(self.plan_document, json.loads(self.fixture.routes.read_text()))

    def test_failed_second_source_keeps_first_receipt_and_partial_failure(self):
        calls = []
        def protocol(settings, kind):
            calls.append(settings)
            if len(calls) == 2:
                raise RuntimeError('retained server failure')
            return self.fake_protocol(settings, kind)
        with self.fixture.pinned(), patch.object(campaign, 'run_source115', side_effect=protocol), \
                patch.object(campaign, 'source115_result', side_effect=self.fake_result):
            with self.assertRaisesRegex(RuntimeError, 'retained server failure'):
                campaign.generate115(self.args)
        bundle = json.loads((self.args.output / 'source115-receipts.json').read_text())
        self.assertEqual(('FAIL', 1), (bundle['status'], len(bundle['receipts'])))
        first = json.loads(Path(bundle['receipts'][0]['receipt']).read_text())
        self.assertEqual('PASS', first['status'])
        failed = json.loads((calls[1].output.parent / 'source115-receipt.json').read_text())
        self.assertEqual(('FAIL', False), (failed['status'], failed['stoppedCleanly']))
        with self.fixture.pinned(), self.assertRaises(FileExistsError):
            campaign.generate115(self.args)

    def test_parent_canonical_thirty_routes_are_retained_without_replacing_114(self):
        canonical = copy.deepcopy(self.plan_document)
        canonical['routes'].extend(campaign.pending_routes())
        canonical['routes'].extend(campaign.pending116_routes())
        for row in canonical['routes']:
            if row['id'].startswith('native114/'):
                row['sourceStatus'] = 'REQUIRES_DISPOSABLE_114_GENERATION'
        self.args.routes = self.fixture.root / 'parent canonical routes.json'
        write_json(self.args.routes, canonical)
        before = digest(self.args.routes)
        with self.fixture.pinned(), patch.object(campaign, 'run_source115', side_effect=self.fake_protocol), \
                patch.object(campaign, 'source115_result', side_effect=self.fake_result):
            self.assertEqual(0, campaign.generate115(self.args))
        actual = json.loads((self.args.output / 'native117-upgrade-routes.json').read_text())
        self.assertEqual(before, digest(self.args.routes))
        self.assertEqual([row for row in canonical['routes'] if row['id'].startswith('native114/')],
                         [row for row in actual['routes'] if row['id'].startswith('native114/')])

    def test_canonical_recipe_change_is_not_a_new_source_approval(self):
        canonical = copy.deepcopy(self.plan_document)
        canonical['routes'].extend(campaign.pending_routes())
        canonical['routes'].extend(campaign.pending116_routes())
        next(row for row in canonical['routes'] if row['id'] == 'legacy/original110-chained-113')['sourceHashesSha256'] = '0' * 64
        self.args.routes = self.fixture.root / 'changed routes.json'
        write_json(self.args.routes, canonical)
        with self.fixture.pinned(), patch.object(campaign, 'run_source115') as launch:
            with self.assertRaisesRegex(AssertionError, 'sealed F source lineage'):
                campaign.generate115(self.args)
            launch.assert_not_called()


class Source116CampaignTests(unittest.TestCase):
    def setUp(self):
        root = RIG / "build/native117-campaign116-unit"
        root.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=root)
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.routes = self.root / "approved thirty.json"
        self.document = dict(format=1, acceptedPower114=ACCEPTED_POWER114,
            acceptedPower115=campaign.ACCEPTED_POWER115, qualificationStatus="UNQUALIFIED",
            source115Receipts="unchanged historical bundle", source115ReceiptsSha256="A" * 64, routes=[])
        for kind, identities in REQUIRED.items():
            for identity in sorted(identities - SOURCE116[kind]):
                self.document["routes"].append(dict(id=identity, kind=kind,
                    source=str(self.root / "protected" / identity), sourceManifest=str(self.root / "historical pins"),
                    sourceHashes=str(self.root / "historical hashes"), sourceStatus="RETAINED_UNIT_RECEIPT",
                    historicalReceiptWitness="byte-for-byte " + kind + ":" + identity))
        write_json(self.routes, self.document)
        self.output = self.root / "new upgraded plan"
        self.args = Namespace(routes=self.routes, output=self.output, validate_only=False)
        self.no_process = patch("subprocess.Popen", side_effect=AssertionError("No Minecraft process in campaign sidecar tests"))
        self.no_process.start()
        self.addCleanup(self.no_process.stop)

    def guards(self):
        from contextlib import ExitStack
        stack = ExitStack()
        stack.enter_context(patch.object(campaign, "require_source114_receipt"))
        stack.enter_context(patch.object(campaign, "require_source115_receipt"))
        return stack

    def upgrade(self):
        with self.guards():
            self.assertEqual(0, campaign.upgrade116(self.args))
        return self.output / "native117-upgrade-routes.json"

    def attachment(self):
        plan = self.upgrade()
        sources_path = self.root / "generated sources/source116-routes.json"
        bundle_path = sources_path.with_name("source116-receipts.json")
        rows, receipts = [], []
        for kind, identities in SOURCE116.items():
            for identity in sorted(identities):
                path = self.root / "generated sources" / identity / "source116-receipt.json"
                write_json(path, dict(status="PASS", scope="UNIT_ONLY_NEVER_RUNTIME"))
                checksum = digest(path)
                rows.append(dict(id=identity, kind=kind, source=str(path.parent / "runtime"),
                    sourceManifest=str(self.root / "pins116.json"), sourceHashes=str(path.parent / "world-hashes.json"),
                    sourceStatus="GENERATED_DISPOSABLE_116_VERIFIED", source116Receipt=str(path), source116ReceiptSha256=checksum))
                receipts.append(dict(id=identity, receipt=str(path), sha256=checksum))
        generated = dict(format=1, acceptedPower116=ACCEPTED_POWER116, qualificationStatus="UNQUALIFIED", routes=rows)
        bundle = dict(status="PASS", scope=campaign.SOURCE116_SCOPE, acceptedPower116=ACCEPTED_POWER116,
            canonicalSourcesUnchanged=True, seedCodeUnchanged=True, receipts=receipts)
        write_json(sources_path, generated)
        write_json(bundle_path, bundle)
        args = Namespace(routes=plan, sources=sources_path, receipts=bundle_path, output=self.root / "attached plan")
        return args, generated, bundle

    def test_required_route_set_is_now_exactly_sixteen_frozen_twenty_connected(self):
        self.assertEqual((16, 20, 36), (len(REQUIRED["frozen"]), len(REQUIRED["connected"]), sum(map(len, REQUIRED.values()))))

    def test_old_thirty_routes_cannot_run_without_explicit_upgrade(self):
        with self.assertRaisesRegex(AssertionError, "36"):
            campaign.require_routes(self.document)

    def test_every_new_source_is_required_and_duplicates_do_not_substitute(self):
        document = copy.deepcopy(self.document)
        document["routes"].extend(campaign.pending116_routes())
        campaign.require_routes(document)
        for identity in SOURCE116["frozen"] | SOURCE116["connected"]:
            for duplicate in (False, True):
                changed = copy.deepcopy(document)
                changed["routes"] = [row for row in changed["routes"] if row["id"] != identity]
                if duplicate:
                    changed["routes"].append(copy.deepcopy(changed["routes"][0]))
                with self.subTest(identity=identity, duplicate=duplicate), self.assertRaises(AssertionError):
                    campaign.require_routes(changed)

    def test_upgrade_retains_all_thirty_rows_receipts_and_input_hash(self):
        before = digest(self.routes)
        path = self.upgrade()
        result = json.loads(path.read_text())
        self.assertEqual(before, digest(self.routes))
        self.assertEqual(self.document["routes"], [row for row in result["routes"] if not row["id"].startswith("native116/")])
        self.assertEqual(self.document["source115ReceiptsSha256"], result["source115ReceiptsSha256"])
        self.assertEqual(("UNQUALIFIED", ACCEPTED_POWER116, 36),
                         (result["qualificationStatus"], result["acceptedPower116"], len(result["routes"])))
        self.assertTrue(all(row.get("sourceStatus") == "REQUIRES_APPROVED_DISPOSABLE_116_SOURCE"
                            for row in result["routes"] if row["id"].startswith("native116/")))

    def test_upgrade_never_regenerates_114_or_115_sources(self):
        with self.guards(), patch.object(campaign, "generate114", side_effect=AssertionError("no regeneration")), \
                patch.object(campaign, "generate115", side_effect=AssertionError("no regeneration")):
            self.assertEqual(0, campaign.upgrade116(self.args))

    def test_upgrade_refuses_missing_duplicate_or_already_upgraded_rows(self):
        for rows in (self.document["routes"][:-1], self.document["routes"] + [self.document["routes"][0]],
                     self.document["routes"] + campaign.pending116_routes()):
            write_json(self.routes, dict(self.document, routes=rows))
            with self.guards(), self.assertRaisesRegex(AssertionError, "30 historical"):
                campaign.upgrade116(self.args)
        self.assertFalse(self.output.exists())

    def test_upgrade_requires_old_source_receipts_not_destination_pass(self):
        with patch.object(campaign, "require_source114_receipt", side_effect=AssertionError("unapproved native114")), self.assertRaisesRegex(AssertionError, "unapproved native114"):
            campaign.upgrade116(self.args)
        self.assertFalse(self.output.exists())

    def test_upgrade_does_not_replace_output_and_validate_only_is_read_only(self):
        self.args.validate_only = True
        with self.guards():
            self.assertEqual(0, campaign.upgrade116(self.args))
        self.assertFalse(self.output.exists())
        self.args.validate_only = False
        self.upgrade()
        with self.guards(), self.assertRaises(FileExistsError):
            campaign.upgrade116(self.args)

    def test_attach_requires_each_of_six_raw_receipt_guards(self):
        args, generated, _ = self.attachment()
        before = digest(args.routes)
        with self.guards(), patch.object(campaign, "require_source116_receipt") as guard:
            self.assertEqual(0, campaign.attach116(args))
        self.assertEqual(generated["routes"], [call.args[0] for call in guard.call_args_list])
        result = json.loads((args.output / "native117-upgrade-routes.json").read_text())
        self.assertEqual(before, digest(args.routes))
        self.assertEqual(self.document["routes"], [row for row in result["routes"] if not row["id"].startswith("native116/")])
        campaign.require_routes(result)
        self.assertEqual("UNQUALIFIED", result["qualificationStatus"])

    def test_attach_rejects_prior_destination_pass_bundle(self):
        args, _, bundle = self.attachment()
        for key, value in (("scope", "DESTINATION_PASS"), ("status", "UNQUALIFIED"), ("acceptedPower116", "0" * 40),
                           ("canonicalSourcesUnchanged", False), ("seedCodeUnchanged", False)):
            write_json(args.receipts, dict(bundle, **{key: value}))
            with self.subTest(key=key), self.guards(), patch.object(campaign, "require_source116_receipt") as guard, self.assertRaisesRegex(AssertionError, "stopped source116"):
                campaign.attach116(args)
            guard.assert_not_called()
        self.assertFalse(args.output.exists())

    def test_attach_rejects_missing_duplicate_or_mixed_receipts(self):
        args, generated, bundle = self.attachment()
        for receipts in (bundle["receipts"][:-1], bundle["receipts"] + [bundle["receipts"][0]]):
            write_json(args.receipts, dict(bundle, receipts=receipts))
            with self.guards(), self.assertRaisesRegex(AssertionError, "Exactly six"):
                campaign.attach116(args)
        write_json(args.receipts, bundle)
        changed = copy.deepcopy(generated)
        changed["routes"][-1]["source116ReceiptSha256"] = "0" * 64
        write_json(args.sources, changed)
        with self.guards(), patch.object(campaign, "require_source116_receipt"), self.assertRaisesRegex(AssertionError, "bundle and source route"):
            campaign.attach116(args)

    def test_attach_does_not_hide_failed_raw_evidence_guard(self):
        args, _, _ = self.attachment()
        with self.guards(), patch.object(campaign, "require_source116_receipt", side_effect=AssertionError("lost tile")), self.assertRaisesRegex(AssertionError, "lost tile"):
            campaign.attach116(args)
        self.assertFalse(args.output.exists())

    def test_attach_refuses_replacement_of_existing_receipts(self):
        args, _, _ = self.attachment()
        doc = json.loads(args.routes.read_text())
        next(row for row in doc["routes"] if row["id"].startswith("native116/"))["sourceStatus"] = "GENERATED_DISPOSABLE_116_VERIFIED"
        write_json(args.routes, doc)
        with self.guards(), self.assertRaisesRegex(AssertionError, "replace existing"):
            campaign.attach116(args)

    def test_target_cannot_start_before_native116_source_guard(self):
        plan = self.upgrade()
        target = self.root / "target117.json"
        write_json(target, {})
        doc = json.loads(plan.read_text())
        selected = next(row for row in doc["routes"] if row["id"] == "native116/full")
        args = Namespace(routes=plan, target_manifest=target, output=self.root / "target runtime",
            kind="frozen", ticks=512, java8=self.root / "java16.exe", port=25595)
        with self.guards(), patch.object(campaign.native117, "pinned_profile", return_value=(self.root / "seed", "native", [])), \
                patch.object(campaign, "select_shard", return_value=[selected]), \
                patch.object(campaign.native117, "run") as frozen, patch.object(campaign.native117_connected, "run") as connected_run:
            with self.assertRaisesRegex(AssertionError, "stopped-source receipt"):
                campaign.run(args)
            frozen.assert_not_called()
            connected_run.assert_not_called()
        self.assertFalse(args.output.exists())

    def test_cli_routes_upgrade_and_attach_without_starting_a_server(self):
        with patch.object(campaign, "upgrade116", return_value=0) as upgrade:
            self.assertEqual(0, campaign.main(["upgrade116", "--routes", str(self.routes), "--output", str(self.output), "--validate-only"]))
        self.assertTrue(upgrade.call_args.args[0].validate_only)
        with patch.object(campaign, "attach116", return_value=0) as attach:
            self.assertEqual(0, campaign.main(["attach116", "--routes", str(self.routes), "--receipts", "bundle.json", "--output", str(self.output)]))
        self.assertEqual(Path("bundle.json"), attach.call_args.args[0].receipts)


if __name__ == '__main__':
    unittest.main()
