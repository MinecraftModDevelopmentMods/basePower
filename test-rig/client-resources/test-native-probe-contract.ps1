param(
    [string]$RepositoryRoot = (Join-Path $PSScriptRoot '../..'),
    [string]$TargetRoot = $PSScriptRoot,
    [string]$JavaHome = 'C:/Users/johnb/.jdks/temurin-21.0.7+6'
)
$ErrorActionPreference = 'Stop'
$RepositoryRoot = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$TargetRoot = (Resolve-Path -LiteralPath $TargetRoot).Path
function Assert-NativeContract([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}
function Canonical-NativeContract([string]$Source) {
    [regex]::Replace($Source, '"(?:\\.|[^"\\])*"|''(?:\\.|[^''\\])*''|/\*[\s\S]*?\*/|//[^\r\n]*|\s+', {
        param($match)
        if ($match.Value.StartsWith('"') -or $match.Value.StartsWith("'")) { $match.Value } else { '' }
    })
}
$java = Join-Path $JavaHome 'bin/java.exe'
$source = Join-Path $PSScriptRoot 'contracts/NativeProbeContract.java'
$rules = Join-Path $PSScriptRoot 'contracts/native61-adapters.properties'
$output = & $java $source $RepositoryRoot $TargetRoot $rules verify 2>&1
$code = $LASTEXITCODE
if ($code -ne 0) {
    $errorLine = @($output | ForEach-Object { "$_" } | Where-Object { $_ -match '^Exception in thread "main" java\.lang\.IllegalStateException: ' }) | Select-Object -First 1
    if ($errorLine) { throw ($errorLine -replace '^Exception in thread "main" java\.lang\.IllegalStateException: ', '') }
    throw "Native source parser failed: $($output -join [Environment]::NewLine)"
}
$output | ForEach-Object { "$_" } | Where-Object { $_ -notmatch '^Picked up JAVA_TOOL_OPTIONS:' } | Write-Output
$pack = Get-Content -LiteralPath (Join-Path $TargetRoot 'resources/pack.mcmeta') -Raw | ConvertFrom-Json
Assert-NativeContract (($pack.pack.min_format -join ',') -ceq '94,1' -and ($pack.pack.max_format -join ',') -ceq '94,1' -and
    -not ($pack.pack.PSObject.Properties.Name -contains 'pack_format')) 'Native probe metadata must retain exact root data range 94.1.'
$metadata = Get-Content -LiteralPath (Join-Path $TargetRoot 'resources/META-INF/mods.toml') -Raw
foreach ($pin in 'loaderVersion="[61,62)"','versionRange="[1.21.11]"','versionRange="[61.1.0]"','versionRange="[2.4.15.121111]"','showAsResourcePack=false') {
    Assert-NativeContract ($metadata.Contains($pin)) "Missing native probe metadata pin: $pin"
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
Assert-NativeContract ((Canonical-NativeContract $runner).Contains((Canonical-NativeContract $captionGate))) 'Runner must require all 351 caption checks and 355 negative controls (126/130 for Power only).'
foreach ($fragment in "bookPacketType -ne 'ClientboundRecipeBookAddPacket'", "tagsPacketType -eq 'ClientboundUpdateTagsPacket'",
        'Frozen profile SHA-256 is required and must match.', 'DIAGNOSTIC_LOOSE_POWER_CLIENT_PASS_NOT_FROZEN_CANDIDATE_QUALIFICATION',
        'runner-failure.json', 'Native tag packet SHA-256 mismatch.') {
    Assert-NativeContract ($runner.Contains($fragment)) "Missing native client runner contract: $fragment"
}
Assert-NativeContract ($runner -notmatch '\b(?:Remove-Item|Clear-Content)\b') 'Runner must not delete old or failed evidence.'
$failure = [regex]::Match($runner, '(?s)\} catch \{\s*# Preserve native partial results.*?\} finally \{').Value
Assert-NativeContract ($failure.Contains('runner-failure.json') -and $failure.Contains('if (-not (Test-Path')) 'Runner must preserve partial native results and write its failure separately.'
Write-Output 'PASS: native metadata, exact caption counters, paired book/tag packet guards, frozen versus loose-output provenance, and retained-failure contracts. No graphical pass is implied.'
