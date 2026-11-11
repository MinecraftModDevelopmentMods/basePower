param([string]$EvidenceRoot)
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$outputRoot = if ($EvidenceRoot) { [IO.Path]::GetFullPath($EvidenceRoot) }
    else { Join-Path $project 'run-client-probe-contract-controls' }
$output = Join-Path $outputRoot ((Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $output | Out-Null
$relative = 'java/zone/moddev/mc/advantageworks/client'
$controls = @(
    @{ name='recipe-pixel-threshold'; file='RecipeBookGroupingProbe'; before='colored > 1000'; after='colored > 100' },
    @{ name='recipe-cell-count'; file='RecipeBookGroupingProbe'; before='shown.size() == 13'; after='shown.size() >= 1' },
    @{ name='recipe-packet-tail'; file='RecipeBookGroupingProbe'; before='require(!buffer.isReadable(), "Trailing recipe packet bytes");'; after='require(true, "Trailing recipe packet bytes");' },
    @{ name='tank-packet-quantity'; file='TankVisualProbe'; before='client.getTank().getFluidAmount() == amount &&'; after='client.getTank().getFluidAmount() >= 0 &&' },
    @{ name='tank-simulation-mutation'; file='TankVisualProbe'; before='server.getTank().getFluidAmount() == 0'; after='server.getTank().getFluidAmount() >= 0' },
    @{ name='tank-pixel-coordinate'; file='TankVisualProbe'; before='actual.getRGB(x, y) != expectedPixel'; after='actual.getRGB(0, 0) != expectedPixel' },
    @{ name='tank-whole-gauge-zero-differences'; file='TankVisualProbe'; before='row.getAsJsonObject().get("different_pixels").getAsInt() == 0'; after='row.getAsJsonObject().get("different_pixels").getAsInt() >= 0' },
    @{ name='bucket-volume'; file='OilRenderProbe'; before='contents.getAmount() != 1000'; after='contents.getAmount() != 100' },
    @{ name='bucket-fluid-identity'; file='OilRenderProbe'; before='contents.getFluid() != expected'; after='false' },
    @{ name='fluid-primitive-capture'; file='OilRenderProbe'; before='int vertices = built.drawState().vertexCount();'; after='int vertices = 4;' },
    @{ name='fluid-tags-restore'; file='OilRenderProbe'; before='net.minecraft.core.registries.Registries.FLUID, previous)).apply();'; after='net.minecraft.core.registries.Registries.FLUID, synthetic)).apply();' },
    @{ name='missing-model'; file='ClientResourceProbe'; before='require(model != missing,'; after='require(model == missing,' },
    @{ name='missing-model-quads'; file='ClientResourceProbe'; before='!requireQuads || !quads.isEmpty()'; after='true' },
    @{ name='missing-caption'; file='MachineGuiLayoutProbe'; before='require(labelError == null, labelError);'; after='require(true, labelError);' },
    @{ name='caption-negative-control'; file='MachineGuiLayoutProbe'; before='require(labelError(background, expected, background, screen, viewport[2]) != null,'; after='require(true,' },
    @{ name='caption-viewport'; file='MachineGuiLayoutProbe'; before='{{320, 240, 1}, {426, 240, 2}, {640, 360, 1}}'; after='{{320, 240, 1}}' },
    @{ name='geometry-count'; file='ChildRendererGeometryProbe'; before='require(vertices.size() == count,'; after='require(vertices.size() >= 0,' },
    @{ name='geometry-epsilon'; file='ChildRendererGeometryProbe'; before='EPSILON = 0.0001F'; after='EPSILON = 0.1F' },
    @{ name='geometry-degenerate-threshold'; file='ChildRendererGeometryProbe'; before='x * x + y * y + z * z > 0.00000001F'; after='x * x + y * y + z * z >= 0F' },
    @{ name='geometry-primitive-capture'; file='ChildRendererGeometryProbe'; before='data.getFloat(base + p)'; after='0F' },
    @{ name='geometry-native-buffer-import'; file='ChildRendererGeometryProbe'; before='import com.mojang.blaze3d.vertex.BufferBuilder;'; after='import fake.BufferBuilder;' },
    @{ name='geometry-actual-texture'; file='ChildRendererGeometryProbe'; before='expectedTexture.equals(selectedTexture)'; after='true' },
    @{ name='geometry-matrix-balance'; file='ChildRendererGeometryProbe'; before='position.equals(original.pose()) && normal.equals(original.normal())'; after='true' },
    @{ name='geometry-manual-gap'; file='ChildRendererGeometryProbe'; before='passed == 25 && gaps == 1'; after='passed >= 0' },
    @{ name='projection-framebuffer-axis'; file='NativeGuiProjection'; before='widthField.setInt(window, width);'; after='widthField.setInt(window, height);' },
    @{ name='projection-restoration'; file='NativeGuiProjection'; before='RenderSystem.setProjectionMatrix(projection, projectionType);'; after='' },
    @{ name='runner-caption-count'; path='run.ps1'; before='{ 351 } else { 126 }'; after='{ 350 } else { 126 }'; error='Runner must require all 351' },
    @{ name='runner-negative-count'; path='run.ps1'; before='($expectedGuiTextChecks + 4)'; after='($expectedGuiTextChecks + 3)'; error='Runner must require all 351' },
    @{ name='native-tag-packet-tail'; file='RecipeBookGroupingProbe'; before='require(!buffer.isReadable(), "Trailing native tag packet bytes");'; after='require(true, "Trailing native tag packet bytes");' },
    @{ name='native-tag-packet-required'; file='RecipeBookGroupingProbe'; before='payload != null && !payload.isEmpty()'; after='true' },
    @{ name='native-tag-exact-members'; file='RecipeBookGroupingProbe'; before='.equals(expected.get(key.location()))'; after='.containsAll(expected.get(key.location()))' }
)
$results = @()
$inputs = @{}
foreach ($file in Get-ChildItem -LiteralPath $PSScriptRoot -File -Recurse) {
    $inputs[$file.FullName] = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
}
try {
    # A failing prerequisite must never masquerade as a successful negative control.
    & (Join-Path $PSScriptRoot 'test-probe-contract.ps1') -RepositoryRoot $project
    $fixture = Join-Path $output 'valid-fixture'
    Copy-Item -LiteralPath $PSScriptRoot -Destination $fixture -Recurse
    & (Join-Path $PSScriptRoot 'test-probe-contract.ps1') -RepositoryRoot $project -TargetRoot $fixture
    foreach ($control in $controls) {
        $fixture = Join-Path $output $control.name
        Copy-Item -LiteralPath $PSScriptRoot -Destination $fixture -Recurse
        $path = if ($control.path) { Join-Path $fixture $control.path }
            else { Join-Path $fixture "$relative/$($control.file).java" }
        $source = [IO.File]::ReadAllText($path)
        $mutationCount = [regex]::Matches($source, [regex]::Escape($control.before)).Count
        if ($mutationCount -ne 1) { throw "Negative control needs exactly one mutation point: $($control.name), got $mutationCount" }
        # Generated mutants only; actual probes, reference blobs and worlds are untouched.
        [IO.File]::WriteAllText($path, $source.Replace($control.before, $control.after), [Text.UTF8Encoding]::new($false))
        $expected = if ($control.error) { $control.error } else { "$($control.file) changed beyond explicit native adapters" }
        $rejected = $false
        $failure = $null
        try { & (Join-Path $PSScriptRoot 'test-probe-contract.ps1') -RepositoryRoot $project -TargetRoot $fixture | Out-Null }
        catch {
            $failure = $_.Exception.Message
            $rejected = $failure.StartsWith($expected, [StringComparison]::Ordinal) -or
                (-not $control.error -and $failure.StartsWith("Changed inherited oracle method: $($control.file).", [StringComparison]::Ordinal))
        }
        $results += [ordered]@{ control=$control.name; expected='REJECT_FOR_INTENDED_REASON'; rejected=$rejected; error=$failure }
        if (-not $rejected) { throw "Weakened oracle was accepted or rejected for an unrelated error: $($control.name). Actual: $failure. Evidence: $output" }
    }
    foreach ($path in $inputs.Keys) {
        if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -cne $inputs[$path]) { throw "Actual probe source changed during controls: $path" }
    }
} finally {
    [ordered]@{ scope='STATIC_CONTRACT_MUTATIONS_NOT_RENDERED_CLIENT'; baseline='c310d0ca63ad9a8408dd4bba75172afafa5c71b7';
        planned=$controls.Count; completed=$results.Count; inputs=$inputs; results=$results } |
        ConvertTo-Json -Depth 7 | Set-Content -LiteralPath (Join-Path $output 'results.json') -Encoding utf8
}
Write-Output "PASS: $($controls.Count) weakened-oracle controls rejected for their intended reasons; unmodified fixture passed. JVM source parsing only; no Minecraft client/world was run. Source and worlds unchanged. Evidence: $output"
$global:LASTEXITCODE = 0
