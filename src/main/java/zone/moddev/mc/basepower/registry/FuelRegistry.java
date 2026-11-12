package zone.moddev.mc.basepower.registry;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.component.CookingFuel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.providers.number.ints.ResolvableInt;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.ForgeEventFactory;

import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * The fuel registry keeps track of the cooking time for new burnables added by PowerAdvantage. The 
 * actual registration of these burnables occurs in <code>zone.moddev.mc.basepower.init.Fuels</code>
 * @author DrCyano
 *
 */
public class FuelRegistry {


	private final Map<Item,List<Function<ItemStack,Short>>> burnMap = new HashMap<>();
	private final Map<Item,List<Function<ItemStack,ItemStack>>> postBurnMap = new HashMap<>();

	private FuelRegistry(){}
	
	private static final Lock initLock = new ReentrantLock();
	private static FuelRegistry instance = null;
	
	/**
	 * Gets a singleton instance of FuelRegistry
	 * @return A global instance of FuelRegistry
	 */
	public static FuelRegistry getInstance(){
		if(instance == null){
			initLock.lock();
			try{
				if(instance == null){
					// thread-safe singleton instantiation
					instance = new FuelRegistry();
				}
			} finally{
				initLock.unlock();
			}
		}
		return instance;
	}


	/**
	 * Registers a fuel item/block
	 * @param block The fuel item/block
	 * @param burnTime How long it should burn (in game ticks)
	 */
	public void registerFuel(Block block, final Short burnTime){
		registerFuel(Item.byBlock(block),(ItemStack i)->burnTime);
	}
	/**
	 * Registers a fuel item/block
	 * @param item The fuel item/block
	 * @param burnTime How long it should burn (in game ticks)
	 */
	public void registerFuel(Item item, final Short burnTime){
		registerFuel(item,(ItemStack i)->burnTime);
	}
	/**
	 * Registers a fuel item/block as an algorithm for calculating the burn time based on the specific ItemStack instance
	 * @param block The fuel item/block
	 * @param algorithm A function (usually as a lambda expression) that computes the burntime of an ItemStack
	 */
	public void registerFuel(Block block, Function<ItemStack,Short> algorithm){
		registerFuel(Item.byBlock(block),algorithm);
	}
	/**
	 * Registers a fuel item/block as an algorithm for calculating the burn time based on the specific ItemStack instance
	 * @param item The fuel item/block
	 * @param algorithm A function (usually as a lambda expression) that computes the burntime of an ItemStack
	 */
	public void registerFuel(Item item, Function<ItemStack,Short> algorithm){
		burnMap.computeIfAbsent(item,(Item i)->new ArrayList<>()).add(0,algorithm);
	}

	/**
	 * Registers an algorithm to decide what to do to an item after it has been "burned". If a post-burn algorithm is
	 * not specified, then burning an item simply consumes it. Use this method if you want to have an item turn into
	 * a different item when burned or change its item NBT data.
	 * @param item The burned item
	 * @param algorithm A function stransforming the item before burning into the item after burning
	 */
	public void registerPostBurnItem(Item item, Function<ItemStack,ItemStack> algorithm){
		postBurnMap.computeIfAbsent(item,(Item i)->new ArrayList<>()).add(0,algorithm);
	}
	/**
	 * Gets all of the registered algorithms for a given Item. The list will be sorted in the reverse order in which the
	 * algorithms were added. This list is mutable unless it is empty, so you can use this method to remove algorithms,
	 * but not to add new algorithms (use the <code>registerFuel(...)</code> methods to add new fuel algorithms).
	 * @param item The item to look-up
	 * @return A list of all registered algorithms for this item. If no algorithms exist for the given item, an
	 * immutable empty list is returned.
	 */
	public List<Function<ItemStack,Short>> getFuelAlgorithmsForItem(Item item){
		List<Function<ItemStack,Short>> list = burnMap.get(item);
		if(list == null) return Collections.emptyList();
		return list;
	}

