"""Bounded F-candidate provider, cold-restart and positive worldgen witnesses.

Retains WorksRunner's profile-assertion, completion-before-save, positive count,
zero biome-violation and solid-count restart oracles. Configuration evidence is
reported separately from physical generation; a zero sample never proves it.
"""

import argparse
import copy
import json
from pathlib import Path
import shutil
import socket
import time
import traceback

import native1211neo as native
from matrix import WORLD, digest, fingerprint, write_json
from native117 import output_path, require_disjoint

OVERWORLD = "minecraft:overworld"
OIL = "basepower:fluid_deposit/desert_crude_oil"
LI, SULFUR = "baseelectric:ore/lithium", "baseelectric:ore/sulfur"
MINERAL_SULFUR = "mineralogy:ore/mineralogy/sulfur_ore"
FROZEN = {
    "basepower": "E9ED662E15C3510E2A48CE85448D3D39C6EF2667208F29EB281791200507F77E",
    "advantageworkstest": "807877B8B2B31BB5BA922E1007ADFE16B954588327BFD6AFD9D2AF959CB0CD1D",
    "basemetals": "B4BB1CBCD54DFCF2EC8465ACE82715EDCCC5BF1E5C15E14C627AE7CC58E8FED1",
    "mmdlib": "EE417C82D588B0050A49709B6430CF836315E71E4DB2DF4036D3735454BC5311",
    "orespawn": "BF02244E25B5FBF89CCAD45F41B2624B850D4BBCB70CF66822249BA233A00976",
    "basesteam": "AD4BACD5158252F4AE257DF694098E47E7C309B21BFD9F583AF6A18E569FABB9",
    "baseelectric": "61457EDF8B1C097AE17805DCC26CB7E0F4F661208B777A0B53C56BB383EEDA85",
    "mineralogy": "E7A29C978CE751735F0842B89D4ADFC3E5B4A34188C3F41B5B2AE24A94BDCFFD",
}


def require_frozen(mods, mineralogy):
    required = set(FROZEN) - (set() if mineralogy else {"mineralogy"})
    if set(mods) != required or any(mods[key][2].upper() != FROZEN[key] for key in required):
        raise AssertionError("Only the exact sealed release family is permitted")


def enabled(row):
    return row.get("enabled") is True and row.get("dimensions", {}).get(OVERWORLD, {}).get("enabled") is True


def require_profile(data, scenario):
    ores, fluids = data.get("ores", {}), data.get("fluid_deposits", {})
    if scenario == "legacy":
        expected = {"basepower:legacy/basepower_crude_oil_0": "basepower:crude_oil",
                    "baseelectric:legacy/baseelectric_li_ore_0": "baseelectric:li_ore",
                    "baseelectric:legacy/baseelectric_sulfur_ore_1": "baseelectric:sulfur_ore"}
        for identity, block in expected.items():
            row = ores.get(identity, {})
            if row.get("block") != block or not enabled(row):
                raise AssertionError("Missing active legacy provider rule: " + identity)
            candidates = [name for section in (ores, fluids) for name, rule in section.items()
                          if rule.get("block") == block and enabled(rule)]
            if candidates != [identity]:
                raise AssertionError("Duplicate active legacy/modern candidates: " + str(candidates))
        if OIL in fluids or LI in ores or SULFUR in ores:
            raise AssertionError("Legacy authority must not acquire modern duplicate rules")
        return
    mineralogy = scenario == "mineralogy"
    for identity, block, state in ((LI, "baseelectric:li_ore", scenario != "override"),
                                    (SULFUR, "baseelectric:sulfur_ore", not mineralogy)):
        row = ores.get(identity, {})
        if row.get("block") != block or type(row.get("enabled")) is not bool or row["enabled"] != state:
            raise AssertionError("Incorrect visible Electric provider default: " + identity)
        dimension = row.get("dimensions", {}).get(OVERWORLD, {})
        if dimension.get("enabled") is not True or (dimension.get("min_y"), dimension.get("max_y")) != (1, 31):
            raise AssertionError("Electric generation range changed: " + identity)
        if dimension.get("frequency") != (0.125 if identity == LI else 1.0):
            raise AssertionError("Electric generation frequency changed: " + identity)
    oil = fluids.get(OIL, {})
    if oil.get("block") != "basepower:crude_oil" or oil.get("enabled") is not (scenario != "override"):
        raise AssertionError("Missing or overwritten Power desert-oil decision")
    dimension = oil.get("dimensions", {}).get(OVERWORLD, {})
    if (dimension.get("min_y"), dimension.get("max_y"), dimension.get("frequency")) != (0, 48, 0.08):
        raise AssertionError("Power oil rate or placement height changed")
    if dimension.get("biome_dictionary") != ["SANDY"] or set(dimension.get("excluded_biome_dictionary", [])) != {"BEACH", "MESA"}:
        raise AssertionError("Power oil biome restriction changed")
    if mineralogy and not enabled(ores.get(MINERAL_SULFUR, {})):
        raise AssertionError("Mineralogy must own active sulfur with the Electric alternative visibly disabled")


