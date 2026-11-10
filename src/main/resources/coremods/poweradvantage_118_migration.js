var Opcodes = Java.type('org.objectweb.asm.Opcodes');
var InsnNode = Java.type('org.objectweb.asm.tree.InsnNode');
var InsnList = Java.type('org.objectweb.asm.tree.InsnList');
var JumpInsnNode = Java.type('org.objectweb.asm.tree.JumpInsnNode');
var LabelNode = Java.type('org.objectweb.asm.tree.LabelNode');
var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');
var VarInsnNode = Java.type('org.objectweb.asm.tree.VarInsnNode');

// Only pre-datafix migration needs bytecode integration.
var hook = 'zone/moddev/mc/basepower/migration/LegacyWorldDataHook';
var metadata = 'zone/moddev/mc/basepower/migration/LegacyWorldMetadata';
var dataFixTypes = 'net/minecraft/util/datafix/DataFixTypes';

function previousInstruction(instruction) {
    var previous = instruction.getPrevious();
    while (previous !== null && previous.getOpcode() < 0) previous = previous.getPrevious();
    return previous;
}

function isEmbeddedPlayerCall(call) {
    // The Forge 50 reader pushes PLAYER, then the fixer, value and version.
    // Inspect the receiver for this invocation, not an unrelated earlier GETSTATIC.
    var argument = call;
    var loads = [Opcodes.ILOAD, Opcodes.ALOAD, Opcodes.ALOAD];
    for (var index = 0; index < loads.length; ++index) {
        argument = previousInstruction(argument);
        if (argument === null || argument.getOpcode() !== loads[index]) return false;
    }
    var receiver = previousInstruction(argument);
    return receiver !== null && receiver.getOpcode() === Opcodes.GETSTATIC &&
            receiver.owner === dataFixTypes && receiver.name === 'PLAYER' &&
            receiver.desc === 'L' + dataFixTypes + ';';
}

