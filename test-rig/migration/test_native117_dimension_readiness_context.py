"""Explicit dimension context without weakening readiness receipt semantics."""

import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

import native117_actors as live
import native117_audit_actors as audit
from native117_readiness import require_ready

BOUNDS = (6, -2, 13, 2)


def ready(key, label):
    rows = [dict(x=x, z=z, blockAndEntitiesLoaded=True, entityTicking=True)
            for x in range(6, 14) for z in range(-2, 3)]
    return dict(protocol="native117-chunk-readiness-v1", status="PASS", label="entities-" + label,
        minecraft="1.17.1", dimension=key, bounds=dict(zip(("minX", "minZ", "maxX", "maxZ"), BOUNDS)),
        checks=1, elapsedMillis=1, requestedWorldTime=10, completedWorldTime=11,
        maximumChecks=1200, maximumWaitMillis=120000, chunks=rows,
        transitions=[dict(check=1, worldTime=11, changes=rows)])


class DimensionReadinessContextTest(unittest.TestCase):
    def test_live_calls_pass_actual_nether_and_end_context(self):
        for identity, key in ((-1, "minecraft:the_nether"), (1, "minecraft:the_end")):
            with self.subTest(key=key), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                (root / "dimension-fixture.json").write_text(json.dumps(dict(dimension=identity)))
                server = Mock()
                with patch.object(live, "await_ready", return_value=ready(key, "conversion")) as wait:
                    live.ready_entities(server, root, "dimensions", "conversion")
                self.assertEqual((root, BOUNDS, "entities-conversion", key), wait.call_args.args[1:])
                wait.call_args.args[0].send("probe")
                server.send.assert_called_with("execute in " + key + " run probe")

    def test_replay_passes_same_explicit_dimension_and_keeps_raw_gate(self):
        for identity, key in ((-1, "minecraft:the_nether"), (1, "minecraft:the_end")):
            with self.subTest(key=key), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                (root / "dimension-fixture.json").write_text(json.dumps(dict(dimension=identity)))
                folder = root / "advantage-works-results"; folder.mkdir()
                recorded = {}
                for phase in ("conversion", "restart", "runtime-restart"):
                    (folder / ("native-readiness-entities-" + phase + ".json")).write_text(json.dumps(ready(key, phase)))
                    recorded["saved-entities-" + phase] = dict(snapshotSha256="A" * 64)
                with patch.object(audit, "raw", return_value=dict(gameTime=12)), \
                     patch.object(audit, "replay_entity_storage", return_value=dict(status="PASS", matchedEntities=4,
                        snapshotSha256="A" * 64, auditedDimensions=1)):
                    result = audit.native_controls(root, "dimensions", recorded)
                self.assertEqual(6, len(result))

    def test_wrong_dimension_still_fails(self):
        document = ready("minecraft:the_nether", "conversion")
        self.assertIs(document, require_ready(document, BOUNDS, "entities-conversion", "minecraft:the_nether"))
        for key in ("minecraft:overworld", "minecraft:the_end"):
            with self.subTest(key=key), self.assertRaises(AssertionError):
                require_ready(document, BOUNDS, "entities-conversion", key)


if __name__ == "__main__":
    unittest.main()
