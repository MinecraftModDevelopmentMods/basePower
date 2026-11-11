"""Independently replay each saved checkpoint from the full Forge 61 endurance run."""

import endurance
import native12111
from audit_native1201_endurance import main
from native117_broad import read_json
from native12111_endurance import measure_batch


class Forge61:
    MINECRAFT, FORGE = native12111.MINECRAFT, native12111.FORGE
    verify_runtime = staticmethod(native12111.audit_staged)
    parse_snapshot = staticmethod(native12111.parse_endurance_snapshot)

    @staticmethod
    def require_native_boot(path):
        native12111.require_clean_log(path)
        text = path.read_text(encoding="utf-8")
        for marker in (f"Starting minecraft server version {native12111.MINECRAFT}",
                       f"Forge mod loading, version {native12111.FORGE}, for MC {native12111.MINECRAFT} "
                       f"with MCP {native12111.MCP}", "Done ("):
            endurance.require(marker in text, f"Missing native startup witness in {path.name}: {marker}")

    @staticmethod
    def verify_batch(runtime, label, documents, definitions):
        stations = ("S-04", "E-03", "W-04", "W-03")
        measured = measure_batch({station: documents[station] for station in stations}, definitions)
        recorded = read_json(runtime / f"semantic-batch-{label}.json")
        endurance.require(measured["status"] == "PASS" and measured == recorded,
                          f"{runtime.name}/{label}: replay disagrees with saved item and fluid observations")


if __name__ == "__main__":
    main(adapter=Forge61)
