package zone.moddev.mc.basepower.init;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Consumer;

import cyano.poweradvantage.api.ConduitType;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;

import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.fluids.ForgeFlowingFluid;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegisterEvent;
import zone.moddev.mc.basepower.PowerAdvantage;

/** Native Forge fluids and their persistent PowerAdvantage conduit identities. */
public final class Fluids {
    public static final ConduitType fluidConduit_general = new ConduitType("fluid");
    private static final Map<Fluid, ConduitType> FLUID_TYPES = new IdentityHashMap<>();

    public static ConduitType fluidConduit_water;
    public static ConduitType fluidConduit_lava;
    public static PowerFluid.Source crude_oil;
    public static PowerFluid.Flowing flowing_crude_oil;
    public static PowerFluid.Source refined_oil;
    public static PowerFluid.Flowing flowing_refined_oil;
    private static FluidType crudeOilType;
    private static FluidType refinedOilType;

    private static boolean initialized;

    private Fluids() {}

    public static synchronized void init() {
        if (initialized) return;
        fluidConduit_water = registerNewFluid(net.minecraft.world.level.material.Fluids.WATER);
        fluidConduit_lava = registerNewFluid(net.minecraft.world.level.material.Fluids.LAVA);
        initialized = true;
    }
    public static void registerFluids(RegisterEvent event) {
        if (ForgeRegistries.Keys.FLUID_TYPES.equals(event.getRegistryKey())) {
            crudeOilType = oilType("crude_oil", 850, 6000);
            refinedOilType = oilType("refined_oil", 720, 1000);
            event.register(ForgeRegistries.Keys.FLUID_TYPES, id("crude_oil"), () -> crudeOilType);
            event.register(ForgeRegistries.Keys.FLUID_TYPES, id("refined_oil"), () -> refinedOilType);
        }
        if (!ForgeRegistries.Keys.FLUIDS.equals(event.getRegistryKey())) return;
        prepareOilFluids();

        event.register(ForgeRegistries.Keys.FLUIDS, id("flowing_crude_oil"), () -> flowing_crude_oil);
        event.register(ForgeRegistries.Keys.FLUIDS, id("crude_oil"), () -> crude_oil);
        event.register(ForgeRegistries.Keys.FLUIDS, id("flowing_refined_oil"), () -> flowing_refined_oil);
        event.register(ForgeRegistries.Keys.FLUIDS, id("refined_oil"), () -> refined_oil);
        registerNewFluid(crude_oil);
        registerNewFluid(flowing_crude_oil);
        registerNewFluid(refined_oil);
        registerNewFluid(flowing_refined_oil);
    }

    static synchronized void prepareOilFluids() {
        if (crude_oil != null) return;
        flowing_crude_oil = new PowerFluid.Flowing(Kind.CRUDE);
        crude_oil = new PowerFluid.Source(Kind.CRUDE);
        flowing_refined_oil = new PowerFluid.Flowing(Kind.REFINED);
        refined_oil = new PowerFluid.Source(Kind.REFINED);
    }

    private static Identifier id(String name) {
        return Identifier.fromNamespaceAndPath(PowerAdvantage.MODID, name);
    }

    private static FluidType oilType(String name, int density, int viscosity) {
        return new FluidType(FluidType.Properties.create().descriptionId("fluid.basepower." + name)
                .density(density).viscosity(viscosity).temperature(300)) {
            @Override public void initializeClient(Consumer<IClientFluidTypeExtensions> consumer) {
                consumer.accept(new IClientFluidTypeExtensions() {
                    @Override public Identifier getStillTexture() { return id("blocks/" + name + "_still"); }
                    @Override public Identifier getFlowingTexture() { return id("blocks/" + name + "_flow"); }
                });
            }
        };
    }

    public static ConduitType registerNewFluid(Fluid fluid) {
        Identifier id = ForgeRegistries.FLUIDS.getKey(fluid);
        ConduitType type = new ConduitType(id == null ? "unknown_fluid" : id.toString());
        FLUID_TYPES.put(fluid, type);
        return type;
    }

    public static boolean isFluidType(ConduitType type) {
        return type.equals(fluidConduit_general) || FLUID_TYPES.containsValue(type);
    }

    public static ConduitType fluidToConduitType(Fluid fluid) {
        ConduitType type = FLUID_TYPES.get(fluid);
        return type == null ? registerNewFluid(fluid) : type;
    }

    public static Fluid conduitTypeToFluid(ConduitType type) {
        for (Map.Entry<Fluid, ConduitType> entry : FLUID_TYPES.entrySet()) {
            if (ConduitType.areSameType(entry.getValue(), type)) return entry.getKey();
        }
        return null;
    }

    public static Fluid byName(String name) {
        if (name == null || name.trim().isEmpty()) return null;
        Identifier id = name.indexOf(':') >= 0 ? Identifier.parse(name)
                : Identifier.fromNamespaceAndPath(PowerAdvantage.MODID, name);
        Fluid fluid = registeredFluid(id);
        if (fluid == null && name.indexOf(':') < 0) {
            fluid = registeredFluid(Identifier.fromNamespaceAndPath("minecraft", name));
        }
        return fluid;
    }

    private static Fluid registeredFluid(Identifier id) {
        // Forge's defaulted fluid registry returns EMPTY, not null, for unknown names.
        // Do not let a missing basepower:water hide the legacy vanilla fallback.
        return net.minecraftforge.registries.ForgeRegistries.FLUIDS.containsKey(id)
                ? net.minecraftforge.registries.ForgeRegistries.FLUIDS.getValue(id) : null;
    }

    public enum Kind { CRUDE, REFINED }

    public abstract static class PowerFluid extends ForgeFlowingFluid {
        private final Kind kind;

        protected PowerFluid(Kind kind) {
            super(properties(kind));
            this.kind = kind;
        }
        public Kind getKind() { return kind; }

        private static ForgeFlowingFluid.Properties properties(Kind kind) {
            return new ForgeFlowingFluid.Properties(
                    () -> kind == Kind.CRUDE ? crudeOilType : refinedOilType,
                    () -> kind == Kind.CRUDE ? crude_oil : refined_oil,
                    () -> kind == Kind.CRUDE ? flowing_crude_oil : flowing_refined_oil)
                    .bucket(() -> kind == Kind.CRUDE ? Items.crude_oil_bucket : Items.refined_oil_bucket)
                    .block(() -> kind == Kind.CRUDE ? Blocks.crude_oil_block : Blocks.refined_oil_block)
                    .slopeFindDistance(4).levelDecreasePerBlock(1)
                    .tickRate(kind == Kind.CRUDE ? 15 : 10).explosionResistance(100.0F);
        }

        public static final class Flowing extends PowerFluid {
            public Flowing(Kind kind) { super(kind); }
            @Override protected void createFluidStateDefinition(StateDefinition.Builder<Fluid, FluidState> builder) {
                super.createFluidStateDefinition(builder);
                builder.add(LEVEL);
            }
            @Override public int getAmount(FluidState state) { return state.getValue(LEVEL); }
            @Override public boolean isSource(FluidState state) { return false; }
        }

        public static final class Source extends PowerFluid {
            public Source(Kind kind) { super(kind); }
            @Override public int getAmount(FluidState state) { return 8; }
            @Override public boolean isSource(FluidState state) { return true; }
        }
    }
}
