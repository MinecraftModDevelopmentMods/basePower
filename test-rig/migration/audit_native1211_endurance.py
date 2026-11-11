"""Independently replay each saved checkpoint from the full Forge 52 endurance run."""

import endurance
import native1211
from audit_native1201_endurance import main
from native117_broad import read_json
from native1211_endurance import measure_batch


class Forge52:
    MINECRAFT, FORGE = native1211.MINECRAFT, native1211.FORGE
    verify_runtime = staticmethod(native1211.audit_staged)
    parse_snapshot = staticmethod(native1211.parse_endurance_snapshot)

    @staticmethod
    def require_native_boot(path):
        native1211.require_clean_log(path)
        text = path.read_text(encoding="utf-8")
        for marker in (f"Starting minecraft server version {native1211.MINECRAFT}",
                       f"Forge mod loading, version {native1211.FORGE}, for MC {native1211.MINECRAFT} "
                       f"with MCP {native1211.MCP}", "Done ("):
            endurance.require(marker in text, f"Missing native startup witness in {path.name}: {marker}")

    @staticmethod
    def verify_batch(runtime, label, documents, definitions):
        stations = ("S-04", "E-03", "W-04", "W-03")
        measured = measure_batch({station: documents[station] for station in stations}, definitions)
        recorded = read_json(runtime / f"semantic-batch-{label}.json")
        endurance.require(measured["status"] == "PASS" and measured == recorded,
                          f"{runtime.name}/{label}: replay disagrees with saved item and fluid observations")


if __name__ == "__main__":
    main(adapter=Forge52)
