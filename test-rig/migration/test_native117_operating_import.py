import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from matrix import digest
from native117_operating_import import approved_source


class OperatingSourceApprovalTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.report = self.root / 'native116-machine-operation-results.json'

    def write(self, value):
        self.report.write_text(json.dumps(value))
        return digest(self.report)

    def test_changed_source_report_is_rejected_before_any_profile_is_opened(self):
        checksum = self.write(dict(status='PASS'))
        self.write(dict(status='FAIL'))
        with patch('native117_operating_import.native116.pinned_profile') as profile:
            with self.assertRaisesRegex(AssertionError, 'report changed'):
                approved_source(self.root, checksum)
            profile.assert_not_called()

    def test_failed_partial_or_rewritten_protocol_is_not_source_approval(self):
        baseline = dict(status='PASS', group='all', protocolUnchanged=True,
            phases={phase: dict(status='PASS', cases=132) for phase in ('operation', 'restart')})
        changes = [dict(status='FAIL'), dict(group='steam'), dict(protocolUnchanged=False),
            dict(phases={'operation': dict(status='PASS', cases=132)}),
            dict(phases={phase: dict(status='PASS', cases=131) for phase in ('operation', 'restart')})]
        for change in changes:
            with self.subTest(change=change), patch('native117_operating_import.native116.pinned_profile') as profile:
                checksum = self.write(dict(baseline, **change))
                with self.assertRaisesRegex(AssertionError, 'complete accepted 132-case source'):
                    approved_source(self.root, checksum)
                profile.assert_not_called()


if __name__ == '__main__':
    unittest.main()
