package zone.moddev.mc.basepower.recipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraftforge.fml.ModList;
import zone.moddev.mc.basepower.PowerAdvantage;
import zone.moddev.mc.basepower.RecipeMode;

/** Additive native tags: server policy is resolved and synchronized by vanilla. */
final class MaterialPolicyPack implements PackResources {
    private static final String NAME = "Power Advantage material aliases";
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
        resources.put(new ResourceLocation("forge", "tags/items/" + path + ".json"), tag.toString());
    }

    @Override public InputStream getRootResource(String name) throws FileNotFoundException {
        throw new FileNotFoundException(name);
    }

    @Override public InputStream getResource(PackType type, ResourceLocation id) throws FileNotFoundException {
        if (!hasResource(type, id)) throw new FileNotFoundException(id.toString());
        return new ByteArrayInputStream(resources.get(id).getBytes(StandardCharsets.UTF_8));
    }

    @Override public Collection<ResourceLocation> getResources(PackType type, String namespace,
            String prefix, int depth, Predicate<String> filter) {
        if (type != PackType.SERVER_DATA) return Collections.emptyList();
        java.util.List<ResourceLocation> found = new java.util.ArrayList<>();
        for (ResourceLocation id : resources.keySet()) {
            String path = id.getPath();
            if (namespace.equals(id.getNamespace()) && path.startsWith(prefix + "/")
                    && filter.test(path.substring(path.lastIndexOf('/') + 1))) found.add(id);
        }
        return found;
    }

    @Override public boolean hasResource(PackType type, ResourceLocation id) {
        return type == PackType.SERVER_DATA && resources.containsKey(id);
    }
    @Override public Set<String> getNamespaces(PackType type) {
        return type == PackType.SERVER_DATA ? Collections.singleton("forge") : Collections.emptySet();
    }
    @Override @SuppressWarnings("unchecked")
    public <T> T getMetadataSection(MetadataSectionSerializer<T> serializer) {
        return serializer == PackMetadataSection.SERIALIZER
                ? (T)new PackMetadataSection(new TextComponent(NAME), 9) : null;
    }
    @Override public String getName() { return NAME; }
    @Override public void close() {}
}
