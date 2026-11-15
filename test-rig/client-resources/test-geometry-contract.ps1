param(
    [string]$RepositoryRoot = (Join-Path $PSScriptRoot '../..'),
    [string]$TargetRoot = $PSScriptRoot,
    [string]$NativeSourcesJar
)
$ErrorActionPreference = 'Stop'
$RepositoryRoot = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$TargetRoot = (Resolve-Path -LiteralPath $TargetRoot).Path
$checks = 0
function Assert-GeometryContract([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
    $script:checks++
}
$relative = 'java/zone/moddev/mc/advantageworks/client'
$geometry = Get-Content -LiteralPath (Join-Path $TargetRoot "$relative/ChildRendererGeometryProbe.java") -Raw
$main = Get-Content -LiteralPath (Join-Path $TargetRoot "$relative/ClientResourceProbe.java") -Raw
$runner = Get-Content -LiteralPath (Join-Path $TargetRoot 'run.ps1') -Raw
Assert-GeometryContract ($geometry -notmatch '(?m)^import zone\.moddev\.mc\.(?:basesteam|baseelectric)\.') 'Child renderer probe must not add compile dependencies on the expansions.'
Assert-GeometryContract ($geometry -notmatch 'new (?:DrillBitRenderer|LaserDrillRenderer|LaserTurretRenderer|HydroturbineRenderer)|renderGeometry\(|Unsafe|allocateInstance|new Client(?:World|Level)|loadWorld\(') 'Use dispatcher-owned production renderer instances without bypassing construction or loading a world.'
foreach ($fragment in 'minecraft.getBlockEntityRenderDispatcher().getRenderer(tile)', 'registeredEntityRenderer(minecraft, type)',
        'renderer.render(tile, partial, matrices, buffers', 'new BufferBuilder(allocation, type.mode(), type.format())',
        'buffer.buildOrThrow()', 'private static final float EPSILON = 0.0001F;',
        'mesh(vertices, 24)', 'mesh(vertices, 16)', 'mesh(vertices, 40)',
        'new float[]{0F, 0.5F}', 'for (Direction facing : Direction.values())',
        'partial-tick rotation did not move vertices', 'Production renderer did not restore its matrix stack',
        'position.equals(original.pose()) && normal.equals(original.normal())',
        'native_buffers', 'vertices_xyz_uv_normal_alpha', 'bounds_xyz', 'uv_bounds',
        'Float.isFinite(value)', 'blank/degenerate quad', 'Beam cardinal bounds/length changed',
        'Idle turret uses the active optic UVs', 'Active turret uses the idle optic UVs',
        'Production renderer selected the wrong texture', 'ImageIO.read(resource)',
        'renderFailure.addSuppressed(failure)', 'child-renderer-geometry.json',
        'MANUAL_GAP_WORLD_REQUIRED', 'PASS_WITH_MANUAL_GAPS',
        'cases.size() == 26', 'passed == 25 && gaps == 1') {
    Assert-GeometryContract ($geometry.Contains($fragment)) "Missing geometry contract: $fragment"
}
$geometryCall = $main.IndexOf('if (Boolean.getBoolean("advantageworks.childRendererGeometry")) ChildRendererGeometryProbe.verify(minecraft, result);')
Assert-GeometryContract ($geometryCall -gt $main.IndexOf('RecipeBookGroupingProbe.verify(minecraft, result);')) 'Optional geometry must follow all existing resource oracles.'
Assert-GeometryContract ($main.IndexOf('result.addProperty("primary_resource_status", "PASS");') -lt $geometryCall) 'Keep primary resource results separate from the extra renderer scope.'
Assert-GeometryContract ($runner.Contains('[switch]$ChildRendererGeometry') -and $runner.Contains('-Dadvantageworks.childRendererGeometry=true')) 'Additional renderer geometry must be explicitly selected.'
Assert-GeometryContract ($runner.Contains('if ($ChildRendererGeometry -and -not $RequireExtensions)')) 'Geometry must require the combined scope.'
# The primary contract compares all six complete bodies, including this capture,
# against SHA-bound released Forge 1.21.1 Git blobs with exact NeoForge adapters only.
& (Join-Path $PSScriptRoot 'test-probe-contract.ps1') -RepositoryRoot $RepositoryRoot -TargetRoot $TargetRoot
$checks++
foreach ($fragment in 'buffer.buildOrThrow()', 'int count = built.drawState().vertexCount();',
        'ByteBuffer data = built.vertexBuffer();', 'int base = i * format.getVertexSize();',
        'data.getFloat(base + p)', 'data.getFloat(base + uv)', 'data.get(base + n) / 127F',
        '(data.get(base + c + 3) & 255) / 255F', 'vertices.add(vertex);',
        'valueOfType(entry.getKey(), RenderType.CompositeState.class)',
        'valueOfType(state, RenderStateShard.EmptyTextureStateShard.class)',
        'expectedTexture.equals(selectedTexture)', 'type::isValid',
        'type.create(net.minecraft.core.BlockPos.ZERO, state)') {
    Assert-GeometryContract ($geometry.Contains($fragment)) "Missing actual native primitive/factory capture: $fragment"
}
if ($NativeSourcesJar) {
    # This optional local source audit does not start Java or Gradle. Its hash is
    # bound to JOML 1.10.5, used by the native 1.21.1 client candidate.
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $NativeSourcesJar).Path)
    try {
        $entry = $archive.GetEntry('org/joml/Matrix4f.java')
        Assert-GeometryContract ($null -ne $entry) 'Native source archive lacks Matrix4f.java.'
        $reader = [IO.StreamReader]::new($entry.Open())
        try { $source = $reader.ReadToEnd() } finally { $reader.Dispose() }
    } finally { $archive.Dispose() }
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $hash = [BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($source))).Replace('-', '') }
    finally { $sha.Dispose() }
    Assert-GeometryContract ($hash -ceq '99210EE3C018FEAD7233DB4C10815A13FB97D809A57A47E8B7763E19DCC4F958') 'Native Matrix4f source hash changed; re-establish API equivalence, do not rebaseline.'
    foreach ($formula in 'this._m00(2.0f / (right - left))', '._m11(2.0f / (top - bottom))',
            '._m22((zZeroToOne ? 1.0f : 2.0f) / (zNear - zFar))',
            '._m30((right + left) / (left - right))', '._m31((top + bottom) / (bottom - top))',
            '._m32((zZeroToOne ? zNear : (zFar + zNear)) / (zNear - zFar))') {
        Assert-GeometryContract ($source.Contains($formula)) "Native projection formula changed: $formula"
    }
    # JOML setOrtho matches GL's left/right/bottom/top ordering.
    foreach ($view in @(@(128,96,-1,1,0), @(1000,180,-1000,1000,0),
            @(320,240,1000,3000,-2000), @(426,240,1000,3000,-2000), @(640,360,1000,3000,-2000))) {
        $width,$height,$near,$far,$depth = $view
        $oldArgs = @(0,$width,$height,0,$near,$far)
        $newArgs = @(0,$width,$height,0,$near,$far)
        $gl = @((2.0/($oldArgs[1]-$oldArgs[0])), (2.0/($oldArgs[3]-$oldArgs[2])), (-2.0/($oldArgs[5]-$oldArgs[4])),
                (-($oldArgs[1]+$oldArgs[0])/($oldArgs[1]-$oldArgs[0])), (-($oldArgs[3]+$oldArgs[2])/($oldArgs[3]-$oldArgs[2])),
                (-($oldArgs[5]+$oldArgs[4])/($oldArgs[5]-$oldArgs[4])))
        $native = @((2.0/($newArgs[1]-$newArgs[0])), (2.0/($newArgs[3]-$newArgs[2])), (-2.0/($newArgs[5]-$newArgs[4])),
                (-($newArgs[1]+$newArgs[0])/($newArgs[1]-$newArgs[0])), (-($newArgs[3]+$newArgs[2])/($newArgs[3]-$newArgs[2])),
                (-($newArgs[5]+$newArgs[4])/($newArgs[5]-$newArgs[4])))
        Assert-GeometryContract (($gl -join ',') -ceq ($native -join ',')) 'Native projection differs from accepted GL coefficients.'
        Assert-GeometryContract ([Math]::Abs($native[1]*0+$native[4]-1) -lt 1e-9 -and
                [Math]::Abs($native[1]*$height+$native[4]+1) -lt 1e-9 -and
                [Math]::Abs($native[0]*$width+$native[3]-1) -lt 1e-9) 'Probe corners must retain their downward Y axis and full viewport.'
        Assert-GeometryContract ([Math]::Abs($native[2]*$depth+$native[5]-($gl[2]*$depth+$gl[5])) -lt 1e-9) 'Native model-view translation changed the capture depth.'
    }
    Write-Output "PASS: SHA-bound native Matrix4f top/bottom adapter proof, five projection/depth configurations. Source: $NativeSourcesJar"
} else {
    Write-Output 'NOT RUN: engine-source projection audit; supply -NativeSourcesJar with the JOML 1.10.5 sources archive.'
}
Write-Output "PASS: $checks static additional-geometry wiring and primary-oracle preservation checks. No renderer, Java or client was run."
