from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile

import native263_engine_proof as proof


class Native66SourceContracts(unittest.TestCase):
    def test_real_official_target_primary_source_archive_is_pinned(self):
        sdk = Path("D:/BaseMetals/BaseMetals 263/BaseMetals/.gradle/mavenizer/repo/net/minecraftforge/forge/26.3-66.0.0/forge-26.3-66.0.0-sources.jar")
        self.assertEqual(proof.SOURCE_PINS, proof.verify_sources(sdk))
        with tempfile.TemporaryDirectory() as directory:
            changed = Path(directory) / "altered-sources.jar"
            with ZipFile(changed, "w") as archive:
                archive.writestr("net/minecraft/SharedConstants.java", "WORLD_VERSION = 4903")
            with self.assertRaises(AssertionError):
                proof.verify_sources(changed)

    def test_archive_names_alone_do_not_satisfy_per_entry_source_proof(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "sources.jar"
            with ZipFile(path, "w") as archive:
                for name in proof.SOURCE_PINS:
                    archive.writestr(name, "changed actual implementation")
            with patch.object(proof.native, "validate_hash"), self.assertRaises(AssertionError):
                proof.verify_sources(path)


if __name__ == "__main__":
    unittest.main()
