"""Translate legacy fixture expectations to the released base* identities."""

import re

NAMESPACES = {"poweradvantage": "basepower", "steamadvantage": "basesteam",
              "electricadvantage": "baseelectric"}
_ID = re.compile(r"^(?:minecraft:)?(poweradvantage|steamadvantage|electricadvantage)[:.](.+)$")
_COMMAND_ID = re.compile(r"(?<![\w.:/])(poweradvantage|steamadvantage|electricadvantage):")


def target_id(value):
    if not isinstance(value, str):
        return value
    if value in ("crude_oil", "refined_oil"):
        return "basepower:" + value
    match = _ID.fullmatch(value)
    if match:
        return NAMESPACES[match[1]] + ":" + match[2]
    return value


def fixture_ids(value):
    """Apply to fixture definitions, never to observed target state."""
    if isinstance(value, dict):
        return {target_id(key): fixture_ids(entry) for key, entry in value.items()}
    if isinstance(value, list):
        return [fixture_ids(entry) for entry in value]
    return target_id(value)


def command_ids(command):
    return _COMMAND_ID.sub(lambda match: NAMESPACES[match[1]] + ":", command)
