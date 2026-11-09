var Opcodes = Java.type('org.objectweb.asm.Opcodes');
var InsnNode = Java.type('org.objectweb.asm.tree.InsnNode');
var InsnList = Java.type('org.objectweb.asm.tree.InsnList');
var JumpInsnNode = Java.type('org.objectweb.asm.tree.JumpInsnNode');
var LabelNode = Java.type('org.objectweb.asm.tree.LabelNode');
var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');
var VarInsnNode = Java.type('org.objectweb.asm.tree.VarInsnNode');

// Only pre-datafix migration needs bytecode integration on Forge 1.17.
var hook = 'zone/moddev/mc/basepower/migration/LegacyWorldDataHook';

function patchPlayerDataFix(classNode) {
    var descriptor = '(Lcom/mojang/datafixers/DataFixer;' +
            'Lnet/minecraft/util/datafix/DataFixTypes;' +
            'Lnet/minecraft/nbt/CompoundTag;I)Lnet/minecraft/nbt/CompoundTag;';
    var matches = 0;
    var matchedMethod = null;
    var matchedCall = null;
    for (var i = 0; i < classNode.methods.size(); ++i) {
        var method = classNode.methods.get(i);
        for (var instruction = method.instructions.getFirst(); instruction !== null;
                instruction = instruction.getNext()) {
            if (instruction.getOpcode() === Opcodes.INVOKESTATIC &&
                    instruction.owner === 'net/minecraft/nbt/NbtUtils' && instruction.desc === descriptor) {
                ++matches;
                matchedMethod = method;
                matchedCall = instruction;
            }
        }
    }
    if (matches !== 1) {
        throw new Error('Power Advantage requires exactly one native player datafix call in ' + classNode.name);
    }
    matchedMethod.instructions.set(matchedCall, new MethodInsnNode(Opcodes.INVOKESTATIC,
            'zone/moddev/mc/basepower/migration/LegacyPlayerDataFixes', 'update', descriptor, false));
    return classNode;
}

