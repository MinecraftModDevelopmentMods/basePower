"""The newer engine may select native metadata without rewriting old port fixtures."""

import copy
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import native117_recipes as shared
from native12111_recipes import Engine


class RecipeAdapter(unittest.TestCase):
    def generated_metadata(self, native):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)

            def stage(runtime, *_):
                runtime.mkdir()
                return "unused-no-server"

            engine = SimpleNamespace(stage=stage, server=object, version=("fixture", "fixture"),
                                     result_name="unused-results.json", loot_directory="loot_table")
            if native:
                engine.pack_metadata = copy.deepcopy(Engine.pack_metadata)
            else:
                engine.pack_format = 48
            with patch.object(shared, "MODES", ()):
                shared.progression_campaign(root, None, None, None, 25000, {}, engine=engine)
            metadata = root / "progression" / shared.WORLD / "datapacks/progression-override/pack.mcmeta"
            return json.loads(metadata.read_text())["pack"]

    def test_native_exact_minor_format_is_used(self):
        self.assertEqual(Engine.pack_metadata, self.generated_metadata(True))
        self.assertNotIn("pack_format", Engine.pack_metadata)

    def test_existing_engines_keep_their_own_format(self):
        self.assertEqual(48, self.generated_metadata(False)["pack_format"])

    def test_native_keyed_pruning_must_match_the_exact_removed_recipe_set(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "console.log"
            line = "[Server thread/ERROR] [minecraft/ServerRecipeBook]: Tried to load unrecognized recipe: ResourceKey[minecraft:recipe / basepower:sprocket_stickwood_normal] removed now."
            path.write_text(line)
            result = shared.audit_unlock_log(path, {"basepower:sprocket_stickwood_normal"}, set(),
                                             errors=lambda text: text.splitlines())
            self.assertEqual(["basepower:sprocket_stickwood_normal"], result["observed_removed_ids"])
            for replacement in ("other:recipe", "basepower:sprocket_stickwood_normal] corrupted"):
                path.write_text(line.replace("basepower:sprocket_stickwood_normal", replacement))
                with self.assertRaises(AssertionError):
                    shared.audit_unlock_log(path, {"basepower:sprocket_stickwood_normal"}, set(),
                                            errors=lambda text: text.splitlines())


if __name__ == "__main__":
    unittest.main()
