"""Native64 normal-world sampling with lossless separate WorldGenSettings custody."""

import copy
from pathlib import Path
import shutil

from matrix import digest, write_json
from native117 import output_path
import native12111_worldgen as previous
import native2612 as native
import native2612_layout as layout
from native2612_resources import FAMILY, arguments, bind, preflight, require


CAMPAIGNS = ("advantage", "legacy-import")
WORLD = previous.WORLD
RESULT = "native2612-worldgen-results.json"
validate_profile = previous.validate_profile
validate_initial_hierarchy = previous.validate_initial_hierarchy
mutated_profile = previous.mutated_profile
check_files = previous.check_files
validate_sample = bind(previous.validate_sample, native=native)
configure_normal_world = previous.configure_normal_world
_campaign_spec = bind(previous.campaign_spec, native=native)


def campaign_spec(name):
    require(name in CAMPAIGNS, "Worldgen route lacks an approved native64 provider pin: " + name)
    return _campaign_spec(name)


def select_mods(mods, spec):
    require(set(mods) == FAMILY and spec["requiredMods"] <= mods.keys(),
            "Worldgen needs all six exact frozen jars without an unpinned provider")
    return dict(mods)


def normal_world(world):
    level = layout.read_level(world)
    require(str(level["LevelName"]) == WORLD, "Unexpected native normal-world identity")
    require("WorldGenSettings" not in level, "Historical embedded settings are not native64 authority")
    saved = layout.read_worldgen(world)
    data = saved["data"]
    generator = data["dimensions"]["minecraft:overworld"]["generator"]
    require(str(generator["type"]) == "minecraft:noise" and str(generator["settings"]) == "minecraft:overworld",
            "Must sample actual normal terrain, not the flat machine fixture")
    require(type(data["seed"]) is layout.nbtlib.Long, "Missing native world seed")
    # Retain the complete native SavedData envelope, not a projected set of known settings.
    return copy.deepcopy(saved)


def validate_restart(first, restart, spec):
    previous.validate_restart(first, restart, spec)
    for field in ("counts", "biomeViolations", "biomeBoundaryBlocks"):
        require(first[field] == restart[field], "Native64 restart measurements changed; no historical exclusion is inherited")


class WorldgenServer(native.Server):
    @staticmethod
    def unexpected_line(line):
        return native.Server.unexpected_line(line) or any(marker in line for marker in (
            "Worldgen sample failed", "Advantage Works command failed"))


def run(args):
    require(type(args.sample_timeout) is int and 300 <= args.sample_timeout <= 3600, "Bound the sampling deadline")
    spec = campaign_spec(args.campaign)
    inputs = preflight(args, [Path(__file__), Path(previous.__file__), spec["template"],
                              *(source for source, _ in spec["configFixtures"])])
    mods = select_mods(inputs.mods, spec)
    native.require_free_port(args.port)
    if args.preflight_only:
        return dict(status="READY_NOT_RUN", qualificationAllowed=False, campaign=args.campaign, **inputs.receipt())
    runtime = output_path(args.output)
    expected = native.stage(runtime, *inputs.target, args.port)
    shutil.copy2(Path(__file__), runtime / "native2612-worldgen-source.py")
    configure_normal_world(runtime, spec, args.port)
    for source, destination in spec["configFixtures"]:
        path = runtime / destination
        path.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, path)
    report = dict(status="RUNNING", campaign=args.campaign, **inputs.receipt(), phases={})
    server, initial, saved_settings = None, None, None
    pids = []
    try:
        for phase in ("initial", "restart"):
            native.verify_runtime(runtime, inputs.target[1], expected)
            server = WorldgenServer(runtime, inputs.java, native.LAUNCHER, "worldgen-" + phase)
            pids.append(server.process.pid)
            require(len(pids) == len(set(pids)), "Worldgen restart reused a process")
            server.send("gamerule minecraft:spawn_mobs false")
            server.send("advworks sample-worldgen " + str(spec["worldgenSampleRadius"]))
            server.wait("Worldgen sample wrote ", args.sample_timeout)
            sample_file = runtime / "advantage-works-results/worldgen-sample.json"
            sample = native.read_json(sample_file)
            shutil.copy2(sample_file, runtime / f"worldgen-{phase}.json")
            validate_sample(sample, spec, mods)
            server.send("save-all flush")
            server.wait("Saved the game", 30)
            server.close()
            server = None
            world = runtime / WORLD
            settings = normal_world(world)
            raw = layout.shared_file(world, "minecraft:world_gen_settings")
            for source, name in ((world / "level.dat", f"level-{phase}.dat"),
                                 (raw, f"world-gen-settings-{phase}.dat")):
                shutil.copy2(source, runtime / name)
            profile_path = world / "serverconfig/orespawn-worldgen.json"
            profile = native.read_json(profile_path)
            write_json(runtime / f"orespawn-profile-{phase}.json", profile)
            prefix = "first" if phase == "initial" else "restart"
            assertions = validate_profile(profile, spec[prefix + "ProfileAssertions"])
            check_files(runtime, spec[prefix + "FileAssertions"])
            for source, destination in spec["configFixtures"]:
                require(digest(runtime / destination) == inputs.hashes[str(source)], "Legacy import rewrote its source")
            if initial is None:
                validate_initial_hierarchy(profile, mods, legacy=args.campaign == "legacy-import")
                initial, saved_settings = sample, settings
                if spec["profileMutations"]:
                    write_json(profile_path, mutated_profile(profile, spec["profileMutations"]))
            else:
                validate_restart(initial, sample, spec)
                require(settings == saved_settings, "Native worldgen SavedData changed across restart")
            report["phases"][phase] = dict(status="PASS", pid=pids[-1], assertions=assertions,
                                            sampleSha256=digest(runtime / f"worldgen-{phase}.json"),
                                            stoppedLevelSha256=digest(runtime / f"level-{phase}.dat"),
                                            stoppedSettingsSha256=digest(runtime / f"world-gen-settings-{phase}.dat"))
            write_json(runtime / RESULT, report)
        report["status"] = "PASS"
    except BaseException as failure:
        report.update(status="FAIL", error=repr(failure))
        raise
    finally:
        try:
            if server is not None:
                server.close()
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
    parser.add_argument("--campaign", choices=CAMPAIGNS, required=True)
    parser.add_argument("--sample-timeout", type=int, default=1800)
    print(run(parser.parse_args())["status"], flush=True)


if __name__ == "__main__":
    main()
