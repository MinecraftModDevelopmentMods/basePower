import copy
import unittest
from unittest.mock import patch
import json
from pathlib import Path
import tempfile
import os

from native117_respawn import native_respawn_verdict, publish_request
from native117_audit_respawn import replay


class NativeRespawnAdapterTest(unittest.TestCase):
    def test_tick_reader_never_observes_definition_truncation(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "dimension-fixture.json"
            old = {"dimension": -1, "respawn": {"bedMode": "forced", "keepInventory": True}}
            path.write_text(json.dumps(old))
            if os.name == "nt":
                import ctypes
                from ctypes import wintypes
                kernel = ctypes.WinDLL("kernel32", use_last_error=True)
                kernel.CreateFileW.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD,
                                               wintypes.LPVOID, wintypes.DWORD, wintypes.DWORD, wintypes.HANDLE]
                kernel.CreateFileW.restype = wintypes.HANDLE
                kernel.CloseHandle.argtypes = [wintypes.HANDLE]
                handle = kernel.CreateFileW(str(path), 0x80000000, 1, None, 3, 128, None)
                if handle == wintypes.HANDLE(-1).value:
                    raise ctypes.WinError(ctypes.get_last_error())
                try:
                    binding = publish_request(Path(temp), "respawn-keep-bed", True, "bed")
                finally:
                    kernel.CloseHandle(handle)
            else:
                with path.open("rb"):
                    binding = publish_request(Path(temp), "respawn-keep-bed", True, "bed")
            self.assertEqual(old, json.loads(path.read_text()))
            self.assertTrue((Path(temp) / binding["path"]).is_file())

    def test_failed_request_write_keeps_the_root_fixture_unchanged(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "dimension-fixture.json"
            old = {"dimension": 1, "respawn": {"bedMode": "missing", "keepInventory": False}}
            path.write_text(json.dumps(old))
            original = Path.open
            def failed_request(target, *args, **kwargs):
                if target.parent.name == "respawn-requests" and args and args[0] == "xb":
                    raise OSError("disk write failed")
                return original(target, *args, **kwargs)
            with patch.object(Path, "open", failed_request):
                with self.assertRaisesRegex(OSError, "disk write failed"):
                    publish_request(Path(temp), "respawn-drop-missing", False, "missing")
            self.assertEqual(old, json.loads(path.read_text()))
            self.assertEqual([], list((Path(temp) / "respawn-requests").iterdir()))

    def test_only_exact_native_handler_is_translated(self):
        for handler, expected in (("ServerPlayNetHandler.PERFORM_RESPAWN", "NetHandlerPlayServer.PERFORM_RESPAWN"),
                                  ("Other.PERFORM_RESPAWN", "Other.PERFORM_RESPAWN"),
                                  ("NetHandlerPlayServer.PERFORM_RESPAWN", "NetHandlerPlayServer.PERFORM_RESPAWN")):
            original = {"respawn": {"handler": handler}, "retain": {"Inventory": [17], "bed": "missing"}}
            before = copy.deepcopy(original)
            with patch("native117_respawn.respawn_verdict", return_value={"status": "TEST"}) as oracle:
                self.assertEqual({"status": "TEST"}, native_respawn_verdict(original, {1: "enchantment"}, {"radius": 10}))
                adapted = copy.deepcopy(before)
                adapted["respawn"]["handler"] = expected
                oracle.assert_called_once_with(adapted, {1: "enchantment"}, {"radius": 10})
            self.assertEqual(before, original)

    def test_aborted_campaign_never_becomes_a_pass(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for name in ("results.json", "source-fingerprints.json"):
                (root / name).write_text(json.dumps({}))
            manifest = root / "archived-inputs.json"
            manifest.write_text("{}")
            with self.assertRaisesRegex(ValueError, "explicit pinned target manifest"):
                replay(root)
            with patch("native117_audit_respawn.pinned_profile") as pin:
                result = replay(root, manifest)
            pin.assert_called_once_with(manifest.resolve(), "target117")
            self.assertEqual("FAIL", result["status"])
            self.assertIn("Campaign summary missing", result["findings"][0])
            self.assertFalse((root / "summary.json").exists())


if __name__ == "__main__":
    unittest.main()