function initializeCoreMod() {
    return {
        'poweradvantage_legacy_player_file': {
            'target': { 'type': 'CLASS', 'name': 'net.minecraft.world.level.storage.PlayerDataStorage' },
            'transformer': patchPlayerDataFix
        },
        'poweradvantage_legacy_embedded_player': {
            'target': { 'type': 'CLASS', 'name': 'net.minecraft.world.level.storage.PrimaryLevelData' },
            'transformer': patchPlayerDataFix
        },
        'poweradvantage_legacy_world_info': {
            'target': { 'type': 'CLASS', 'name': 'net.minecraft.world.level.storage.LevelStorageSource' },
            'transformer': function(classNode) {
                var worldReaders = 0;
                var packReaders = 0;
                for (var i = 0; i < classNode.methods.size(); ++i) {
                    var method = classNode.methods.get(i);
                    var worldReader = method.desc === '(Lcom/mojang/serialization/DynamicOps;' +
                            'Lnet/minecraft/world/level/DataPackConfig;' +
                            'Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;' +
                            'Ljava/io/File;Lcom/mojang/datafixers/DataFixer;)' +
                            'Lnet/minecraft/world/level/storage/PrimaryLevelData;';
                    var packReader = method.desc === '(Ljava/io/File;Lcom/mojang/datafixers/DataFixer;)' +
                            'Lnet/minecraft/world/level/DataPackConfig;';
                    if (!worldReader && !packReader) continue;
                    if (worldReader) {
                        var prefix = new InsnList();
                        prefix.add(new VarInsnNode(Opcodes.ALOAD, 3));
                        prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, hook,
                                'prepareLegacyWorld', '(Ljava/io/File;)V', false));
                        method.instructions.insert(prefix);
                    }
                    var reads = 0;
                    for (var read = method.instructions.getFirst(); read !== null; read = read.getNext()) {
                        if (read.getOpcode() === Opcodes.INVOKESTATIC && read.owner === 'net/minecraft/nbt/NbtIo' &&
                                read.desc === '(Ljava/io/File;)Lnet/minecraft/nbt/CompoundTag;') {
                            var replacement = new MethodInsnNode(Opcodes.INVOKESTATIC,
                                    'zone/moddev/mc/basepower/migration/LegacyWorldMetadata', 'read', read.desc, false);
                            method.instructions.set(read, replacement);
                            read = replacement;
                            ++reads;
                        }
                    }
                    if (reads !== 1) throw new Error('Power Advantage expected one level metadata read');
                    if (worldReader) ++worldReaders;
                    if (packReader) ++packReaders;
                }
                if (worldReaders !== 1 || packReaders !== 1) {
                    throw new Error('Power Advantage could not attach both legacy level readers');
                }
                return classNode;
            }
        },
        'poweradvantage_legacy_chunk_status': {
            'target': { 'type': 'CLASS', 'name': 'net.minecraft.world.level.chunk.storage.ChunkStorage' },
            'transformer': function(classNode) {
                var descriptor = '(Lnet/minecraft/resources/ResourceKey;' +
                        'Ljava/util/function/Supplier;Lnet/minecraft/nbt/CompoundTag;)' +
                        'Lnet/minecraft/nbt/CompoundTag;';
                var patchedRead = false;
                var patchedReturn = false;
                var patchedOwnership = false;
                var patchedEntities = false;
                for (var i = 0; i < classNode.methods.size(); ++i) {
                    var method = classNode.methods.get(i);
                    if (method.desc === '(Lnet/minecraft/world/level/ChunkPos;)Lnet/minecraft/nbt/CompoundTag;') {
                        for (var read = method.instructions.getFirst(); read !== null; read = read.getNext()) {
                            if (read.getOpcode() === Opcodes.ARETURN) {
                                method.instructions.insertBefore(read, new MethodInsnNode(Opcodes.INVOKESTATIC,
                                        hook, 'copyChunkForMigration',
                                        '(Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/nbt/CompoundTag;', false));
                                patchedOwnership = true;
                            }
                        }
                    }
                    if (method.desc !== descriptor) continue;
                    // Other coremods prepend their preparation. Stay after all those entry
                    // hooks so modded item durability is rescued before entity flattening.
                    for (var first = method.instructions.getFirst(); first !== null; first = first.getNext()) {
                        if (first.getOpcode() === Opcodes.INVOKESTATIC && first.owner === classNode.name &&
                                first.desc === '(Lnet/minecraft/nbt/CompoundTag;)I') {
                            var entities = new InsnList();
                            entities.add(new VarInsnNode(Opcodes.ALOAD, 3));
                            entities.add(new MethodInsnNode(Opcodes.INVOKESTATIC, hook,
                                    'prepareLegacyEntities', '(Lnet/minecraft/nbt/CompoundTag;)V', false));
                            method.instructions.insertBefore(first, entities);
                            patchedEntities = true;
                            break;
                        }
                    }
                    var prepare = new InsnList();
                    prepare.add(new VarInsnNode(Opcodes.ALOAD, 3));
                    prepare.add(new MethodInsnNode(Opcodes.INVOKESTATIC, hook,
                            'prepareLegacyChunk', '(Lnet/minecraft/nbt/CompoundTag;)V', false));
                    method.instructions.insert(prepare);
                    patchedRead = true;
                    for (var instruction = method.instructions.getFirst(); instruction !== null;
                            instruction = instruction.getNext()) {
                        if (instruction.getOpcode() === Opcodes.ARETURN) {
                            method.instructions.insertBefore(instruction, new MethodInsnNode(Opcodes.INVOKESTATIC,
                                    hook, 'finalizeLegacyChunk',
                                    '(Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/nbt/CompoundTag;', false));
                            patchedReturn = true;
                        }
                    }
                }
                if (!patchedRead || !patchedReturn || !patchedOwnership || !patchedEntities) {
                    throw new Error('Power Advantage could not attach its legacy chunk converter');
                }
                return classNode;
            }
        },
        'poweradvantage_separate_entity_storage': {
            'target': { 'type': 'CLASS', 'name': 'net.minecraft.world.level.chunk.storage.EntityStorage' },
            'transformer': function(classNode) {
                var matches = 0;
                var descriptor = '(Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/nbt/CompoundTag;';
                for (var i = 0; i < classNode.methods.size(); ++i) {
                    var method = classNode.methods.get(i);
                    if (method.desc !== descriptor || (method.access & Opcodes.ACC_STATIC) !== 0) continue;
                    ++matches;
                    var prefix = new InsnList();
                    prefix.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, hook,
                            'prepareEntityChunkForMigration', descriptor, false));
                    prefix.add(new VarInsnNode(Opcodes.ASTORE, 1));
                    method.instructions.insert(prefix);
                    for (var instruction = method.instructions.getFirst(); instruction !== null;
                            instruction = instruction.getNext()) {
                        if (instruction.getOpcode() === Opcodes.ARETURN) {
                            method.instructions.insertBefore(instruction, new MethodInsnNode(Opcodes.INVOKESTATIC,
                                    hook, 'finalizeLegacyChunk', descriptor, false));
                        }
                    }
                }
                if (matches !== 1) throw new Error('Power Advantage requires exactly one native entity-chunk converter');
                return classNode;
            }
        },
        'poweradvantage_legacy_worldgen_guard': {
            'target': { 'type': 'CLASS', 'name': 'net.minecraft.server.level.WorldGenRegion' },
            'transformer': function(classNode) {
                var descriptor = '(Lnet/minecraft/core/BlockPos;' +
                        'Lnet/minecraft/world/level/block/state/BlockState;II)Z';
                var patched = false;
                for (var i = 0; i < classNode.methods.size(); ++i) {
                    var method = classNode.methods.get(i);
                    if (method.desc !== descriptor) continue;
                    var allowed = new LabelNode();
                    var prefix = new InsnList();
                    prefix.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, hook,
                            'shouldBlockWorldgenWrite', '(Lnet/minecraft/core/BlockPos;)Z', false));
                    prefix.add(new JumpInsnNode(Opcodes.IFEQ, allowed));
                    prefix.add(new InsnNode(Opcodes.ICONST_0));
                    prefix.add(new InsnNode(Opcodes.IRETURN));
                    prefix.add(allowed);
                    method.instructions.insert(prefix);
                    patched = true;
                }
                if (!patched) throw new Error('Power Advantage could not protect existing legacy terrain');
                return classNode;
            }
        }
    };
}
