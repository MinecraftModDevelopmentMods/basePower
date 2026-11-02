package zone.moddev.mc.basepower.init;

import zone.moddev.mc.basepower.PowerAdvantage;
import cyano.poweradvantage.api.FluidCategoryRegistry;
import zone.moddev.mc.basepower.RecipeMode;
import zone.moddev.mc.basepower.compat.BaseMetalsCompat;
import zone.moddev.mc.basepower.registry.still.recipe.DistillationRecipeRegistry;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.common.FMLLog;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fml.common.registry.GameRegistry;
import net.minecraftforge.oredict.OreDictionary;
import net.minecraftforge.oredict.ShapedOreRecipe;


public abstract class Recipes {

	/*
	 * Normal - can craft all necessary machine parts
	 * Apocalyptic - must find key parts as treasure in chests
	 * Tech-progression - making the first key part is very complicated, but once made, key parts can be duplicated fairly easily
	 */


	private static boolean initDone = false;

	public static void init() {
		if (initDone) return;
		Blocks.init();
		Items.init();

		OreDictionary.registerOre("bread", net.minecraft.init.Items.BREAD);
		OreDictionary.registerOre("coal", net.minecraft.init.Items.COAL);
		OreDictionary.registerOre("furnace", net.minecraft.init.Blocks.FURNACE);

		OreDictionary.registerOre("potato", net.minecraft.init.Items.POISONOUS_POTATO);
		OreDictionary.registerOre("potato", net.minecraft.init.Items.POTATO);
		BaseMetalsCompat.addCrusherRecipe("potato", new ItemStack(Items.starch, 1));
		GameRegistry.addSmelting(Items.starch, new ItemStack(Items.bioplastic_ingot, 1), 0.1f);

		GameRegistry.addRecipe(new ShapedOreRecipe(new ItemStack(Items.rotator_tool, 1), "xx", "x*", " x", 'x', "ingotIron", '*', "sprocket"));

		GameRegistry.addRecipe(new ShapedOreRecipe(new ItemStack(Blocks.item_conveyor, 5), "xxx", "ghg", "xxx", 'x', "plateSteel", 'g', "sprocket", 'h', net.minecraft.init.Blocks.HOPPER));
		GameRegistry.addRecipe(new ShapedOreRecipe(new ItemStack(Blocks.item_filter_block, 1), "x", "y", "z", 'y', "stone", 'x', Blocks.item_conveyor, 'z', net.minecraft.init.Blocks.WOODEN_PRESSURE_PLATE));
		GameRegistry.addRecipe(new ShapedOreRecipe(new ItemStack(Blocks.item_filter_food, 1), "x", "y", "z", 'y', "bread", 'x', Blocks.item_conveyor, 'z', net.minecraft.init.Blocks.WOODEN_PRESSURE_PLATE));
		GameRegistry.addRecipe(new ShapedOreRecipe(new ItemStack(Blocks.item_filter_fuel, 1), "x", "y", "z", 'y', "coal", 'x', Blocks.item_conveyor, 'z', net.minecraft.init.Blocks.WOODEN_PRESSURE_PLATE));
		GameRegistry.addRecipe(new ShapedOreRecipe(new ItemStack(Blocks.item_filter_inventory, 1), "x", "y", "z", 'y', "chest", 'x', Blocks.item_conveyor, 'z', net.minecraft.init.Blocks.WOODEN_PRESSURE_PLATE));
		GameRegistry.addRecipe(new ShapedOreRecipe(new ItemStack(Blocks.item_filter_ore, 1), "x", "y", "z", 'y', "ingotGold", 'x', Blocks.item_conveyor, 'z', net.minecraft.init.Blocks.WOODEN_PRESSURE_PLATE));
		GameRegistry.addRecipe(new ShapedOreRecipe(new ItemStack(Blocks.item_filter_plant, 1), "x", "y", "z", 'y', "treeSapling", 'x', Blocks.item_conveyor, 'z', net.minecraft.init.Blocks.WOODEN_PRESSURE_PLATE));
		GameRegistry.addRecipe(new ShapedOreRecipe(new ItemStack(Blocks.item_filter_smelt, 1), "x", "y", "z", 'y', "furnace", 'x', Blocks.item_conveyor, 'z', net.minecraft.init.Blocks.WOODEN_PRESSURE_PLATE));
		GameRegistry.addRecipe(new ShapedOreRecipe(new ItemStack(Blocks.item_filter_overflow, 1), "x", "z", 'x', Blocks.item_conveyor, 'z', net.minecraft.init.Blocks.WOODEN_PRESSURE_PLATE));

		GameRegistry.addRecipe(new ShapedOreRecipe(new ItemStack(Blocks.steel_frame, 1), "xxx", "x x", "xxx", 'x', "barsSteel"));

		GameRegistry.addRecipe(new ShapedOreRecipe(new ItemStack(Blocks.storage_tank, 1), "xxx", "xpx", "xxx", 'x', "ingotPlastic", 'p', "pipe"));
		GameRegistry.addRecipe(new ShapedOreRecipe(new ItemStack(Blocks.metal_storage_tank, 1), "xxx", "xpx", "xxx", 'x', "ingotSteel", 'p', "pipe"));
		GameRegistry.addRecipe(new ShapedOreRecipe(new ItemStack(Blocks.fluid_drain, 1), " x ", "w#w", "ppp", 'x', "bars", 'w', "plateSteel", '#', "frameSteel", 'p', "pipe"));
		GameRegistry.addRecipe(new ShapedOreRecipe(new ItemStack(Blocks.fluid_discharge, 1), "ppp", "w#w", " x ", 'x', "bars", 'w', "plateSteel", '#', "frameSteel", 'p', "pipe"));
		GameRegistry.addRecipe(new ShapedOreRecipe(new ItemStack(Blocks.still, 1), "bpb", " f ", 'b', net.minecraft.init.Items.BUCKET, 'p', "pipe", 'f', net.minecraft.init.Blocks.FURNACE));
		GameRegistry.addRecipe(new ShapedOreRecipe(new ItemStack(Blocks.fluid_switch, 1), " l ", "pfp", 'l', net.minecraft.init.Blocks.LEVER, 'p', "pipe", 'f', "frameSteel"));


		// recipe modes
		if (PowerAdvantage.recipeMode == RecipeMode.NORMAL) {
			// normal means easy
			GameRegistry.addRecipe(new ShapedOreRecipe(new ItemStack(Items.sprocket, 4), " x ", "x/x", " x ", 'x', "ingotSteel", '/', "stickWood"));
			GameRegistry.addRecipe(new ShapedOreRecipe(new ItemStack(Items.sprocket, 4), " x ", "x/x", " x ", 'x', "ingotSteel", '/', "rod"));

			GameRegistry.addRecipe(new ShapedOreRecipe(new ItemStack(Blocks.fluid_pipe, 6), "xxx", "   ", "xxx", 'x', "ingotIron"));
			GameRegistry.addRecipe(new ShapedOreRecipe(new ItemStack(Blocks.fluid_pipe, 6), "xxx", "   ", "xxx", 'x', "ingotCopper"));
			GameRegistry.addRecipe(new ShapedOreRecipe(new ItemStack(Blocks.metal_storage_tank, 1), "xxx", "xpx", "xxx", 'x', "ingotIron", 'p', "pipe"));
		} else if (PowerAdvantage.recipeMode == RecipeMode.TECH_PROGRESSION) {
			// make things a little more complicated with tech-progression mode
			BaseMetalsCompat.setStrongHammers(false);
			GameRegistry.addRecipe(new ShapedOreRecipe(new ItemStack(Items.sprocket, 4), " x ", "x/x", " x ", 'x', "ingotSteel", '/', "rod"));
			if (OreDictionary.getOres("rod").isEmpty())
				GameRegistry.addRecipe(new ShapedOreRecipe(new ItemStack(Items.sprocket, 4), " x ", "x/x", " x ", 'x', "ingotSteel", '/', "nuggetBronze"));
			GameRegistry.addRecipe(new ShapedOreRecipe(new ItemStack(Blocks.fluid_pipe, 6), "xxx", "   ", "xxx", 'x', "ingotIron"));
		} else if (PowerAdvantage.recipeMode == RecipeMode.APOCALYPTIC) {
			// apocalyptic means some things are not craftable, but some stuff can be recycled
			ItemStack steelPlate = BaseMetalsCompat.getSteelPlate();
			if (steelPlate != null) {
				BaseMetalsCompat.addCrusherRecipe(Blocks.item_conveyor, steelPlate.copy());
				BaseMetalsCompat.addCrusherRecipe(Blocks.item_filter_block, steelPlate.copy());
				BaseMetalsCompat.addCrusherRecipe(Blocks.item_filter_food, steelPlate.copy());
				BaseMetalsCompat.addCrusherRecipe(Blocks.item_filter_fuel, steelPlate.copy());
				BaseMetalsCompat.addCrusherRecipe(Blocks.item_filter_inventory, steelPlate.copy());
				BaseMetalsCompat.addCrusherRecipe(Blocks.item_filter_ore, steelPlate.copy());
				BaseMetalsCompat.addCrusherRecipe(Blocks.item_filter_plant, steelPlate.copy());
				BaseMetalsCompat.addCrusherRecipe(Blocks.item_filter_smelt, steelPlate.copy());
				BaseMetalsCompat.addCrusherRecipe(Blocks.item_filter_overflow, steelPlate.copy());
			}

			BaseMetalsCompat.addCrusherRecipe(Blocks.storage_tank, new ItemStack(Blocks.fluid_pipe, 1));
			BaseMetalsCompat.addCrusherRecipe(Blocks.fluid_discharge, new ItemStack(Blocks.fluid_pipe, 2));
			BaseMetalsCompat.addCrusherRecipe(Blocks.fluid_drain, new ItemStack(Blocks.fluid_pipe, 2));

			GameRegistry.addRecipe(new ShapedOreRecipe(new ItemStack(Blocks.fluid_pipe, 3), "xxx", "   ", "xxx", 'x', "ingotIron"));
			GameRegistry.addRecipe(new ShapedOreRecipe(new ItemStack(Blocks.fluid_pipe, 3), "xxx", "   ", "xxx", 'x', "ingotCopper"));
			GameRegistry.addRecipe(new ShapedOreRecipe(new ItemStack(Blocks.fluid_pipe, 3), "xxx", "   ", "xxx", 'x', "ingotLead"));
		}


		initDone = true;
	}

