"""Forge 37 geometry contract; live agreement is required before qualification."""

import hashlib
from pathlib import Path
import zipfile

FORCED_LEVEL = 31
FULL_LEVEL = 33
MAX_DISTANCE = 11
LAST_HOLDER_LEVEL = FULL_LEVEL + MAX_DISTANCE
HOLDER_RADIUS = LAST_HOLDER_LEVEL - FORCED_LEVEL
MINECRAFT_VERSION = "1.17.1"
FORGE_VERSION = "37.1.1"
MCP_VERSION = "20210706.113038"
SOURCE_ARCHIVE_SHA256 = "ACB95ACEBB65CE2EE034E887F324F6CC18C7C628D0CF97358F4D2F64572AAA0B"
SOURCE_SHA256 = "5BB6FDF6E625CB86C5A7975F686DB89306729C0517D39350BFD88BD77C5EBBD4"
ENGINE_SOURCES = {
    "net/minecraft/server/level/DistanceManager.java": SOURCE_SHA256,
    "net/minecraft/server/level/TicketType.java": "E75C0250F0B78C5177E01FFCB19CBDB3B49F7F8B8842509DDA6AAB315C7017BF",
    "net/minecraft/server/level/ChunkHolder.java": "C86B3183F14E85EA9DCD1A07D261A55A704B42C60D6AB3C8135928277E8892AB",
    "net/minecraft/server/level/ChunkMap.java": "3795347D92F9DE3379218A2D08017ED6E72903FA55827A02CFC7E9A6E064C89F",
    "net/minecraft/world/level/chunk/ChunkStatus.java": "E0316DF8268A3EA478E8162BCFF02511A6DAF81B373CA7C9CBCAC1B4F65586FD",
    "net/minecraft/server/level/ServerChunkCache.java": "AC48BB72690AD00BF1ACEE880C000C2AE5C4DE12AE68F36B0BDF5844A942607E",
}

# Raw Forge-patched injected-sources.jar entries, not re-labelled 1.16 sources.
# DistanceManager.m_140799_ (updateChunkForced) constructs TicketType.f_9445_
# (FORCED) at 31. ChunkHolder.m_140074_ derives FULL status from level 33;
# m_140083_ indexes FullChunkStatus with clamp(33 - level + 1), so 31 ticks entities.
# ChunkStatus.m_62421_ (maxDistance) returns the size of its 11-entry f_62330_ list.
# ChunkMap.f_140127_ is 33 + that distance and creates holders through that level:
# 44 is the last holder, and 44 - 31 = 13 is the forced-ticket generation halo.
def verify_engine_sources(archive):
    """Explicit local source-proof audit; live snapshots still require their own guard."""
    archive = Path(archive)
    if hashlib.sha256(archive.read_bytes()).hexdigest().upper() != SOURCE_ARCHIVE_SHA256:
        raise AssertionError("Native117 engine source archive differs from the verified Forge 37 bytes")
    with zipfile.ZipFile(archive) as sources:
        for name, expected in ENGINE_SOURCES.items():
            if hashlib.sha256(sources.read(name)).hexdigest().upper() != expected:
                raise AssertionError("Native117 engine source entry differs: " + name)
    return dict(minecraft=MINECRAFT_VERSION, forge=FORGE_VERSION, mcp=MCP_VERSION,
                archivePath=str(archive.resolve()), archiveSha256=SOURCE_ARCHIVE_SHA256,
                sources=dict(ENGINE_SOURCES))


def require_geometry(snapshot, forced=False, *, max_distance=MAX_DISTANCE):
    geometry = snapshot.get("ticketGeometry", {})
    expected = dict(fullLevel=FULL_LEVEL, entityTickingLevel=FORCED_LEVEL,
                    maxDistance=max_distance, lastHolderLevel=FULL_LEVEL + max_distance,
                    holderRadius=FULL_LEVEL + max_distance - FORCED_LEVEL)
    if any(geometry.get(key) != value for key, value in expected.items()):
        raise AssertionError("Live ticket geometry differs from the verified engine: " + str(geometry))
    levels = geometry.get("observedForcedLevels")
    if levels not in ([], [FORCED_LEVEL]) or forced and levels != [FORCED_LEVEL]:
        raise AssertionError("Actual native forced-ticket level was not verified: " + str(levels))
    return geometry