	/**
	 * Gets all of the registered algorithms for a given Item. The list will be sorted in the reverse order in which the
	 * algorithms were added. This list is mutable unless it is empty, so you can use this method to remove algorithms,
	 * but not to add new algorithms (use the <code>registerFuel(...)</code> methods to add new fuel algorithms).
	 * @param item The item to look-up
	 * @return A list of all registered algorithms for this item. If no algorithms exist for the given item, an
	 * immutable empty list is returned.
	 */
	public List<Function<ItemStack,ItemStack>> getPostBurnAlgorithmsForItem(Item item){
		List<Function<ItemStack,ItemStack>> list = postBurnMap.get(item);
		if(list == null) return Collections.emptyList();
		return list;
	}
	/**
	 * Do not call this method directly! This method is for the Forge API only!<br>
	 * Gets the burn time for a given item from this registry. If an items doesn't burn, 0 will be returned.
	 * @param fuel The fuel item
	 * @return The number of ticks that the item should burn, or 0 if it isn't flammable
	 */
	public int getBurnTime(ItemStack fuel) {
		if(fuel == null) return 0;
		for(Function<ItemStack,Short> algorithm : getFuelAlgorithmsForItem(fuel.getItem())){
			short value = algorithm.apply(fuel);
			if(value > 0) return value;
		}
		return 0;
	}

	/**
	 * Does a global look-up of the fuel value for an item.
	 * @param fuel Item to be burned
	 * @return The number of ticks that the item should burn, or 0 if it isn't flammable
	 */
	public static short getActualBurntimeForItem(ItemStack fuel){
		return getActualBurntimeForItem(fuel, null);
	}

	/** Resolves native cooking fuel in the machine's world and container context. */
	public static short getActualBurntimeForItem(ItemStack fuel, BlockEntity machine){
		if(fuel == null || fuel.isEmpty()) return 0;
		int custom = getInstance().getBurnTime(fuel);
		if (custom > 0) return (short) Math.min(Short.MAX_VALUE, custom);
		CookingFuel component = fuel.get(DataComponents.COOKING_FUEL);
		LootContext context = component != null && component.burnTime() instanceof ResolvableInt.Reference
				? fuelContext(machine) : null;
		int vanilla = nativeBurnTime(component, context);
		int burnTime = ForgeEventFactory.getItemBurnTime(fuel, vanilla, net.minecraft.world.item.crafting.RecipeType.SMELTING);
		return (short) Math.min(Short.MAX_VALUE, Math.max(0, burnTime));
	}

	static int nativeBurnTime(CookingFuel component, LootContext context) {
		if (component == null) return 0;
		// A reference requires the server's reloaded data; do not invent a client-side value.
		if (context == null && component.burnTime() instanceof ResolvableInt.Reference) return 0;
		return component.burnTime().get(context, 0);
	}

	private static LootContext fuelContext(BlockEntity machine) {
		var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
		ServerLevel level = machine != null && machine.getLevel() instanceof ServerLevel world
				? world : server == null ? null : server.overworld();
		if (level == null) return null;
		var parameters = new LootParams.Builder(level);
		if (machine != null && machine.getLevel() == level) {
			parameters.withParameter(LootContextParams.BLOCK_STATE, machine.getBlockState())
					.withParameter(LootContextParams.BLOCK_ENTITY, machine)
					.withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(machine.getBlockPos()));
			if (machine instanceof Container container) {
				parameters.withParameter(LootContextParams.CONTAINER, container);
				return new LootContext.Builder(parameters.create(LootContextParamSets.CONTAINER_PROCESS)).create(Optional.empty());
			}
		}
		return new LootContext.Builder(parameters.create()).create(Optional.empty());
	}

	/**
	 * Handles the consumption of a fuel item for you, including turning a lava bucket into an empty bucket and similar
	 * such complexities.
	 * @param fuelItem The item that is being consumed for fuel
	 * @return The item after being consumed (usually the same item with a decremented stack size)
	 */
	public static ItemStack decrementFuelItem(ItemStack fuelItem){
		if(fuelItem == null) return null;
		List<Function<ItemStack, ItemStack>> list = FuelRegistry.getInstance().getPostBurnAlgorithmsForItem(fuelItem.getItem());
		if(list.isEmpty()){
			var remainder = fuelItem.getCount() == 1 ? fuelItem.getCraftingRemainder() : null;
			fuelItem.shrink(1);
			if(fuelItem.isEmpty()){
				fuelItem = remainder == null ? ItemStack.EMPTY : remainder.create();
			}
		} else {
			for (Function<ItemStack, ItemStack> algorithm : list) {
				fuelItem = algorithm.apply(fuelItem);
			}
		}
		return fuelItem;
	}
}