def require_sample(document, radius, mineralogy=False):
    ids = {"basepower:crude_oil", "baseelectric:li_ore", "baseelectric:sulfur_ore", "mineralogy:sulfur_ore", "mineralogy:crude_oil"}
    if (document.get("format"), document.get("minecraft"), document.get("loader"), document.get("neoforge"),
            document.get("kind")) != (1, native.MINECRAFT, "neoforge", native.NEOFORGE, "sample"):
        raise AssertionError("Sampling evidence must identify the native 1.21.1 loader")
    if document.get("station") != "worldgen" or document.get("radius") != radius:
        raise AssertionError("Wrong or incomplete physical sampling report")
    counts = document.get("counts", {})
    if set(counts) != ids or any(type(count) is not int or count < 0 for count in counts.values()):
        raise AssertionError("Missing or non-integral worldgen quantities")
    required = {"basepower:crude_oil", "baseelectric:li_ore",
                "mineralogy:sulfur_ore" if mineralogy else "baseelectric:sulfur_ore"}
    if any(counts[key] < 1 for key in required):
        raise AssertionError("Zero-count sample is not generation proof: " + str(counts))
    if mineralogy and counts["baseelectric:sulfur_ore"] != 0:
        raise AssertionError("Disabled Electric sulfur generated beside the Mineralogy owner")
    centers = document.get("centers", {})
    for biome in ("DESERT", "OCEAN"):
        pair = centers.get(biome, "NOT_FOUND").split(",")
        if len(pair) != 2 or any(not part.lstrip("-").isdigit() for part in pair):
            raise AssertionError("Both desert and counter-biome samples are required: " + biome)
    if centers["DESERT"] == centers["OCEAN"]:
        raise AssertionError("Desert and ocean must be independent biome observations")
    if type(document.get("chunksScanned")) is not int or document["chunksScanned"] < (2 * radius + 1) ** 2:
        raise AssertionError("Incomplete bounded chunk count")
    violations = document.get("biomeViolations", {})
    if set(violations) != {"basepower:crude_oil", "mineralogy:crude_oil"} or any(type(value) is not int or value != 0 for value in violations.values()):
        raise AssertionError("Missing or nonzero oil biome violations")
    if not isinstance(document.get("biomeBoundaryBlocks"), dict) or not isinstance(document.get("chunkLoading"), dict):
        raise AssertionError("Missing native sampling diagnostics")
    loading = document["chunkLoading"]
    if (loading.get("protocol") != "native_FULL_off_thread_future_poll_v1"
            or type(loading.get("requests")) is not int or loading["requests"] <= 0
            or loading.get("releasedTickets") != loading["requests"] or loading.get("activeTickets") != 0
            or loading.get("dispatcherClosed") is not True or loading.get("executorShutdown") is not True):
        raise AssertionError("Native FULL-chunk sampling must release every ticket and close its dispatcher")


def require_restart(first, second):
    if first != second:
        raise AssertionError("Provider decisions or quantities changed across cold restart")


def require_provider_restart(first, second):
    expected = copy.deepcopy(first)
    # OreSpawn rebuilds this diagnostic from the two authored EA rules at boot.
    # A user's saved enabled edit intentionally leaves the old diagnostic stale.
    # Recompute only these two known fields; compare every other field exactly.
    for policy in expected.get("ore_source_policies", {}).values():
        for candidate in policy.get("candidates", []):
            identity = candidate.get("source_id")
            if identity in (LI, SULFUR) and candidate.get("external") is False:
                candidate["placement_active"] = enabled(expected["ores"][identity])
    require_restart(expected, second)


