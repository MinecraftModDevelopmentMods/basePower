package zone.moddev.mc.basepower.resources;

import cyano.poweradvantage.api.GUIBlock;
import java.io.InputStream;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Material;
import org.junit.BeforeClass;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import zone.moddev.mc.basepower.init.Entities;
import zone.moddev.mc.basepower.machines.conveyors.BlockConveyor;
import zone.moddev.mc.basepower.machines.conveyors.TileEntityConveyor;
import static org.junit.Assert.*;

public class NativeBlockEntityFactoryTest {
    @BeforeClass public static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test public void nativeFactoriesKeepTheActualPositionAndFacing() {
        BlockConveyor block = new BlockConveyor(Material.PISTON, 0.75F);
        BlockEntityType<TileEntityConveyor> old = Entities.CONVEYOR;
        try {
            Entities.CONVEYOR = BlockEntityType.Builder.of(TileEntityConveyor::new, block).build(null);
            BlockPos position = new BlockPos(37, 83, -41);
            BlockState state = block.defaultBlockState().setValue(BlockConveyor.FACING, net.minecraft.core.Direction.UP);
            TileEntityConveyor placed = (TileEntityConveyor)block.newBlockEntity(position, state);
            TileEntityConveyor restored = Entities.CONVEYOR.create(position, state);
            for (TileEntityConveyor tile : new TileEntityConveyor[] {placed, restored}) {
                assertEquals(position, tile.getBlockPos());
                assertSame(state, tile.getBlockState());
                assertSame(Entities.CONVEYOR, tile.getType());
                assertNotNull(block.getTicker(null, state, Entities.CONVEYOR));
                net.minecraft.nbt.CompoundTag saved = new net.minecraft.nbt.CompoundTag();
                saved.putInt("x", 0);
                tile.load(saved);
                assertEquals("NBT cannot replace the native factory's final position", position, tile.getBlockPos());
            }
            assertNull(block.getTicker(null, state, BlockEntityType.CHEST));
        } finally { Entities.CONVEYOR = old; }
    }

    @Test public void nativeTickersActuallyDispatchBothMachineFamilies() throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream input = GUIBlock.class.getResourceAsStream("/cyano/poweradvantage/api/GUIBlock.class")) {
            assertNotNull(input);
            new ClassReader(input).accept(node, 0);
        }
        int powered = 0, conveyors = 0;
        for (MethodNode method : node.methods) for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode)instruction;
            if (!call.name.equals("tick") || !call.desc.equals("()V")) continue;
            if (call.owner.equals("cyano/poweradvantage/api/PoweredEntity")) powered++;
            if (call.owner.equals("zone/moddev/mc/basepower/machines/conveyors/TileEntityConveyor")) conveyors++;
        }
        assertEquals(1, powered);
        assertEquals(1, conveyors);
    }
}
