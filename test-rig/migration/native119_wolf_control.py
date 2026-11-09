"""Measure native tame-wolf loading on a fresh Forge world with no mod JARs."""

import argparse
from pathlib import Path
import socket

import nbtlib
from matrix import WORLD, digest, plain, write_json
from native117 import output_path, require_disjoint
import native119


CASES = (("tame8", 8, True, 20), ("tame20", 20, True, 20), ("wild8", 8, False, 8))
OWNER = (2026, -417, 118, 41)
PREFIX = "has the following entity data: "


def observe(server, runtime, phase, case, identity):
    name, starting_health, tame, expected_health = case
    selector = f"@e[type=minecraft:wolf,tag=wolf_control_{name},limit=1]"
    server.send(f"data get entity {selector}")
    server.wait(PREFIX, 30)
    log = runtime / f"console-{phase}.log"
    raw = next(line.partition(PREFIX)[2] for line in reversed(log.read_text(encoding="utf-8").splitlines())
               if PREFIX in line)
    saved = plain(nbtlib.parse_nbt(raw).unpack())
    if (saved.get("Health") != expected_health or saved.get("UUID") != list(identity)
            or saved.get("Owner") != (list(OWNER) if tame else None)
            or f"wolf_control_{name}" not in saved.get("Tags", ())):
        raise AssertionError(f"Unexpected native wolf load: {name} / {phase}")
    return dict(inputHealth=starting_health, health=saved["Health"], uuid=saved["UUID"],
                owner=saved.get("Owner"), observedNBT=raw)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("profile", "seed", "inventory", "java17", "output"):
        parser.add_argument("--" + name, required=True, type=Path)
    parser.add_argument("--inventory-sha256", required=True)
    parser.add_argument("--port", required=True, type=int)
    args = parser.parse_args()
    output = output_path(args.output)
    for path in (args.profile, args.seed, args.inventory):
        require_disjoint(output, path)
    seed, inventory, _ = native119.read_target(args.profile, args.seed, args.inventory, args.inventory_sha256)
    with socket.socket() as port:
        port.bind(("127.0.0.1", args.port))
    launcher = native119.stage(output, (seed, inventory, {}), args.port, normal=True)
    report = dict(status="RUNNING", scope="FRESH_FORGE_45_NO_MOD_JARS", nativeDataVersion=3337,
                  inventorySha256=digest(args.inventory), boots={})
    try:
        for phase in ("summon", "restarted"):
            server = native119.Server(output, args.java17, launcher, phase)
            try:
                server.send("forceload add 0 0")
                server.send("say WOLF-CONTROL-CHUNK-TICKET")
                server.wait("WOLF-CONTROL-CHUNK-TICKET", 30)
                observed = {}
                for index, case in enumerate(CASES):
                    name, health, tame, _ = case
                    identity = (2026, 11, 9, 7100 + index)
                    if phase == "summon":
                        owner = ",Owner:[I;" + ",".join(map(str, OWNER)) + "]" if tame else ""
                        uuid = ",".join(map(str, identity))
                        tags = (f'{{UUID:[I;{uuid}]{owner},Health:{health}.0f,NoAI:1b,NoGravity:1b,'
                                f'Invulnerable:1b,Tags:["wolf_control_{name}"],Attributes:'
                                f'[{{Name:"minecraft:generic.max_health",Base:{20 if tame else 8}.0d}}]}}')
                        server.send(f"summon minecraft:wolf {2 + index * 4} 200 4 {tags}")
                        server.wait("Summoned new", 30)
                    observed[name] = observe(server, output, phase, case, identity)
                server.send("save-all flush")
                server.wait("Saved the game", 60)
            finally:
                server.close()
            report["boots"][phase] = observed
        if int(nbtlib.load(output / WORLD / "level.dat")["Data"]["DataVersion"]) != 3337:
            raise AssertionError("The control world is not native 1.19.4")
        report["status"] = "PASS"
    except BaseException as error:
        report.update(status="FAIL", error=repr(error))
        raise
    finally:
        write_json(output / "native119-wolf-control.json", report)
    print("PASS: Forge 45 without any mods reproduced the native tame-wolf heal")


if __name__ == "__main__":
    main()
