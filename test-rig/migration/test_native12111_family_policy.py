"""Materialized family profile policy; real jar/hash/manifest checks, no launch."""

import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile

import native12111 as native


class Native12111FamilyPolicyTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="native61-family-policy-")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name).resolve()
        self.seed = self.root / "seed"
        self.seed.mkdir()
        code = self.seed / "unit-code"
        code.write_bytes(b"unit boundary: official seed custody is tested separately")
        self.inventory = dict(forge=native.FORGE_VERSION, files={
            code.name: dict(sha256=native.digest(code), size=code.stat().st_size)})
        self.inventory_file = self.root / "inventory.json"
        self.inventory_file.write_text(json.dumps(self.inventory), encoding="utf-8")
        self.profile = self.root / "family.json"
        self.document = dict(mode="packaged", minecraftVersion=native.MINECRAFT,
                             forge=dict(version=native.FORGE_VERSION), qualificationStatus=native.EXPLORATORY, mods=[])
        for identity, version in (("basepower", "2.4.15.121111"), ("basesteam", "2.2.16.121111"),
                                  ("baseelectric", "2.2.16.121111"), ("basemetals", "3.1.2.121111"),
                                  ("orespawn", "4.1.0.121111"), ("advantageworkstest", "1.1.5.121111")):
            self.add_jar(identity, version)
        self.save()

    def add_jar(self, identity, version):
        path = self.root / (identity + "-" + version + ".jar")
        self.write_jar(path, identity, version)
        row = dict(id=identity, version=version, path=str(path), file=path.name, sha256=native.digest(path))
        self.document["mods"].append(row)
        return row

    def write_jar(self, path, identity, version, *, power_sha=None):
        with ZipFile(path, "w") as jar:
            jar.writestr("META-INF/mods.toml", 'modLoader="javafml"\nloaderVersion="[61,)"\n'
                         'license="LGPL-3.0"\n[[mods]]\nmodId="' + identity + '"\nversion="' + version + '"\n')
            if identity == "advantageworkstest":
                power = next(row["sha256"] for row in self.document["mods"] if row["id"] == "basepower")
                jar.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nBuilt-On: " + native.FORGE_VERSION
                             + "\nPower-Input-Status: " + native.EXPLORATORY + "\nPower-Input-SHA256: "
                             + (power_sha or power) + "\n")

    def save(self):
        self.profile.write_text(json.dumps(self.document), encoding="utf-8")

    def read(self, *, observed_inventory=None, inventory_sha=None):
        # Isolate only the official runtime seed boundary, not artifact or profile validation.
        with patch.object(native, "inspect_seed", return_value=(
                self.inventory if observed_inventory is None else observed_inventory)), \
                patch.object(native, "stage") as stage, patch.object(native, "java21") as java:
            result = native.read_family_target(self.profile, self.seed, self.inventory_file,
                                               inventory_sha or native.digest(self.inventory_file))
        stage.assert_not_called()
        java.assert_not_called()
        return result

    def test_valid_six_jar_profile_passes_exact_checks_without_writes(self):
        before = {path: path.read_bytes() for path in self.root.rglob("*") if path.is_file()}
        seed, inventory, mods = self.read()
        self.assertEqual(seed, self.seed)
        self.assertEqual(inventory, self.inventory)
        self.assertEqual(set(mods), {"basepower", "basesteam", "baseelectric", "basemetals", "orespawn", "advantageworkstest"})
        for row in self.document["mods"]:
            self.assertEqual(mods[row["id"]], (row["version"], Path(row["path"]), row["sha256"]))
        self.assertEqual(before, {path: path.read_bytes() for path in self.root.rglob("*") if path.is_file()})

    def test_basemetals_missing_is_rejected_not_a_normal_supported_profile(self):
        self.document["mods"] = [row for row in self.document["mods"] if row["id"] != "basemetals"]
        self.save()
        before = self.profile.read_bytes()
        with self.assertRaisesRegex(ValueError, "BaseMetals"):
            self.read()
        self.assertEqual(self.profile.read_bytes(), before)

    def test_every_family_requirement_remains_required(self):
        original = copy.deepcopy(self.document)
        for identity in ("basepower", "basesteam", "baseelectric", "basemetals", "orespawn", "advantageworkstest"):
            with self.subTest(missing=identity):
                self.document = copy.deepcopy(original)
                self.document["mods"] = [row for row in self.document["mods"] if row["id"] != identity]
                self.save()
                with self.assertRaisesRegex(ValueError, "family profile"):
                    self.read()

    def test_additional_pinned_provider_does_not_replace_basemetals(self):
        self.add_jar("mineralogy", "6.2.0.121111")
        self.save()
        self.assertEqual(len(self.read()[2]), 7)
        self.document["mods"] = [row for row in self.document["mods"] if row["id"] != "basemetals"]
        self.save()
        with self.assertRaisesRegex(ValueError, "BaseMetals"):
            self.read()

    def test_basemetals_sha_tamper_is_not_accepted(self):
        row = next(row for row in self.document["mods"] if row["id"] == "basemetals")
        with Path(row["path"]).open("ab") as jar:
            jar.write(b"changed bytes")
        with self.assertRaisesRegex(AssertionError, "artifact hash"):
            self.read()

    def test_basemetals_mod_identity_cannot_be_spoofed_by_a_valid_sha(self):
        row = next(row for row in self.document["mods"] if row["id"] == "basemetals")
        self.write_jar(Path(row["path"]), "othermod", row["version"])
        row["sha256"] = native.digest(Path(row["path"]))
        self.save()
        with self.assertRaisesRegex(ValueError, "identity/version"):
            self.read()

    def test_older_basemetals_version_is_rejected(self):
        row = next(row for row in self.document["mods"] if row["id"] == "basemetals")
        row["version"] = "3.1.2.121011"
        self.save()
        with self.assertRaisesRegex(ValueError, "ordinary jar pin"):
            self.read()

    def test_duplicate_basemetals_identity_is_rejected(self):
        self.document["mods"].append(copy.deepcopy(next(
            row for row in self.document["mods"] if row["id"] == "basemetals")))
        self.save()
        with self.assertRaisesRegex(ValueError, "ordinary jar pin"):
            self.read()

    def test_required_basemetals_does_not_bypass_harness_compile_pin(self):
        row = next(row for row in self.document["mods"] if row["id"] == "advantageworkstest")
        self.write_jar(Path(row["path"]), row["id"], row["version"], power_sha="F" * 64)
        row["sha256"] = native.digest(Path(row["path"]))
        self.save()
        with self.assertRaisesRegex(AssertionError, "Harness compile pin"):
            self.read()

    def test_required_basemetals_does_not_bypass_inventory_hash_or_seed_custody(self):
        with self.assertRaisesRegex(AssertionError, "artifact hash"):
            self.read(inventory_sha="F" * 64)
        changed = copy.deepcopy(self.inventory)
        changed["files"] = {}
        with self.assertRaisesRegex(AssertionError, "sealed code inventory"):
            self.read(observed_inventory=changed)


if __name__ == "__main__":
    unittest.main()
