"""Read historical oracles from their pinned trees, not a newer port's checkout."""

from functools import lru_cache
from pathlib import Path
import subprocess


RIG = Path(__file__).resolve().parents[1]
POWER114 = "ef08e5cd134dc75bd73518bf4c90a6711e67280f"
POWER115 = "5b8e73a78391960643420a09798a1ef8eb06f518"
POWER116 = "c310d0ca63ad9a8408dd4bba75172afafa5c71b7"
ADVANTAGE117 = "bfd51b06942ed4f7f8b59576e7ef233e79a91e02"
BASE117_ADAPTERS = "7aef5b899a45aa23f33397081e762c46d770e39c"
STATIONS = "src/main/resources/assets/advantageworkstest/works/works-1.13.2.json"
JAVA = "src/main/java/zone/moddev/mc/advantageworks/"
JAVA_TESTS = "src/test/java/zone/moddev/mc/advantageworks/"

# These named files now serve the active port. All other ledger entries still
# check the worktree directly, so an unrelated edit cannot hide in the archive.
ACTIVE_ADAPTERS = frozenset({
    STATIONS, "client-resources/resources/gui-label-expectations.json",
    *("migration/" + name + ".py" for name in (
        "endurance", "extended_lifecycle", "lifecycle", "oil_transfer",
        "progression", "semantic_machine_audit",
    )),
    *(JAVA_TESTS + name + "Test.java" for name in (
        "BayDrillAssertions", "ConnectedSession", "LifecycleProbe",
        "Native114Definition", "Native115Definition", "NativeTicketFixture",
        "ProfileManifest",
        "WorksDefinition", "WorldgenProtocolContract", "WorldgenSamplingJob",
        "runner/Native114Profile", "runner/Native115Profile",
        "runner/WorldgenCompletion", "runner/WorldgenFailureCleanup",
    )),
})
RELOCATED_TESTS = frozenset(JAVA_TESTS + name + "Test.java" for name in (
    "WorldgenCompletion", "WorldgenFailureCleanup",
))
SPLIT_TESTS = frozenset("migration/test_" + name + ".py" for name in (
    "broad_result_audit", "steam_control_audit", "native114_broad",
    "native115_broad", "native116_broad", "native115_geometry",
    "native116_geometry", "native115_harvest", "native116_harvest",
    "native116_redstone_reload", "recipe_unlocks",
))


@lru_cache(maxsize=None)
def git_blob(commit, relative):
    return subprocess.check_output(
        ["git", "show", commit + ":test-rig/" + relative], cwd=RIG.parent
    )


def oracle_bytes(commit, relative):
    if relative in ACTIVE_ADAPTERS | RELOCATED_TESTS | SPLIT_TESTS or relative == "migration/steam_control_audit.py":
        return git_blob(commit, relative)
    return (RIG / relative).read_bytes()
