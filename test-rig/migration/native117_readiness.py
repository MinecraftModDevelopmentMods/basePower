"""Read-only native chunk/entity readiness gate shared by disposable runtime tests."""

import json
from pathlib import Path
import re

PROTOCOL = "native117-chunk-readiness-v1"
MAX_CHUNKS = 256
MAX_CHECKS = 1200
MAX_WAIT_MILLIS = 120000


def chunk_set(bounds):
    if len(bounds) != 4 or any(type(value) is not int for value in bounds):
        raise AssertionError("Readiness bounds must contain four integer chunk coordinates")
    min_x, min_z, max_x, max_z = bounds
    width, depth = max_x - min_x + 1, max_z - min_z + 1
    if (min(min_x, min_z) < -1875000 or max(max_x, max_z) > 1874999 or
            not 1 <= width <= 64 or not 1 <= depth <= 64 or width * depth > MAX_CHUNKS):
        raise AssertionError("Readiness bounds exceed the native command limits")
    return {(x, z) for x in range(min_x, max_x + 1) for z in range(min_z, max_z + 1)}


def require_ready(document, bounds, label, dimension="minecraft:overworld"):
    expected = chunk_set(bounds)
    if (document.get("protocol") != PROTOCOL or document.get("status") != "PASS"
            or document.get("label") != label or document.get("minecraft") != "1.17.1"
            or document.get("dimension") != dimension):
        raise AssertionError("Missing or failed native readiness receipt")
    if document.get("bounds") != dict(zip(("minX", "minZ", "maxX", "maxZ"), bounds)):
        raise AssertionError("Native readiness receipt covers the wrong bounds")
    for field in ("checks", "elapsedMillis", "requestedWorldTime", "completedWorldTime"):
        if type(document.get(field)) is not int or document[field] < 0:
            raise AssertionError("Invalid native readiness clock/check evidence")
    if (not 1 <= document["checks"] <= MAX_CHECKS or document["elapsedMillis"] > MAX_WAIT_MILLIS
            or document.get("maximumChecks") != MAX_CHECKS or document.get("maximumWaitMillis") != MAX_WAIT_MILLIS
            or document["completedWorldTime"] < document["requestedWorldTime"]):
        raise AssertionError("Native readiness time bounds were exceeded")

    def states(rows):
        if not isinstance(rows, list) or not rows:
            raise AssertionError("Missing native readiness chunk states")
        result = {}
        for row in rows:
            if (type(row.get("x")) is not int or type(row.get("z")) is not int
                    or type(row.get("blockAndEntitiesLoaded")) is not bool
                    or type(row.get("entityTicking")) is not bool):
                raise AssertionError("Invalid native readiness state")
            key = row["x"], row["z"]
            if key not in expected or key in result:
                raise AssertionError("Duplicate or out-of-bounds native readiness chunk")
            result[key] = row["blockAndEntitiesLoaded"], row["entityTicking"]
        return result

    final = states(document.get("chunks"))
    if set(final) != expected or not all(value == (True, True) for value in final.values()):
        raise AssertionError("Not every native chunk and entity section is ready")
    transitions = document.get("transitions")
    if not isinstance(transitions, list) or not transitions:
        raise AssertionError("Missing native readiness transitions")
    replay, previous_check, previous_time = {}, 0, document["requestedWorldTime"]
    for frame in transitions:
        check, time = frame.get("check"), frame.get("worldTime")
        if (type(check) is not int or not previous_check < check <= document["checks"]
                or type(time) is not int or not previous_time <= time <= document["completedWorldTime"]):
            raise AssertionError("Invalid native readiness transition order")
        changes = states(frame.get("changes"))
        if not replay and (check != 1 or set(changes) != expected):
            raise AssertionError("Incomplete initial native readiness observation")
        if any(replay.get(key) == value for key, value in changes.items()):
            raise AssertionError("Native readiness transition did not change state")
        replay.update(changes)
        if all(value == (True, True) for value in replay.values()) and check != document["checks"]:
            raise AssertionError("Native readiness completed before its reported final check")
        previous_check, previous_time = check, time
    if replay != final or previous_check != document["checks"]:
        raise AssertionError("Native readiness transition replay differs from completion")
    return document


def await_ready(server, runtime, bounds, label, dimension="minecraft:overworld"):
    chunk_set(bounds)
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", label):
        raise AssertionError("Unsafe native readiness label")
    path = Path(runtime) / "advantage-works-results" / ("native-readiness-" + label + ".json")
    if path.exists(): raise FileExistsError(path)
    server.send("advreadiness chunks " + " ".join(map(str, bounds)) + " " + label)
    server.wait("ADVREADINESS RESULT " + label + " status=", 150)
    document = json.loads(path.read_text(encoding="utf-8"))
    require_ready(document, bounds, label, dimension)
    server.wait("ADVREADINESS COMPLETE " + label, 10)
    return document
