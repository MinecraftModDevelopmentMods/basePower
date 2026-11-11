param(
    [string]$RepositoryRoot = (Join-Path $PSScriptRoot '../..'),
    [string]$AssetsDirectory = (Join-Path $env:APPDATA '.minecraft/assets'),
    [Parameter(Mandatory = $true)][string]$Evidence
)
$ErrorActionPreference = 'Stop'
$RepositoryRoot = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$AssetsDirectory = (Resolve-Path -LiteralPath $AssetsDirectory).Path
$Evidence = [IO.Path]::GetFullPath($Evidence)
if (Test-Path -LiteralPath $Evidence) { throw 'Refusing to replace Eclipse preparation evidence.' }
New-Item -ItemType Directory -Path $Evidence | Out-Null
$index = Join-Path $AssetsDirectory 'indexes/29.json'
if ((Get-FileHash -LiteralPath $index -Algorithm SHA1).Hash -ine '39CAFD4FD3EDC4A1E3C2F20BD83EA3382DAB43F1') {
    throw 'The human client requires the actual Minecraft 1.21.11 asset index 29.'
}
$manifest = Get-Content -LiteralPath $index -Raw | ConvertFrom-Json
$objects = @($manifest.objects.PSObject.Properties.Value | Sort-Object hash -Unique)
foreach ($entry in $objects) {
    $path = Join-Path $AssetsDirectory ('objects/' + $entry.hash.Substring(0, 2) + '/' + $entry.hash)
    if ((Get-Item -LiteralPath $path).Length -ne $entry.size -or (Get-FileHash -LiteralPath $path -Algorithm SHA1).Hash -ine $entry.hash) {
        throw "Missing or changed native client asset: $($entry.hash)"
    }
}
$records = @()
foreach ($name in 'runClient.launch','runClientAllAdvantage.launch') {
    $path = Join-Path $RepositoryRoot $name
    [xml]$launch = Get-Content -LiteralPath $path -Raw
    $node = @($launch.launchConfiguration.stringAttribute | Where-Object { $_.key -eq 'org.eclipse.jdt.launching.PROGRAM_ARGUMENTS' })
    if ($node.Count -ne 1) { throw "Expected one argument element in $name" }
    $program = $node[0].value
    if ($program -notmatch '1\.21\.11-61\.1\.0' -or $program -notmatch '--launchTarget forge_userdev_client\b' -or
        $program -match '--(?:server|port|world)\b|advantageworks\.clientResources') {
        throw "Not a normal native human-client launch: $name"
    }
    foreach ($argument in '--assetIndex','--assetsDir') {
        if ([regex]::Matches($program, ([regex]::Escape($argument) + '\s+(?:"[^"]+"|\S+)')).Count -ne 1) {
            throw "Missing or repeated $argument in $name"
        }
    }
    $before = (Get-FileHash -LiteralPath $path).Hash
    Copy-Item -LiteralPath $path -Destination (Join-Path $Evidence $name)
    $program = [regex]::Replace($program, '--assetIndex\s+(?:"[^"]+"|\S+)', '--assetIndex 29')
    $program = [regex]::Replace($program, '--assetsDir\s+(?:"[^"]+"|\S+)',
        [Text.RegularExpressions.MatchEvaluator]{ param($match) '--assetsDir "' + $AssetsDirectory + '"' })
    if ($program -match '\{(?:asset_index|assets_root)\}') { throw 'Unresolved native asset argument.' }
    $node[0].SetAttribute('value', $program)
    $settings = [Xml.XmlWriterSettings]::new()
    $settings.Encoding = [Text.UTF8Encoding]::new($false)
    $settings.Indent = $true
    $writer = [Xml.XmlWriter]::Create($path, $settings)
    try { $launch.Save($writer) } finally { $writer.Dispose() }
    $records += [ordered]@{ path=$path; beforeSHA256=$before; afterSHA256=(Get-FileHash -LiteralPath $path).Hash }
}
[ordered]@{
    status='PREPARED_NORMAL_HUMAN_CLIENT_NOT_A_GAMEPLAY_PASS'
    minecraft='1.21.11'; forge='61.1.0'; assetIndex=29
    assetIndexSHA1=(Get-FileHash -LiteralPath $index -Algorithm SHA1).Hash
    assetsDirectory=$AssetsDirectory; verifiedAssetObjects=$objects.Count; launches=$records
    note='Only generated argument XML changed; no root build/default-profile/source or jar edits. Reapply after Gradle regenerates launches.'
} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $Evidence 'receipt.json') -Encoding utf8
Write-Output "Prepared normal and combined Eclipse client arguments with $($objects.Count) verified native assets. No client/world started. Evidence: $Evidence"