	public static void initCrudeOilAliasDistillationRecipes() {
		Fluid refinedOil = FluidRegistry.getFluid("refined_oil");
		if (refinedOil == null) return;
		for (String alias : FluidCategoryRegistry.getAliases(FluidCategoryRegistry.CRUDE_OIL)) {
			Fluid input = FluidRegistry.getFluid(alias);
			if (input != null && DistillationRecipeRegistry.getInstance()
					.getDistillationRecipeForFluid(input) == null) {
				DistillationRecipeRegistry.addDistillationRecipe(alias, 2, refinedOil.getName(), 1);
			}
		}
		DistillationRecipeRegistry.clearRecipeCache();
	}

	public static void initDistillationRecipes(String[] distillRecipes) {
		for (String recipe : distillRecipes) {
			String r = recipe.trim();
			if (r.isEmpty()) continue;
			try {
				int numIn, numOut;
				String fluidIn, fluidOut;
				String inputStr = r.substring(0, r.indexOf("->")).trim();
				numIn = Integer.parseInt(inputStr.substring(0, inputStr.indexOf('*')).trim());
				fluidIn = inputStr.substring(inputStr.indexOf('*') + 1).trim();
				String outputStr = r.substring(inputStr.length() + "->".length()).trim();
				numOut = Integer.parseInt(outputStr.substring(0, outputStr.indexOf('*')).trim());
				fluidOut = outputStr.substring(outputStr.indexOf('*') + 1).trim();
				DistillationRecipeRegistry.addDistillationRecipe(fluidIn, numIn, fluidOut, numOut);
			} catch (Exception ex) {
				FMLLog.severe("%s: Failed to add fluid distillation recipe \"%s\". %s", PowerAdvantage.MODID, r, ex);
			}
		}
	}

}
