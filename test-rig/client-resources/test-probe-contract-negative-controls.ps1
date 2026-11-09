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
    @{ name='recipe-packet-tail'; file='RecipeBookGroupingProbe'; before='require(!buffer.isReadable(),'; after='require(true,' },
    @{ name='tank-packet-quantity'; file='TankVisualProbe'; before='clientTank.getTank().getFluidAmount() == 10000'; after='clientTank.getTank().getFluidAmount() == 1000' },
    @{ name='tank-simulation-mutation'; file='TankVisualProbe'; before='serverTank.getTank().getFluidAmount() == 0'; after='serverTank.getTank().getFluidAmount() >= 0' },
    @{ name='tank-pixel-coordinate'; file='TankVisualProbe'; before='image.getRGB(88, 39)'; after='image.getRGB(0, 0)' },
    @{ name='tank-blue-threshold'; file='TankVisualProbe'; before='blue > red + 20 && blue > green + 10'; after='blue >= 0' },
    @{ name='bucket-volume'; file='OilRenderProbe'; before='contents.getAmount() != 1000'; after='contents.getAmount() != 100' },
    @{ name='bucket-fluid-identity'; file='OilRenderProbe'; before='contents.getFluid() != expected'; after='false' },
    @{ name='fluid-primitive-capture'; file='OilRenderProbe'; before='int vertices = built.getFirst().vertexCount();'; after='int vertices = 4;' },
    @{ name='fluid-tags-restore'; file='OilRenderProbe'; before='fluidRegistry.reset(restore);'; after='fluidRegistry.reset(temporary);' },
    @{ name='missing-model'; file='ClientResourceProbe'; before='require(model != missing,'; after='require(model == missing,' },
    @{ name='missing-model-quads'; file='ClientResourceProbe'; before='!requireQuads || !quads.isEmpty()'; after='true' },
    @{ name='missing-caption'; file='MachineGuiLayoutProbe'; before='require(labelError == null, labelError);'; after='require(true, labelError);' },
    @{ name='caption-negative-control'; file='MachineGuiLayoutProbe'; before='require(labelError(background, expected, background, screen, viewport[2]) != null,'; after='require(true,' },
    @{ name='caption-viewport'; file='MachineGuiLayoutProbe'; before='{{320, 240, 1}, {426, 240, 2}, {640, 360, 1}}'; after='{{320, 240, 1}}' },
    @{ name='geometry-count'; file='ChildRendererGeometryProbe'; before='require(vertices.size() == count,'; after='require(vertices.size() >= 0,' },
    @{ name='geometry-epsilon'; file='ChildRendererGeometryProbe'; before='EPSILON = 0.0001F'; after='EPSILON = 0.1F' },
    @{ name='geometry-degenerate-threshold'; file='ChildRendererGeometryProbe'; before='x * x + y * y + z * z > 0.00000001F'; after='x * x + y * y + z * z >= 0F' },
    @{ name='geometry-primitive-capture'; file='ChildRendererGeometryProbe'; before='data.getFloat(base + p)'; after='0F' },
    @{ name='geometry-native-buffer-import'; file='ChildRendererGeometryProbe'; before='import com.mojang.blaze3d.vertex.BufferBuilder;'; after='import fake.BufferBuilder;'; error='ChildRendererGeometryProbe import bindings changed' },
    @{ name='geometry-actual-texture'; file='ChildRendererGeometryProbe'; before='expectedTexture.equals(selectedTexture)'; after='true' },
    @{ name='geometry-matrix-balance'; file='ChildRendererGeometryProbe'; before='position.equals(original.pose()) && normal.equals(original.normal())'; after='true' },
    @{ name='geometry-manual-gap'; file='ChildRendererGeometryProbe'; before='passed == 25 && gaps == 1'; after='passed >= 0' },
    @{ name='projection-y-order'; file='NativeGuiProjection'; before='orthographic(0, width, 0, height, near, far)'; after='orthographic(0, width, height, 0, near, far)'; error='Native projection adapter changed' },
    @{ name='projection-restoration'; file='NativeGuiProjection'; before='RenderSystem.setProjectionMatrix(projection);'; after=''; error='Native projection adapter changed' },
    @{ name='runner-caption-count'; path='run.ps1'; before='{ 351 } else { 126 }'; after='{ 350 } else { 126 }'; error='Runner must require all 351' },
    @{ name='runner-negative-count'; path='run.ps1'; before='($expectedGuiTextChecks + 4)'; after='($expectedGuiTextChecks + 3)'; error='Runner must require all 351' }
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
        catch { $failure = $_.Exception.Message; $rejected = $failure.StartsWith($expected, [StringComparison]::Ordinal) }
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
Write-Output "PASS: $($controls.Count) weakened-oracle controls rejected for their intended reasons; unmodified fixture passed. No Java/client was run; source and worlds unchanged. Evidence: $output"