def settings(runtime, world_seed, normal):
    path = runtime / "server.properties"
    overrides = {"level-seed": str(world_seed), "view-distance": "3", "simulation-distance": "3",
                 "generate-structures": "false", "max-tick-time": "120000"}
    if normal:
        overrides.update({"level-type": "minecraft:normal", "generator-settings": "{}"})
    lines = [line for line in path.read_text().splitlines() if line.partition("=")[0] not in overrides]
    path.write_text("\n".join(lines + [key + "=" + value for key, value in overrides.items()]) + "\n", encoding="utf-8")


def sample(server, runtime, phase, radius, timeout):
    destination = runtime / "advantage-works-results/worldgen-sample.json"
    failure = runtime / "advantage-works-results/worldgen-sample-failed.json"
    if destination.exists() or failure.exists():
        raise FileExistsError("Refusing to overwrite sampling evidence")
    server.send("advworks sample-worldgen " + str(radius))
    server.wait("Worldgen sample wrote ", timeout)
    if failure.exists():
        raise AssertionError("Native worldgen failed: " + failure.read_text())
    document = native.read_json(destination)
    destination.rename(destination.with_name("worldgen-sample-" + phase + ".json"))
    return document


class BoundedServer(native.Server):
    def __init__(self, deadline, *args):
        self.deadline = deadline
        super().__init__(*args)

    def wait(self, token, timeout=60):
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("Bounded worldgen campaign budget exhausted")
        return super().wait(token, min(timeout, remaining, 120 if token == "Done (" else timeout))


