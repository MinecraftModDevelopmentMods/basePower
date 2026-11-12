"""Version the legacy no-duplicate contract for visible, disabled native26 alternatives.

Historical drivers and failed receipts remain unchanged. Only three literal
absence assertions change; original OS1 payloads and all sampling/restart gates
are still checked independently.
"""

import copy
from pathlib import Path

from matrix import digest, write_json
from native117_actor_io import bind
import native2612 as native
import native2612_retained_worldgen as retained
import native2612_strict_migration as strict
import native2612_worldgen as worldgen
from native2612_resources import arguments, require


SPEC = native.RIG / "profiles/native2612/legacy-visible-alternatives.json"
RESULT = "native2612-legacy-alternative-worldgen-results.json"


def contract_spec():
    value = native.read_json(SPEC)
    require(value["protocol"] == "NATIVE26_LEGACY_VISIBLE_DISABLED_ALTERNATIVES_V1"
            and len(value["replacesOnly"]) == 3
            and value["replacement"] == dict(present=True, field="enabled", expectedBoolean=False),
            "Unexpected versioned legacy alternative contract")
    return value


def campaign_spec(name):
    require(name == "legacy-import", "This contract is only for the legacy import route")
    spec = copy.deepcopy(worldgen.campaign_spec(name))
    replacements = {row["id"]: row for row in contract_spec()["replacesOnly"]}
    require(len(replacements) == 3, "Duplicate alternative contract IDs")
    for phase in ("first", "restart"):
        seen = set()
        for assertion in spec[phase + "ProfileAssertions"]:
            if assertion["id"] not in replacements:
                continue
            require(assertion["id"] not in seen, "Duplicated historical alternative assertion")
            target = replacements[assertion["id"]]
            require(assertion == {key: target[key] for key in ("id", "section", "entry")} | {"present": False},
                    "Historical absence assertion changed; review rather than replace it blindly")
            assertion.update(contract_spec()["replacement"])
            seen.add(assertion["id"])
        require(seen == replacements.keys(), "Missing or duplicated historical alternative assertions")
    return spec


def validate_profile(document, assertions):
    rows = worldgen.validate_profile(document, assertions)
    for target in contract_spec()["replacesOnly"]:
        rule = document[target["section"]][target["entry"]]
        require(rule.get("enabled") is False and rule.get("block") == target["block"],
                "Modern alternative must be visible but disabled: " + target["entry"])
        require(rule.get("retrogen", False) is False, "Import must not enable retrogen")
        dimensions = rule.get("dimensions")
        require(isinstance(dimensions, dict) and "minecraft:overworld" in dimensions,
                "Alternative is not a complete editable placement rule")
        for placement in dimensions.values():
            require(isinstance(placement, dict)
                    and all(key in placement for key in ("enabled", "min_y", "max_y", "frequency", "host_blocks", "host_tags"))
                    and placement["min_y"] <= placement["max_y"] and placement["frequency"] > 0
                    and isinstance(placement["host_blocks"], list) and placement["host_blocks"],
                    "Disabled alternative lost its actual editable geometry/hosts")
        if target["section"] == "ores":
            require(all(key in row for row in dimensions.values() for key in
                        ("min_quantity", "max_quantity", "pattern", "placement_channel")),
                    "Typed ore alternative is incomplete")
        else:
            require(all(key in row for row in dimensions.values() for key in
                        ("min_radius", "max_radius", "min_vertical_radius", "max_vertical_radius")),
                    "Fluid alternative is incomplete")
    spec = campaign_spec("legacy-import")
    expected = dict(ores={}, fluid_deposits={})
    for source, destination in spec["configFixtures"]:
        old = destination.stem
        current = {"poweradvantage": "basepower", "electricadvantage": "baseelectric"}[old]
        projected = retained.os1_profile(native.read_json(source), current, old)
        expected["ores"].update(projected["ores"])
    outputs = {row["block"] for row in contract_spec()["replacesOnly"]}
    audit = retained.audit_transition(expected, document, outputs)
    require(audit["status"] == "PASS", "Imported OS1 settings changed: " + str(audit["issues"]))
    return rows + [dict(id="source-os1-payload-and-no-active-duplicates", status="PASS", contract=audit)]


class LegacyServer(worldgen.WorldgenServer):
    def validate_native_log(self, path):
        super().validate_native_log(path)
        strict.clean_log(path)


def preflight(args, sources):
    return worldgen.preflight(args, [*sources, SPEC, Path(worldgen.__file__), Path(retained.__file__), Path(strict.__file__)])


def run(args):
    execute = bind(worldgen.run, campaign_spec=campaign_spec, validate_profile=validate_profile,
                   WorldgenServer=LegacyServer, preflight=preflight, RESULT=RESULT, __file__=__file__)
    result = execute(args)
    result["expectationContract"] = dict(path=str(SPEC), sha256=digest(SPEC), **contract_spec())
    result["historicalDriverSha256"] = digest(Path(worldgen.__file__))
    if not args.preflight_only:
        write_json(Path(args.output) / RESULT, result)
    return result


def main():
    parser = arguments(__doc__)
    parser.add_argument("--campaign", choices=("legacy-import",), default="legacy-import")
    parser.add_argument("--sample-timeout", type=int, default=1800)
    print(run(parser.parse_args())["status"], flush=True)


if __name__ == "__main__":
    main()
