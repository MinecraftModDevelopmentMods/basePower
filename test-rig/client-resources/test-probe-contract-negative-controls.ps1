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
    @{ name='tank-packet-quantity'; file='TankVisualProbe'; before='client.getTank().getFluidAmount() == amount'; after='client.getTank().getFluidAmount() >= 0' },
    @{ name='tank-simulation-mutation'; file='TankVisualProbe'; before='server.getTank().getFluidAmount() == 0'; after='server.getTank().getFluidAmount() >= 0' },
    @{ name='tank-pixel-coordinate'; file='TankVisualProbe'; before='expected.getRGB(x, y)'; after='expected.getRGB(0, 0)' },
    @{ name='tank-reference-threshold'; file='TankVisualProbe'; before='visible >= rows * TILE / 2'; after='visible >= 0' },
    @{ name='bucket-volume'; file='OilRenderProbe'; before='contents.getAmount() != 1000'; after='contents.getAmount() != 100' },
    @{ name='bucket-fluid-identity'; file='OilRenderProbe'; before='contents.getFluid() != expected'; after='false' },
    @{ name='fluid-primitive-capture'; file='OilRenderProbe'; before='int vertices = built.drawState().vertexCount();'; after='int vertices = 4;' },
    @{ name='fluid-tags-restore'; file='OilRenderProbe'; before='BuiltInRegistries.FLUID.bindTags(previous);'; after='BuiltInRegistries.FLUID.bindTags(synthetic);' },
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
    @{ name='projection-y-order'; file='NativeGuiProjection'; before='setOrtho(0, width, height, 0, near, far)'; after='setOrtho(0, width, 0, height, near, far)' },
    @{ name='projection-restoration'; file='NativeGuiProjection'; before='RenderSystem.setProjectionMatrix(projection, sorting);'; after='' },
    @{ name='tank-independent-sprite'; file='TankVisualProbe'; before='graphics.blit(GAUGE_X, y, 0, TILE, TILE, sprite,'; after='graphics.blit(0, 0, 0, TILE, TILE, sprite,' },
    @{ name='tank-pixel-equality'; file='TankVisualProbe'; before='if (actual.getRGB(x, y) != expectedPixel) different++;'; after='if (false) different++;' },
    @{ name='tank-native-capability'; file='TankVisualProbe'; before='require(handler != null,'; after='require(true,' },
    @{ name='registry-packet-tail'; file='RecipeBookGroupingProbe'; before='require(!snapshotBuffer.isReadable(),'; after='require(true,' },
    @{ name='registry-receipt-sha'; file='RecipeBookGroupingProbe'; before='require(packetHash.equals(receipt.get("packetSha256").getAsString())'; after='require(true || packetHash.equals(receipt.get("packetSha256").getAsString())' },
    @{ name='registry-receipt-version'; file='RecipeBookGroupingProbe'; before='"21.1.265".equals(receipt.get("neoForgeVersion").getAsString())'; after='true' },
    @{ name='registry-receipt-same-ids'; file='RecipeBookGroupingProbe'; before='require(receipt.get("sameRegistries").getAsBoolean(),'; after='require(true,' },
    @{ name='registry-receipt-filename'; file='RecipeBookGroupingProbe'; before='snapshotName(packetName).equals(receipt.get("file").getAsString())'; after='true' },
    @{ name='registry-wire-count'; file='RecipeBookGroupingProbe'; before='require(wireCount == receipt.get("registryCount").getAsInt(),'; after='require(true,' },
    @{ name='registry-apply-exact-ids'; file='RecipeBookGroupingProbe'; before='applied.byId(id.getIntKey()) != null && applied.byId(id.getIntKey()) == applied.get(id.getValue())'; after='true' },
    @{ name='registry-per-packet-load'; file='RecipeBookGroupingProbe'; before='NativeRecipeFixture fixture = loadNativeFixture(file, manifest);'; after='NativeRecipeFixture fixture = loadNativeFixture(packets.get(0), manifest);' },
    @{ name='registry-sidecar-set'; file='RecipeBookGroupingProbe'; before='require(snapshotNames.equals(packetNames.stream().map(RecipeBookGroupingProbe::snapshotName).collect(Collectors.toSet())),'; after='require(true,' },
    @{ name='registry-packet-recipe-count'; file='RecipeBookGroupingProbe'; before='require(packet.getRecipes().size() == manifest.getAsJsonObject("registrySnapshot").get("recipeCount").getAsInt(),'; after='require(true,' },
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
    [ordered]@{ scope='STATIC_CONTRACT_MUTATIONS_NOT_RENDERED_CLIENT'; baseline='8d9937b3c0c9ef28a231b90fc2ff6adfea1af733';
        planned=$controls.Count; completed=$results.Count; inputs=$inputs; results=$results } |
        ConvertTo-Json -Depth 7 | Set-Content -LiteralPath (Join-Path $output 'results.json') -Encoding utf8
}
Write-Output "PASS: $($controls.Count) weakened-oracle controls rejected for their intended reasons; unmodified fixture passed. No Java/client was run; source and worlds unchanged. Evidence: $output"
