"""Translate the fixture's old inventory command to the native 1.17 command."""

import re
from nbtlib import parse_nbt

REPLACE = re.compile(r'replaceitem block (-?\d+ -?\d+ -?\d+) (container\.\d+) ([a-z0-9_.-]+:[a-z0-9_./-]+)(\{.*\})? ([1-9]\d*)')


def native(command):
    if not command.startswith('replaceitem '):
        return command
    match = REPLACE.fullmatch(command)
    if match is None:
        raise ValueError('Unrecognized legacy fixture inventory command')
    payload = match[4] or ''
    if payload:
        parse_nbt(payload)
    return 'item replace block ' + match[1] + ' ' + match[2] + ' with ' + match[3] + payload + ' ' + match[5]
