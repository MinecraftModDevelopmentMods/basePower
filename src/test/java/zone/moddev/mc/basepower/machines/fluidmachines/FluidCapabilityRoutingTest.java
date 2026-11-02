package zone.moddev.mc.basepower.machines.fluidmachines;

import cyano.poweradvantage.api.simple.TileEntitySimpleFluidMachine;
import cyano.poweradvantage.api.fluid.FluidRequest;
import java.lang.reflect.Constructor;
import java.util.concurrent.Callable;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fluids.*;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;
import org.junit.*;
import static org.junit.Assert.*;

public class FluidCapabilityRoutingTest {
    private static Capability<IFluidHandler> original;

    @BeforeClass public static void bootstrap() throws Exception {
        net.minecraft.init.Bootstrap.register();
        original=CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY;
        if(original==null) {
            // Unit tests do not run FML's capability-field injection.
            Constructor<Capability> constructor=Capability.class.getDeclaredConstructor(String.class,Capability.IStorage.class,Callable.class);
            constructor.setAccessible(true);
            CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY=constructor.newInstance("fluid-routing-test",null,null);
        }
    }
    @AfterClass public static void restore() { CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY=original; }

    private static final class TwoTank extends TileEntitySimpleFluidMachine {
        final FluidTank input=new FluidTank(1000);
        TwoTank() { super(1000,"test"); }
        @Override protected ItemStack[] getInventory() { return new ItemStack[0]; }
        @Override public void tickUpdate(boolean server) {}
        @Override public FluidTank getTank() { return tank; }
        @Override public boolean isFluidSource() { return true; }
        @Override public boolean isFluidSink() { return true; }
        @Override public FluidRequest getFluidRequest(Fluid offer) { return FluidRequest.REQUEST_NOTHING; }
        @Override public boolean canAccept(Fluid offer) { return offer==FluidRegistry.WATER; }
        @Override public int fill(EnumFacing face,FluidStack packet,boolean execute) {
            return packet!=null && canFill(face,packet.getFluid()) ? input.fill(packet,execute) : 0;
        }
        @Override public boolean canFill(EnumFacing face,Fluid fluid) { return face==EnumFacing.UP && fluid==FluidRegistry.WATER; }
        @Override public FluidTankInfo[] getTankInfo(EnumFacing face) { return new FluidTankInfo[]{input.getInfo(),tank.getInfo()}; }
    }

    @Test public void capabilityFillsTheOverriddenInputRatherThanExposingOutputTank() {
        TwoTank tile=new TwoTank();
        IFluidHandler port=tile.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY,EnumFacing.UP);
        assertEquals(1000,port.fill(new FluidStack(FluidRegistry.WATER,1000),true));
        assertEquals(1000,tile.input.getFluidAmount());
        assertEquals(0,tile.getTank().getFluidAmount());
    }
    @Test public void capabilityHonorsFluidAndSideRestrictions() {
        TwoTank tile=new TwoTank();
        assertEquals(0,tile.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY,EnumFacing.UP).fill(new FluidStack(FluidRegistry.LAVA,1000),true));
        assertEquals(0,tile.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY,EnumFacing.DOWN).fill(new FluidStack(FluidRegistry.WATER,1000),true));
        assertEquals(0,tile.input.getFluidAmount());assertEquals(0,tile.getTank().getFluidAmount());
    }
    @Test public void simulationCapacityAndBothTankPropertiesRemainAccurate() {
        TwoTank tile=new TwoTank();IFluidHandler port=tile.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY,EnumFacing.UP);
        assertEquals(1000,port.fill(new FluidStack(FluidRegistry.WATER,1000),false));
        assertEquals(0,tile.input.getFluidAmount());assertEquals(0,tile.getTank().getFluidAmount());
        tile.input.fill(new FluidStack(FluidRegistry.WATER,999),true);
        assertEquals(1,port.fill(new FluidStack(FluidRegistry.WATER,10),true));
        assertEquals(0,port.fill(new FluidStack(FluidRegistry.WATER,1),true));
        assertEquals(2,port.getTankProperties().length);
    }
    @Test public void drainStillReturnsOnlyOutputAndNeverTheInputReserve() {
        TwoTank tile=new TwoTank();tile.input.fill(new FluidStack(FluidRegistry.WATER,1000),true);
        tile.getTank().fill(new FluidStack(FluidRegistry.LAVA,500),true);
        IFluidHandler port=tile.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY,EnumFacing.UP);
        assertEquals(250,port.drain(250,false).amount);assertEquals(500,tile.getTank().getFluidAmount());
        assertEquals(500,port.drain(600,true).amount);assertNull(port.drain(1,true));
        assertEquals(1000,tile.input.getFluidAmount());
    }
}
