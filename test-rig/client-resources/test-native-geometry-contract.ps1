param(
    [string]$RepositoryRoot = (Join-Path $PSScriptRoot '../..'),
    [string]$TargetRoot = $PSScriptRoot,
    [string]$NativeSourcesJar
)
$ErrorActionPreference = 'Stop'
$RepositoryRoot = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$TargetRoot = (Resolve-Path -LiteralPath $TargetRoot).Path
$checks = 0
function Assert-NativeGeometry([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
    $script:checks++
}
$relative = 'java/zone/moddev/mc/advantageworks/client'
$geometry = Get-Content -LiteralPath (Join-Path $TargetRoot "$relative/ChildRendererGeometryProbe.java") -Raw
$main = Get-Content -LiteralPath (Join-Path $TargetRoot "$relative/ClientResourceProbe.java") -Raw
$projection = Get-Content -LiteralPath (Join-Path $TargetRoot "$relative/NativeGuiProjection.java") -Raw
$runner = Get-Content -LiteralPath (Join-Path $TargetRoot 'run.ps1') -Raw
& (Join-Path $PSScriptRoot 'test-native-probe-contract.ps1') -RepositoryRoot $RepositoryRoot -TargetRoot $TargetRoot
Assert-NativeGeometry ($geometry -notmatch '(?m)^import zone\.moddev\.mc\.(?:basesteam|baseelectric)\.') 'Geometry must not compile against expansion substitutes.'
Assert-NativeGeometry ($geometry -notmatch 'new (?:DrillBitRenderer|LaserDrillRenderer|LaserTurretRenderer|HydroturbineRenderer)|renderGeometry\(|Unsafe|allocateInstance|new Client(?:World|Level)|loadWorld\(') 'Use dispatcher-owned native production renderers without constructor/world bypasses.'
foreach ($fragment in 'minecraft.getBlockEntityRenderDispatcher().getRenderer(tile)', 'minecraft.getEntityRenderDispatcher().renderers.get(type)',
        'renderer.createRenderState()', 'renderer.extractRenderState(tile, state, partial', 'renderer.submit(state, matrices, collector',
        'geometry.render(submitted.last(), buffers.getBuffer(pipeline))', 'new BufferBuilder(allocation, type.mode(), type.format())',
        'try (var built = buffer.buildOrThrow())', 'int count = built.drawState().vertexCount();', 'ByteBuffer data = built.vertexBuffer();',
        'int base = i * format.getVertexSize();', 'data.getFloat(base + p)', 'data.getFloat(base + uv)', 'data.get(base + n) / 127F',
        '(data.get(base + c + 3) & 255) / 255F', 'vertices.add(vertex);', 'valueOfType(entry.getKey(), net.minecraft.client.renderer.rendertype.RenderSetup.class)',
        'texturesField.get(state)).get("Sampler0")', 'expectedTexture.equals(selectedTexture)', 'ImageIO.read(resource)',
        'private static final float EPSILON = 0.0001F;', 'mesh(vertices, 24)', 'mesh(vertices, 16)', 'mesh(vertices, 40)',
        'new float[]{0F, 0.5F}', 'for (Direction facing : Direction.values())', 'partial-tick rotation did not move vertices',
        'position.equals(original.pose()) && normal.equals(original.normal())', 'matrices.isEmpty() && matrices.last() == original',
        'native_buffers', 'vertices_xyz_uv_normal_alpha', 'bounds_xyz', 'uv_bounds', 'Float.isFinite(value)', 'blank/degenerate quad',
        'Beam cardinal bounds/length changed', 'Idle turret uses the active optic UVs', 'Active turret uses the idle optic UVs',
        'renderFailure.addSuppressed(failure)', 'child-renderer-geometry.json', 'MANUAL_GAP_WORLD_REQUIRED', 'PASS_WITH_MANUAL_GAPS',
        'cases.size() == 26', 'passed == 25 && gaps == 1', 'type::isValid', 'type.create(net.minecraft.core.BlockPos.ZERO, state)') {
    Assert-NativeGeometry ($geometry.Contains($fragment)) "Missing native geometry contract: $fragment"
}
foreach ($fragment in 'new GuiRenderState()', 'new GuiRenderer(', 'new TextureTarget(', 'graphics.renderDeferredElements()',
        'renderer.render(fog.getBuffer(FogRenderer.FogMode.NONE))', 'Screenshot.takeScreenshot(target, captured::set)',
        'fence.awaitCompletion(10_000_000_000L)', 'RenderSystem.executePendingTasks()', 'image.getPixel(x, y)',
        'targetField.set(minecraft, original)', 'widthField.setInt(minecraft.getWindow(), width)',
        'heightField.setInt(minecraft.getWindow(), height)', 'minecraft.getWindow().setGuiScale(scale)',
        'RenderSystem.setProjectionMatrix(projection, projectionType)', 'target.destroyBuffers()') {
    Assert-NativeGeometry ($projection.Contains($fragment)) "Missing real native GUI extraction/readback/restoration: $fragment"
}
Assert-NativeGeometry ($projection -notmatch 'readPixels|glReadPixels|matrixMode|loadIdentity|\.ortho\(') 'Native GUI proof must not retain removed immediate-mode GL.'
Assert-NativeGeometry ($main.IndexOf('geometry = inspect("child_geometry"') -gt $main.IndexOf('primary_resource_status')) 'Bounded child geometry must follow all primary scopes.'
Assert-NativeGeometry ($main.Contains('require(primary && geometry,')) 'All failed native scopes must keep the complete probe red.'
Assert-NativeGeometry ($runner.Contains('if ($ChildRendererGeometry -and -not $RequireExtensions)')) 'Geometry must require the combined scope.'
if ($NativeSourcesJar) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $NativeSourcesJar).Path)
    $sources = @{}
    try {
        foreach ($pin in @{
            'net/minecraft/client/gui/render/GuiRenderer.java'='8242E8AA34C6EEDDE5E1D8BC279D929DABA29E0EFFE3CC9E92AECA0FB94E1519'
            'net/minecraft/client/renderer/CachedOrthoProjectionMatrixBuffer.java'='E74DC690F46A7D62263734A5F885980499C43927E2A88D34344238601AEBA724'
            'net/minecraft/client/Screenshot.java'='3C14AF62FF2854E4F3E0832C51ED88846D98A9C0E319D425F9469644CFA4EC12'
        }.GetEnumerator()) {
            $entry = $archive.GetEntry($pin.Key)
            Assert-NativeGeometry ($null -ne $entry) "Native source entry missing: $($pin.Key)"
            $reader = [IO.StreamReader]::new($entry.Open())
            try { $text = $reader.ReadToEnd() } finally { $reader.Dispose() }
            $sha = [Security.Cryptography.SHA256]::Create()
            try { $hash = [BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($text))).Replace('-', '') }
            finally { $sha.Dispose() }
            Assert-NativeGeometry ($hash -ceq $pin.Value) "Mapped Forge 61 source changed: $($pin.Key)"
            $sources[$pin.Key] = $text
        }
    } finally { $archive.Dispose() }
    Assert-NativeGeometry ($sources['net/minecraft/client/gui/render/GuiRenderer.java'].Contains('window.getWidth() / window.getGuiScale()')) 'Native GUI projection must use the scoped framebuffer width and GUI scale.'
    Assert-NativeGeometry ($sources['net/minecraft/client/gui/render/GuiRenderer.java'].Contains('window.getHeight() / window.getGuiScale()')) 'Native GUI projection must use the scoped framebuffer height and GUI scale.'
    Assert-NativeGeometry ($sources['net/minecraft/client/renderer/CachedOrthoProjectionMatrixBuffer.java'].Contains('this.invertY ? p_409651_ : 0.0F, this.invertY ? 0.0F : p_409651_')) 'Native GUI projection must retain the downward Y axis.'
    Assert-NativeGeometry ($sources['net/minecraft/client/Screenshot.java'].Contains('.copyTextureToBuffer(')) 'Native screenshot proof must read the actual GPU target.'
} else { Write-Output 'NOT RUN: native engine-source audit; supply the actual Forge 61 mapped source archive.' }
Write-Output "PASS: $checks native geometry, extraction/readback/restoration and optional engine-source checks. This static audit is not a graphical acceptance receipt."
