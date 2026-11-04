package zone.moddev.mc.basepower.init;

import cyano.poweradvantage.api.FluidCategoryRegistry;
import zone.moddev.mc.basepower.PowerAdvantage;
import zone.moddev.mc.basepower.RecipeMode;
import zone.moddev.mc.basepower.compat.BaseMetalsCompat;
import zone.moddev.mc.basepower.registry.still.recipe.DistillationRecipeRegistry;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fml.common.FMLLog;
import net.minecraftforge.fml.common.registry.GameRegistry;
import net.minecraftforge.oredict.OreDictionary;


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

		if (PowerAdvantage.recipeMode == RecipeMode.TECH_PROGRESSION) {
			// make things a little more complicated with tech-progression mode
			BaseMetalsCompat.setStrongHammers(false);
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
		}


		initDone = true;
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

	public static void initCrudeOilAliasDistillationRecipes() {
		Fluid refinedOil = FluidRegistry.getFluid("refined_oil");
		if (refinedOil == null) return;
		for (String alias : FluidCategoryRegistry.getAliases(FluidCategoryRegistry.CRUDE_OIL)) {
			Fluid input = FluidRegistry.getFluid(alias);
			if (input != null && DistillationRecipeRegistry.getInstance().getDistillationRecipeForFluid(input) == null) {
				DistillationRecipeRegistry.addDistillationRecipe(alias, 2, refinedOil.getName(), 1);
			}
		}
	}

}
