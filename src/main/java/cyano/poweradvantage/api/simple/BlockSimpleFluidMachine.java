package cyano.poweradvantage.api.simple;

import zone.moddev.mc.basepower.init.Fluids;

/**
 * This block class provides all of the standard code for creating a machine 
 * block with an inventory and user interface that gives fluids to connected 
 * fluid consumers.<br>
 * Example usage:<br><pre>
int guiID = zone.moddev.mc.basepower.registry.MachineGUIRegistry.addGUI(new MySimpleMachineGUI());
Block myMachineBlock = new MyBlockSimpleFluidSource(guiID,PowerAdvantage.getInstance());
myMachineBlock.setTranslationKey(MODID+"."+"my_machine");
myMachineBlock.setRegistryName(MODID, "my_machine");
net.minecraftforge.fml.common.registry.ForgeRegistries.BLOCKS.register(myMachineBlock);
 * </pre>
 * @author DrCyano
 *
 */
public abstract class BlockSimpleFluidMachine extends BlockSimplePowerMachine {

    protected BlockSimpleFluidMachine(net.minecraft.world.level.block.Block.Properties properties) {
        super(properties, Fluids.fluidConduit_general);
    }

}
