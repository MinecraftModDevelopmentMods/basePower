"""Load the original bay IDs for historical, synthetic audit fixtures."""

import hashlib
import json
from pathlib import Path


RESOURCE = "assets/advantageworkstest/works/works-1.13.2.json"
ROOT = Path(__file__).resolve().parents[1]


def historical_station_bytes():
    data = (ROOT / "src/main/resources" / RESOURCE).read_bytes()
    for current, original in ((b"basepower", b"poweradvantage"), (b"basesteam", b"steamadvantage"),
                              (b"baseelectric", b"electricadvantage")):
        data = data.replace(current, original)
    hashes = json.loads((ROOT / "migration/native116-historical-inputs.json").read_text())["hashes"]
    if hashlib.sha256(data).hexdigest().upper() != hashes["src/main/resources/" + RESOURCE]:
        raise AssertionError("Reconstructed historical station layout does not match the original bytes")
    return data


def historical_bytes(relative):
    if relative == "src/main/resources/" + RESOURCE:
        return historical_station_bytes()
    data = (ROOT / relative).read_bytes()
    overlays = json.loads((ROOT / "migration/base116-namespace-overlay.json").read_text())["files"]
    for hunk in reversed(overlays.get(relative, [])):
        offset = hunk["byteOffset"]
        edited = hunk["current"].encode()
        if data[offset:offset + len(edited)] != edited:
            raise AssertionError("Expected namespace edit changed: " + relative)
        data = data[:offset] + hunk["historical"].encode() + data[offset + len(edited):]
    return data
