"""Native64 oil transport and separately classified actual-world renderer evidence."""

from pathlib import Path
import shutil

import lifecycle
from matrix import digest, write_json
from native117 import output_path
from native117_lifecycle import authorize_lifecycle
from native117_oil import NativeOilRun, construction_commands, exercise, fixtures, require_document
import native2612 as native
import native2612_layout as layout
from native2612_resources import arguments, artifact, pixel_pair, preflight, require


RESULT = "native2612-oil-results.json"
OILS = ("crude_oil", "refined_oil")


def native_boot(path):
    native.require_clean_log(path)
    text = path.read_text(encoding="utf-8")
    require(f"Starting minecraft server version {native.MINECRAFT}" in text
            and f"Forge mod loading, version {native.FORGE}, for MC {native.MINECRAFT} with MCP {native.MCP}" in text,
            "Missing native64 oil startup proof")


def validate_snapshot(snapshot, definition, *, initial_air=False):
    require_document(snapshot, definition)
    require((snapshot["minecraft"], snapshot["forge"], snapshot["station"]) == (native.MINECRAFT, native.FORGE, "lifecycle")
            and type(snapshot["worldTime"]) is int and snapshot["worldTime"] >= 0,
            "Historical oil snapshot is not target evidence")
    for lane in definition:
        for row in snapshot["cases"][lane["id"]]:
            require(row["loaded"] is True, "Oil witness is not actually loaded")
            if initial_air:
                require(set(row) == {"pos", "loaded", "block", "state"}
                        and row["block"] == "minecraft:air" and row["state"] == "Block{minecraft:air}",
                        "Initial oil witness is not untouched loaded air")
        if initial_air:
            continue
        for role in ("drain", "output"):
            data = lifecycle.parse(lifecycle.at(snapshot, lane["id"], lane[role]))
            tank = data["Tank"]
            require(isinstance(tank, dict), "Missing native tank data")
            if set(tank) == {"Empty"}:
                require(type(tank["Empty"]) is layout.nbtlib.Byte and int(tank["Empty"]) == 1,
                        "Unknown native empty-tank encoding")
            else:
                require(set(tank) == {"FluidName", "Amount"} and tank["FluidName"] == lane["fluid"]
                        and type(tank["Amount"]) is layout.nbtlib.Int and tank["Amount"] >= 0,
                        "Unknown/ambiguous native oil tank data")
    return snapshot


class OilRun(NativeOilRun):
    expected_launcher = native.LAUNCHER
    max_ticket_distance = 11
    require_boot = staticmethod(native_boot)

    def __init__(self, *args):
        super().__init__(*args)
        self.pids = []

    def open_server(self, label):
        native.audit_staged(self.runtime)
        server = native.Server(self.runtime, self.java, self.launcher, label)
        self.pids.append(server.process.pid)
        require(len(self.pids) == len(set(self.pids)), "Oil restart reused a process")
        return server

    def probe(self, operation, label):
        initial_air = (operation, label) == ("load", "oil-loaded") and self.boots == ["oil-first-fill"] \
            and not self.construction_sealed and self.pending_construction == construction_commands(self.definition)
        return validate_snapshot(super().probe(operation, label), self.definition, initial_air=initial_air)


