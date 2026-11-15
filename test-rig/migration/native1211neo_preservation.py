"""Exact, reversible native test adaptations of the accepted Forge 1.21.1 input."""

from historical_test_inputs import ACCEPTED_FORGE1211, ACTIVE_ADAPTERS, JAVA_TESTS, git_blob


TEST_PROJECT = b"zone.moddev.mc.advantageworks.unit.TestProject.path"

# Only these reviewed expressions differ. Assertions, thresholds, profile pins
# and negative controls outside these expressions must match the accepted tree.
REVIEWED_TRANSLATIONS = {
    JAVA_TESTS + "ProfileManifestTest.java": (
        (b'new File("profiles")', TEST_PROJECT + b'("profiles").toFile()'),
    ),
    JAVA_TESTS + "WorldgenProtocolContractTest.java": (
        (b'        assertTrue("Sampling must yield to native server ticks", source.contains("TickEvent.ServerTickEvent"));\n'
         b'        assertTrue(source.contains("TickEvent.Phase.END"));',
         b'        assertTrue("Sampling must yield to native server ticks", source.contains("ServerTickEvent.Post"));'),
        (b"Paths.get(path)", TEST_PROJECT + b"(path)"),
    ),
    JAVA_TESTS + "runner/Native114ProfileTest.java": (
        (b'new File("profiles/native114")', TEST_PROJECT + b'("profiles/native114").toFile()'),
        (b'new File(".")', TEST_PROJECT + b'(".").toFile()'),
        (b'new File("profiles/packaged-current.json")', TEST_PROJECT + b'("profiles/packaged-current.json").toFile()'),
    ),
    JAVA_TESTS + "runner/Native115ProfileTest.java": (
        (b'new File("profiles/native115")', TEST_PROJECT + b'("profiles/native115").toFile()'),
        (b'new File(".")', TEST_PROJECT + b'(".").toFile()'),
        (b'new File("profiles/native114/packaged-current.json")',
         TEST_PROJECT + b'("profiles/native114/packaged-current.json").toFile()'),
        (b'new File("profiles/packaged-current.json")', TEST_PROJECT + b'("profiles/packaged-current.json").toFile()'),
    ),
}


def normalized(data):
    return data.replace(b"\r\n", b"\n")


def translate_adapter(relative, data, *, reverse=False):
    if relative not in ACTIVE_ADAPTERS:
        raise ValueError("Not a reviewed active adapter: " + relative)
    result = normalized(data)
    pairs = REVIEWED_TRANSLATIONS.get(relative, ())
    for historical, native in reversed(pairs) if reverse else pairs:
        before, after = (native, historical) if reverse else (historical, native)
        if result.count(before) != 1 or after in result:
            raise AssertionError("Missing, duplicate or mixed adapter translation in " + relative)
        result = result.replace(before, after, 1)
    return result


def require_active_adapter(relative, data):
    accepted = normalized(git_blob(ACCEPTED_FORGE1211, relative))
    expected = translate_adapter(relative, accepted)
    observed = normalized(data)
    if observed != expected or translate_adapter(relative, observed, reverse=True) != accepted:
        raise AssertionError("Unreviewed active adapter change against " + ACCEPTED_FORGE1211 + ": " + relative)


def require_native_readiness_tick(source, bridge):
    required_source = (
        "WorksLevelTick.listen(NeoForge.EVENT_BUS, probe::tick);",
        "private void tick(WorksLevelTick event)",
        "if (observation == null || event.phase != WorksLevelTick.Phase.END || event.level != world) return;",
    )
    required_bridge = (
        "import net.neoforged.neoforge.event.tick.LevelTickEvent;",
        "bus.addListener((LevelTickEvent.Pre event) -> consumer.accept(new WorksLevelTick(event.getLevel(), Phase.START)));",
        "bus.addListener((LevelTickEvent.Post event) -> consumer.accept(new WorksLevelTick(event.getLevel(), Phase.END)));",
    )
    if (any(source.count(value) != 1 for value in required_source)
            or any(bridge.count(value) != 1 for value in required_bridge)
            or bridge.count("bus.addListener(") != 2):
        raise AssertionError("Readiness must observe the requested world's native post-level tick")
