"""Synthetic adapter controls, never substitute campaign receipts."""

import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import nbtlib

from matrix import WORLD, digest
import native117_recipes as old
import native262_resources as resources


def level_fixture(root, world=WORLD):
    target = Path(root) / world
    target.mkdir(parents=True, exist_ok=True)
    versions = {**resources.native.VERSIONS, "forge": "65.1.0", "minecraft": "26.2"}
    data = nbtlib.Compound(DataVersion=nbtlib.Int(4903), LevelName=nbtlib.String(world),
                          Version=nbtlib.Compound(Name=nbtlib.String("26.2")))
    entries = nbtlib.List[nbtlib.Compound]([nbtlib.Compound(ModId=nbtlib.String(key), ModVersion=nbtlib.String(value))
                                         for key, value in versions.items()])
    registry = nbtlib.Compound(ids=nbtlib.List[nbtlib.Compound]([
        nbtlib.Compound(K=nbtlib.String("minecraft:stone"), V=nbtlib.Int(1))]))
    result = nbtlib.File({"Data": data, "fml": nbtlib.Compound(LoadingModList=entries, Registries=nbtlib.Compound({
        "minecraft:item": copy.deepcopy(registry), "minecraft:block": copy.deepcopy(registry)}))})
    result.save(target / "level.dat", gzipped=True)
    return target, result


