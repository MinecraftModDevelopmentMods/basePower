package zone.moddev.mc.advantageworks.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.BackupConfirmScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.DatapackLoadFailureScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real native components and button callbacks; no confirmation-screen or gameplay pass claim. */
public class NativeExperimentalFixtureGateTest {
    @Test public void actualNativeLoginCodecAcceptsTheBoundedProbeNameAndRejectsTheOldName() {
        var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            var packet = new net.minecraft.network.protocol.login.ServerboundHelloPacket("AdvantageProbe", new java.util.UUID(1L, 2L));
            var codec = net.minecraft.network.protocol.login.ServerboundHelloPacket.STREAM_CODEC;
            codec.encode(buffer, packet);
            assertEquals(packet, codec.decode(buffer));
            assertFalse(buffer.isReadable());
            buffer.clear();
            try {
                codec.encode(buffer, new net.minecraft.network.protocol.login.ServerboundHelloPacket("AdvantageClientProbe", new java.util.UUID(1L, 2L)));
                fail("Oversize login name accepted");
            } catch (io.netty.handler.codec.EncoderException expected) { assertTrue(expected.getMessage().contains("max 16")); }
        } finally { buffer.release(); }
    }

    private static Button skip(AtomicInteger calls) {
        return Button.builder(Component.translatable(NativeExperimentalFixtureGate.SKIP), button -> calls.incrementAndGet()).build();
    }

    @Test public void exactNativeSkipButtonReceivesTheApprovedKeyEvent() {
        AtomicInteger calls = new AtomicInteger();
        Button button = skip(calls);
        Button chosen = NativeExperimentalFixtureGate.select(BackupConfirmScreen.class,
                Component.translatable(NativeExperimentalFixtureGate.TITLE), List.of(button));
        assertSame(button, chosen);
        assertEquals(40, InputConstants.KEY_RETURN);
        chosen.onPress(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
        assertEquals(1, calls.get());
    }

    @Test public void otherScreensAndLookalikeTitlesAreNeverSelected() {
        Button button = skip(new AtomicInteger());
        for (Class<?> type : List.of(ConfirmScreen.class, DatapackLoadFailureScreen.class, Object.class))
            assertNull(NativeExperimentalFixtureGate.select(type, Component.translatable(NativeExperimentalFixtureGate.TITLE), List.of(button)));
        for (Component title : List.of(Component.literal("These worlds use Experimental Settings"),
                Component.translatable("selectWorld.backupQuestion.customized"),
                Component.translatable("selectWorld.backupQuestion.snapshot"),
                Component.translatable(NativeExperimentalFixtureGate.TITLE, "extra"),
                Component.translatable(NativeExperimentalFixtureGate.TITLE).append("extra")))
            assertNull(NativeExperimentalFixtureGate.select(BackupConfirmScreen.class, title, List.of(button)));
    }

    @Test public void missingDuplicateHiddenAndDisabledSkipButtonsFailClosed() {
        for (int mode = 0; mode < 4; mode++) {
            Button button = skip(new AtomicInteger());
            if (mode == 2) button.visible = false;
            if (mode == 3) button.active = false;
            List<Button> buttons = mode == 0 ? List.of() : mode == 1 ? List.of(button, skip(new AtomicInteger())) : List.of(button);
            try {
                NativeExperimentalFixtureGate.select(BackupConfirmScreen.class,
                        Component.translatable(NativeExperimentalFixtureGate.TITLE), buttons);
                fail("Unusable/ambiguous skip button accepted");
            } catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("one visible active")); }
        }
    }

    @Test public void copiedRootPermitsOnlyTheExactEmptyObsoleteBiomeIndexRemoval() {
        CompoundTag source = new CompoundTag(), fml = new CompoundTag(), registries = new CompoundTag(), index = new CompoundTag();
        index.put("aliases", new ListTag()); index.put("ids", new ListTag()); index.put("overrides", new ListTag());
        index.putIntArray("blocked", new int[0]);
        registries.put("minecraft:worldgen/biome", index); fml.put("Registries", registries); source.put("fml", fml);
        CompoundTag data = new CompoundTag(); data.putInt("DataVersion", 5023); source.put("Data", data);
        CompoundTag original = source.copy(), observed = source.copy();
        assertEquals("UNCHANGED_TYPED_ROOT", NativeExperimentalFixtureGate.verifyCopiedLevel(source, observed));
        observed.getCompound("fml").orElseThrow().getCompound("Registries").orElseThrow().remove("minecraft:worldgen/biome");
        assertEquals("ONLY_FML_LOWERCASE_EMPTY_BIOME_INDEX_REMOVED", NativeExperimentalFixtureGate.verifyCopiedLevel(source, observed));
        for (int mode = 0; mode < 4; mode++) {
            CompoundTag wrong = observed.copy();
            if (mode == 0) wrong.getCompound("Data").orElseThrow().putLong("DataVersion", 5023L);
            if (mode == 1) wrong.getCompound("Data").orElseThrow().putInt("SpawnX", 1);
            if (mode == 2) wrong.getCompound("fml").orElseThrow().putString("unknown", "change");
            if (mode == 3) wrong.getCompound("fml").orElseThrow().getCompound("Registries").orElseThrow().put("other:index", new CompoundTag());
            try { NativeExperimentalFixtureGate.verifyCopiedLevel(source, wrong); fail("Unproven typed NBT change accepted"); }
            catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("differs beyond")); }
        }
        assertEquals(original, source);
    }
}
