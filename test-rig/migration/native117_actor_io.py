"""Bind exact 1.16 UUID/dimension representations to unchanged historical oracles."""

import copy
import types
import json

import actors
import dimensions
from matrix import normalized, parse_nbt, plain
from native117_format import actor_nbt
from native117_dimension_io import parse_dimension_nbt, validate_record
from native117_identity import target_id


def semantic(value, keys, registry):
    data = actor_nbt(plain(value))
    result = actors.semantic(data, keys, registry)
    if "id" in result and data.get("id") == "baseelectric:hydroturbine":
        result["id"] = data["id"]
    return result


def source_identities(state):
    """Rebrand typed source fields only; observed IDs and user NBT stay literal."""
    result = copy.deepcopy(state)

    def remap_id(value, key="id"):
        if key in value:
            value[key] = target_id(value[key])

    def items(value):
        if not isinstance(value, dict):
            return
        for key in ("Inventory", "EnderItems", "Items", "ArmorItems", "HandItems"):
            for entry in value.get(key, ()):
                stack(entry)
        for key in ("Item", "SaddleItem", "ArmorItem"):
            stack(value.get(key))

    def stack(value):
        if not isinstance(value, dict) or "Count" not in value or "id" not in value:
            return
        remap_id(value)
        tag = value.get("tag", {})
        for key in ("Enchantments", "StoredEnchantments"):
            for enchantment in tag.get(key, ()):
                remap_id(enchantment)
        items(tag.get("BlockEntityTag"))

    def entity(value):
        remap_id(value)
        items(value)
        for passenger in value.get("Passengers", ()):
            entity(passenger)

    for key in ("file", "adapter", "embedded"):
        items(result.get(key))
    for value in result.get("entities", {}).values():
        entity(value)
    generator = result.get("generator")
    if generator:
        remap_id(generator, "name")
        if "tile" in generator:
            entity(generator["tile"])
    return result


def native_normalized(value, registry=None):
    return normalized(actor_nbt(plain(value)), registry)


def bind(function, **bindings):
    return types.FunctionType(function.__code__, dict(function.__globals__, **bindings),
                              function.__name__, function.__defaults__)


capture_actors = bind(actors.capture, semantic=semantic)


class DimensionJson:
    @staticmethod
    def loads(text):
        return validate_record(json.loads(text))


capture_dimensions = bind(dimensions.capture, semantic=semantic, normalized=native_normalized,
                          parse_nbt=parse_dimension_nbt, json=DimensionJson)


def parsed_actor(entry):
    return actor_nbt(plain(parse_nbt(entry)))
