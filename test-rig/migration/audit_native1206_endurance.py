"""Independently replay each saved checkpoint from the full Forge 50 endurance run."""

import endurance
import native1206
from audit_native1201_endurance import main
from native117_broad import read_json
from native1206_endurance import measure_batch


class Forge50:
    MINECRAFT, FORGE = native1206.MINECRAFT, native1206.FORGE
    verify_runtime = staticmethod(native1206.audit_staged)
    parse_snapshot = staticmethod(native1206.parse_endurance_snapshot)

    @staticmethod
    def require_native_boot(path):
        native1206.require_clean_log(path)
        text = path.read_text(encoding="utf-8")
        for marker in (f"Starting minecraft server version {native1206.MINECRAFT}",
                       f"Forge mod loading, version {native1206.FORGE}, for MC {native1206.MINECRAFT} "
                       f"with MCP {native1206.MCP}", "Done ("):
            endurance.require(marker in text, f"Missing native startup witness in {path.name}: {marker}")

    @staticmethod
    def verify_batch(runtime, label, documents, definitions):
        stations = ("S-04", "E-03", "W-04", "W-03")
        measured = measure_batch({station: documents[station] for station in stations}, definitions)
        recorded = read_json(runtime / f"semantic-batch-{label}.json")
        endurance.require(measured["status"] == "PASS" and measured == recorded,
                          f"{runtime.name}/{label}: replay disagrees with saved item and fluid observations")


if __name__ == "__main__":
    main(adapter=Forge50)
