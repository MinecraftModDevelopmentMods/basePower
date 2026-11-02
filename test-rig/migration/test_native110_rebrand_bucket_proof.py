"""Synthetic four-boot permit rejection controls; no server or artifact mutation."""
import copy
import unittest
from pathlib import Path
from unittest.mock import patch

import native110_rebrand_bucket_proof as proof


NOTICE = "[12:34:56] [Server thread/ERROR] [FML]: Trying to activate the universal filled bucket too late. Call it statically in your Mods class. Mod: Base Metals"


def boots():
    return [dict(label=label, status="PASS_BOOT", exitCode=0,
        diagnostic=dict(originalSeverity="ERROR", message=NOTICE)) for label in proof.BOOT_LABELS]


def role():
    return dict(status="PASS", operationCases=3, exactColdCases=3,
                startupFlags={"operate": {}, "restart": {}})


class FreshRebrandBucketProofTest(unittest.TestCase):
    def test_four_complete_boots_and_three_real_bucket_cold_cases_are_required(self):
        proof.require_four_boots(boots(), role())
        for mutation in ("missing", "duplicate", "unknown", "exit", "bool-exit", "changed-line", "wrong-mod", "missing-oil", "missing-cold"):
            observed, validated = boots(), role()
            if mutation == "missing": observed.pop()
            elif mutation == "duplicate": observed[-1] = copy.deepcopy(observed[0])
            elif mutation == "unknown": observed[0]["label"] = "old-proof"
            elif mutation == "exit": observed[0]["exitCode"] = 1
            elif mutation == "bool-exit": observed[0]["exitCode"] = False
            elif mutation == "changed-line": observed[0]["diagnostic"]["message"] += " "
            elif mutation == "wrong-mod": observed[0]["diagnostic"]["message"] = NOTICE.replace("Base Metals", "basePower")
            elif mutation == "missing-oil": validated["operationCases"] = 2
            else: validated["exactColdCases"] = 2
            with self.subTest(mutation=mutation), self.assertRaises(AssertionError):
                proof.require_four_boots(observed, validated)

    def test_an_old_pass_or_a_changed_full_candidate_never_binds(self):
        candidate = dict(manifestSha256="a" * 64, mods=[dict(id=id, sha256=id, version="v", runtimeVersion="v")
                         for id in sorted(proof.TARGET_IDS)], codeInventory={"files": {"engine": "sealed"}})
        doc = dict(kind=proof.KIND, status="PASS", candidate=candidate)
        proof.bind_candidate(doc, candidate)
        for mutation in ("old-proof", "manifest", "power", "steam", "electric", "harness", "base", "ore", "engine"):
            changed = copy.deepcopy(candidate)
            document = copy.deepcopy(doc)
            if mutation == "old-proof": document["kind"] = "historical-legacy-bucket-proof"
            elif mutation == "manifest": changed["manifestSha256"] = "b" * 64
            elif mutation == "engine": changed["codeInventory"]["files"]["engine"] = "changed"
            else:
                id = {"power": "basepower", "steam": "basesteam", "electric": "baseelectric", "harness": "advantageworkstest", "base": "basemetals", "ore": "orespawn"}[mutation]
                next(m for m in changed["mods"] if m["id"] == id)["sha256"] = "changed"
            with self.subTest(mutation=mutation), self.assertRaises(AssertionError): proof.bind_candidate(document, changed)

    def test_exact_line_is_pending_until_the_whole_fresh_campaign_passes(self):
        lines = ["Starting minecraft server version 1.10.2", "Forge Mod Loader version 12.18.3.2511 for Minecraft 1.10.2 loading", NOTICE]
        self.assertEqual("PENDING_DIAGNOSTIC_PROOF", proof.legacy.held_diagnostics(lines, proof.legacy.BASE_SHA, proof.legacy.PUBLIC_FORGE_SHA)["status"])
        for bad in (lines + [NOTICE], lines + ["[12:34:57] [Server thread/ERROR] [FML]: Other failure"], lines[:-1]):
            with self.assertRaises(AssertionError): proof.legacy.held_diagnostics(bad, proof.legacy.BASE_SHA, proof.legacy.PUBLIC_FORGE_SHA)

    def test_baseline_mod_inventory_cannot_contain_a_harness_or_power(self):
        proof.require_baseline_mods({"basemetals": "2.4.0", "orespawn": "4.0.8.110021", "Forge": "12.18.3.2511"},
                                    {"basemetals": "2.4.0", "orespawn": "4.0.8.110021"})
        for extra in ("basepower", "advantageworkstest", "unknown"):
            with self.subTest(extra=extra), self.assertRaises(AssertionError):
                proof.require_baseline_mods({"basemetals": "2.4.0", "orespawn": "4.0.8.110021", extra: "v"},
                                            {"basemetals": "2.4.0", "orespawn": "4.0.8.110021"})

    def test_first_strict_failure_is_required_and_cannot_be_a_preservation_failure(self):
        candidate = dict(manifestSha256="a" * 64)
        failed = dict(status="FAIL", targetManifestSha256="a" * 64,
                      failure="Native runtime diagnostic at line 123: " + NOTICE)
        with patch.object(proof, "digest", return_value="B" * 64), patch.object(proof.legacy, "read", return_value=failed):
            proof.require_strict_failure(Path("strict.json"), "b" * 64, candidate)
            for field, value in (("status", "PASS"), ("targetManifestSha256", "c" * 64),
                                 ("failure", "Unticked imported state changed"),
                                 ("failure", "Native runtime diagnostic at line 123: " + NOTICE.replace("Base Metals", "Other Mod"))):
                bad = copy.deepcopy(failed)
                bad[field] = value
                with self.subTest(field=field, value=value), patch.object(proof.legacy, "read", return_value=bad), self.assertRaises(AssertionError):
                    proof.require_strict_failure(Path("strict.json"), "b" * 64, candidate)
            with self.assertRaises(AssertionError): proof.require_strict_failure(Path("strict.json"), "changed", candidate)

    def test_each_mode_requires_its_exact_original_observer_with_no_other_extra(self):
        candidate = dict(mods=[dict(path="core.jar", sha256="core")])
        for mode in ("connected", "passive-source355", "synthetic-nested"):
            correct = {"core.jar": "core", "observer.jar": proof.observer_sha(mode)}
            proof.require_destination_inventory(correct, candidate, mode)
            for bad in ({"core.jar": "core"}, {**correct, "extra.jar": "unexpected"},
                        {"core.jar": "core", "observer.jar": "changed"},
                        {**correct, "core.jar": "changed"}):
                with self.subTest(mode=mode, bad=bad), self.assertRaises(AssertionError):
                    proof.require_destination_inventory(bad, candidate, mode)
        with self.assertRaises(AssertionError): proof.observer_sha("unknown")


if __name__ == "__main__": unittest.main()
