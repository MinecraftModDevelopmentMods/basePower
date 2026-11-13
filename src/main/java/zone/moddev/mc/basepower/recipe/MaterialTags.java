package zone.moddev.mc.basepower.recipe;

import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackMetadataResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraftforge.event.AddPackFindersEvent;

/** Config-dependent aliases resolved by the server's native tag reload. */
public final class MaterialTags {
    private MaterialTags() {}

    public static void registerPack(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.SERVER_DATA) return;
        event.addRepositorySource(consumer -> {
            Pack pack = Pack.readMetaAndCreate(MaterialPolicyPack.LOCATION, new Pack.ResourcesSupplier() {
                @Override public PackMetadataResources openMetadata(PackLocationInfo location) {
                    return new MaterialPolicyPack();
                }
                @Override public java.util.stream.Stream<PackResources> openResources(PackLocationInfo location, Pack.Metadata metadata) {
                    return java.util.stream.Stream.of(new MaterialPolicyPack());
                }
            }, event.getPackType(), new PackSelectionConfig(true, Pack.Position.BOTTOM, false));
            if (pack != null) consumer.accept(pack);
        });
    }

}
