from pathlib import Path
import tempfile
import unittest

from audit_native117_endurance import retained_protocol
from matrix import digest


class EnduranceProtocolArchive(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.folder = self.root / 'protocol-sources'
        self.folder.mkdir()
        self.path = self.folder / 'native117_commands.py'
        self.path.write_bytes(b'original command protocol\n')
        self.hashes = {str(self.root / 'working-copy/native117_commands.py'): digest(self.path)}

    def test_archived_bytes_remain_auditable_when_working_copy_is_absent(self):
        self.assertEqual({'native117_commands.py': self.path}, retained_protocol(self.root, self.hashes))

    def test_changed_archive_cannot_be_replaced_by_a_summary_claim(self):
        self.path.write_bytes(b'changed protocol')
        with self.assertRaisesRegex(AssertionError, 'Archived protocol source changed'):
            retained_protocol(self.root, self.hashes)

    def test_missing_extra_or_ambiguous_inputs_fail(self):
        self.path.unlink()
        with self.assertRaises(AssertionError): retained_protocol(self.root, self.hashes)
        self.path.write_bytes(b'original command protocol\n')
        (self.folder / 'unlisted.py').write_bytes(b'additional')
        with self.assertRaisesRegex(AssertionError, 'inventory differs'):
            retained_protocol(self.root, self.hashes)
        (self.folder / 'unlisted.py').unlink()
        self.hashes[str(self.root / 'other/native117_commands.py')] = digest(self.path)
        with self.assertRaisesRegex(AssertionError, 'Ambiguous'):
            retained_protocol(self.root, self.hashes)


if __name__ == '__main__':
    unittest.main()