def validate_in_world_render(document, root, profile_sha256):
    """Requires future real-world producer data; the existing worldless probe cannot satisfy it."""
    require((document["protocol"], document["minecraft"], document["forge"])
            == ("NATIVE64_IN_WORLD_OIL_ATLAS_V1", native.MINECRAFT, native.FORGE), "Wrong in-world oil protocol")
    require(document["profileSha256"] == profile_sha256 and document["worldLoaded"] is True
            and document["dimension"] == "minecraft:overworld" and document["syntheticWorld"] is False,
            "Oil rendering must use the actual pinned loaded world")
    level = layout._load(artifact(root, document["stoppedLevel"]))["Data"]
    require(type(level["DataVersion"]) is layout.nbtlib.Int and int(level["DataVersion"]) == layout.DATA_VERSION
            and str(level["Version"]["Name"]) == native.MINECRAFT, "In-world oil used a historical or fake stopped level")
    phases = document["phases"]
    require(len(phases) == 2 and [row["phase"] for row in phases] == ["initial", "after-resource-reload"],
            "Actual atlas reload is required")
    initial = {}
    for index, phase in enumerate(phases):
        require(type(phase["atlasGeneration"]) is int and phase["atlasGeneration"] >= 0,
                "Missing atlas generation")
        if index:
            require(phase["reloadCompleted"] is True and phase["atlasGeneration"] > phases[0]["atlasGeneration"],
                    "Atlas was not actually reloaded")
        cases = phase["cases"]
        require(len(cases) == 4 and {(row["oil"], row["flowing"]) for row in cases}
                == {(oil, flowing) for oil in OILS for flowing in (True, False)}, "Incomplete in-world oil cases")
        for row in cases:
            key = row["oil"], row["flowing"]
            require(type(row["flowing"]) is bool and row["worldBlock"] == "basepower:" + row["oil"]
                    and row["modelLayer"] == "TRANSLUCENT" and row["missingModel"] is False,
                    "Wrong actual oil block/model/layer")
            expected = "basepower:blocks/" + row["oil"] + ("_flow" if row["flowing"] else "_still")
            require(row["modelSprite"] == row["atlasSprite"] == expected and row["modelLookup"] == "IClientFluidTypeExtensions.getModel",
                    "World rendering did not select the current native oil model")
            require(all(type(row[field]) is int and row[field] != 0 for field in ("modelSpriteIdentity", "atlasSpriteIdentity", "modelIdentity"))
                    and row["modelSpriteIdentity"] == row["atlasSpriteIdentity"],
                    "Missing real model/sprite identity")
            require(isinstance(row["pos"], list) and len(row["pos"]) == 3 and all(type(value) is int for value in row["pos"])
                    and type(row["worldTime"]) is int and row["worldTime"] >= 0, "Missing actual-world position/time")
            require(type(row["vertices"]) is int and row["vertices"] >= 4, "Blank in-world fluid geometry")
            pixel_pair(root, row["pixels"])
            if index:
                old = initial[key]
                require(row["modelSpriteIdentity"] != old["modelSpriteIdentity"] and row["modelIdentity"] != old["modelIdentity"],
                        "Oil model retained a stale pre-reload atlas sprite")
            else:
                initial[key] = row
    controls = document["negativeControls"]
    require(set(controls) == {"missing-model", "wrong-sprite", "stale-atlas"}, "Missing actual-world oil negative controls")
    for row in controls.values():
        pixel_pair(root, row, matches=False)
    return dict(status="PASS", scope="ACTUAL_WORLD_OIL_AND_ATLAS_RELOAD", cases=8, negativeControls=3)


def run(args):
    require(type(args.seconds) is int and 2 <= args.seconds <= 120, "Bound oil observation intervals")
    inputs = preflight(args, [Path(__file__), Path(lifecycle.__file__), Path(exercise.__code__.co_filename)])
    native.require_free_port(args.port)
    if args.preflight_only:
        return dict(status="READY_NOT_RUN", qualificationAllowed=False, **inputs.receipt())
    runtime = output_path(args.output)
    expected = native.stage(runtime, *inputs.target, args.port)
    shutil.copy2(Path(__file__), runtime / "native2612-oil-source.py")
    pins = {"jars": inputs.receipt()["target_jars"]}
    definition = fixtures(pins)
    require(len(definition) == 4, "Only the four approved Power oil lanes are pinned")
    write_json(runtime / "lifecycle-fixture.json", definition)
    authorize_lifecycle(runtime)
    report = dict(status="RUNNING", scope="BUCKET_PIPE_TRANSPORT_NOT_WORLD_RENDERING", **inputs.receipt(),
                  inWorldRendering="NOT_RUN_SEPARATE_CLIENT_WITNESS_REQUIRED")
    runner = OilRun(runtime, inputs.java, native.LAUNCHER, definition, pins, inputs.seed_before)

    def progress(value):
        report.update(value)
        write_json(runtime / RESULT, report)

    try:
        exercise(runner, args.seconds, progress)
        require(len(runner.pids) == 3, "Need three distinct native oil processes")
        report.update(status="PASS", processIds=runner.pids)
    except BaseException as failure:
        report.update(status="FAIL", error=repr(failure))
        raise
    finally:
        try:
            runner.close()
            native.verify_runtime(runtime, inputs.target[1], expected)
            report["sourceUnchanged"] = inputs.unchanged()
        except BaseException as failure:
            report.update(status="FAIL", custodyError=repr(failure))
            raise
        finally:
            write_json(runtime / RESULT, report)
    return report


def main():
    parser = arguments(__doc__)
    parser.add_argument("--seconds", type=int, default=8)
    print(run(parser.parse_args())["status"], flush=True)


if __name__ == "__main__":
    main()
