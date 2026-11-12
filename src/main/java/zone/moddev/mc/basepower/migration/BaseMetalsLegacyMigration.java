package zone.moddev.mc.basepower.migration;

import cyano.poweradvantage.api.migration.LegacyMigrationApi;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;
import zone.moddev.mc.basemetals.api.migration.Legacy112MigrationExtension;

/** Loaded only when Base Metals is present. */
public final class BaseMetalsLegacyMigration implements Legacy112MigrationExtension {
    private BaseMetalsLegacyMigration() {}

    public static void register() {
        Legacy112MigrationExtension.register(Identifier.fromNamespaceAndPath("basepower", "legacy_world"),
                new BaseMetalsLegacyMigration());
    }

    @Override
    public void prepareWorld(CompoundTag levelSnapshot) {
        LegacyWorldDataHook.prepareLegacyEnchantmentContext(levelSnapshot);
    }

    @Override
    public boolean prepareData(CompoundTag payload, int dataVersion, Payload kind) {
        return LegacyWorldDataHook.prepareExternalPayload(payload, kind == Payload.PLAYER);
    }

    @Override
    public void beforeChunkFlattening(CompoundTag chunk) {
        LegacyWorldDataHook.rememberLegacyChunk(chunk);
        LegacyWorldDataHook.prepareLegacyEntities(chunk);
    }

    @Override
    public void afterPlayerFlattening(CompoundTag preparedSource, CompoundTag result) {
        LegacyPlayerDataFixes.preserveNames(preparedSource, result);
    }

    @Override
    public BlockState decodeBlockState(Identifier legacyId, int metadata) {
        if (!LegacyMigrationApi.handlesNamespace(legacyId.getNamespace())) return null;
        Identifier target = LegacyMigrationApi.blockTarget(legacyId);
        if (!ForgeRegistries.BLOCKS.containsKey(target)) {
            throw new IllegalStateException("No replacement for saved Advantage block " + legacyId + " (expected " + target + ")");
        }
        return LegacyMigrationApi.decodeBlockState(legacyId, ForgeRegistries.BLOCKS.getValue(target), metadata);
    }
}