function patchPlayerDataFix(classNode) {
    var descriptor = '(Lcom/mojang/datafixers/DataFixer;' +
            'Lnet/minecraft/nbt/CompoundTag;I)Lnet/minecraft/nbt/CompoundTag;';
    var matches = 0;
    var matchedMethod = null;
    var matchedCall = null;
    for (var i = 0; i < classNode.methods.size(); ++i) {
        var method = classNode.methods.get(i);
        for (var instruction = method.instructions.getFirst(); instruction !== null;
                instruction = instruction.getNext()) {
            if (instruction.getOpcode() === Opcodes.INVOKEVIRTUAL &&
                    instruction.owner === dataFixTypes && instruction.desc === descriptor) {
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
            'zone/moddev/mc/basepower/migration/LegacyPlayerDataFixes', 'updateNativePlayer',
            '(Lnet/minecraft/util/datafix/DataFixTypes;' + descriptor.substring(1), false));
    return classNode;
}

function initializeCoreMod() {
    return {
        'poweradvantage_legacy_player_file': {
            'target': { 'type': 'CLASS', 'name': 'net.minecraft.world.level.storage.PlayerDataStorage' },
            'transformer': patchPlayerDataFix
        },
        'poweradvantage_legacy_world_info': {
            'target': { 'type': 'CLASS', 'name': 'net.minecraft.world.level.storage.LevelStorageSource' },
            'transformer': function(classNode) {
                var worldReaders = [];
                var rawReturns = [];
                var lightweightReturns = [];
                var embeddedSites = [];
                var dynamic = 'Lcom/mojang/serialization/Dynamic;';
                var nbt = 'Lnet/minecraft/nbt/CompoundTag;';
                var embedded = '(Lcom/mojang/datafixers/DataFixer;' + dynamic + 'I)' + dynamic;
                for (var i = 0; i < classNode.methods.size(); ++i) {
                    var method = classNode.methods.get(i);
                    if (method.name === 'readLevelDataTagFixed' &&
                            method.desc === '(Ljava/nio/file/Path;Lcom/mojang/datafixers/DataFixer;)' + dynamic) {
                        worldReaders.push(method);
                    }
                    var calls = [];
                    var playerCall = null;
                    for (var insn = method.instructions.getFirst(); insn !== null; insn = insn.getNext()) {
                        if (insn.getOpcode() === Opcodes.ARETURN) {
                            if (method.name === 'readLevelDataTagRaw' && method.desc === '(Ljava/nio/file/Path;)' + nbt) {
                                rawReturns.push({ method: method, instruction: insn });
                            } else if (method.name === 'readLightweightData' &&
                                    method.desc === '(Ljava/nio/file/Path;)Lnet/minecraft/nbt/Tag;') {
                                lightweightReturns.push({ method: method, instruction: insn });
                            }
                        }
                        if (insn.getOpcode() === Opcodes.INVOKEVIRTUAL && insn.owner === dataFixTypes &&
                                insn.desc === embedded) {
                            calls.push(insn);
                            if (isEmbeddedPlayerCall(insn)) playerCall = insn;
                        }
                    }
                    if (playerCall !== null) {
                        if (calls.length !== 1) throw new Error('Power Advantage found an ambiguous embedded player reader');
                        embeddedSites.push({ method: method, instruction: playerCall });
                    }
                }
                if (worldReaders.length !== 1 || rawReturns.length !== 1 ||
                        lightweightReturns.length !== 1 || embeddedSites.length !== 1) {
                    throw new Error('Power Advantage could not attach its Forge 50 level and embedded player readers');
                }
                var prefix = new InsnList();
                prefix.add(new VarInsnNode(Opcodes.ALOAD, 0));
                prefix.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, 'java/nio/file/Path', 'toFile', '()Ljava/io/File;', true));
                prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, hook, 'prepareLegacyWorld', '(Ljava/io/File;)V', false));
                worldReaders[0].instructions.insert(prefix);
                rawReturns[0].method.instructions.insertBefore(rawReturns[0].instruction, new MethodInsnNode(
                        Opcodes.INVOKESTATIC, metadata, 'remapRaw', '(' + nbt + ')' + nbt, false));
                lightweightReturns[0].method.instructions.insertBefore(lightweightReturns[0].instruction, new MethodInsnNode(
                        Opcodes.INVOKESTATIC, metadata, 'remapLightweight', '(Lnet/minecraft/nbt/Tag;)Lnet/minecraft/nbt/Tag;', false));
                embeddedSites[0].method.instructions.set(embeddedSites[0].instruction, new MethodInsnNode(
                        Opcodes.INVOKESTATIC, 'zone/moddev/mc/basepower/migration/LegacyPlayerDataFixes',
                        'updateNativeEmbeddedPlayer', '(L' + dataFixTypes + ';' + embedded.substring(1), false));
                return classNode;
            }
        },
        'poweradvantage_legacy_chunk_status': {
            'target': { 'type': 'CLASS', 'name': 'net.minecraft.world.level.chunk.storage.ChunkStorage' },
            'transformer': function(classNode) {
                var descriptor = '(Lnet/minecraft/resources/ResourceKey;' +
                        'Ljava/util/function/Supplier;Lnet/minecraft/nbt/CompoundTag;Ljava/util/Optional;)' +
                        'Lnet/minecraft/nbt/CompoundTag;';
                var patchedRead = false;
                var patchedReturn = false;
                var patchedOwnership = false;
                var patchedEntities = false;
                for (var i = 0; i < classNode.methods.size(); ++i) {
                    var method = classNode.methods.get(i);
                    if (method.desc === '(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/concurrent/CompletableFuture;') {
                        for (var read = method.instructions.getFirst(); read !== null; read = read.getNext()) {
                            if (read.getOpcode() === Opcodes.ARETURN) {
                                method.instructions.insertBefore(read, new MethodInsnNode(Opcodes.INVOKESTATIC,
                                        hook, 'copyChunkReadForMigration',
                                        '(Ljava/util/concurrent/CompletableFuture;)Ljava/util/concurrent/CompletableFuture;', false));
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
                var storage = 'net/minecraft/world/level/chunk/storage/SimpleRegionStorage';
                var descriptor = '(Lnet/minecraft/nbt/CompoundTag;I)Lnet/minecraft/nbt/CompoundTag;';
                for (var i = 0; i < classNode.methods.size(); ++i) {
                    var method = classNode.methods.get(i);
                    for (var call = method.instructions.getFirst(); call !== null; call = call.getNext()) {
                        if (call.getOpcode() !== Opcodes.INVOKEVIRTUAL || call.owner !== storage ||
                                call.name !== 'upgradeChunkTag' || call.desc !== descriptor) continue;
                        method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, hook,
                                'upgradeEntityChunk', '(L' + storage + ';' + descriptor.substring(1), false));
                        ++matches;
                    }
                }
                if (matches !== 1) throw new Error('Power Advantage requires exactly one native entity-chunk converter');
                return classNode;
            }
        },
        'poweradvantage_legacy_worldgen_guard': {
            'target': { 'type': 'CLASS', 'name': 'net.minecraft.server.level.WorldGenRegion' },
            'transformer': function(classNode) {
                var descriptor = '(Lnet/minecraft/core/BlockPos;)Z';
                var patched = false;
                for (var i = 0; i < classNode.methods.size(); ++i) {
                    var method = classNode.methods.get(i);
                    if (method.desc !== descriptor || method.name !== 'ensureCanWrite') continue;
                    var allowed = new LabelNode();
                    var prefix = new InsnList();
                    prefix.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    prefix.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, hook,
                            'shouldBlockWorldgenWrite', '(Lnet/minecraft/server/level/WorldGenRegion;Lnet/minecraft/core/BlockPos;)Z', false));
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
