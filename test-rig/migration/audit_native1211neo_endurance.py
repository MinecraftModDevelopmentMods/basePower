"""Independently replay every raw NeoForge 1.21.1 endurance checkpoint."""

import endurance
import native1211neo as native
from audit_native1201_endurance import main
from native117_broad import read_json
from native1211neo_endurance import measure_batch


class NeoForge1211:
    MINECRAFT, FORGE = native.MINECRAFT, native.NEOFORGE
    RECEIPT_NAME = "native1211neo-endurance-results.json"
    MODES = ("fresh1211", "retained-upgrade-native1211neo")
    LOADER_VERSION_KEY = "neoforge"
    verify_runtime = staticmethod(native.audit_staged)
    parse_snapshot = staticmethod(native.parse_endurance_snapshot)

    @staticmethod
    def validate_checkpoint(document):
        endurance.require(document.get("loader") == "neoforge" and document.get("neoforge") == native.NEOFORGE,
                          "Checkpoint lacks the native NeoForge runtime identity")

    @staticmethod
    def require_native_boot(path):
        native.require_clean_log(path)
        text = path.read_text(encoding="utf-8")
        for marker in (f"Starting minecraft server version {native.MINECRAFT}",
                       f"NeoForge mod loading, version {native.NEOFORGE}, for MC {native.MINECRAFT}", "Done ("):
            endurance.require(marker in text, f"Missing native startup witness in {path.name}: {marker}")

    @staticmethod
    def verify_batch(runtime, label, documents, definitions):
        stations = ("S-04", "E-03", "W-04", "W-03")
        measured = measure_batch({station: documents[station] for station in stations}, definitions)
        recorded = read_json(runtime / f"semantic-batch-{label}.json")
        endurance.require(measured["status"] == "PASS" and measured == recorded,
                          f"{runtime.name}/{label}: replay disagrees with the saved observations")


if __name__ == "__main__":
    main(adapter=NeoForge1211)
