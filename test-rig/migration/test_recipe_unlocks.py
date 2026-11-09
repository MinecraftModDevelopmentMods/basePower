import unittest
from collections import Counter
from io import BytesIO
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory
from unittest.mock import patch
from zipfile import ZipFile

import recipe_unlocks
from recipe_unlocks import MATERIALS


SOURCES = (
    ("PowerAdvantage", "poweradvantage", "d4ebfea7b64d9b1c56b5fa3b27ca9f53ba38d1dd"),
    ("SteamAdvantage/SteamAdvantage", "steamadvantage", "9aa16f3c346dd8684993a88168ea281dbcf119e2"),
    ("ElectricAdvantage/ElectricAdvantage", "electricadvantage", "6b674e3fdba5f0296d8074adbb630bd6b1c5b62d"),
)


class RecipeUnlockRunnerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        source = recipe_unlocks.POWER.parents[1] / "PA113"
        folder = TemporaryDirectory()
        cls.addClassCleanup(folder.cleanup)
        destination = Path(folder.name)
        for path, mod, commit in SOURCES:
            data = "src/main/resources/data/" + mod
            archive = subprocess.check_output(["git", "-C", str(source / path),
                "archive", "--format=zip", commit, data + "/recipes", data + "/advancements/recipes"])
            with ZipFile(BytesIO(archive)) as files:
                files.extractall(destination / path)
        worktree = patch.object(recipe_unlocks, "POWER", destination / "PowerAdvantage")
        worktree.start()
        cls.addClassCleanup(worktree.stop)

    def test_fixture_covers_crafting_and_smelting_but_not_crushing(self):
        recipes = recipe_unlocks.fixture()["recipes"]
        self.assertEqual(Counter(recipe["id"].split(":")[0] for recipe in recipes),
                         {"poweradvantage": 35, "steamadvantage": 24, "electricadvantage": 50})
        self.assertEqual(109, len({recipe["id"] for recipe in recipes}))
        self.assertTrue(all(":crushing_" not in recipe["id"] for recipe in recipes))

    def test_every_case_exercises_inventory_predicates(self):
        for recipe in recipe_unlocks.fixture()["recipes"]:
            self.assertTrue(recipe["ingredients"], recipe["id"])
            self.assertTrue(all(set(predicate) in ({"item"}, {"tag"})
                                for predicate in recipe["ingredients"].values()), recipe["id"])

    def test_standalone_expansions_do_not_require_the_other_expansion(self):
        for extensions, count in (("none", 35), ("steam", 59), ("electric", 85)):
            self.assertEqual(count, len(recipe_unlocks.fixture(extensions)["recipes"]))

    def test_human_kit_is_small_unique_and_uses_registered_id_shapes(self):
        self.assertEqual(27, len(MATERIALS))
        self.assertEqual(len(MATERIALS), len(set(MATERIALS)))
        self.assertTrue(all(":" in item for item in MATERIALS))


if __name__ == "__main__":
    unittest.main()
