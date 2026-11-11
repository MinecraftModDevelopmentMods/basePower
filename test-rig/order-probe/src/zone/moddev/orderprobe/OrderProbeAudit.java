package zone.moddev.orderprobe;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Audits exported woven bytecode. It never loads or changes a game target class. */
public final class OrderProbeAudit {
    private static final String REGION = "net/minecraft/world/level/chunk/storage/SimpleRegionStorage";
    private static final String LEVEL = "net/minecraft/world/level/storage/LevelStorageSource";
    private static final String WORLDGEN = "net/minecraft/server/level/WorldGenRegion";
    private static final String TAG = "Lnet/minecraft/nbt/CompoundTag;";

    private static MethodNode method(ClassNode node, String name, String descriptor) {
        List<MethodNode> found = node.methods.stream().filter(row -> row.name.equals(name)
                && (descriptor == null || row.desc.equals(descriptor))).toList();
        ProbeGuard.require(found.size() == 1, "missing/ambiguous method " + node.name + "." + name);
        return found.getFirst();
    }

    private static List<AbstractInsnNode> instructions(MethodNode method) {
        return Arrays.stream(method.instructions.toArray()).filter(row -> row.getOpcode() >= 0).toList();
    }

    private static String codeHash(MethodNode method, String owner) throws Exception {
        Map<LabelNode, Integer> labels = new IdentityHashMap<>();
        int index = 0;
        for (AbstractInsnNode row : method.instructions) {
            if (row instanceof LabelNode label) labels.put(label, index);
            if (row.getOpcode() >= 0) index++;
        }
        List<Object> code = new ArrayList<>();
        for (AbstractInsnNode row : instructions(method)) {
            List<Object> item = new ArrayList<>();
            item.add(row.getOpcode());
            if (row instanceof MethodInsnNode call) item.addAll(List.of(call.owner.equals(owner) ? "SELF" : call.owner, call.name, call.desc, call.itf));
            else if (row instanceof FieldInsnNode field) item.addAll(List.of(field.owner.equals(owner) ? "SELF" : field.owner, field.name, field.desc));
            else if (row instanceof VarInsnNode variable) item.add(variable.var);
            else if (row instanceof IntInsnNode integer) item.add(integer.operand);
            else if (row instanceof TypeInsnNode type) item.add(type.desc.equals(owner) ? "SELF" : type.desc);
            else if (row instanceof JumpInsnNode jump) item.add(labels.get(jump.label));
            else if (row instanceof LdcInsnNode constant) item.add(List.of(constant.cst.getClass().getName(), constant.cst.toString()));
            else if (row instanceof IincInsnNode increment) item.addAll(List.of(increment.var, increment.incr));
            else if (!(row instanceof InsnNode)) throw new AssertionError("Unreviewed handler instruction " + row.getClass());
            code.add(item);
        }
        ProbeGuard.require(method.tryCatchBlocks.isEmpty(), "unreviewed handler exception path");
        return ProbeGuard.sha(new Gson().toJson(code).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static ClassNode exported(Path exports, String name) throws Exception {
        Path file = exports.resolve(name + ".class");
        ClassNode node = ProbeGuard.node(Files.readAllBytes(file));
        ProbeGuard.require(node.name.equals(name), "export class identity mismatch");
        return node;
    }

    private static MethodNode handler(JsonObject pins, Path mods, ClassNode target, String mod, String suffix) throws Exception {
        String mixinClass = (mod.equals("basepower") ? "zone.moddev.mc.basepower.mixin." : "zone.moddev.mc.basemetals.mixin.")
                + target.name.substring(target.name.lastIndexOf('/') + 1) + "Mixin";
        String name = mod + "$" + suffix;
        List<MethodNode> matches = target.methods.stream().filter(row -> row.name.endsWith(name)).toList();
        ProbeGuard.require(matches.size() == 1, "missing/duplicate woven handler " + name);
        MethodNode actual = matches.getFirst();
        AnnotationNode merged = ProbeGuard.annotation(actual.visibleAnnotations, "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;");
        if (merged == null) merged = ProbeGuard.annotation(actual.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;");
        ProbeGuard.require(mixinClass.equals(ProbeGuard.value(merged, "mixin")), "woven handler origin differs " + name);
        ProbeGuard.require(pins.getAsJsonObject("priorities").get(mod + ".mixins.json").getAsInt()
                == ((Number) ProbeGuard.value(merged, "priority")).intValue(), "woven priority differs " + name);
        Path jar = mods.resolve(pins.getAsJsonObject("mods").getAsJsonObject(mod).get("file").getAsString());
        ClassNode originalClass = ProbeGuard.node(ProbeGuard.entry(jar, mixinClass.replace('.', '/') + ".class"));
        MethodNode original = method(originalClass, name, actual.desc);
        if (suffix.equals("protectTerrain")) {
            List<AbstractInsnNode> source = instructions(original);
            ProbeGuard.require(source.get(0) instanceof VarInsnNode receiver && receiver.getOpcode() == Opcodes.ALOAD && receiver.var == 0
                    && source.get(1) instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST && cast.desc.equals(target.name),
                    "reviewed worldgen receiver cast changed");
            // MixinTargetContext.transformTypeNode removes precisely this redundant
            // ALOAD 0 / CHECKCAST target. Transform the expected source, not observed code.
            original.instructions.remove(source.get(1));
        }
        ProbeGuard.require(codeHash(original, originalClass.name).equals(codeHash(actual, target.name)), "woven handler body changed " + name);
        if (!suffix.equals("protectTerrain")) {
            ProbeGuard.require(instructions(actual).stream().noneMatch(row -> row instanceof MethodInsnNode call
                    && (call.name.equals("cancel") || call.name.equals("setReturnValue"))), "cooperative handler cancels " + name);
        }
        return actual;
    }

    private static List<String> sequence(MethodNode method, Map<String, String> names) {
        List<String> result = new ArrayList<>();
        for (AbstractInsnNode row : instructions(method)) {
            if (row instanceof MethodInsnNode call && names.containsKey(call.name)) result.add(names.get(call.name));
        }
        return result;
    }

    private static void pair(List<String> values, String context) {
        ProbeGuard.require(values.size() == 2 && new java.util.HashSet<>(values).equals(java.util.Set.of("basepower", "basemetals")),
                "missing/duplicate callback pair " + context + ": " + values);
    }

    private static boolean dominates(MethodNode method, int earlier, int later) {
        List<AbstractInsnNode> code = instructions(method);
        Map<LabelNode, Integer> labels = new IdentityHashMap<>();
        int index = 0;
        for (AbstractInsnNode row : method.instructions) {
            if (row instanceof LabelNode label) labels.put(label, index);
            if (row.getOpcode() >= 0) index++;
        }
        // If later is reachable while removing earlier, earlier does not dominate it.
        java.util.Set<Integer> visited = new java.util.HashSet<>();
        java.util.ArrayDeque<Integer> pending = new java.util.ArrayDeque<>();
        pending.add(0);
        while (!pending.isEmpty()) {
            int position = pending.removeFirst();
            if (position == earlier || position >= code.size() || !visited.add(position)) continue;
            if (position == later) return false;
            AbstractInsnNode row = code.get(position);
            for (TryCatchBlockNode handler : method.tryCatchBlocks) {
                if (position >= labels.get(handler.start) && position < labels.get(handler.end)) pending.add(labels.get(handler.handler));
            }
            if (row instanceof JumpInsnNode jump) {
                pending.add(labels.get(jump.label));
                if (row.getOpcode() != Opcodes.GOTO) pending.add(position + 1);
            } else if (row instanceof TableSwitchInsnNode table) {
                pending.add(labels.get(table.dflt)); table.labels.forEach(label -> pending.add(labels.get(label)));
            } else if (row instanceof LookupSwitchInsnNode lookup) {
                pending.add(labels.get(lookup.dflt)); lookup.labels.forEach(label -> pending.add(labels.get(label)));
            } else if (row.getOpcode() != Opcodes.ATHROW && (row.getOpcode() < Opcodes.IRETURN || row.getOpcode() > Opcodes.RETURN)) {
                pending.add(position + 1);
            }
        }
        return true;
    }

    public static JsonObject audit(JsonObject pins, Path runtime, Path exports, Path nativeClasses) throws Exception {
        ProbeGuard.verifyProduction(pins, runtime.resolve("mods"));
        ClassNode region = exported(exports, REGION), level = exported(exports, LEVEL), worldgen = exported(exports, WORLDGEN);
        Map<String, String> heads = new LinkedHashMap<>(), returns = new LinkedHashMap<>(), worldHeads = new LinkedHashMap<>(), terrain = new LinkedHashMap<>();
        for (String mod : List.of("basepower", "basemetals")) {
            heads.put(handler(pins, runtime.resolve("mods"), region, mod, "prepareChunk").name, mod);
            returns.put(handler(pins, runtime.resolve("mods"), region, mod, "finishChunk").name, mod);
            worldHeads.put(handler(pins, runtime.resolve("mods"), level, mod, "prepareWorld").name, mod);
            terrain.put(handler(pins, runtime.resolve("mods"), worldgen, mod, "protectTerrain").name, mod);
        }
        MethodNode staging = handler(pins, runtime.resolve("mods"), region, "basepower", "prepareInventories");
        MethodNode upgrade = method(region, "upgradeChunkTag", "(" + TAG + "I" + TAG + ")" + TAG);
        List<String> headOrder = sequence(upgrade, heads);
        pair(headOrder, "region heads");
        List<AbstractInsnNode> code = instructions(upgrade);
        ClassNode nativeRegion = exported(nativeClasses, REGION);
        MethodNode nativeUpgrade = method(nativeRegion, "upgradeChunkTag", upgrade.desc);
        List<String> nativeExceptions = nativeUpgrade.tryCatchBlocks.stream().map(row -> row.type).toList();
        ProbeGuard.require(nativeExceptions.equals(List.of("java/lang/Exception"))
                && upgrade.tryCatchBlocks.stream().map(row -> row.type).toList().equals(nativeExceptions),
                "native exception-handler coverage changed");
        int stagedAt = -1, getVersionAt = -1, lastHead = -1;
        List<Integer> headSites = new ArrayList<>(), returnSites = new ArrayList<>();
        List<List<String>> returned = new ArrayList<>();
        List<String> pendingReturns = new ArrayList<>();
        for (int i = 0; i < code.size(); i++) {
            AbstractInsnNode row = code.get(i);
            if (row instanceof MethodInsnNode call) {
                if (heads.containsKey(call.name)) { lastHead = i; headSites.add(i); }
                if (call.name.equals(staging.name)) { ProbeGuard.require(stagedAt < 0, "duplicate inventory staging"); stagedAt = i; }
                if (call.owner.equals("net/minecraft/nbt/NbtUtils") && call.name.equals("getDataVersion")) {
                    ProbeGuard.require(getVersionAt < 0, "duplicate native data-version invocation"); getVersionAt = i;
                }
                if (returns.containsKey(call.name)) { pendingReturns.add(returns.get(call.name)); returnSites.add(i); }
                ProbeGuard.require(!call.name.equals("isCancelled"), "unexpected cancellable branch in cooperative upgrade target");
            }
            if (row.getOpcode() == Opcodes.ARETURN) {
                pair(pendingReturns, "native return " + returned.size());
                for (int site : headSites) ProbeGuard.require(dominates(upgrade, site, i), "HEAD callback can be bypassed");
                for (int site : returnSites) ProbeGuard.require(dominates(upgrade, site, i), "RETURN callback can be bypassed");
                ProbeGuard.require(dominates(upgrade, returnSites.getFirst(), returnSites.getLast()), "return callbacks can execute out of order");
                returned.add(List.copyOf(pendingReturns)); pendingReturns.clear();
                returnSites.clear();
            }
        }
        ProbeGuard.require(stagedAt > lastHead && getVersionAt > stagedAt, "inventory staging is not after both HEAD callbacks and before native DFU version read");
        for (int site : headSites) ProbeGuard.require(dominates(upgrade, site, stagedAt), "HEAD callback can be bypassed before staging");
        ProbeGuard.require(dominates(upgrade, stagedAt, getVersionAt), "inventory staging can be bypassed");
        ProbeGuard.require(dominates(upgrade, headSites.getFirst(), headSites.getLast()), "HEAD callbacks can execute out of order");
        long nativeReturns = instructions(nativeUpgrade).stream().filter(row -> row.getOpcode() == Opcodes.ARETURN).count();
        ProbeGuard.require(nativeReturns == 2 && returned.size() == nativeReturns && pendingReturns.isEmpty(), "native return coverage changed");
        List<String> levelOrder = sequence(method(level, "readLevelDataTagFixed", null), worldHeads);
        List<String> worldgenOrder = sequence(method(worldgen, "ensureCanWrite", null), terrain);
        pair(levelOrder, "world heads"); pair(worldgenOrder, "worldgen heads");
        JsonObject result = new JsonObject();
        result.addProperty("status", "PASS"); result.addProperty("scope", "INSTRUMENTED_ORDER_TEST_ONLY");
        result.addProperty("qualificationAllowed", false); result.addProperty("mode", pins.get("mode").getAsString());
        result.add("regionHeads", new Gson().toJsonTree(headOrder));
        result.add("regionReturns", new Gson().toJsonTree(returned));
        result.add("worldHeads", new Gson().toJsonTree(levelOrder));
        result.add("worldgenHeads", new Gson().toJsonTree(worldgenOrder));
        result.addProperty("allHandlerBodiesMatchPinnedProduction", true);
        result.add("expectedWeavingTransforms", new Gson().toJsonTree(List.of(
                "basepower$protectTerrain: redundant receiver CHECKCAST elided by Mixin0.8.7",
                "basemetals$protectTerrain: redundant receiver CHECKCAST elided by Mixin0.8.7")));
        result.addProperty("nativeReturnsCovered", nativeReturns);
        result.addProperty("exceptionEdgesChecked", true);
        JsonObject hashes = new JsonObject();
        for (String name : List.of(REGION, LEVEL, WORLDGEN)) hashes.addProperty(name, ProbeGuard.sha(Files.readAllBytes(exports.resolve(name + ".class"))));
        result.add("exportedClassSha256", hashes);
        return result;
    }

    public static void main(String[] args) throws Exception {
        JsonObject pins = ProbeGuard.read(Path.of(args[1]));
        if (args[0].equals("guard")) {
            ProbeGuard.verifyProduction(pins, Path.of(args[2]));
            System.out.println("ORDER_PROBE_GUARD_PASS");
        } else if (args[0].equals("audit")) {
            JsonObject result = audit(pins, Path.of(args[2]), Path.of(args[3]), Path.of(args[4]));
            Files.writeString(Path.of(args[5]), new Gson().toJson(result) + "\n", StandardOpenOption.CREATE_NEW);
            System.out.println(new Gson().toJson(result));
        } else throw new AssertionError("Unknown audit command");
    }
}