class ResourcesAdapterTest(unittest.TestCase):
    def test_native_loader_errors_abort_waits_without_becoming_allowed_diagnostics(self):
        for line in ("[00:00:00] [modloading-worker-0/ERROR] [FML/]: Caught exception during event FMLCommonSetupEvent",
                     "[00:00:00] [main/FATAL] [FML/]: Failed to complete lifecycle event COMMON_SETUP",
                     "[00:00:00] [main/ERROR] [minecraft/Main]: Failed to start the minecraft server"):
            self.assertTrue(resources.RecipeServer.unexpected_line(line))
        self.assertFalse(resources.RecipeServer.unexpected_line("[00:00:00] [main/DEBUG] [unit/]: Caught exception during event control"))
        self.assertFalse(resources.RecipeServer.unexpected_line("Common setup complete"))

    def test_exact_engine_metadata_and_read_only_bindings(self):
        self.assertEqual(("26.2", "65.1.0"), resources.Engine.version)
        self.assertEqual({"min_format": [107, 1], "max_format": [107, 1],
                          "description": "Disposable loot-override witness"}, resources.Engine.pack_metadata)
        self.assertIs(resources.unlock_campaign.__code__, old.unlock_campaign.__code__)
        self.assertIs(resources.progression_campaign.__code__, old.progression_campaign.__code__)
        self.assertIs(resources.unlock_campaign.__globals__["verify_level"], resources.verify_saved_level)
        self.assertIs(old.unlock_campaign.__globals__["verify_level"], old.verify_level)
        self.assertEqual({"engine": None}, resources.progression_campaign.__kwdefaults__)

    def test_new_engine_selects_native_pack_codec_without_changing_old_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)

            def stage(runtime, *_):
                runtime.mkdir()
                return "NOT_RUN"

            with patch.object(resources.Engine, "stage", stage), patch.dict(resources.progression_campaign.__globals__, MODES=()):
                resources.progression_campaign(root, None, None, None, 26520, {}, engine=resources.Engine)
            pack = json.loads((root / "progression" / WORLD / "datapacks/progression-override/pack.mcmeta").read_text())
            self.assertEqual(resources.PACK_METADATA, pack["pack"])
            self.assertNotIn("pack_format", pack["pack"])
        self.assertEqual(("NORMAL", "TECH_PROGRESSION", "APOCALYPTIC"), old.MODES)

    def test_native_stopped_level_checks_exact_all_six_mods_and_registry_maps(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            world, saved = level_fixture(root)
            entry = {"jars": [dict(id=key, version=value) for key, value in resources.native.VERSIONS.items()]}
            result = resources.verify_saved_level(root, entry)
            self.assertEqual(4903, result["dataVersion"])
            self.assertEqual(digest(world / "level.dat"), result["sha256"])
            for change in (lambda tag: tag["Data"].update(DataVersion=nbtlib.Int(4671)),
                           lambda tag: tag["fml"]["LoadingModList"].append(copy.deepcopy(tag["fml"]["LoadingModList"][0])),
                           lambda tag: tag["fml"]["Registries"].pop("minecraft:item"),
                           lambda tag: tag["fml"]["LoadingModList"].pop()):
                wrong = copy.deepcopy(saved)
                change(wrong)
                wrong.save(world / "level.dat", gzipped=True)
                with self.assertRaises((AssertionError, ValueError, KeyError)):
                    resources.verify_saved_level(root, entry)

    def test_packet_exports_require_every_mode_and_nonempty_hash_bound_item_tags(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for mode in ("normal", "tech", "apocalyptic"):
                (root / f"recipes-{mode}-sweep.bin").write_bytes(b"synthetic-unit-packet")
                tags = root / f"tags-{mode}.bin"
                tags.write_bytes(b"synthetic-unit-tags")
                manifest = dict(bookPacketType="ClientboundRecipeBookAddPacket", tagsPacketType="ClientboundUpdateTagsPacket",
                                itemTagCount=2, tagsPacketFile=tags.name, tagsPacketSha256=digest(tags))
                (root / f"recipes-{mode}-sweep.json").write_text(json.dumps(manifest))
            self.assertEqual(3, len(resources.verify_packet_exports(root)))
            path = root / "recipes-normal-sweep.json"
            original = json.loads(path.read_text())
            for key, value in (("tagsPacketFile", "../outside.bin"), ("itemTagCount", True),
                               ("tagsPacketSha256", "0" * 64), ("bookPacketType", "OldRecipePacket")):
                path.write_text(json.dumps({**original, key: value}))
                with self.assertRaises((AssertionError, KeyError)):
                    resources.verify_packet_exports(root)
            path.write_text(json.dumps(original))
            (root / "recipes-apocalyptic-sweep.bin").write_bytes(b"")
            with self.assertRaises(AssertionError):
                resources.verify_packet_exports(root)

    def test_retained_pixels_are_replayed_not_accepted_from_a_pass_label(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            a, b = root / "a.rgba", root / "b.rgba"
            a.write_bytes(bytes((1, 2, 3, 255)) * 4)
            b.write_bytes(a.read_bytes())
            spec = dict(width=2, height=2, actualRgba=dict(file=a.name, sha256=digest(a)),
                        referenceRgba=dict(file=b.name, sha256=digest(b)))
            self.assertEqual(0, resources.pixel_pair(root, spec))
            with self.assertRaises(AssertionError):
                resources.pixel_pair(root, {**spec, "referenceRgba": spec["actualRgba"]})
            b.write_bytes(bytes((10, 2, 3, 255)) * 4)
            spec["referenceRgba"]["sha256"] = digest(b)
            with self.assertRaises(AssertionError):
                resources.pixel_pair(root, spec)
            self.assertEqual(4, resources.pixel_pair(root, spec, matches=False))
            spec["width"] = True
            with self.assertRaises(AssertionError):
                resources.pixel_pair(root, spec)

    def test_artifact_path_escape_hash_and_hardlink_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "witness"
            path.write_bytes(b"synthetic-unit-artifact")
            self.assertEqual(path, resources.artifact(root, dict(file=path.name, sha256=digest(path))))
            for value in (dict(file="../witness", sha256=digest(path)), dict(file=path.name, sha256="0" * 64)):
                with self.assertRaises(AssertionError):
                    resources.artifact(root, value)
            linked = root / "hardlink"
            linked.hardlink_to(path)
            with self.assertRaises(AssertionError):
                resources.artifact(root, dict(file=path.name, sha256=digest(path)))


if __name__ == "__main__":
    unittest.main()
