package zone.moddev.mc.advantageworks;

import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import net.minecraft.command.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.inventory.*;
import net.minecraft.item.*;
import net.minecraft.item.crafting.*;
import net.minecraft.nbt.*;
import net.minecraft.network.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.*;
import net.minecraft.util.*;
import net.minecraft.util.math.*;
import net.minecraft.village.*;
import net.minecraft.world.*;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.fluids.*;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.registry.VillagerRegistry;

/** Test-only finite stock replay. No production recipes or gameplay state are patched. */
final class NativeSurvivalProgressionProbe {
    private static final File MARKER = new File("advantage-survival.allow");
    private static final File STATE = new File("survival-state.dat");
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static NativeSurvivalProgressionProbe instance;
    private static final BlockPos STILL = new BlockPos(8, 70, 8), HYDRO = new BlockPos(20, 70, 8),
            REFINERY = new BlockPos(21, 70, 8), BOILER = new BlockPos(8, 70, 20),
            STEAM_FURNACE = new BlockPos(10, 70, 20), BENCH = new BlockPos(8, 70, 32);
    private final List<ItemStack> stock = new ArrayList<>();
    private final Map<String, MerchantRecipe> offers = new TreeMap<>();
    private final List<TileEntity> held = new ArrayList<>();
    private final NativeEntityHold actors = new NativeEntityHold();
    private WorldServer world;
    private FakePlayer player;
    private JsonObject plan, report;
    private JsonArray receipts;
    private NBTTagCompound state;
    private String label;
    private int index, ticks;
    private boolean paused = true, operating, checkpoint;
    private boolean transferred;

    static void register() {
        if (!MARKER.isFile()) return;
        instance = new NativeSurvivalProgressionProbe();
        MinecraftForge.EVENT_BUS.register(instance);
    }