def run_case(args, scenario):
    profile = args.mineralogy_profile if scenario == "mineralogy" else args.family_profile
    target = native.read_family_target(profile, args.seed, args.inventory, args.inventory_sha256)
    require_frozen(target[2], scenario == "mineralogy")
    java, version = native.java21(args.java)
    runtime = args.output / scenario
    for protected in (profile, args.seed, args.inventory, java, *(entry[1] for entry in target[2].values())):
        require_disjoint(runtime, protected)
    protected_seed = fingerprint(args.seed)
    expected = native.stage(runtime, *target, args.port)
    generate = args.sample and scenario in ("family", "mineralogy")
    settings(runtime, args.world_seed, generate)
    legacy = {}
    if scenario == "legacy":
        for owner, source in (("poweradvantage", native.RIG.parent / "src/test/resources/orespawn-legacy/poweradvantage.json"),
                               ("electricadvantage", native.RIG.parent.parent / "ElectricAdvantage/ElectricAdvantage/src/test/resources/orespawn-legacy/electricadvantage.json")):
            destination = runtime / "config/orespawn" / (owner + ".json")
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)
            legacy[str(destination.relative_to(runtime))] = digest(source)
    report = dict(status="RUNNING", scenario=scenario, qualificationStatus=native.EXPLORATORY,
                  minecraft=native.MINECRAFT, neoforge=native.NEOFORGE, javaVersion=version,
                  profileSha256=digest(profile), inventorySha256=digest(args.inventory), jars=expected,
                  worldSeed=args.world_seed, normalWorld=generate, generationProof="NOT_RUN", phases={})
    receipt = runtime / "native1211neo-worldgen-results.json"
    write_json(receipt, report)
    server = None
    try:
        first = None
        for phase in ("fresh", "cold-restart"):
            native.verify_runtime(runtime, target[1], expected)
            server = BoundedServer(args.deadline, runtime, java, native.LAUNCHER, phase)
            document = None
            if generate:
                document = sample(server, runtime, phase, args.radius, args.sample_timeout)
                # Retain the complete actual report even when its oracle fails.
                report["phases"][phase] = dict(sample=document)
                write_json(receipt, report)
                require_sample(document, args.radius, scenario == "mineralogy")
            server.send("save-all flush")
            server.wait("Saved the game", 30)
            server.close()
            server = None
            world_profile = runtime / WORLD / "serverconfig/orespawn-worldgen.json"
            data = native.read_json(world_profile)
            raw_copy = runtime / ("provider-" + phase + ".json")
            shutil.copy2(world_profile, raw_copy)
            # The first override boot establishes fresh defaults; the user then
            # edits only the authoritative saved-world decision before restart.
            require_profile(data, "family" if scenario == "override" and phase == "fresh" else scenario)
            for name, checksum in legacy.items():
                native.validate_hash(runtime / name, checksum)
            report["phases"].setdefault(phase, {}).update(profileSha256=digest(raw_copy), profile=data,
                                                         configurationStatus="PASS")
            if first is not None:
                require_provider_restart(first, data)
                if generate:
                    require_restart(report["phases"]["fresh"]["sample"]["counts"], document["counts"])
            first = copy.deepcopy(data)
            if scenario == "override" and phase == "fresh":
                first["ores"][LI]["enabled"] = False
                first["fluid_deposits"][OIL]["enabled"] = False
                write_json(world_profile, first)
                write_json(runtime / "user-override.json", first)
            write_json(receipt, report)
        if scenario == "legacy":
            migration = runtime / "config/orespawn-os3-migration-report.json"
            content = migration.read_text(encoding="utf-8")
            if content.count("os1_config_source") < 2:
                raise AssertionError("Missing independent legacy import reports")
            report["legacyMigrationReportSha256"] = digest(migration)
        report.update(status="PASS", generationProof="PASS" if generate else "NOT_RUN")
    except BaseException as error:
        report.update(status="FAIL", failure=repr(error), traceback=traceback.format_exc())
        raise
    finally:
        try:
            if server is not None:
                server.close()
            native.verify_runtime(runtime, target[1], expected)
            report["seedUnchanged"] = fingerprint(args.seed) == protected_seed
            report["profileUnchanged"] = digest(profile) == report["profileSha256"]
            native.validate_hash(args.inventory, args.inventory_sha256)
            if not report["seedUnchanged"] or not report["profileUnchanged"]:
                raise AssertionError("Protected inputs changed")
        except BaseException as error:
            report.update(status="FAIL", custodyError=repr(error))
            raise
        finally:
            write_json(receipt, report)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("seed", "inventory", "java", "output"):
        parser.add_argument("--" + name, required=True, type=lambda value: Path(value).resolve())
    parser.add_argument("--inventory-sha256", required=True)
    parser.add_argument("--family-profile", type=Path, default=native.RIG / "build/neo1211-family-f.json")
    parser.add_argument("--mineralogy-profile", type=Path, default=native.RIG / "build/neo1211-mineralogy-f.json")
    parser.add_argument("--scenario", choices=("all", "providers", "family", "mineralogy", "override", "legacy"), default="all")
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--sample", action="store_true")
    parser.add_argument("--radius", type=int, default=4)
    parser.add_argument("--sample-timeout", type=int, default=180)
    parser.add_argument("--world-seed", type=int, default=12345)
    parser.add_argument("--budget-seconds", type=int, default=480)
    args = parser.parse_args()
    if (not 26540 <= args.port <= 65535 or not 1 <= args.radius <= 4
            or not 30 <= args.sample_timeout <= 240 or not 60 <= args.budget_seconds <= 600):
        parser.error("Use a disposable port >=26540, radius 1..4 and bounded 30..240-second sample")
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", args.port))
    args.output = output_path(args.output)
    args.deadline = time.monotonic() + args.budget_seconds
    scenarios = (("family", "mineralogy", "override", "legacy") if args.scenario == "all" else
                 ("family", "mineralogy", "override") if args.scenario == "providers" else (args.scenario,))
    summary = dict(status="RUNNING", cases={}, sourceSha256=digest(Path(__file__)))
    receipt = args.output / "worldgen-campaign.json"
    write_json(receipt, summary)
    for scenario in scenarios:
        if time.monotonic() >= args.deadline:
            summary["cases"][scenario] = dict(status="NOT_RUN_BUDGET", generationProof="NOT_RUN")
            continue
        try:
            summary["cases"][scenario] = run_case(args, scenario)
        except Exception as error:
            summary["cases"][scenario] = dict(status="FAIL", failure=repr(error), traceback=traceback.format_exc())
        write_json(receipt, summary)
    summary["status"] = "PASS" if all(row["status"] == "PASS" for row in summary["cases"].values()) else "FAIL"
    write_json(receipt, summary)
    return int(summary["status"] != "PASS")


if __name__ == "__main__":
    raise SystemExit(main())
