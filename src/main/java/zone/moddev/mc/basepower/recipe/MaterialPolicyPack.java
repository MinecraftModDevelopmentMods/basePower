package zone.moddev.mc.basepower.recipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraftforge.fml.ModList;
import zone.moddev.mc.basepower.PowerAdvantage;
import zone.moddev.mc.basepower.RecipeMode;

/** Additive native tags: server policy is resolved and synchronized by vanilla. */
final class MaterialPolicyPack implements PackResources {
    static final String ID = "basepower:material_policy";
    static final String NAME = "Power Advantage material aliases";
    static final PackLocationInfo LOCATION =
            new PackLocationInfo(ID, Component.literal(NAME), PackSource.BUILT_IN, Optional.empty());
    private final Map<ResourceLocation, String> resources = new LinkedHashMap<>();

    MaterialPolicyPack() {
        // Pack instances are created for each reload, after common config loading.
        this(PowerAdvantage.plasticIsAlsoRubber,
                PowerAdvantage.recipeMode == RecipeMode.NORMAL && ModList.get().isLoaded("baseelectric"));
    }

    MaterialPolicyPack(boolean plasticIsRubber, boolean normalSolder) {
        JsonArray rubber = new JsonArray();
        if (plasticIsRubber) rubber.add("basepower:bioplastic_ingot");
        put("rubber", rubber);
        put("ingots/rubber", rubber);
        JsonArray solder = new JsonArray();
        if (normalSolder) {
            for (String metal : new String[] {"lead", "tin", "silver"}) {
                JsonObject optional = new JsonObject();
                optional.addProperty("id", "#forge:ingots/" + metal);
                optional.addProperty("required", false);
                solder.add(optional);
            }
        }
        put("solder", solder);
    }

    private void put(String path, JsonArray values) {
        JsonObject tag = new JsonObject();
        tag.addProperty("replace", false);
        tag.add("values", values);
        resources.put(ResourceLocation.fromNamespaceAndPath("forge", "tags/item/" + path + ".json"), tag.toString());
    }

    @Override public IoSupplier<InputStream> getRootResource(String... names) { return null; }

    @Override public IoSupplier<InputStream> getResource(PackType type, ResourceLocation id) {
        if (type != PackType.SERVER_DATA || !resources.containsKey(id)) return null;
        return () -> new ByteArrayInputStream(resources.get(id).getBytes(StandardCharsets.UTF_8));
    }

    @Override public void listResources(PackType type, String namespace, String prefix, ResourceOutput output) {
        if (type != PackType.SERVER_DATA) return;
        for (ResourceLocation id : resources.keySet()) {
            if (namespace.equals(id.getNamespace()) && id.getPath().startsWith(prefix + "/")) {
                output.accept(id, getResource(type, id));
            }
        }
    }
    @Override public Set<String> getNamespaces(PackType type) {
        return type == PackType.SERVER_DATA ? Collections.singleton("forge") : Collections.emptySet();
    }
    @Override @SuppressWarnings("unchecked")
    public <T> T getMetadataSection(MetadataSectionSerializer<T> serializer) {
        return serializer == PackMetadataSection.TYPE
                ? (T)new PackMetadataSection(Component.literal(NAME), 48, Optional.empty()) : null;
    }
    @Override public PackLocationInfo location() { return LOCATION; }
    @Override public void close() {}
}
