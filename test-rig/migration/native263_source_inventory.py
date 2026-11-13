"""Inventory every target adapter and its source contracts with the Native66 engine pin."""

import argparse
import ast
from pathlib import Path
import subprocess

import native263 as native
from matrix import digest, write_json

DRIVERS = ("native263", "native263_broad", "native263_machines", "native263_networks",
           "native263_operations", "native263_resources", "native263_oil", "native263_interop",
           "native263_worldgen", "native263_retained_worldgen", "native263_legacy_alternatives",
           "native263_strict_migration", "native263_upgrade", "native263_actors",
           "native263_actor_fixtures", "native263_stopped_actor_reference", "native263_actor_comparison",
           "native263_transformer_order", "native263_actor_transformer_order", "native263_client",
           "native263_endurance", "native263_endurance_replay", "native263_engine_proof", "native263_server_proof",
           "native263_source262")


def inventory():
    root = native.RIG
    files = set(root.glob("migration/native263*.py")) | set(root.glob("migration/test_native263*.py"))
    pending = list(files)
    while pending:
        path = pending.pop()
        for node in ast.walk(ast.parse(path.read_text(encoding="utf-8-sig"))):
            names = [alias.name for alias in node.names] if isinstance(node, ast.Import) else (
                [node.module] if isinstance(node, ast.ImportFrom) and node.module else [])
            for name in names:
                dependency = root / "migration" / (name.split(".")[0] + ".py")
                if dependency.is_file() and dependency not in files:
                    files.add(dependency)
                    pending.append(dependency)
    for path in ("src/main", "src/test", "src/runner", "unit-support", "unit-bootstrap", "platform-controls",
                 "client-resources", "profiles/native263", "order-probe"):
        files.update(file for file in (root / path).rglob("*") if file.is_file()
                     and file.suffix in (".java", ".json", ".properties", ".toml", ".mcmeta", ".ps1", ".py", ".md"))
    files.update(root / name for name in ("build.gradle", "gradle.properties", "NATIVE263.md"))
    fixtures = root / "migration/fixtures"
    files.update(path for path in fixtures.rglob("*") if path.is_file()
                 and path.relative_to(fixtures).parts[0].startswith("native263"))
    for name in DRIVERS:
        if root / "migration" / (name + ".py") not in files:
            raise AssertionError("Missing required Native66 driver: " + name)
    return dict(format=1, engineProtocol=native.ENGINE_PROTOCOL, minecraft=native.MINECRAFT, forge=native.FORGE,
                mcp=native.MCP, qualificationAllowed=False,
                sourceHead=subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
                driverContracts={name + ".py": "NATIVE66" for name in DRIVERS},
                targetFileProtocols={file.name: "NATIVE66" for file in sorted(root.glob("migration/native263*.py"))},
                files={file.relative_to(root).as_posix(): digest(file) for file in sorted(files)})


def verify(document):
    actual = inventory()
    if document != actual:
        raise AssertionError("Native66 source/driver inventory changed; retain the original and make a new pin")
    return actual


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("seal", "verify"))
    parser.add_argument("--inventory", type=Path, required=True)
    parser.add_argument("--sha256")
    args = parser.parse_args()
    if args.command == "seal":
        if args.inventory.exists():
            raise FileExistsError(args.inventory)
        write_json(args.inventory, inventory())
    else:
        native.validate_hash(args.inventory, args.sha256)
        verify(native.read_json(args.inventory))
