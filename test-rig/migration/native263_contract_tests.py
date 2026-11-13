"""Run focused target contracts, explicitly listing unexecuted packaged-input tests."""

import argparse
import json
import os
from pathlib import Path
import sys
import unittest


PENDING_INPUTS = {
    "test_native263_client.ClientAdapterTest.test_actual_native_sdk_launch_is_read_only_and_excludes_mutable_child_outputs": "Parent combined Eclipse launch and asset/classpath pins pending",
    "test_native263_client.ClientAdapterTest.test_agents_classpath_injection_legacy_target_and_connection_arguments_fail": "Parent combined Eclipse launch pending",
    "test_native263_client.ClientAdapterTest.test_missing_native_client_extra_cannot_pass_sdk_preflight": "Parent combined Eclipse launch pending",
    "test_native263_endurance.Native263EnduranceContracts.test_frozen_oracle_verifier_rejects_changed_bytes_duplicate_fields_and_wrong_purpose": "Actual six-jar profile and ordered-runtime proof pins pending",
    "test_native263_endurance.Native263EnduranceContracts.test_full_closure_binds_classifier_review_receipt_and_native_primary_sources": "Actual six-jar profile and ordered-runtime proof pins pending",
}


def flatten(suite):
    for test in suite:
        if isinstance(test, unittest.TestSuite):
            yield from flatten(test)
        else:
            yield test


def run(output, sdk, include_runtime_inputs=False):
    output = Path(output)
    if output.exists():
        raise FileExistsError(output)
    os.environ["NATIVE263_TEST_SDK"] = str(Path(sdk).resolve(strict=True))
    root = Path(__file__).resolve().parent
    tests = list(flatten(unittest.defaultTestLoader.discover(str(root), pattern="test_native263*.py")))
    omitted = {test.id(): PENDING_INPUTS[test.id()] for test in tests
               if test.id() in PENDING_INPUTS and not include_runtime_inputs}
    selected = [test for test in tests if test.id() not in omitted]
    if set(PENDING_INPUTS) - {test.id() for test in tests}:
        raise AssertionError("Pending test inventory no longer matches actual test definitions")
    output.mkdir(parents=True)
    with (output / "tests.log").open("x", encoding="utf-8") as stream:
        result = unittest.TextTestRunner(stream=stream, verbosity=2).run(unittest.TestSuite(selected))
    document = dict(status=("PASS_CONTRACTS_RUNTIME_INPUTS_PENDING" if omitted else "PASS_ALL_TARGET_TESTS")
                    if result.wasSuccessful() else "FAIL", qualificationAllowed=False, engineProtocol="NATIVE66",
                    run=result.testsRun, failures=len(result.failures), errors=len(result.errors),
                    notRun=omitted, selectedTestIds=[test.id() for test in selected], sdk=str(Path(sdk).resolve()))
    with (output / "receipt.json").open("x", encoding="utf-8") as stream:
        json.dump(document, stream, indent=2, sort_keys=True)
        stream.write("\n")
    return document


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--include-runtime-inputs", action="store_true")
    args = parser.parse_args()
    report = run(args.output, args.sdk, args.include_runtime_inputs)
    print(json.dumps(report, indent=2))
    sys.exit(0 if report["status"].startswith("PASS_") else 1)
