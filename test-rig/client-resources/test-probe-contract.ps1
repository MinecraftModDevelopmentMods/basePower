param(
    [string]$RepositoryRoot = (Join-Path $PSScriptRoot '../..'),
    [string]$TargetRoot = $PSScriptRoot
)
$ErrorActionPreference = 'Stop'
$RepositoryRoot = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$TargetRoot = (Resolve-Path -LiteralPath $TargetRoot).Path
$baseline = 'c310d0ca63ad9a8408dd4bba75172afafa5c71b7'
$relative = 'java/zone/moddev/mc/advantageworks/client'
$referenceHashes = [ordered]@{
    ClientResourceProbe = '014E12FFFD8B1E52FF32FA55ABCD6A1708094AF00D85AFB2F1424D448E349453'
    TankVisualProbe = '666F6D8FC5F1B925665E019E4F38DAB90766A04CB64C9C256AD3BAC9BE6CF327'
    OilRenderProbe = 'E99F2D29D58F5388406FB29F419D68FF02C3C9EB51A44018C9A3440E83F37777'
    RecipeBookGroupingProbe = 'E30466B80CB0496E637B3C6AAC9D490EF55C8E0FE0EBC5ACAEF7EF90D8E9C1B1'
    MachineGuiLayoutProbe = '8D89215AEFFDDC96A7175AE84F315DFF009D6E0F0FC1942C986E626B5806AFFF'
    ChildRendererGeometryProbe = 'B56A5F683FCC1312D3B749BEC11C57099ECB73E5C87C953EF55050EAE3320650'
}
$checks = 0
function Assert-Contract([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
    $script:checks++
}
function Get-TextHash([string]$Text) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { [BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($Text))).Replace('-', '') }
    finally { $sha.Dispose() }
}
function Read-Baseline([string]$Path, [string]$Hash) {
    # Read immutable Git bytes, not a mutable 1.16 working tree or the 1.17 target.
    $info = [Diagnostics.ProcessStartInfo]::new('git', "-C `"$RepositoryRoot`" show ${baseline}:test-rig/client-resources/$Path")
    $info.UseShellExecute = $false
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.StandardOutputEncoding = [Text.Encoding]::UTF8
    $process = [Diagnostics.Process]::Start($info)
    try {
        $text = $process.StandardOutput.ReadToEnd()
        $errorText = $process.StandardError.ReadToEnd()
        $process.WaitForExit()
        Assert-Contract ($process.ExitCode -eq 0) "Cannot read accepted baseline $Path : $errorText"
        Assert-Contract ((Get-TextHash $text) -ceq $Hash) "Reference SHA-256 mismatch for $Path at $baseline. Do not rebaseline the oracle."
        $text
    } finally { $process.Dispose() }
}
function Canonical-Source([string]$Source) {
    $Source = [regex]::Replace($Source, '(?m)^import [^\r\n]+\r?\n', '')
    # Preserve text literals; only comments and whitespace outside literals differ.
    [regex]::Replace($Source, '"(?:\\.|[^"\\])*"|''(?:\\.|[^''\\])*''|/\*[\s\S]*?\*/|//[^\r\n]*|\s+', {
        param($match)
        if ($match.Value.StartsWith('"') -or $match.Value.StartsWith("'")) { $match.Value } else { '' }
    })
}
function Adapt-Exact([string]$Source, [string]$Before, [string]$After, [string]$Name, [int]$Count = 1) {
    $beforeKey = Canonical-Source $Before
    $afterKey = Canonical-Source $After
    Assert-Contract ([regex]::Matches($Source, [regex]::Escape($beforeKey)).Count -eq $Count) "Native adapter $Name no longer matches the accepted baseline exactly."
    $Source.Replace($beforeKey, $afterKey)
}
function Expected-NativeImports([string]$Source) {
    # Bind short names to native classes too: an unchanged body must not be able
    # to capture a substitute BufferBuilder, model or renderer through an import.
    $moves = @{
        'com.mojang.blaze3d.matrix.MatrixStack'='com.mojang.blaze3d.vertex.PoseStack'
        'com.mojang.blaze3d.vertex.IVertexBuilder'='com.mojang.blaze3d.vertex.VertexConsumer'
        'net.minecraft.block.Block'='net.minecraft.world.level.block.Block'
        'net.minecraft.block.BlockState'='net.minecraft.world.level.block.state.BlockState'
        'net.minecraft.client.entity.player.ClientPlayerEntity'='net.minecraft.client.player.LocalPlayer'
        'net.minecraft.client.gui.recipebook.RecipeList'='net.minecraft.client.gui.screens.recipebook.RecipeCollection'
        'net.minecraft.client.gui.recipebook.RecipeWidget'='net.minecraft.client.gui.screens.recipebook.RecipeButton'
        'net.minecraft.client.renderer.BufferBuilder'='com.mojang.blaze3d.vertex.BufferBuilder'
        'net.minecraft.client.renderer.IRenderTypeBuffer'='net.minecraft.client.renderer.MultiBufferSource'
        'net.minecraft.client.renderer.model.BakedQuad'='net.minecraft.client.renderer.block.model.BakedQuad'
        'net.minecraft.client.renderer.model.IBakedModel'='net.minecraft.client.resources.model.BakedModel'
        'net.minecraft.client.renderer.RenderState'='net.minecraft.client.renderer.RenderStateShard'
        'net.minecraft.client.renderer.texture.AtlasTexture'='net.minecraft.client.renderer.texture.TextureAtlas'
        'net.minecraft.client.renderer.tileentity.TileEntityRenderer'='net.minecraft.client.renderer.blockentity.BlockEntityRenderer'
        'net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher'='net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher'
        'net.minecraft.client.renderer.vertex.DefaultVertexFormats'='com.mojang.blaze3d.vertex.DefaultVertexFormat'
        'net.minecraft.client.renderer.vertex.VertexFormat'='com.mojang.blaze3d.vertex.VertexFormat'
        'net.minecraft.client.renderer.vertex.VertexFormatElement'='com.mojang.blaze3d.vertex.VertexFormatElement'
        'net.minecraft.client.resources.I18n'='net.minecraft.client.resources.language.I18n'
        'net.minecraft.client.shader.Framebuffer'='com.mojang.blaze3d.pipeline.RenderTarget'
        'net.minecraft.client.util.ClientRecipeBook'='net.minecraft.client.ClientRecipeBook'
        'net.minecraft.client.world.ClientWorld'='net.minecraft.client.multiplayer.ClientLevel'
        'net.minecraft.entity.EntityType'='net.minecraft.world.entity.EntityType'
        'net.minecraft.entity.player.PlayerEntity'='net.minecraft.world.entity.player.Player'
        'net.minecraft.entity.player.PlayerInventory'='net.minecraft.world.entity.player.Inventory'
        'net.minecraft.fluid.FlowingFluid'='net.minecraft.world.level.material.FlowingFluid'
        'net.minecraft.fluid.Fluid'='net.minecraft.world.level.material.Fluid'
        'net.minecraft.fluid.FluidState'='net.minecraft.world.level.material.FluidState'
        'net.minecraft.inventory.container.Slot'='net.minecraft.world.inventory.Slot'
        'net.minecraft.inventory.IInventory'='net.minecraft.world.Container'
        'net.minecraft.item.BucketItem'='net.minecraft.world.item.BucketItem'
        'net.minecraft.item.crafting.IRecipe'='net.minecraft.world.item.crafting.Recipe'
        'net.minecraft.item.crafting.RecipeBook'='net.minecraft.stats.RecipeBook'
        'net.minecraft.item.crafting.RecipeItemHelper'='net.minecraft.world.entity.player.StackedContents'
        'net.minecraft.item.crafting.RecipeManager'='net.minecraft.world.item.crafting.RecipeManager'
        'net.minecraft.item.Item'='net.minecraft.world.item.Item'
        'net.minecraft.item.ItemStack'='net.minecraft.world.item.ItemStack'
        'net.minecraft.network.PacketBuffer'='net.minecraft.network.FriendlyByteBuf'
        'net.minecraft.network.play.server.SUpdateRecipesPacket'='net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket'
        'net.minecraft.resources.IResource'='net.minecraft.server.packs.resources.Resource'
        'net.minecraft.tileentity.TileEntity'='net.minecraft.world.level.block.entity.BlockEntity'
        'net.minecraft.tileentity.TileEntityType'='net.minecraft.world.level.block.entity.BlockEntityType'
        'net.minecraft.util.Direction'='net.minecraft.core.Direction'
        'net.minecraft.util.math.BlockPos'='net.minecraft.core.BlockPos'
        'net.minecraft.util.NonNullList'='net.minecraft.core.NonNullList'
        'net.minecraft.util.ResourceLocation'='net.minecraft.resources.ResourceLocation'
        'net.minecraft.util.text.TranslationTextComponent'='net.minecraft.network.chat.TranslatableComponent'
        'net.minecraft.world.IBlockDisplayReader'='net.minecraft.world.level.BlockAndTintGetter'
        'net.minecraft.world.IBlockReader'='net.minecraft.world.level.BlockGetter'
        'net.minecraft.world.lighting.WorldLightManager'='net.minecraft.world.level.lighting.LevelLightEngine'
        'net.minecraft.world.LightType'='net.minecraft.world.level.LightLayer'
    }
    @([regex]::Matches($Source, '(?m)^import ([^;\r\n]+);') | ForEach-Object {
        $name = $_.Groups[1].Value
        if ($moves.ContainsKey($name)) { $moves[$name] } else { $name }
    }) -join "`n"
}
function Translate-NativeNames([string]$Source) {
    # Fixed translations only. Never derive an oracle from target code.
    $qualified = [ordered]@{
        'net.minecraft.block.FlowingFluidBlock'='net.minecraft.world.level.block.LiquidBlock'
        'net.minecraft.block.BlockState'='net.minecraft.world.level.block.state.BlockState'
        'net.minecraft.block.Block'='net.minecraft.world.level.block.Block'
        'net.minecraft.fluid.Fluids'='net.minecraft.world.level.material.Fluids'
        'net.minecraft.item.ItemStack'='net.minecraft.world.item.ItemStack'
        'net.minecraft.item.Items'='net.minecraft.world.item.Items'
        'net.minecraft.item.Item'='net.minecraft.world.item.Item'
        'net.minecraft.util.ResourceLocation'='net.minecraft.resources.ResourceLocation'
        'net.minecraft.util.math.vector.Matrix4f'='com.mojang.math.Matrix4f'
        'net.minecraft.util.math.vector.Matrix3f'='com.mojang.math.Matrix3f'
        'net.minecraft.util.text.StringTextComponent'='net.minecraft.network.chat.TextComponent'
        'net.minecraft.util.text.ITextComponent'='net.minecraft.network.chat.Component'
        'net.minecraft.util.CooldownTracker'='net.minecraft.world.item.ItemCooldowns'
        'net.minecraft.util.registry.WorldGenRegistries'='net.minecraft.data.BuiltinRegistries'
        'net.minecraft.util.registry.Registry'='net.minecraft.core.Registry'
        'net.minecraft.nbt.CompressedStreamTools'='net.minecraft.nbt.NbtIo'
        'net.minecraft.nbt.CompoundNBT'='net.minecraft.nbt.CompoundTag'
        'net.minecraft.nbt.ListNBT'='net.minecraft.nbt.ListTag'
        'net.minecraft.entity.player.PlayerInventory'='net.minecraft.world.entity.player.Inventory'
        'net.minecraft.inventory.container.WorkbenchContainer'='net.minecraft.world.inventory.CraftingMenu'
        'net.minecraft.inventory.container.RecipeBookContainer'='net.minecraft.world.inventory.RecipeBookMenu'
        'net.minecraft.item.crafting.RecipeBookCategory'='net.minecraft.world.inventory.RecipeBookType'
        'net.minecraft.state.BooleanProperty'='net.minecraft.world.level.block.state.properties.BooleanProperty'
        'net.minecraft.state.Property'='net.minecraft.world.level.block.state.properties.Property'
        'net.minecraft.world.World'='net.minecraft.world.level.Level'
        'net.minecraft.world.biome.Biomes'='net.minecraft.world.level.biome.Biomes'
        'net.minecraft.profiler.EmptyProfiler'='net.minecraft.util.profiling.InactiveProfiler'
        'net.minecraft.resources.IResource'='net.minecraft.server.packs.resources.Resource'
        'net.minecraft.client.resources.I18n'='net.minecraft.client.resources.language.I18n'
        'com.mojang.blaze3d.matrix.MatrixStack'='com.mojang.blaze3d.vertex.PoseStack'
        'net.minecraft.network.play.client.CMoveVehiclePacket'='net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket'
        'net.minecraft.network.ProtocolType'='net.minecraft.network.ConnectionProtocol'
    }
    foreach ($pair in $qualified.GetEnumerator()) { $Source = $Source.Replace($pair.Key, $pair.Value) }
    $classes = [ordered]@{
        IBakedModel='BakedModel'; PlayerInventory='Inventory'; TileEntity='BlockEntity'
        IBlockReader='BlockGetter'; IBlockDisplayReader='BlockAndTintGetter'; LightType='LightLayer'
        WorldLightManager='LevelLightEngine'; DefaultVertexFormats='DefaultVertexFormat'; AtlasTexture='TextureAtlas'
        RecipeWidget='RecipeButton'; RecipeList='RecipeCollection'; IRecipe='Recipe'; RecipeItemHelper='StackedContents'
        SUpdateRecipesPacket='ClientboundUpdateRecipesPacket'; PacketBuffer='FriendlyByteBuf'
        Framebuffer='RenderTarget'; MatrixStack='PoseStack'; ClientPlayerEntity='LocalPlayer'
        PlayerEntity='Player'; IInventory='Container'; ClientWorld='ClientLevel'
        TileEntityType='BlockEntityType'; TileEntityRenderer='BlockEntityRenderer'; IVertexBuilder='VertexConsumer'
        IRenderTypeBuffer='MultiBufferSource'; IResource='Resource'
    }
    foreach ($pair in $classes.GetEnumerator()) { $Source = [regex]::Replace($Source, "\b$($pair.Key)\b", $pair.Value) }
    $pairs = [ordered]@{
        '1.16.5'='1.17.1'; '36.2.34'='37.1.1'; '2.4.7.116051'='2.4.9.117011'; '2.2.8.116051'='2.2.10.117011'
        '.getParticleTexture('='.getParticleIcon('; 'RenderSystem.color4f('='RenderSystem.setShaderColor('
        'new RenderTarget('='new com.mojang.blaze3d.pipeline.TextureTarget('
        'PoseStack.Entry'='PoseStack.Pose'; 'ForgeRegistries.TILE_ENTITIES'='ForgeRegistries.BLOCK_ENTITIES'
        'TileEntityRendererDispatcher.instance'='minecraft.getBlockEntityRenderDispatcher()'
        'RenderType.State.class'='RenderType.CompositeState.class'; 'RenderState.TextureState.class'='RenderStateShard.EmptyTextureStateShard.class'
    }
    foreach ($pair in $pairs.GetEnumerator()) { $Source = $Source.Replace($pair.Key, $pair.Value) }
    Canonical-Source $Source
}
function Expected-NativeProbe([string]$Name, [string]$Source) {
    $source = Translate-NativeNames $Source
    switch ($Name) {
        ClientResourceProbe {
            $source = Adapt-Exact $source 'getModel(stack, null, null)' 'getModel(stack, null, null, 0)' 'native item-model seed'
        }
        TankVisualProbe {
            $source = Adapt-Exact $source 'new MetalTankTileEntity()' 'new MetalTankTileEntity(BlockPos.ZERO, zone.moddev.mc.basepower.init.Blocks.metal_storage_tank.defaultBlockState())' 'native tank factories' 2
            $source = Adapt-Exact $source 'new BlockGetter() {' 'new BlockGetter() { @Override public int getHeight() { return 256; } @Override public int getMinBuildHeight() { return 0; }' 'native tank height'
            $source = Adapt-Exact $source 'world, BlockPos.ZERO, Direction.UP);' 'world, BlockPos.ZERO, Direction.UP, BlockPos.ZERO.above());' 'native adjacent culling position'
            $source = Adapt-Exact $source 'RenderSystem.matrixMode(GL_PROJECTION); RenderSystem.pushMatrix(); RenderSystem.loadIdentity(); RenderSystem.ortho(0.0D, width, height, 0.0D, -1.0D, 1.0D); RenderSystem.matrixMode(GL_MODELVIEW); RenderSystem.pushMatrix(); RenderSystem.loadIdentity(); RenderSystem.disableLighting();' 'NativeGuiProjection projection = new NativeGuiProjection(width, height, -1, 1, 0);' 'tank shader projection'
            $source = Adapt-Exact $source 'RenderSystem.popMatrix(); RenderSystem.matrixMode(GL_PROJECTION); RenderSystem.popMatrix(); RenderSystem.matrixMode(GL_MODELVIEW);' 'projection.close();' 'tank projection restore'
        }
        RecipeBookGroupingProbe {
            $source = Adapt-Exact $source 'ClientboundUpdateRecipesPacket packet = new ClientboundUpdateRecipesPacket(); FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(Files.readAllBytes(file))); try { packet.read(buffer); require(!buffer.isReadable(), "Trailing recipe packet bytes"); }' 'FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(Files.readAllBytes(file))); ClientboundUpdateRecipesPacket packet; try { packet = new ClientboundUpdateRecipesPacket(buffer); require(!buffer.isReadable(), "Trailing recipe packet bytes"); }' 'native packet decoding'
            $source = Adapt-Exact $source 'RenderSystem.matrixMode(GL_PROJECTION); RenderSystem.pushMatrix(); RenderSystem.loadIdentity(); RenderSystem.ortho(0, width, height, 0, -1000, 1000); RenderSystem.matrixMode(GL_MODELVIEW); RenderSystem.pushMatrix(); RenderSystem.loadIdentity();' 'NativeGuiProjection projection = new NativeGuiProjection(width, height, -1000, 1000, 0);' 'recipe shader projection'
            $source = Adapt-Exact $source 'RenderSystem.disableLighting();' '' 'native recipe shader lighting'
            $source = Adapt-Exact $source 'RenderSystem.popMatrix(); RenderSystem.matrixMode(GL_PROJECTION); RenderSystem.popMatrix(); RenderSystem.matrixMode(GL_MODELVIEW);' 'projection.close();' 'recipe projection restore'
        }
        OilRenderProbe {
            $source = Adapt-Exact $source @'
net.minecraft.tags.ITagCollectionSupplier previousTags = net.minecraft.tags.TagCollectionManager.getInstance();
net.minecraft.tags.ITagCollection<Fluid> previousFluids = net.minecraft.tags.FluidTags.getAllTags();
net.minecraft.tags.ITagCollectionSupplier restore = net.minecraft.tags.ITagCollectionSupplier.of(previousTags.getBlocks(), previousTags.getItems(), previousFluids, previousTags.getEntityTypes());
net.minecraft.tags.TagRegistry<?> fluidRegistry = net.minecraft.tags.TagRegistryManager.get(new ResourceLocation("fluid"));
'@ @'
net.minecraft.tags.TagContainer previousTags = net.minecraft.tags.SerializationTags.getInstance();
net.minecraft.tags.TagCollection<Fluid> previousFluids = net.minecraft.tags.FluidTags.getAllTags();
java.util.Map<net.minecraft.resources.ResourceKey<? extends net.minecraft.core.Registry<?>>, net.minecraft.tags.TagCollection<?>> restoredCollections = new java.util.HashMap<>(previousTags.collections);
restoredCollections.put(net.minecraft.core.Registry.FLUID_REGISTRY, previousFluids);
net.minecraft.tags.TagContainer restore = new net.minecraft.tags.TagContainer(restoredCollections);
net.minecraft.tags.StaticTagHelper<?> fluidRegistry = net.minecraft.tags.StaticTags.get(new ResourceLocation("fluid"));
'@ 'native fluid tag snapshot'
            $source = $source.Replace('net.minecraft.tags.ITag<', 'net.minecraft.tags.Tag<').Replace('net.minecraft.tags.ITag.fromSet(', 'net.minecraft.tags.Tag.fromSet(')
            $source = Adapt-Exact $source 'net.minecraft.tags.ITagCollectionSupplier temporary = net.minecraft.tags.ITagCollectionSupplier.of(previousTags.getBlocks(), previousTags.getItems(), net.minecraft.tags.ITagCollection.of(tags), previousTags.getEntityTypes());' @'
java.util.Map<net.minecraft.resources.ResourceKey<? extends net.minecraft.core.Registry<?>>, net.minecraft.tags.TagCollection<?>> temporaryCollections = new java.util.HashMap<>(previousTags.collections);
temporaryCollections.put(net.minecraft.core.Registry.FLUID_REGISTRY, net.minecraft.tags.TagCollection.of(tags));
net.minecraft.tags.TagContainer temporary = new net.minecraft.tags.TagContainer(temporaryCollections);
'@ 'native isolated fluid tags'
            $source = $source.Replace('net.minecraft.tags.TagCollectionManager.bind(', 'net.minecraft.tags.SerializationTags.bind(')
            $source = Adapt-Exact $source 'new BlockAndTintGetter() {' 'new BlockAndTintGetter() { @Override public int getHeight() { return 256; } @Override public int getMinBuildHeight() { return 0; }' 'native fluid height'
            $source = Adapt-Exact $source 'buffer.begin(7, DefaultVertexFormat.BLOCK);' 'buffer.begin(com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);' 'native fluid quad enum'
        }
        MachineGuiLayoutProbe {
            $source = Adapt-Exact $source 'block.createTileEntity(block.defaultBlockState(), null)' '((net.minecraft.world.level.block.EntityBlock)block).newBlockEntity(BlockPos.ZERO, block.defaultBlockState())' 'native GUI tile factory'
            $source = Adapt-Exact $source 'tile.setLevelAndPosition(world, BlockPos.ZERO);' 'tile.setLevel(world);' 'native GUI world assignment'
            $source = Adapt-Exact $source 'RenderSystem.matrixMode(5889); RenderSystem.pushMatrix(); RenderSystem.loadIdentity(); RenderSystem.ortho(0, viewport[0], viewport[1], 0, 1000, 3000); RenderSystem.matrixMode(5888); RenderSystem.pushMatrix(); RenderSystem.loadIdentity(); RenderSystem.translatef(0, 0, -2000); RenderSystem.enableAlphaTest();' 'NativeGuiProjection projection = new NativeGuiProjection(viewport[0], viewport[1], 1000, 3000, -2000);' 'GUI shader projection'
            $source = Adapt-Exact $source 'RenderSystem.popMatrix(); RenderSystem.matrixMode(5889); RenderSystem.popMatrix(); RenderSystem.matrixMode(5888);' 'projection.close();' 'GUI projection restore'
        }
        ChildRendererGeometryProbe {
            $source = Adapt-Exact $source 'BlockEntity tile = type.create();' @'
net.minecraft.world.level.block.state.BlockState state = ForgeRegistries.BLOCKS.getValues().stream().map(net.minecraft.world.level.block.Block::defaultBlockState).filter(type::isValid).findFirst().orElseThrow(() -> new IllegalStateException("No valid native block state for " + id));
BlockEntity tile = type.create(net.minecraft.core.BlockPos.ZERO, state);
'@ 'native registered renderer tile factory'
            $source = Adapt-Exact $source 'type.mode() == 7' 'type.mode() == com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS' 'native renderer quad enum'
        }
    }
    $source
}
foreach ($name in $referenceHashes.Keys) {
    $source = Read-Baseline "$relative/$name.java" $referenceHashes[$name]
    $target = Get-Content -LiteralPath (Join-Path $TargetRoot "$relative/$name.java") -Raw
    $actualImports = @([regex]::Matches($target, '(?m)^import ([^;\r\n]+);') | ForEach-Object { $_.Groups[1].Value }) -join "`n"
    Assert-Contract ((Expected-NativeImports $source) -ceq $actualImports) "$name import bindings changed beyond explicit native class moves."
    $expected = Expected-NativeProbe $name $source
    $actual = Canonical-Source $target
    # Complete bodies include inputs, captures, case loops, operands and thresholds.
    if ($expected -cne $actual) {
        $index = 0
        while ($index -lt [Math]::Min($expected.Length, $actual.Length) -and $expected[$index] -ceq $actual[$index]) { $index++ }
        throw "$name changed beyond explicit native adapters at character $index. Expected: $($expected.Substring($index, [Math]::Min(180, $expected.Length-$index))). Actual: $($actual.Substring($index, [Math]::Min(180, $actual.Length-$index)))"
    }
    Assert-Contract $true "$name complete oracle"
}
$projection = @'
package zone.moddev.mc.advantageworks.client;
final class NativeGuiProjection implements AutoCloseable {
    private final Matrix4f projection = RenderSystem.getProjectionMatrix().copy();
    private final PoseStack modelView = RenderSystem.getModelViewStack();
    NativeGuiProjection(float width, float height, float near, float far, float depth) {
        RenderSystem.setProjectionMatrix(Matrix4f.orthographic(0, width, 0, height, near, far));
        modelView.pushPose(); modelView.setIdentity(); modelView.translate(0, 0, depth); RenderSystem.applyModelViewMatrix();
    }
    @Override public void close() {
        modelView.popPose(); RenderSystem.applyModelViewMatrix(); RenderSystem.setProjectionMatrix(projection);
    }
}
'@
# Matrix4f's six-argument overload takes left/right/TOP/BOTTOM/near/far.
# Preserve the old downward Y axis, depth extents and balanced state restoration.
Assert-Contract ((Canonical-Source $projection) -ceq (Canonical-Source (Get-Content -LiteralPath (Join-Path $TargetRoot "$relative/NativeGuiProjection.java") -Raw))) 'Native projection adapter changed its top/bottom order, extents, depth or state restoration.'
$projectionSource = Get-Content -LiteralPath (Join-Path $TargetRoot "$relative/NativeGuiProjection.java") -Raw
$projectionImports = @([regex]::Matches($projectionSource, '(?m)^import ([^;\r\n]+);') | ForEach-Object { $_.Groups[1].Value }) -join "`n"
Assert-Contract ($projectionImports -ceq "com.mojang.blaze3d.systems.RenderSystem`ncom.mojang.blaze3d.vertex.PoseStack`ncom.mojang.math.Matrix4f") 'Native projection imports must resolve to the real shader and matrix APIs.'
$labels = 'resources/gui-label-expectations.json'
$acceptedLabels = Read-Baseline $labels 'EC0B66B926053E90C216D856B5F33C19D5B7E92DBC4C9E2E25812B401AEBAC0E'
Assert-Contract (($acceptedLabels.Replace("`r`n", "`n")) -ceq ([IO.File]::ReadAllText((Join-Path $TargetRoot $labels)).Replace("`r`n", "`n"))) 'Independent human-facing caption expectations changed.'
$pack = Get-Content -LiteralPath (Join-Path $TargetRoot 'resources/pack.mcmeta') -Raw | ConvertFrom-Json
Assert-Contract ($pack.pack.pack_format -eq 7) 'Native 1.17 probe resource pack must use format 7.'
$metadata = Get-Content -LiteralPath (Join-Path $TargetRoot 'resources/META-INF/mods.toml') -Raw
foreach ($pin in 'loaderVersion="[37,38)"','versionRange="[1.17.1]"','versionRange="[37.1.1]"','versionRange="[2.4.9.117011]"') {
    Assert-Contract ($metadata.Contains($pin)) "Missing native probe metadata pin: $pin"
}
$runner = Get-Content -LiteralPath (Join-Path $TargetRoot 'run.ps1') -Raw
$captionGate = @'
$expectedGuiTextChecks = if ($RequireExtensions) { 351 } else { 126 }
if ($MachineGuiLayouts -and ($result.machine_gui_text_spec -ne 2 -or $result.machine_gui_text_status -ne 'PASS' -or
        $result.machine_gui_text_checks -ne $expectedGuiTextChecks -or
        $result.machine_gui_negative_controls_rejected -ne ($expectedGuiTextChecks + 4))) {
    throw 'Required-caption GUI contract v2 did not pass; an older no-label probe is not acceptable.'
}
'@
Assert-Contract ((Canonical-Source $runner).Contains((Canonical-Source $captionGate))) 'Runner must require all 351 caption checks and 355 negative controls (126/130 for Power only).'
$failure = [regex]::Match($runner, '(?s)\} catch \{\s*# Preserve native partial results.*?\} finally \{').Value
Assert-Contract ($failure.Contains('runner-failure.json') -and $failure.Contains('if (-not (Test-Path')) 'Runner must preserve partial native results and write its failure separately.'
Assert-Contract ($runner -notmatch '\b(?:Remove-Item|Clear-Content)\b') 'Runner must not delete old or failed evidence.'
Write-Output "PASS: $checks static native probe preservation checks; six complete bodies bound to accepted 1.16 commit $baseline and SHA-256. Compilation and graphical acceptance NOT RUN."
