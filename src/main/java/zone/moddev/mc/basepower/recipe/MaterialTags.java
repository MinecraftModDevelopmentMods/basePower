package zone.moddev.mc.basepower.recipe;

/** Config-dependent aliases resolved by the server's native tag reload. */
public final class MaterialTags {
    private MaterialTags() {}

    public static void registerPack(net.minecraftforge.event.AddPackFindersEvent event) {
        if (event.getPackType() != net.minecraft.server.packs.PackType.SERVER_DATA) return;
        event.addRepositorySource((consumer, constructor) -> {
            net.minecraft.server.packs.repository.Pack pack = net.minecraft.server.packs.repository.Pack.create(
                    "basepower:material_policy", true, MaterialPolicyPack::new, constructor,
                    net.minecraft.server.packs.repository.Pack.Position.BOTTOM,
                    net.minecraft.server.packs.repository.PackSource.BUILT_IN);
            if (pack != null) consumer.accept(pack);
        });
    }

}
