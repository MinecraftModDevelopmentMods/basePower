"""Fail-closed NeoForge artifact, seed, profile and compile-pin custody."""

import copy
import json
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile

import native1211neo as native
import neoforge_code
from matrix import digest


def mod_jar(path, identity, version, *, metadata="META-INF/neoforge.mods.toml", manifest="", duplicate=False):
    path.parent.mkdir(parents=True, exist_ok=True)
    row = f'[[mods]]\nmodId="{identity}"\nversion="{version}"\n'
    with ZipFile(path, "w") as jar:
        jar.writestr(metadata, row * (2 if duplicate else 1))
        jar.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n" + manifest + "\r\n")
    return path


def seed_at(root):
    launcher = root / native.LAUNCHER
    launcher.parent.mkdir(parents=True)
    launcher.write_text("cpw.mods.bootstraplauncher.BootstrapLauncher "
                        f"--fml.neoForgeVersion {native.NEOFORGE} --fml.mcVersion {native.MINECRAFT}",
                        encoding="utf-8")
    (root / "server.jar").write_bytes(b"sealed server fixture")
    return launcher


class ArtifactIdentity(unittest.TestCase):
    def test_requires_exact_native_metadata_identity_and_version(self):
        with tempfile.TemporaryDirectory() as folder:
            jar = Path(folder) / "fixture.jar"
            mod_jar(jar, "basesteam", "2.2.20.121012")
            self.assertEqual("2.2.20.121012", native.jar_identity(jar, "basesteam", "2.2.20.121012"))
            for options in ({"metadata": "META-INF/mods.toml"}, {"duplicate": True},
                            {"identity": "baseelectric"}, {"version": "2.2.20.121011"}):
                with self.subTest(options=options):
                    arguments = dict(identity="basesteam", version="2.2.20.121012")
                    arguments.update(options)
                    mod_jar(jar, **arguments)
                    with self.assertRaises(AssertionError):
                        native.jar_identity(jar, "basesteam", "2.2.20.121012")

    def test_manifest_version_unfolding_does_not_accept_the_wrong_loader_suffix(self):
        with tempfile.TemporaryDirectory() as folder:
            jar = Path(folder) / "fixture.jar"
            mod_jar(jar, "mmdlib", "${file.jarVersion}",
                    manifest="Implementation-Version: 2.0.1.121012+\r\n Neo\r\n")
            self.assertEqual("2.0.1.121012+Neo", native.jar_identity(jar, "mmdlib", "2.0.1.121012+Neo"))
            with self.assertRaises(AssertionError):
                native.jar_identity(jar, "mmdlib", "2.0.1.121012+Forge")

    def test_hash_is_required_exact_and_case_insensitive(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "fixture.jar"
            path.write_bytes(b"pinned")
            native.validate_hash(path, digest(path).lower())
            for sha in ("", "A" * 63, "G" * 64, "0" * 64):
                with self.subTest(sha=sha), self.assertRaises(AssertionError):
                    native.validate_hash(path, sha)

    def test_compile_pin_binds_the_exact_power_hash_native_loader_and_exploratory_status(self):
        sha = "AB" * 32
        manifest = {"Built-On": native.FORGE_VERSION, "Power-Input-Status": native.EXPLORATORY,
                    "Power-Input-SHA256": sha}
        with tempfile.TemporaryDirectory() as folder:
            jar = Path(folder) / "harness.jar"
            for changed in ({}, {"Built-On": "1.21.1-52.1.14"}, {"Power-Input-Status": "QUALIFIED"},
                            {"Power-Input-SHA256": "0" * 64}):
                with self.subTest(changed=changed):
                    text = "".join(f"{key}: {value}\r\n" for key, value in (manifest | changed).items())
                    mod_jar(jar, "advantageworkstest", "1.1.6.121012", manifest=text)
                    if changed:
                        with self.assertRaises(AssertionError):
                            native.verify_compile_pin(jar, sha)
                    else:
                        native.verify_compile_pin(jar, sha.lower())

    def test_java_gate_requires_21_not_17_or_25(self):
        with tempfile.TemporaryDirectory() as folder:
            java = Path(folder) / "java.exe"
            java.touch()
            for version in ("17.0.1", "21.0.7", "25.0.1"):
                result = SimpleNamespace(stdout="", stderr=f'openjdk version "{version}"')
                with self.subTest(version=version), patch.object(native.subprocess, "run", return_value=result):
                    if version.startswith("21."):
                        self.assertEqual((java.resolve(), result.stderr), native.java21(java))
                    else:
                        with self.assertRaisesRegex(ValueError, "requires Java 21"):
                            native.java21(java)


class SeedCustody(unittest.TestCase):
    def test_seed_identity_is_native_and_every_code_file_is_sealed(self):
        with tempfile.TemporaryDirectory() as folder:
            seed = Path(folder)
            launcher = seed_at(seed)
            result = native.inspect_seed(seed)
            self.assertEqual(("neoforge", "21.1.265", "1.21.1"),
                             tuple(result[key] for key in ("loader", "neoforge", "minecraft")))
            self.assertEqual({native.LAUNCHER, "server.jar"}, result["files"].keys())
            self.assertEqual(digest(launcher), result["files"][native.LAUNCHER]["sha256"])
            self.assertEqual(launcher.stat().st_size, result["files"][native.LAUNCHER]["size"])

    def test_seed_rejects_wrong_missing_or_duplicate_loader_flags_and_missing_bootstrap(self):
        with tempfile.TemporaryDirectory() as folder:
            seed = Path(folder)
            launcher = seed_at(seed)
            original = launcher.read_text(encoding="utf-8")
            for command in (original.replace("21.1.265", "21.1.246"), original.replace("1.21.1", "1.21.2"),
                            original + " --fml.neoForgeVersion 21.1.265",
                            original.replace("--fml.neoForgeVersion 21.1.265", "--fml.forgeVersion 52.1.14"),
                            original.replace("cpw.mods.bootstraplauncher.BootstrapLauncher", "not.the.Bootstrap")):
                with self.subTest(command=command):
                    launcher.write_text(command, encoding="utf-8")
                    with self.assertRaises(AssertionError):
                        native.inspect_seed(seed)

    def test_code_inventory_detects_modified_added_and_deleted_code(self):
        for change in ("modify", "add", "delete"):
            with self.subTest(change=change), tempfile.TemporaryDirectory() as folder:
                seed = Path(folder)
                launcher = seed_at(seed)
                inventory = native.inspect_seed(seed)
                if change == "modify":
                    launcher.write_text(launcher.read_text(encoding="utf-8") + " --unexpected", encoding="utf-8")
                elif change == "add":
                    (seed / "libraries/unexpected.jar").write_bytes(b"unexpected")
                else:
                    (seed / "server.jar").unlink()
                with self.assertRaises(AssertionError):
                    neoforge_code.verify_code(seed, inventory)

    def test_code_inventory_does_not_mistake_world_config_or_mods_for_launcher_code(self):
        with tempfile.TemporaryDirectory() as folder:
            seed = Path(folder)
            seed_at(seed)
            inventory = native.inspect_seed(seed)
            for name in ("world/level.dat", "config/fml.toml", "mods/fixture.jar", "logs/latest.log"):
                path = seed / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b"mutable runtime state")
            self.assertEqual(inventory, neoforge_code.verify_code(seed, inventory))

    def test_runtime_mod_hashes_are_checked_separately_from_code(self):
        with tempfile.TemporaryDirectory() as folder:
            runtime = Path(folder)
            seed_at(runtime)
            inventory = native.inspect_seed(runtime)
            (runtime / "mods").mkdir()
            jar = runtime / "mods/fixture.jar"
            jar.write_bytes(b"pinned")
            expected = {jar.name: digest(jar)}
            native.verify_runtime(runtime, inventory, expected)
            jar.write_bytes(b"tampered")
            with self.assertRaises(AssertionError):
                native.verify_runtime(runtime, inventory, expected)

    def test_seal_is_new_disjoint_and_under_the_rig_build_directory(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            seed = root / "protected-seed"
            seed_at(seed)
            rig = root / "rig"
            inventory = rig / "build/inventory.json"
            with patch.object(native, "RIG", rig):
                native.seal(seed, inventory)
                before = inventory.read_bytes()
                self.assertEqual(native.inspect_seed(seed), native.read_json(inventory))
                for output in (inventory, rig / "outside.json", seed / "inventory.json"):
                    with self.subTest(output=output), self.assertRaises(ValueError):
                        native.seal(seed, output)
                self.assertEqual(before, inventory.read_bytes())


class ProfileGuards(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.source_rig = native.RIG
        self.rig = Path(temporary.name) / "rig"
        self.rig.mkdir()
        self.patch = patch.object(native, "RIG", self.rig)
        self.patch.start()
        self.addCleanup(self.patch.stop)
        self.mods = {}
        versions = {"basepower": "2.4.19.121012", "basesteam": "2.2.20.121012",
                    "baseelectric": "2.2.20.121012", "advantageworkstest": "1.1.6.121012",
                    "basemetals": "4.0.1.121012-development", "orespawn": "4.1.1.121012",
                    "mmdlib": "2.0.1.121012+Neo"}
        for identity, version in versions.items():
            path = self.rig / "jars" / f"{identity}-{version}.jar"
            manifest = ""
            if identity == "advantageworkstest":
                manifest = (f"Built-On: {native.FORGE_VERSION}\r\nPower-Input-Status: {native.EXPLORATORY}\r\n"
                            f"Power-Input-SHA256: {self.mods['basepower']['sha256']}\r\n")
            mod_jar(path, identity, version, manifest=manifest)
            self.mods[identity] = dict(id=identity, version=version, path=str(path), file=path.name, sha256=digest(path))
        self.data = dict(loader="neoforge", mode="packaged", minecraftVersion=native.MINECRAFT,
                         neoforgeVersion=native.NEOFORGE, qualificationStatus=native.EXPLORATORY,
                         mods=[self.mods[key] for key in ("basemetals", "orespawn", "mmdlib")])
        self.profile = self.rig / "profile.json"
        self.args = SimpleNamespace(profile=self.profile, power=Path(self.mods["basepower"]["path"]),
                                    power_sha256=self.mods["basepower"]["sha256"],
                                    harness=Path(self.mods["advantageworkstest"]["path"]),
                                    harness_sha256=self.mods["advantageworkstest"]["sha256"])
        self.write_profile(self.data)

    def write_profile(self, data):
        self.profile.write_text(json.dumps(data), encoding="utf-8")

    def test_interop_accepts_only_the_pinned_native_five_mod_set(self):
        self.assertEqual({"basepower", "advantageworkstest", "basemetals", "orespawn", "mmdlib"},
                         native.read_mods(self.args).keys())

    def test_interop_rejects_wrong_runtime_version_mode_or_qualification(self):
        for key, wrong in (("minecraftVersion", "1.21.2"), ("neoforgeVersion", "21.1.246"),
                           ("mode", "development"), ("qualificationStatus", "QUALIFIED")):
            with self.subTest(key=key):
                self.write_profile(self.data | {key: wrong})
                with self.assertRaises(ValueError):
                    native.read_mods(self.args)

    def test_interop_rejects_a_forge_or_missing_loader_label(self):
        for wrong in ("forge", None):
            with self.subTest(loader=wrong):
                data = self.data | {"loader": wrong}
                if wrong is None:
                    data.pop("loader")
                self.write_profile(data)
                with self.assertRaises(ValueError):
                    native.read_mods(self.args)

    def test_interop_rejects_duplicate_missing_or_extra_dependencies(self):
        for mods in (self.data["mods"][:-1], self.data["mods"] + [self.mods["basemetals"]],
                     self.data["mods"] + [self.mods["basesteam"]]):
            with self.subTest(mods=[row["id"] for row in mods]):
                self.write_profile(self.data | {"mods": mods})
                with self.assertRaises(ValueError):
                    native.read_mods(self.args)

    def test_interop_rejects_tampered_artifact_and_nonordinary_jar_name(self):
        jar = Path(self.mods["basemetals"]["path"])
        jar.write_bytes(jar.read_bytes() + b"tampered")
        with self.assertRaises(AssertionError):
            native.read_mods(self.args)
        data = copy.deepcopy(self.data)
        data["mods"][0]["path"] = str(self.rig / "basemetals-deobf.jar")
        self.write_profile(data)
        with self.assertRaises(ValueError):
            native.read_mods(self.args)

    def family(self):
        seed = self.rig / "seed"
        seed_at(seed)
        inventory = self.rig / "inventory.json"
        inventory.write_text(json.dumps(native.inspect_seed(seed)), encoding="utf-8")
        data = self.data | {"neoforge": {"version": native.FORGE_VERSION}, "mods": list(self.mods.values())}
        self.write_profile(data)
        return data, seed, inventory

    def test_family_accepts_exact_sealed_seed_and_full_native_family(self):
        _, seed, inventory = self.family()
        actual_seed, actual_inventory, mods = native.read_family_target(self.profile, seed, inventory, digest(inventory))
        self.assertEqual(seed, actual_seed)
        self.assertEqual(native.inspect_seed(seed), actual_inventory)
        self.assertEqual(self.mods.keys(), mods.keys())

    def test_family_rejects_missing_family_member_duplicate_or_filename_drift(self):
        data, seed, inventory = self.family()
        changed = copy.deepcopy(data["mods"])
        changed[0]["file"] = "renamed.jar"
        for mods in (data["mods"][:-1], data["mods"] + [data["mods"][0]], changed):
            with self.subTest(mods=[row["file"] for row in mods]):
                self.write_profile(data | {"mods": mods})
                with self.assertRaises(ValueError):
                    native.read_family_target(self.profile, seed, inventory, digest(inventory))

    def test_family_rejects_wrong_loader_label(self):
        data, seed, inventory = self.family()
        self.write_profile(data | {"loader": "forge"})
        with self.assertRaises(ValueError):
            native.read_family_target(self.profile, seed, inventory, digest(inventory))

    def test_family_rejects_launcher_tampering_even_when_the_profile_is_unchanged(self):
        _, seed, inventory = self.family()
        (seed / native.LAUNCHER).write_text("tampered", encoding="utf-8")
        with self.assertRaises(AssertionError):
            native.read_family_target(self.profile, seed, inventory, digest(inventory))

    def profile_arguments(self):
        name = "worldgen-legacy-import"
        fixture = native.read_json(self.source_rig / "profiles/native1201" / (name + ".json"))
        template = self.rig / "profiles/native1201" / (name + ".json")
        template.parent.mkdir(parents=True)
        template.write_text(json.dumps(fixture), encoding="utf-8")
        arguments = dict(vars(self.args), name=name, output=self.rig / "build/materialized.json",
                         steam=Path(self.mods["basesteam"]["path"]), steam_sha256=self.mods["basesteam"]["sha256"],
                         electric=Path(self.mods["baseelectric"]["path"]),
                         electric_sha256=self.mods["baseelectric"]["sha256"], mineralogy=None, mineralogy_sha256=None)
        return SimpleNamespace(**arguments), template, fixture

    def test_materialized_profile_preserves_assertions_and_replaces_all_old_artifact_pins(self):
        args, template, fixture = self.profile_arguments()
        before = template.read_bytes()
        native.materialize_profile(args)
        result = native.read_json(args.output)
        for key in ("firstProfileAssertions", "restartProfileAssertions", "configFixtures", "worldgenExpectations"):
            self.assertEqual(fixture.get(key), result.get(key), key)
        self.assertEqual(before, template.read_bytes())
        self.assertEqual("neoforge", result["loader"])
        self.assertEqual(native.MINECRAFT, result["minecraftVersion"])
        self.assertEqual({"version": native.FORGE_VERSION}, result["neoforge"])
        self.assertEqual(native.EXPLORATORY, result["qualificationStatus"])
        self.assertNotIn("sourceCommit", result)
        self.assertNotIn("powerDeobfSha256", result)
        self.assertEqual(digest(template), result["sourceProfileSha256"])
        self.assertEqual(self.mods, {entry["id"]: entry for entry in result["mods"]})

    def test_materialization_rejects_forge_children_missing_pins_and_receipt_overwrite(self):
        args, _, _ = self.profile_arguments()
        for field, value in (("steam", Path("baseSteam-2.2.20.121011.jar")), ("electric_sha256", None)):
            with self.subTest(field=field), self.assertRaises(ValueError):
                native.materialize_profile(SimpleNamespace(**(vars(args) | {field: value})))
        native.materialize_profile(args)
        before = args.output.read_bytes()
        with self.assertRaises(ValueError):
            native.materialize_profile(args)
        self.assertEqual(before, args.output.read_bytes())


if __name__ == "__main__":
    unittest.main()
