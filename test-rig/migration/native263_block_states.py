"""26.3 BlockState.CODEC projections, separate from historical palette semantics."""

import re

from native117_format import decode_palette as padded_palette


IDENTIFIER = re.compile(r"[a-z0-9_.-]+:[a-z0-9_./-]+")
PROPERTY = re.compile(r"[a-z0-9_]+")
VALUE = re.compile(r"[a-z0-9_-]+")


def block_state(value):
    """Project only an actual native string or id/properties compound, never an expectation."""
    if isinstance(value, str):
        identity, properties = value, {}
    elif isinstance(value, dict):
        if set(value) - {"id", "properties"} or "id" not in value:
            raise ValueError("Native263 block state requires id/properties, not historical keys")
        identity, properties = value["id"], value.get("properties", {})
        if not isinstance(properties, dict):
            raise ValueError("Native block-state properties must be a compound")
    else:
        raise ValueError("Native block state must be a compact identifier or compound")
    if not isinstance(identity, str) or IDENTIFIER.fullmatch(identity) is None:
        raise ValueError("Native block state requires a namespaced identifier")
    if any(not isinstance(key, str) or PROPERTY.fullmatch(key) is None
           or not isinstance(item, str) or VALUE.fullmatch(item) is None for key, item in properties.items()):
        raise ValueError("Native block-state properties require exact string values")
    return dict(Name=str(identity), Properties={str(key): str(item) for key, item in properties.items()})


def decode_palette(palette, words, count=4096):
    return padded_palette([palette_state(entry) for entry in palette], words, count)


def palette_state(value):
    # Native ListTag.write wraps non-compounds in heterogeneous lists; its reader
    # unwraps exactly one empty-key compound. nbtlib deliberately retains raw NBT.
    if isinstance(value, dict) and set(value) == {""}:
        return block_state(value[""])
    return block_state(value)
