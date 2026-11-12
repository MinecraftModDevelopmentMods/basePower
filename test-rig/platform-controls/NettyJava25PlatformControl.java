import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.JdkLoggerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public final class NettyJava25PlatformControl {
    public static void main(String[] args) throws Exception {
        for (String name : List.of("zone.moddev.mc.basepower.PowerAdvantage",
                "zone.moddev.mc.advantageworks.NativeActorIo", "net.minecraft.server.MinecraftServer")) {
            try {
                Class.forName(name, false, NettyJava25PlatformControl.class.getClassLoader());
                throw new AssertionError("Family/Minecraft class leaked into isolated Netty control: " + name);
            } catch (ClassNotFoundException expected) { }
        }
        List<LogRecord> captured = new ArrayList<>();
        Logger logger = Logger.getLogger("io.netty");
        logger.setLevel(Level.ALL);
        Handler capture = new Handler() {
            public void publish(LogRecord record) { captured.add(record); }
            public void flush() {}
            public void close() {}
        };
        capture.setLevel(Level.ALL);
        logger.addHandler(capture);
        for (Handler handler : Logger.getLogger("").getHandlers()) handler.setLevel(Level.ALL);
        InternalLoggerFactory.setDefaultFactory(JdkLoggerFactory.INSTANCE);
        if (PlatformDependent.javaVersion() != 25 || PlatformDependent.hasUnsafe()
                || !"io.netty.common".equals(PlatformDependent.class.getModule().getName()))
            throw new AssertionError("Wrong native Java25/default Unsafe/module context");
        var buffer = PlatformDependent.allocateDirect(32);
        buffer.buffer().putInt(0, 0x5A31C0DE);
        if (buffer.buffer().getInt(0) != 0x5A31C0DE) throw new AssertionError("Direct allocation round trip failed");
        buffer.clean();
        String[] names = {"CleanerJava9", "CleanerJava24Linker", "CleanerJava25"};
        String[] messages = {"java.nio.ByteBuffer.cleaner(): unavailable", "java.nio.ByteBuffer.cleaner(): unavailable",
                "java.nio.ByteBuffer.cleaner(): available"};
        String[] reasons = {"sun.misc.Unsafe unavailable",
                "Native access (restricted methods) is not enabled for the io.netty.common module.", null};
        int prior = -1;
        for (int i = 0; i < names.length; i++) {
            final String name = "io.netty.util.internal." + names[i];
            var records = captured.stream().filter(record -> name.equals(record.getLoggerName())).toList();
            if (records.size() != 1) throw new AssertionError("Wrong exact probe count for " + name);
            LogRecord record = records.getFirst();
            int index = captured.indexOf(record);
            if (index <= prior || !record.getLevel().equals(Level.FINE) || !messages[i].equals(record.getMessage()))
                throw new AssertionError("Wrong native probe order/level/message: " + name);
            if (reasons[i] == null ? record.getThrown() != null
                    : !(record.getThrown() instanceof UnsupportedOperationException)
                        || !reasons[i].equals(record.getThrown().getMessage()))
                throw new AssertionError("Wrong native capability exception: " + name);
            prior = index;
            System.out.println("NATIVE_PROBE " + name + " " + record.getMessage() + " reason=" + reasons[i]);
        }
        System.out.println("NETTY_JAVA25_CONTROL_PASS noPower=true noHarness=true noMinecraft=true namedModule=io.netty.common"
                + " unsafe=false directAllocationRoundTrip=true cleaner25=true newJvmFlags=false");
    }
}
