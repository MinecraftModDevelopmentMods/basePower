"""Stopped-boot retained-profile and zero-invalid-output gates; old drivers stay unchanged."""

import copy
from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace
import traceback

from matrix import WORLD, digest, fingerprint, write_json
from native117 import output_path
from native117_actor_io import bind
import native262 as native
import native262_upgrade as upgrade
import native262_actor_transformer_order as actor_order
import native262_retained_worldgen as retained


REJECT = ("Ignoring invalid OreSpawn-managed ore", "Ignoring OreSpawn-managed ore",
          "Ignoring invalid fluid deposit", "Ignoring invalid dimension",
          "Ignoring invalid placement rule for OreSpawn-managed ore",
          "Ignoring unknown dimension selector", "Ignoring invalid selector rule")


def clean_log(path):
    diagnostics = native.require_clean_log(path)
    lines = Path(path).read_text(encoding="utf-8", errors="strict").splitlines()
    failures = [line for line in lines if any(value in line for value in REJECT)]
    if failures:
        raise AssertionError("Ignored/invalid worldgen outputs are forbidden: " + repr(failures[:8]))
    return diagnostics


def contract(expected, actual, registry):
    result = retained.audit_transition(expected, actual, registry)
    outputs = {retained.output_id(row.get("block", key)) for section in retained.SECTIONS
               for key, row in expected.get(section, {}).items() if isinstance(row, dict)}
    for section in retained.SECTIONS:
        for key, rule in actual.get(section, {}).items():
            if not isinstance(rule, dict):
                raise AssertionError("Malformed actual ore/deposit rule: " + key)
            if key not in expected.get(section, {}) and retained.active(rule) and rule.get("block", key) in outputs:
                result["issues"].append(dict(kind="CROSS_SECTION_NEW_ACTIVE_DEFAULT", section=section, rule=key))
            for field, output in [("block", rule.get("block", key)), ("deep_output", rule.get("deep_output")),
                                  *(("outputs", row.get("block")) for row in rule.get("outputs", []))]:
                if output is not None and output not in registry:
                    result["issues"].append(dict(kind="UNKNOWN_ACTUAL_OUTPUT", section=section, rule=key, field=field, output=output))
    result["status"] = "FAIL" if result["issues"] else "PASS"
    return result


def source_profiles(source):
    source = Path(source)
    expected, fact = retained.source_expectation(source)
    global_file = source / "config/orespawn-worldgen.json"
    return dict(world=expected, global_profile=upgrade.read_json(global_file) if global_file.is_file() else None,
                oracle=fact, sourceFingerprint=fingerprint(source))


def server_type(base, source):
    expectations = source_profiles(source)

    class StrictServer(base):
        def close(self):
            if getattr(self, "strict_profile_audited", False):
                return super().close()
            self.strict_profile_audited = True
            failure = None
            try:
                super().close()
            except BaseException as error:
                failure = error
            root = Path(self.native_runtime)
            output = root / "strict-profile-audits" / self.native_label
            output.mkdir(parents=True, exist_ok=False)
            report = dict(status="FAIL", oracle=expectations["oracle"], ignoredOutputWarningCount=None,
                          driverFailure=repr(failure) if failure else None, profiles={})
            try:
                if self.process.poll() is None:
                    raise AssertionError("Never inspect profile/registry state of an active server")
                clean_log(root / ("console-" + self.native_label + ".log"))
                report["ignoredOutputWarningCount"] = 0
                world = root / WORLD
                registry = retained.registered_blocks(world)
                cases = [("world", world / "serverconfig/orespawn-worldgen.json", expectations["world"])]
                if expectations["global_profile"] is not None:
                    cases.append(("global", root / "config/orespawn-worldgen.json", expectations["global_profile"]))
                for label, path, expected in cases:
                    actual = upgrade.read_json(path)
                    checked = contract(expected, actual, registry)
                    write_json(output / (label + ".json"), actual)
                    report["profiles"][label] = dict(file=str(path), sha256=digest(path), contract=checked)
                if any(row["contract"]["status"] != "PASS" for row in report["profiles"].values()):
                    raise AssertionError("Retained global/world profile differs from its own sealed source: " + str(output))
                if fingerprint(Path(source)) != expectations["sourceFingerprint"]:
                    raise AssertionError("Protected source changed")
                if failure is None:
                    report["status"] = "PASS"
            except BaseException as audit:
                report.update(error=repr(audit), traceback=traceback.format_exc())
                if failure is None:
                    failure = audit
                else:
                    failure.add_note("Strict profile/output audit also failed: " + repr(audit))
            finally:
                write_json(output / "receipt.json", report)
            if failure is not None:
                raise failure

    return StrictServer


def machine(args, seal):
    source, reference, receipt, _, _, _ = upgrade.read_source(args)
    native.read_family_target(args.profile, args.seed, args.inventory, args.inventory_sha256)
    native.java25(args.java)
    root = output_path(args.output)
    files = {**upgrade.oracle_closure(), Path(args.profile): digest(args.profile),
             Path(args.inventory): digest(args.inventory), receipt: digest(receipt)}
    files.update({Path(row["path"]): row["sha256"] for row in upgrade.read_json(args.profile)["mods"]})
    if Path(seal).exists():
        raise AssertionError("Never replace a pre-run seal")
    write_json(Path(seal), dict(scope="GENUINE_PRERUN_STRICT_MACHINE_MIGRATION", setupNotStarted=True,
               createdUtc=datetime.now(timezone.utc).isoformat(), files={str(p): h for p, h in files.items()},
               source=str(source), sourceFingerprint=fingerprint(source), reference=str(reference),
               referenceFingerprint=fingerprint(reference), seedFingerprint=fingerprint(Path(args.seed)),
               oracle=source_profiles(source)["oracle"], arguments={k: str(v) if isinstance(v, Path) else v for k, v in vars(args).items()}))
    return upgrade.run(args, server_type=server_type(native.Server, source))


def actors(args):
    def campaign(values, *, server_type: type, stage_function, hook_only=False):
        return actor_order.run_actor_campaign(values, server_type=globals()["server_type"](server_type, values.fixture),
                                              stage_function=stage_function, hook_only=hook_only)
    return bind(actor_order.run, run_actor_campaign=campaign)(args)


def main():
    import sys
    mode = sys.argv.pop(1)
    if mode == "machine":
        parser = upgrade.parser()
        parser.add_argument("--pre-run-seal", type=Path, required=True)
        args = parser.parse_args()
        return machine(args, args.pre_run_seal)
    if mode == "actors":
        return actors(actor_order.parser().parse_args())
    raise ValueError("Expected machine or actors")


if __name__ == "__main__":
    raise SystemExit(main())
