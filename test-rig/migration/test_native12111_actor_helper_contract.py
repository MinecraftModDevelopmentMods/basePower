import copy
import hashlib
from pathlib import Path
import unittest

import native12111_actors as native


class NativeActorHelperContractTest(unittest.TestCase):
    def setUp(self):
        self.contract = (Path(__file__).resolve().parents[1] / "src/main/resources/assets/advantageworkstest"
                         / "native12111-helper-contract.json").read_bytes()
        self.document = dict(helperPolicy="native12111-leash-knot-nosave-v1",
                             helperContractSha256=native.HELPER_CONTRACT_SHA,
                             dimensions={}, entityCount=0, nonpersistentHelperCount=0, observedEntityCount=0)

    def test_driver_uses_the_exact_current_native_helper_evidence(self):
        self.assertEqual(native.HELPER_CONTRACT_SHA, hashlib.sha256(self.contract).hexdigest().upper())
        result = native.validate_helpers(self.document, self.contract)
        self.assertEqual("NOT_REQUIRED", result["status"])

    def test_historical_or_modified_contract_bytes_cannot_qualify(self):
        historical = (Path(__file__).resolve().parents[1] / "src/main/resources/assets/advantageworkstest"
                      / "native1211-helper-contract.json").read_bytes()
        for payload in (historical, self.contract + b" "):
            with self.assertRaisesRegex(AssertionError, "evidence pin differs"):
                native.validate_helpers(self.document, payload)

    def test_stopped_observation_cannot_claim_another_policy_or_hash(self):
        for field, value in (("helperPolicy", "native1211-leash-knot-nosave-v1"),
                             ("helperContractSha256", "0" * 64)):
            document = copy.deepcopy(self.document)
            document[field] = value
            with self.assertRaisesRegex(AssertionError, "policy differs"):
                native.validate_helpers(document, self.contract)


if __name__ == "__main__":
    unittest.main()
