"""Exact source-bound Netty Java25 capability sequence, never an exception wildcard."""

import hashlib
import re
from zipfile import ZipFile


COMMON_JAR_SHA = "2345BC0ED5843FA57AA49EBA67529485C3A1D420FCF0429324C8220C7A80E9A6"
SOURCES_JAR_SHA = "0B5D0B0498D009DDCFAF2B48A84F23F9380F82DAFD8B0061406BAF895349080C"
SOURCE_SHA = {
    "io/netty/util/internal/PlatformDependent.java": "69E9D992C55F08AAC62CD80C398C0E665D5600BA3FD0F7BC54FE512AC4DD8C4B",
    "io/netty/util/internal/CleanerJava9.java": "4B15FA528C78E0020E107A6034B64F8A4C243690F9BAF1A60EF7370909D6E2AF",
    "io/netty/util/internal/CleanerJava24Linker.java": "29E2B3DD59468D41EA702AEE787110D691F8D46A93534F13FEBF809770AC6FAE",
    "io/netty/util/internal/CleanerJava25.java": "D02B9CA4BC2981AC9A34E246AB6B93CA0D63766BBDE742D7A0D9F7C7B359F202",
}
HEADER = re.compile(r"^\[[^\]]+\] \[([^\]]+)/([A-Z]+)\] \[([^\]]+)/\]: (.*)$")
LOGGERS = tuple("io.netty.util.internal." + name for name in ("CleanerJava9", "CleanerJava24Linker", "CleanerJava25"))
COMMON_TAIL = (
    "\tat SECURE-BOOTSTRAP/io.netty.common@4.2.7.Final/io.netty.util.internal.ClassInitializerUtil.tryLoadClasses(ClassInitializerUtil.java:32)",
    "\tat SECURE-BOOTSTRAP/io.netty.transport.classes.kqueue@4.2.7.Final/io.netty.channel.kqueue.Native.<clinit>(Native.java:65)",
    "\tat SECURE-BOOTSTRAP/io.netty.transport.classes.kqueue@4.2.7.Final/io.netty.channel.kqueue.KQueue.<clinit>(KQueue.java:38)",
    "\tat TRANSFORMER/minecraft@26.1.2/net.minecraft.server.network.EventLoopGroupHolder.remote(EventLoopGroupHolder.java:59)",
    "\tat TRANSFORMER/minecraft@26.1.2/net.minecraft.server.network.ServerConnectionListener.startTcpServerListener(ServerConnectionListener.java:57)",
    "\tat TRANSFORMER/minecraft@26.1.2/net.minecraft.server.dedicated.DedicatedServer.initServer(DedicatedServer.java:261)",
    "\tat TRANSFORMER/minecraft@26.1.2/net.minecraft.server.MinecraftServer.runServer(MinecraftServer.java:738)",
    "\tat TRANSFORMER/minecraft@26.1.2/net.minecraft.server.MinecraftServer.lambda$spin$0(MinecraftServer.java:300)",
    "\tat java.base/java.lang.Thread.run(Thread.java:1474)",
)
EXPECTED = (
    ("java.lang.UnsupportedOperationException: sun.misc.Unsafe unavailable",
     "\tat SECURE-BOOTSTRAP/io.netty.common@4.2.7.Final/io.netty.util.internal.CleanerJava9.<clinit>(CleanerJava9.java:70)",
     "\tat SECURE-BOOTSTRAP/io.netty.common@4.2.7.Final/io.netty.util.internal.PlatformDependent.<clinit>(PlatformDependent.java:198)", *COMMON_TAIL),
    ("java.lang.UnsupportedOperationException: Native access (restricted methods) is not enabled for the io.netty.common module.",
     "\tat SECURE-BOOTSTRAP/io.netty.common@4.2.7.Final/io.netty.util.internal.CleanerJava24Linker.<clinit>(CleanerJava24Linker.java:78)",
     "\tat SECURE-BOOTSTRAP/io.netty.common@4.2.7.Final/io.netty.util.internal.PlatformDependent.<clinit>(PlatformDependent.java:200)", *COMMON_TAIL),
    (),
)


def verify_sources(path):
    if hashlib.sha256(path.read_bytes()).hexdigest().upper() != SOURCES_JAR_SHA:
        raise AssertionError("Selected native Netty primary-source archive changed")
    with ZipFile(path) as jar:
        for name, expected in SOURCE_SHA.items():
            if hashlib.sha256(jar.read(name)).hexdigest().upper() != expected:
                raise AssertionError("Selected native cleaner source changed: " + name)
    return SOURCE_SHA.copy()


def classify(text, fallback):
    blocks = []
    for line in text.splitlines():
        if HEADER.fullmatch(line) or not blocks:
            blocks.append([line])
        else:
            blocks[-1].append(line)
    selected = [(index, HEADER.fullmatch(block[0])) for index, block in enumerate(blocks)
                if HEADER.fullmatch(block[0]) and HEADER.fullmatch(block[0])[3] in LOGGERS]
    if not selected:
        return fallback(text)
    valid = len(selected) == 3
    if valid:
        positions = [item[0] for item in selected]
        valid = positions == list(range(positions[0], positions[0] + 3))
        for slot, (index, header) in enumerate(selected):
            body = tuple(line for line in blocks[index][1:] if line.strip())
            valid = valid and (header[1], header[2], header[3], header[4], body) == (
                "Server thread", "DEBUG", LOGGERS[slot],
                "java.nio.ByteBuffer.cleaner(): " + ("available" if slot == 2 else "unavailable"), EXPECTED[slot])
    if not valid:
        failures, diagnostics = fallback(text)
        failures.append("Unproven native64 Java25 cleaner sequence: exact two DEBUG probes and subsequent native25 availability required")
        return failures, diagnostics
    accepted = {index for index, _ in selected[:2]}
    failures, diagnostics = [], []
    for index, block in enumerate(blocks):
        if index in accepted:
            diagnostics.append(dict(classification="SOURCE_PROVEN_NATIVE_NETTY_JAVA25_CLEANER_FALLBACK",
                                    nativeDependencySha256=COMMON_JAR_SHA, primarySourcesSha256=SOURCES_JAR_SHA,
                                    logger=HEADER.fullmatch(block[0])[3], level="DEBUG", rawBlock=block,
                                    requiredSubsequentFallback="io.netty.util.internal.CleanerJava25 available"))
        else:
            errors, other = fallback("\n".join(block))
            failures.extend(errors)
            diagnostics.extend(other)
    return failures, diagnostics
