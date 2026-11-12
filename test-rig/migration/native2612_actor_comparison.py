"""Read-only actor restart diagnosis; never replaces or upgrades the frozen verdict."""

import argparse
import hashlib
import json
import math
from pathlib import Path
import struct
import uuid

import nbtlib

import native117_machine_operations as frozen


CASES = {"steam/extra-explosive-0", "steam/extra-explosive-1"}
SOURCES_SHA256 = "9175F1245E5E2907EA3ECAB13DC40F9B8994E61759E75363F6FB14322335F013"


def differences(before, after, path="$"):
    if isinstance(before, dict) and isinstance(after, dict):
        result = []
        for key in sorted(set(before) | set(after)):
            name = path + "." + key
            if key not in before or key not in after:
                result.append(dict(path=name, before=before.get(key), after=after.get(key),
                                   beforePresent=key in before, afterPresent=key in after))
            else:
                result.extend(differences(before[key], after[key], name))
        return result
    if (isinstance(before, list) and isinstance(after, list) and len(before) == len(after) == 3
            and before[0] == after[0] == "List" and before[1] == after[1]
            and len(before[2]) == len(after[2])):
        return [delta for index, (a, b) in enumerate(zip(before[2], after[2]))
                for delta in differences(a, b, path + "[" + str(index) + "]")]
    return [] if before == after else [dict(path=path, before=before, after=after)]


def actors(case):
    result = {}
    for row in case.get("entities", []):
        identity = str(uuid.UUID(row["uuid"]))
        if identity != row["uuid"] or identity in result:
            raise AssertionError("Duplicate or noncanonical native actor UUID")
        tag = nbtlib.parse_nbt(row["nbt"])
        if not isinstance(tag.get("UUID"), nbtlib.IntArray) or len(tag["UUID"]) != 4:
            raise AssertionError("Missing native actor UUID array")
        actual = uuid.UUID(bytes=struct.pack(">4i", *(int(part) for part in tag["UUID"])))
        if str(actual) != identity:
            raise AssertionError("Native actor metadata and saved UUID disagree")
        result[identity] = tag
    return result


def stationary_cows(case_id, before, after):
    return case_id in CASES and all(
        isinstance(tag.get("id"), nbtlib.String) and str(tag["id"]) == "minecraft:cow"
        and type(tag.get("NoAI")) is nbtlib.Byte and int(tag["NoAI"]) == 1
        and type(tag.get("NoGravity")) is nbtlib.Byte and int(tag["NoGravity"]) == 1
        for tag in (before, after))


def explanation(case_id, before, after, delta):
    if not stationary_cows(case_id, before, after):
        return None
    for index in range(3):
        if delta["path"] != "$.Motion[" + str(index) + "]":
            continue
        a, b = before.get("Motion"), after.get("Motion")
        if (isinstance(a, nbtlib.List) and isinstance(b, nbtlib.List)
                and a.subtype is b.subtype is nbtlib.Double and len(a) == len(b) == 3
                and all(math.isfinite(float(value)) for value in (*a, *b))
                and abs(float(a[index])) > 10.0 and float(b[index]) == 0.0):
            return "Entity.load: only this >10 native velocity component is clamped to zero"
    if delta["path"] == "$.HurtByTimestamp":
        if (type(before.get("HurtByTimestamp")) is nbtlib.Int
                and type(after.get("HurtByTimestamp")) is nbtlib.Int
                and int(before["HurtByTimestamp"]) >= 0 and int(after["HurtByTimestamp"]) == 0
                and all(key not in tag for tag in (before, after)
                        for key in ("last_hurt_by_mob", "ticks_since_last_hurt_by_mob"))):
            return "LivingEntity.readAdditionalSaveData: absent relative hurt key rebases to unticked zero"
    return None


def diagnose(fixture, inspected):
    # Recheck exact selected native load methods before explaining any transition.
    from native2612_operations import native_load_sources
    native_load_sources()
    verdict = frozen.validate_restart(fixture, inspected)
    actual = {row["id"]: row for row in inspected["cases"]}
    observations = []
    for case in fixture["cases"]:
        before, after = actors(case), actors(actual[case["id"]])
        for identity in sorted(set(before) | set(after)):
            if identity not in before or identity not in after:
                observations.append(dict(case=case["id"], uuid=identity, path="$",
                                         beforePresent=identity in before, afterPresent=identity in after,
                                         explanation=None))
                continue
            for delta in differences(frozen.saved_actor_nbt(before[identity]), frozen.saved_actor_nbt(after[identity])):
                observations.append(dict(case=case["id"], uuid=identity, **delta,
                                         explanation=explanation(case["id"], before[identity], after[identity], delta)))
    unknown = sum(row["explanation"] is None for row in observations)
    return dict(diagnosticOnly=True, frozenRestartResult=verdict, actorDifferences=observations,
                unexplainedActorDifferences=unknown, primarySourcesSha256=SOURCES_SHA256,
                scope="Explains exact native load transitions only; no NBT writes or passing reclassification")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("runtime", type=Path)
    args = parser.parse_args()
    paths = [args.runtime / "machine-operation-fixture.json",
             args.runtime / "advantage-works-results/machine-inspection-restarted.json"]
    data = [path.read_bytes() for path in paths]
    report = diagnose(*(json.loads(value) for value in data))
    report["inputSha256"] = {str(path.resolve()): hashlib.sha256(value).hexdigest().upper()
                             for path, value in zip(paths, data)}
    print(json.dumps(report, indent=2, sort_keys=True))
    return int(report["frozenRestartResult"]["status"] != "PASS")


if __name__ == "__main__":
    raise SystemExit(main())