    static void registerCommands(FMLServerStartingEvent event) {
        if (instance == null) return;
        event.registerServerCommand(new CommandBase() {
            @Override public String getName() { return "advsurvival"; }
            @Override public int getRequiredPermissionLevel() { return 4; }
            @Override public String getUsage(ICommandSender sender) { return "/advsurvival first|continue|resume|inspect <label>"; }
            @Override public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
                if (args.length != 2) throw new CommandException(getUsage(sender));
                try { instance.run(server, args[0], args[1]); }
                catch (Exception | LinkageError error) { instance.fail(error); }
            }
        });
    }

    private static void require(boolean value, String reason) { if (!value) throw new IllegalStateException(reason); }
    private static boolean empty(ItemStack stack) { return stack == null || stack.stackSize <= 0; }
    private static String token(ItemStack stack) {
        return empty(stack) ? null : stack.getItem().getRegistryName()+"@"+(stack.isItemStackDamageable() ? 0 : stack.getMetadata());
    }
    private static ItemStack stack(String token, int count) {
        int separator = token.lastIndexOf('@');
        Item item = Item.REGISTRY.getObject(new ResourceLocation(token.substring(0, separator)));
        require(item != null && token.substring(0, separator).equals(String.valueOf(item.getRegistryName())), "Unregistered item " + token);
        return new ItemStack(item, count, Integer.parseInt(token.substring(separator+1)));
    }
    private static JsonObject read(File path) throws Exception {
        return new JsonParser().parse(new String(Files.readAllBytes(path.toPath()), StandardCharsets.UTF_8)).getAsJsonObject();
    }
    private static void write(File path, JsonObject doc) throws Exception {
        require(!path.exists(), "Never overwrite survival evidence: " + path);
        path.getParentFile().mkdirs();
        Files.write(path.toPath(), JSON.toJson(doc).getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.CREATE_NEW);
    }
    private int count(String key) { int count=0; for (ItemStack s:stock) if (key.equals(token(s))) count+=s.stackSize; return count; }
    private void credit(ItemStack stack) {
        if (!empty(stack)) stock.add(stack.copy());
    }
    private ItemStack take(String key, int amount) {
        require(amount > 0 && count(key) >= amount, "Finite stock exhausted: " + key + " x" + amount);
        ItemStack result = null;
        for (Iterator<ItemStack> i=stock.iterator(); i.hasNext() && amount>0;) {
            ItemStack found=i.next();
            if (!key.equals(token(found))) continue;
            int debit=Math.min(found.stackSize, amount);
            if (result==null) { result=found.copy(); result.stackSize=0; }
            require(ItemStack.areItemStackTagsEqual(result,found) && result.getItemDamage()==found.getItemDamage(),
                    "Do not collapse distinct item state into one debit");
            result.stackSize+=debit; found.stackSize-=debit; amount-=debit;
            if (empty(found)) i.remove();
        }
        return result;
    }
    private JsonObject counts() {
        Map<String,Integer> counts=new TreeMap<>();
        for (ItemStack s:stock) counts.put(token(s), counts.getOrDefault(token(s),0)+s.stackSize);
        JsonObject result=new JsonObject();
        counts.forEach(result::addProperty);
        return result;
    }
    private JsonArray typedStock() throws Exception {
        JsonArray result=new JsonArray();
        for(ItemStack item:stock) {
            java.io.ByteArrayOutputStream buffer=new java.io.ByteArrayOutputStream();
            CompressedStreamTools.write(item.writeToNBT(new NBTTagCompound()),new java.io.DataOutputStream(buffer));
            result.add(new JsonPrimitive(Base64.getEncoder().encodeToString(buffer.toByteArray())));
        }
        return result;
    }
    private void initialize(MinecraftServer server, String requestedLabel) throws Exception {
        world=server.worldServerForDimension(0);
        require(MARKER.isFile() && new File("advantage-migration.allow").isFile()
                && world.getWorldInfo().getWorldName().equals("AdvantageWorksMigration")
                && requestedLabel.matches("[A-Za-z0-9_-]+"), "Unmarked survival test world");
        label=requestedLabel;
        require(!new File("advantage-works-results/survival-"+label+".json").exists(), "Repeated survival label");
        plan=read(new File("survival-plan.json"));
        Class<?> power=Class.forName("zone.moddev.mc.basepower.PowerAdvantage");
        require(power.getField("recipeMode").get(null).toString().equals(plan.get("mode").getAsString())
                && power.getField("plasticIsAlsoRubber").getBoolean(null)==plan.get("rubber").getAsBoolean(), "Wrong progression mode/configuration");
        player=new FakePlayer(world,new GameProfile(UUID.fromString("692a77e7-4470-46c9-af3d-bfc1abfb9a98"),"SurvivalProbe"));
        player.connection=new NetHandlerPlayServer(server,new NetworkManager(EnumPacketDirection.SERVERBOUND),player) {
            @Override public void sendPacket(Packet<?> packet) {}
        };
        player.interactionManager.setGameType(GameType.SURVIVAL);
        require(!player.capabilities.isCreativeMode,"No creative inventory or durability policy");
        report=new JsonObject(); receipts=new JsonArray(); report.add("actions",receipts);
        report.addProperty("mode",plan.get("mode").getAsString()); report.addProperty("rubber",plan.get("rubber").getAsBoolean());
        report.addProperty("minecraft","1.10.2"); report.addProperty("label",label);
        report.addProperty("furnaceTiming","BOUNDED_NATIVE_UPDATE_SIMULATION_NOT_WALLCLOCK");
        report.addProperty("machineTiming","NATURAL_WORLD_TICKS");
        report.addProperty("villagerScope","REGISTERED_NATIVE_OFFERS_AND_REAL_TRANSACTIONS; NOT_ENCOUNTER_OR_LEVELING");
    }
    private void run(MinecraftServer server, String phase, String requestedLabel) throws Exception {
        initialize(server,requestedLabel);
        if (phase.equals("first")) {
            require(!STATE.exists(),"Do not reseed an existing progression");
            state=new NBTTagCompound();
            for (Map.Entry<String,JsonElement> entry:plan.getAsJsonObject("initial").entrySet()) {
                int remaining=entry.getValue().getAsInt();
                require(remaining>0 && remaining<=8192,"Unbounded initial resource count");
                while (remaining>0) { int n=Math.min(64,remaining); credit(stack(entry.getKey(),n)); remaining-=n; }
            }
            requireNoComponentsAtStart();
            replay(plan.getAsJsonArray("actions").size()/2);
            save("crafted-half"); finish("CRAFT_CHECKPOINT");
        } else {
            load();
            if (phase.equals("continue")) {
                require(state.getString("phase").equals("crafted-half"),"Wrong continuation checkpoint");
                replay(plan.getAsJsonArray("actions").size());
                require(counts().equals(plan.getAsJsonObject("expectedStock")),"Native finite stock differs from independent planner accounting");
                report.add("craftedStock",counts());
                for (JsonElement target:plan.getAsJsonArray("checkpoints")) require(count(target.getAsString())>0,"Missing crafted milestone "+target);
                setupMachines(); ticks=0; checkpoint=true; operating=true; unpause();
            } else if (phase.equals("resume")) {
                require(state.getString("phase").equals("machines-midcycle"),"Wrong machine checkpoint");
                verifyCold(); ticks=state.getInteger("ticks"); transferred=state.getBoolean("transferred");
                checkpoint=false; operating=true; unpause();
            } else if (phase.equals("inspect")) {
                require(state.getString("phase").equals("complete"),"Incomplete progression cannot pass cold inspection");
                verifyCold(); require(count("baseelectric:petrolplastic_ingot@0")==state.getInteger("prePlantPlastic")+5,
                        "Five retained live-produced plastic ingots, separately from any paid trade stock");
                finish("PASS");
            } else throw new IllegalArgumentException("Unknown survival phase " + phase);
        }
    }
    private void requireNoComponentsAtStart() {
        for (ItemStack s:stock) require(!token(s).startsWith("basepower:") && !token(s).startsWith("basesteam:")
                && (!token(s).startsWith("baseelectric:") || token(s).contains("_ore@")),"Injected Advantage component in starting stock");
        report.add("initialStock",counts());
    }
    private void replay(int end) throws Exception {
        JsonArray actions=plan.getAsJsonArray("actions");
        require(index>=0 && index<end && end<=actions.size(),"Invalid action checkpoint");
        for (;index<end;index++) {
            JsonObject action=actions.get(index).getAsJsonObject();
            String kind=action.get("kind").getAsString();
            JsonObject receipt=new JsonObject(); receipt.addProperty("index",index); receipt.addProperty("kind",kind);
            receipt.addProperty("output",action.get("output").getAsString());
            JsonObject before=counts();
            if (kind.equals("craft")) craft(action,receipt);
            else if (kind.equals("smelt")) smelt(action,receipt);
            else if (kind.equals("crush")) crush(action,receipt);
            else if (kind.equals("trade")) trade(action,receipt);
            else if (kind.equals("water")) credit(collect(take("minecraft:bucket@0",1),"water",BENCH));
            else throw new IllegalArgumentException("Unknown finite action "+kind);
            receipt.add("before",before); receipt.add("after",counts()); receipt.addProperty("status","PASS"); receipts.add(receipt);
        }
    }
    private void craft(JsonObject action, JsonObject receipt) {
        int id=Integer.parseInt(action.get("recipe").getAsString().substring(6));
        IRecipe recipe=CraftingManager.getInstance().getRecipeList().get(id);
        require(count("minecraft:crafting_table@0")>0 || action.get("output").getAsString().equals("minecraft:crafting_table@0")
                || action.get("inputs").getAsJsonArray().size()<=4,"Large craft needs a previously crafted workbench");
        Container owner=new Container() { @Override public boolean canInteractWith(net.minecraft.entity.player.EntityPlayer p) { return true; } };
        InventoryCrafting grid=new InventoryCrafting(owner,3,3);
        int width=action.get("width").getAsInt(),first=-1;
        JsonArray inputs=action.getAsJsonArray("inputs");
        for (int i=0;i<inputs.size();i++) if (!inputs.get(i).isJsonNull()) {
            int slot=(i/width)*3+i%width; grid.setInventorySlotContents(slot,take(inputs.get(i).getAsString(),1));
            if(first<0)first=slot;
        }
        ItemStack expected=stack(action.get("output").getAsString(),action.get("count").getAsInt());
        require(recipe.matches(grid,world) && ItemStack.areItemStacksEqual(CraftingManager.getInstance().findMatchingRecipe(grid,world),expected),
                "Chosen obtainable ingredient alternative must pass the native global recipe manager");
        ItemStack original=grid.getStackInSlot(first);
        grid.setInventorySlotContents(first,null); require(!recipe.matches(grid,world),"Missing ingredient rejected");
        grid.setInventorySlotContents(first,new ItemStack(net.minecraft.init.Blocks.BEDROCK)); require(!recipe.matches(grid,world),"Wrong ingredient rejected");
        grid.setInventorySlotContents(first,original);
        InventoryCraftResult result=new InventoryCraftResult(); result.setInventorySlotContents(0,expected.copy());
        player.inventory.clear();
        SlotCrafting slot=new SlotCrafting(player,grid,result,0,0,0);
        ItemStack taken=slot.decrStackSize(expected.stackSize); slot.onPickupFromSlot(player,taken);
        require(ItemStack.areItemStacksEqual(taken,expected),"Actual crafted quantity and item");
        int buckets=0,other=0;
        for(int i=0;i<9;i++) { ItemStack remainder=grid.getStackInSlot(i); if(!empty(remainder)) {
            if(remainder.getItem()==net.minecraft.init.Items.BUCKET) buckets+=remainder.stackSize; else other+=remainder.stackSize;
            credit(remainder);
        } }
        for(int i=0;i<player.inventory.getSizeInventory();i++) require(empty(player.inventory.getStackInSlot(i)),"No hidden spillover into player inventory");
        require(other==0 && buckets==(action.has("emptyBucketsReturned") ? action.get("emptyBucketsReturned").getAsInt() : 0),
                "Independent remainder policy: exact empty bucket return, no unaccounted ingredient or container");
        credit(taken); receipt.addProperty("negativeControlsRejected",2); receipt.addProperty("returnedEmptyBuckets",buckets);
        receipt.add("selectedInputs",inputs); receipt.addProperty("nativeConsumption","SlotCrafting");
    }
    private void smelt(JsonObject action,JsonObject receipt) {
        require(count("minecraft:furnace@0")>0,"A furnace must be crafted before smelting");
        world.setBlockState(BENCH,net.minecraft.init.Blocks.FURNACE.getDefaultState(),3);
        TileEntityFurnace furnace=(TileEntityFurnace)world.getTileEntity(BENCH);
        world.tickableTileEntities.remove(furnace);
        furnace.setInventorySlotContents(0,take(action.get("input").getAsString(),1));
        furnace.setInventorySlotContents(1,take(action.get("fuel").getAsString(),1));
        int updates=0;
        while (empty(furnace.getStackInSlot(2)) && updates<220) { furnace.update(); updates++; }
        ItemStack actual=furnace.removeStackFromSlot(2);
        require(ItemStack.areItemStacksEqual(actual,stack(action.get("output").getAsString(),action.get("count").getAsInt()))
                && empty(furnace.getStackInSlot(0)) && empty(furnace.getStackInSlot(1)) && furnace.isBurning(),
                "A consumed input and real coal burn, not a furnace registry lookup");
        credit(actual); world.setBlockToAir(BENCH);
        receipt.addProperty("nativeUpdates",updates); receipt.addProperty("coalDebited",1);
        receipt.addProperty("unusedHeat","Discarded between batches; conservative fuel budget, never credited as energy");
    }
    private void crush(JsonObject action,JsonObject receipt) {
        world.setBlockState(BENCH,net.minecraft.init.Blocks.STONE.getDefaultState(),3);
        AxisAlignedBB area=new AxisAlignedBB(BENCH.up());
        for(EntityItem entity:new ArrayList<>(world.getEntitiesWithinAABB(EntityItem.class,area))) {
            require(entity.isDead,"No live stray material in crushing cell");
            world.removeEntityDangerously(entity);
        }
        ItemStack tool=take(action.get("tool").getAsString(),1), input=take(action.get("input").getAsString(),1);
        int damage=tool.getItemDamage(); player.inventory.clear(); player.setHeldItem(EnumHand.MAIN_HAND,tool);
        EntityItem donor=new EntityItem(world,BENCH.getX()+0.5,BENCH.getY()+1.1,BENCH.getZ()+0.5,input);
        require(world.spawnEntity(donor),"Finite material dropped into native hammer cell");
        require(tool.getItem().onItemUse(tool,player,world,BENCH,EnumHand.MAIN_HAND,EnumFacing.UP,0.5f,1,0.5f)==EnumActionResult.SUCCESS,
                "Actual hammer interaction succeeds with obtainable tool under active hardness policy");
        int produced=0;
        for(EntityItem entity:new ArrayList<>(world.getEntitiesWithinAABB(EntityItem.class,area))) if(!entity.isDead) {
            ItemStack out=entity.getEntityItem();
            require(action.get("output").getAsString().equals(token(out)),"Crushing left a wrong item or unconsumed donor");
            produced+=out.stackSize; credit(out); world.removeEntityDangerously(entity);
        }
        require(produced==action.get("count").getAsInt(),"Exact dropped hammer product count");
        require(empty(tool) || tool.getItemDamage()==damage+1,"One durability debit per consumed input");
        require(empty(tool)==action.get("toolBreaks").getAsBoolean(),"Exact tool lifetime, with a replacement crafted before further work");
        credit(tool); player.setHeldItem(EnumHand.MAIN_HAND,null);
        receipt.addProperty("durabilityDebited",1); receipt.addProperty("nativeInteraction","Item.onItemUse + EntityItem outputs");
        receipt.addProperty("toolBroke",empty(tool));
    }
    private ItemStack collect(ItemStack bucket,String fluid,BlockPos floor) {
        int used=state.getInteger("collected/"+fluid)+1000;
        require(used<=(fluid.equals("water") ? 16000 : 1000),"Finite natural fluid budget exhausted");
        world.setBlockState(floor,net.minecraft.init.Blocks.STONE.getDefaultState(),3);
        BlockPos source=floor.up();
        for(EnumFacing face:EnumFacing.HORIZONTALS) world.setBlockState(source.offset(face),net.minecraft.init.Blocks.STONE.getDefaultState(),3);
        world.setBlockState(source,NativeFluids.get(fluid).getBlock().getDefaultState(),3);
        player.setPositionAndRotation(source.getX()+0.5,source.getY()+2,source.getZ()+0.5,0,90);
        player.setHeldItem(EnumHand.MAIN_HAND,bucket);
        ActionResult<ItemStack> result=bucket.getItem().onItemRightClick(bucket,world,player,EnumHand.MAIN_HAND);
        FluidStack contents=FluidUtil.getFluidContained(result.getResult());
        require(result.getType()==EnumActionResult.SUCCESS && contents!=null && contents.getFluid()==NativeFluids.get(fluid)
                && contents.amount==1000 && world.isAirBlock(source),"Actual survival pickup consumes exactly one finite source");
        player.setHeldItem(EnumHand.MAIN_HAND,null); state.setInteger("collected/"+fluid,used);
        return result.getResult();
    }
    private void fillBucketInto(ItemStack bucket,BlockPos target) {
        FluidTank packet=new FluidTank(1000);
        ItemStack returned=FluidUtil.tryEmptyContainer(bucket,packet,1000,player,true);
        require(!empty(returned) && returned.getItem()==net.minecraft.init.Items.BUCKET && packet.getFluidAmount()==1000,
                "Native container empties one bucket, returning an empty container");
        int accepted=new NativeFluidPort(world.getTileEntity(target),EnumFacing.UP).fill(packet.getFluid(),true);
        require(accepted==1000 && packet.drain(accepted,true).amount==accepted,"Machine accepts the complete real bucket packet");
        credit(returned); world.getTileEntity(target).markDirty();
    }
    private void place(String item,BlockPos position) {
        ItemStack taken=take(item+"@0",1);
        require(taken.getItem() instanceof ItemBlock,"Crafted machine is a block item");
        world.setBlockState(position,((ItemBlock)taken.getItem()).getBlock().getDefaultState(),3);
        require(world.getTileEntity(position)==null || !(world.getTileEntity(position) instanceof cyano.poweradvantage.api.PoweredEntity)
                || ((cyano.poweradvantage.api.PoweredEntity)world.getTileEntity(position)).getEnergy(new cyano.poweradvantage.api.ConduitType("electricity"))==0,
                "Newly crafted machinery does not start with injected electricity");
    }
    private void setupMachines() throws Exception {
        keepPlantLoaded();
        state.setInteger("prePlantPlastic",count("baseelectric:petrolplastic_ingot@0"));
        place("basepower:still",STILL); place("baseelectric:hydroelectric_generator",HYDRO);
        place("baseelectric:plastic_refinery",REFINERY); place("basesteam:steam_boiler_coal",BOILER);
        place("basesteam:steam_furnace",STEAM_FURNACE); place("basesteam:steam_pipe",BOILER.east());
        fillBucketInto(collect(take("minecraft:bucket@0",1),"crude_oil",BENCH),STILL);
        zone.moddev.mc.basepower.machines.fluidmachines.StillTileEntity still=
                (zone.moddev.mc.basepower.machines.fluidmachines.StillTileEntity)world.getTileEntity(STILL);
        require(still.getInputTank().getFluidAmount()==1000 && still.getTank().getFluidAmount()==0,
                "Forge capability must fill the still input, never its output tank");
        fillBucketInto(collect(take("minecraft:bucket@0",1),"water",BENCH),BOILER);
        ((IInventory)world.getTileEntity(STILL)).setInventorySlotContents(0,take("minecraft:coal@0",1));
        ((IInventory)world.getTileEntity(BOILER)).setInventorySlotContents(0,take("minecraft:coal@0",1));
        ((IInventory)world.getTileEntity(STEAM_FURNACE)).setInventorySlotContents(0,take("minecraft:coal@0",1));
        ((IInventory)world.getTileEntity(STEAM_FURNACE)).setInventorySlotContents(1,take("minecraft:iron_ore@0",1));
        // The test channel is terrain. Its single source is placed from a separately collected, paid-for bucket.
        for(int x=16;x<=25;x++) {
            world.setBlockState(new BlockPos(x,68,8),net.minecraft.init.Blocks.STONE.getDefaultState(),3);
            world.setBlockState(new BlockPos(x,69,7),net.minecraft.init.Blocks.STONE.getDefaultState(),3);
            world.setBlockState(new BlockPos(x,69,9),net.minecraft.init.Blocks.STONE.getDefaultState(),3);
        }
        world.setBlockState(new BlockPos(16,69,8),net.minecraft.init.Blocks.STONE.getDefaultState(),3);
        world.setBlockState(new BlockPos(25,69,8),net.minecraft.init.Blocks.STONE.getDefaultState(),3);
        ItemStack water=collect(take("minecraft:bucket@0",1),"water",BENCH);
        player.setPositionAndRotation(17.5,71,8.5,0,90); player.setHeldItem(EnumHand.MAIN_HAND,water);
        ActionResult<ItemStack> placed=water.getItem().onItemRightClick(water,world,player,EnumHand.MAIN_HAND);
        require(placed.getType()==EnumActionResult.SUCCESS && placed.getResult().getItem()==net.minecraft.init.Items.BUCKET,
                "Crafted bucket places the sole hydro stream source in survival");
        credit(placed.getResult()); player.setHeldItem(EnumHand.MAIN_HAND,null);
        state.setInteger("naturalStart",(int)world.getTotalWorldTime());
    }
    @SubscribeEvent public void chunk(net.minecraftforge.event.world.ChunkEvent.Load event) {
        if(paused && event.getWorld() instanceof WorldServer && event.getWorld().provider.getDimension()==0) freeze((WorldServer)event.getWorld());
    }
    private void freeze(WorldServer current) {
        for(TileEntity tile:current.tickableTileEntities) if(!held.contains(tile))held.add(tile);
        current.tickableTileEntities.clear();
        for(Entity e:new ArrayList<>(current.loadedEntityList)) if(!(e instanceof net.minecraft.entity.player.EntityPlayer)) actors.hold(e);
    }
    private void unpause() {
        keepPlantLoaded();
        for(TileEntity tile:held) if(!tile.isInvalid() && !world.tickableTileEntities.contains(tile))world.tickableTileEntities.add(tile);
        held.clear(); actors.restore(); paused=false;
        if(!world.playerEntities.contains(player))world.playerEntities.add(player);
        player.setPosition(16.5,72,16.5);
    }
    private void keepPlantLoaded() {
        net.minecraftforge.common.ForgeChunkManager.Ticket ticket=net.minecraftforge.common.ForgeChunkManager.requestTicket(
                AdvantageWorksMod.instance,world,net.minecraftforge.common.ForgeChunkManager.Type.NORMAL);
        require(ticket!=null,"A bounded test chunk ticket is required for natural entity ticks");
        for(int x=-1;x<=3;x++)for(int z=-2;z<=2;z++) {
            net.minecraftforge.common.ForgeChunkManager.forceChunk(ticket,new ChunkPos(x,z));
            world.getChunkFromChunkCoords(x,z);
        }
    }
    @SubscribeEvent public void tick(TickEvent.ServerTickEvent event) {
        if(event.phase!=TickEvent.Phase.END || world==null || !operating)return;
        try {
            ticks++;
            TileEntity furnaceTile=world.getTileEntity(STEAM_FURNACE);
            float temperature=((Number)furnaceTile.getClass().getMethod("getTemperatureLevel").invoke(furnaceTile)).floatValue()*2000;
            state.setFloat("maxSteamTemperature",Math.max(state.getFloat("maxSteamTemperature"),temperature));
            if(((cyano.poweradvantage.api.PoweredEntity)furnaceTile).getEnergy(new cyano.poweradvantage.api.ConduitType("steam"))>0)
                state.setBoolean("steamReceived",true);
            if(checkpoint && ticks==120) {
                operating=false; paused=true; freeze(world); save("machines-midcycle"); finish("MACHINE_CHECKPOINT"); return;
            }
            if(checkpoint)return;
            zone.moddev.mc.basepower.machines.fluidmachines.StillTileEntity still=
                    (zone.moddev.mc.basepower.machines.fluidmachines.StillTileEntity)world.getTileEntity(STILL);
            if(!transferred && still.getTank().getFluidAmount()==500) {
                require(still.getInputTank().getFluidAmount()==0,"Crude-oil debit is exactly twice refined output");
                NativeFluidPort receiver=new NativeFluidPort(world.getTileEntity(REFINERY),EnumFacing.UP);
                FluidStack packet=still.drain(EnumFacing.UP,500,false);
                require(packet!=null && packet.getFluid()==NativeFluids.get("refined_oil") && packet.amount==500
                        && receiver.fill(packet,false)==500,"Both physical ports agree on the exact refined packet");
                require(receiver.fill(still.drain(EnumFacing.UP,500,true),true)==500,"Conserved physical oil transfer"); transferred=true;
            }
            IInventory refinery=(IInventory)world.getTileEntity(REFINERY);
            int plastic=inventoryCount(refinery,"baseelectric:petrolplastic_ingot@0");
            if(plastic==5) {
                require(transferred && new NativeFluidPort(world.getTileEntity(REFINERY),EnumFacing.UP).amount(0)==0
                        && still.getTank().getFluidAmount()==0 && still.getInputTank().getFluidAmount()==0,
                        "1000 crude = 500 refined = five plastic, no residual or duplicate fluid");
                IInventory furnace=(IInventory)world.getTileEntity(STEAM_FURNACE);
                require(inventoryCount(furnace,"minecraft:iron_ingot@0")==1 && inventoryCount(furnace,"minecraft:iron_ore@0")==0,
                        "Crafted coal boiler and pipe powered a real steam-furnace operation");
                require(state.getBoolean("steamReceived") && state.getFloat("maxSteamTemperature")>800,
                        "Network steam produced the high-temperature boost; coal-only smelting cannot satisfy this witness");
                for(int i=0;i<refinery.getSizeInventory();i++)credit(refinery.removeStackFromSlot(i));
                require(count("baseelectric:petrolplastic_ingot@0")==state.getInteger("prePlantPlastic")+5,
                        "Five newly produced ingots, without relabelling previously traded plastic as production");
                require(((IInventory)world.getTileEntity(STILL)).getStackInSlot(0)==null
                        && ((IInventory)world.getTileEntity(BOILER)).getStackInSlot(0)==null,"Both real fuel packets were consumed");
                operating=false; paused=true; freeze(world); save("complete");
                report.addProperty("naturalMachineTicks",ticks); report.addProperty("crudeOilDebited",1000);
                report.addProperty("refinedOilProducedAndConsumed",500);report.addProperty("plasticProduced",5);
                report.addProperty("steamSmeltProduced",1); finish("PASS");
            }
            require(ticks<2200,"Live progression stalled; no output or energy is injected to rescue it");
        } catch(Exception | LinkageError error) { fail(error); }
    }
    private static int inventoryCount(IInventory inventory,String key) {
        int n=0; for(int i=0;i<inventory.getSizeInventory();i++)if(key.equals(token(inventory.getStackInSlot(i))))n+=inventory.getStackInSlot(i).stackSize; return n;
    }
    private NBTTagCompound machineState() {
        NBTTagCompound result=new NBTTagCompound();
        for(BlockPos pos:new BlockPos[]{STILL,HYDRO,REFINERY,BOILER,STEAM_FURNACE}) {
            TileEntity tile=world.getTileEntity(pos); require(tile!=null,"Retained crafted machine "+pos);
            NBTTagCompound entry=new NBTTagCompound(); entry.setTag("tile",tile.writeToNBT(new NBTTagCompound()));
            entry.setString("block",world.getBlockState(pos).toString()); result.setTag(pos.toString(),entry);
        }
        return result;
    }
    private void verifyCold() {
        require(state.getCompoundTag("machines").equals(machineState()),"Exact block and typed machine NBT must survive full process restart without a tick");
        report.addProperty("coldMachineState","EXACT_TYPED_NBT_AND_BLOCK_EQUAL");
    }
    private void save(String phase) throws Exception {
        state.setString("phase",phase); state.setInteger("actionIndex",index); state.setInteger("ticks",ticks);
        state.setBoolean("transferred",transferred);
        NBTTagList items=new NBTTagList(); for(ItemStack item:stock)items.appendTag(item.writeToNBT(new NBTTagCompound())); state.setTag("stock",items);
        NBTTagCompound merchants=new NBTTagCompound(); offers.forEach((key,value)->merchants.setTag(key,value.writeToTags())); state.setTag("offers",merchants);
        if(phase.startsWith("machines")||phase.equals("complete"))state.setTag("machines",machineState());
        CompressedStreamTools.write(state,STATE);
    }
    private void load() throws Exception {
        state=CompressedStreamTools.read(STATE); require(state!=null,"No saved progression"); stock.clear(); offers.clear();
        NBTTagList items=state.getTagList("stock",10); for(int i=0;i<items.tagCount();i++)credit(ItemStack.loadItemStackFromNBT(items.getCompoundTagAt(i)));
        index=state.getInteger("actionIndex");
        NBTTagCompound merchants=state.getCompoundTag("offers");
        for(String key:merchants.getKeySet())offers.put(key,new MerchantRecipe(merchants.getCompoundTag(key)));
        report.addProperty("resumedFrom",state.getString("phase")); report.add("resumedStock",counts());
        report.add("resumedStockTyped",typedStock());
    }
    private void finish(String status) throws Exception {
        report.addProperty("status",status); report.addProperty("actionIndex",index);report.add("stock",counts());
        report.add("stockTyped",typedStock());
        report.addProperty("maximumSteamFurnaceTemperature",state.getFloat("maxSteamTemperature"));
        report.addProperty("steamReceived",state.getBoolean("steamReceived"));
        report.addProperty("waterSourcesCollectedMb",state.getInteger("collected/water"));
        write(new File("advantage-works-results/survival-"+label+".json"),report);
        System.out.println("ADVSURVIVAL COMPLETE "+label+" "+status);
    }
    private void fail(Throwable error) {
        operating=false; paused=true; if(world!=null)freeze(world);
        if(report==null)report=new JsonObject(); report.addProperty("status","FAIL");report.addProperty("failure",error.toString());
        report.addProperty("actionIndex",index);
        if(world!=null && world.getTileEntity(STILL)!=null && world.getTileEntity(HYDRO)!=null
                && world.getTileEntity(REFINERY)!=null && world.getTileEntity(BOILER)!=null && world.getTileEntity(STEAM_FURNACE)!=null)
            report.addProperty("failedMachineState",machineState().toString());
        try { write(new File("advantage-works-results/survival-"+label+".json"),report); }
        catch(Exception writeFailure) { error.addSuppressed(writeFailure); }
        error.printStackTrace();System.out.println("ADVSURVIVAL COMPLETE "+label+" FAIL "+error);
    }

    static JsonObject economy(WorldServer world) {
        JsonObject result=new JsonObject(),durability=new JsonObject(); JsonArray rows=new JsonArray();
        for(Item item:Item.REGISTRY) if(String.valueOf(item.getRegistryName()).contains("crackhammer"))durability.addProperty(token(new ItemStack(item)),item.getMaxDamage());
        result.add("toolDurability",durability);
        String[] professions={"farmer","librarian","smith"};int[] careers={0,0,2};
        for(int p=0;p<professions.length;p++) {
            VillagerRegistry.VillagerProfession profession=VillagerRegistry.instance().getRegistry().getValue(new ResourceLocation("minecraft:"+professions[p]));
            VillagerRegistry.VillagerCareer career=profession.getCareer(careers[p]);
            for(int level=0;level<3;level++) {
                List<EntityVillager.ITradeList> trades=career.getTrades(level); if(trades==null)continue;
                for(int entry=0;entry<trades.size();entry++) {
                    MerchantRecipeList list=new MerchantRecipeList();
                    trades.get(entry).modifyMerchantRecipeList(list,new Random(1871));
                    for(int output=0;output<list.size();output++) {
                        MerchantRecipe offer=list.get(output);String item=token(offer.getItemToSell());
                        if(item==null || !item.startsWith("base") || offer.getItemToSell().hasTagCompound())continue;
                        JsonObject row=new JsonObject();row.addProperty("id",professions[p]+"/"+careers[p]+"/"+level+"/"+entry+"/"+output);
                        row.addProperty("profession",professions[p]);row.addProperty("career",careers[p]);row.addProperty("level",level);row.addProperty("entry",entry);row.addProperty("offer",output);
                        row.addProperty("output",item);row.addProperty("count",offer.getItemToSell().stackSize);
                        row.addProperty("input",token(offer.getItemToBuy()));row.addProperty("price",offer.getItemToBuy().stackSize);
                        if(!empty(offer.getSecondItemToBuy())) { row.addProperty("secondInput",token(offer.getSecondItemToBuy()));row.addProperty("secondPrice",offer.getSecondItemToBuy().stackSize); }
                        row.addProperty("maxUses",offer.getMaxTradeUses());rows.add(row);
                    }
                }
            }
        }
        result.add("trades",rows);
        result.addProperty("additionalLootTablesLoaded",net.minecraftforge.fml.common.Loader.isModLoaded("additional-loot-tables"));
        JsonArray loot=new JsonArray();int sampled=0,nonempty=0;
        try {
            if(Class.forName("zone.moddev.mc.basepower.PowerAdvantage").getField("recipeMode").get(null).toString().equals("APOCALYPTIC")) {
                BlockPos position=new BlockPos(40,70,40);
                for(String table:new String[]{"abandoned_mineshaft","simple_dungeon","village_blacksmith","stronghold_corridor","stronghold_crossing","end_city_treasure","nether_bridge"})
                    for(int seed=1;seed<=64;seed++) {
                        world.setBlockState(position,net.minecraft.init.Blocks.CHEST.getDefaultState(),3);
                        TileEntityChest chest=(TileEntityChest)world.getTileEntity(position);
                        chest.setLootTable(new ResourceLocation("minecraft:chests/"+table),seed);
                        int found=0;
                        for(int slot=0;slot<chest.getSizeInventory();slot++) {
                            ItemStack item=chest.removeStackFromSlot(slot);
                            if(empty(item))continue;
                            found+=item.stackSize;
                            if(token(item).startsWith("basepower:")||token(item).startsWith("basesteam:")||token(item).startsWith("baseelectric:")) {
                                JsonObject entry=new JsonObject();entry.addProperty("table",table);entry.addProperty("seed",seed);
                                entry.addProperty("item",token(item));entry.addProperty("count",item.stackSize);loot.add(entry);
                            }
                        }
                        require(chest.getLootTable()==null,"Native chest must consume its deferred loot table exactly once");
                        if(found>0)nonempty++;sampled++;world.setBlockToAir(position);
                    }
                require(nonempty>0,"Actual native chests need a positive vanilla loot control");
            }
        } catch(ReflectiveOperationException error) { throw new IllegalStateException(error); }
        result.addProperty("physicalChestsSampled",sampled);result.addProperty("nonemptyPhysicalChests",nonempty);
        result.add("physicalChestAdvantageLoot",loot);
        return result;
    }
    private void trade(JsonObject action,JsonObject receipt) {
        JsonObject descriptor=action.getAsJsonObject("trade");String id=descriptor.get("id").getAsString();
        EntityVillager villager=new EntityVillager(world);
        VillagerRegistry.VillagerProfession profession=VillagerRegistry.instance().getRegistry().getValue(new ResourceLocation("minecraft:"+descriptor.get("profession").getAsString()));
        MerchantRecipe offer=offers.get(id);
        if(offer==null) {
            MerchantRecipeList generated=new MerchantRecipeList();
            profession.getCareer(descriptor.get("career").getAsInt()).getTrades(descriptor.get("level").getAsInt()).get(descriptor.get("entry").getAsInt())
                    .modifyMerchantRecipeList(generated,new Random(1871));
            offer=generated.get(descriptor.get("offer").getAsInt()); offers.put(id,offer);
        }
        require(!offer.isRecipeDisabled() && token(offer.getItemToSell()).equals(action.get("output").getAsString())
                && offer.getItemToSell().stackSize==action.get("count").getAsInt()
                && token(offer.getItemToBuy()).equals(descriptor.get("input").getAsString())
                && offer.getItemToBuy().stackSize==descriptor.get("price").getAsInt(),"Native offer matches planned price and stock, without free restock");
        MerchantRecipeList list=new MerchantRecipeList();list.add(offer);
        villager.setProfession(profession);
        NBTTagCompound merchant=new NBTTagCompound();villager.writeEntityToNBT(merchant);
        merchant.setTag("Offers",list.getRecipiesAsTags());
        merchant.setInteger("Career",descriptor.get("career").getAsInt()+1);
        merchant.setInteger("CareerLevel",descriptor.get("level").getAsInt()+1);
        // The packet-only offer setter is client-only in 1.10; the server reads offers from entity NBT.
        villager.readEntityFromNBT(merchant);villager.setCustomer(player);
        require(villager.getRecipes(player).size()==1,"One registered finite offer restored by the native server reader");
        offer=villager.getRecipes(player).get(0);offers.put(id,offer);
        InventoryMerchant inventory=new InventoryMerchant(player,villager);
        inventory.setInventorySlotContents(0,stack("minecraft:dirt@0",1));
        require(empty(inventory.getStackInSlot(2)),"Merchant rejects wrong currency"); inventory.clear();
        inventory.setInventorySlotContents(0,take(descriptor.get("input").getAsString(),descriptor.get("price").getAsInt()));
        if(descriptor.has("secondInput"))inventory.setInventorySlotContents(1,take(descriptor.get("secondInput").getAsString(),descriptor.get("secondPrice").getAsInt()));
        inventory.resetRecipeAndSlots();int uses=offer.getToolUses();
        require(ItemStack.areItemStacksEqual(inventory.getStackInSlot(2),offer.getItemToSell()),"Native merchant output");
        SlotMerchantResult slot=new SlotMerchantResult(player,villager,inventory,2,0,0);
        ItemStack taken=slot.decrStackSize(offer.getItemToSell().stackSize);slot.onPickupFromSlot(player,taken);
        require(offer.getToolUses()==uses+1 && empty(inventory.getStackInSlot(0)) && empty(inventory.getStackInSlot(1))
                && token(taken).equals(action.get("output").getAsString()) && taken.stackSize==action.get("count").getAsInt(),
                "Real merchant transaction consumes exact currency, outputs exact goods, and advances offer use");
        credit(taken);receipt.addProperty("offer",id);receipt.addProperty("usesAfter",offer.getToolUses());receipt.addProperty("nativeConsumption","SlotMerchantResult");
    }
}
