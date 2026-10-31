"""Run every historical mutation control against the 1.17 adapter."""

from unittest.mock import patch
import unittest

import native117_steam_control_audit as native
import test_steam_control_audit as historical_tests


class Native117SteamControlAuditTests(historical_tests.SteamControlAuditTests):
    def setUp(self):
        replacement = patch.object(historical_tests, 'audit', native)
        replacement.start()
        self.addCleanup(replacement.stop)
        line = patch.object(historical_tests, 'LINE', '117')
        line.start()
        self.addCleanup(line.stop)
        super().setUp()


if __name__ == '__main__':
    unittest.main()
