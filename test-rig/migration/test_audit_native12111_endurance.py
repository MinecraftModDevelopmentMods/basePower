import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

from audit_native12111_endurance import Forge61


class NativeEnduranceReplayTest(unittest.TestCase):
    def test_replay_requires_a_matching_positive_semantic_checkpoint(self):
        documents = {station: {"station": station} for station in
                     ("S-04", "E-03", "W-04", "W-03", "E-02")}
        definitions = {"W-03": {"id": "W-03"}}
        measured = {"status": "PASS", "measurements": {"oil": 1000}}
        with TemporaryDirectory() as directory:
            runtime = Path(directory)
            checkpoint = runtime / "semantic-batch-cycle-001.json"
            checkpoint.write_text(json.dumps(measured), encoding="utf-8")
            with patch("audit_native12111_endurance.measure_batch", return_value=measured) as measure:
                Forge61.verify_batch(runtime, "cycle-001", documents, definitions)
                measure.assert_called_once_with({key: value for key, value in documents.items()
                                                if key != "E-02"}, definitions)

                checkpoint.write_text(json.dumps({"status": "PASS", "measurements": {"oil": 999}}),
                                      encoding="utf-8")
                with self.assertRaisesRegex(AssertionError, "replay disagrees"):
                    Forge61.verify_batch(runtime, "cycle-001", documents, definitions)

                failed = {"status": "FAIL", "failures": ["missing oil"]}
                checkpoint.write_text(json.dumps(failed), encoding="utf-8")
                measure.return_value = failed
                with self.assertRaisesRegex(AssertionError, "replay disagrees"):
                    Forge61.verify_batch(runtime, "cycle-001", documents, definitions)
