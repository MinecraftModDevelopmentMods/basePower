param([string]$EvidenceDirectory)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'NativeDependencyPins.psm1') -Force
$project = Split-Path $PSScriptRoot -Parent
$properties = Read-NativeProperties (Join-Path $project 'gradle.properties')
$lockPath = Join-Path $PSScriptRoot 'native-dependencies.json'
$original = Get-Content -LiteralPath $lockPath -Raw | ConvertFrom-Json -AsHashtable
$root = if ($EvidenceDirectory) { [IO.Path]::GetFullPath($EvidenceDirectory) }
    else { Join-Path ([IO.Path]::GetTempPath()) ('power-native-pin-tests-' + [guid]::NewGuid().ToString('N')) }
if (Test-Path -LiteralPath $root) { throw 'Use a new dependency-test evidence directory' }
New-Item -ItemType Directory -Path $root | Out-Null
$results = [Collections.Generic.List[object]]::new()
function Check([string]$Name, [scriptblock]$Action) {
    & $Action
    $results.Add(@{name=$Name; status='PASS'})
}
function Reject([scriptblock]$Action) {
    $failed = $false
    try { & $Action | Out-Null } catch { $failed = $true }
    if (!$failed) { throw 'Expected the unsafe input to be rejected' }
}
function Clone($Value) { return ($Value | ConvertTo-Json -Depth 20 | ConvertFrom-Json -AsHashtable) }
function Test-Lock($Value) {
    $path = Join-Path $root ('lock-' + [guid]::NewGuid().ToString('N') + '.json')
    $Value | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $path -Encoding utf8
    Read-NativeDependencyPins $path $properties
}
function New-TestJar($Pin, [string]$Version, [switch]$Forge, [switch]$OmitHook) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $path = Join-Path $root ([guid]::NewGuid().ToString('N') + '.jar')
    $archive = [IO.Compression.ZipFile]::Open($path, [IO.Compression.ZipArchiveMode]::Create)
    try {
        $name = if ($Forge) { 'META-INF/mods.toml' } else { 'META-INF/neoforge.mods.toml' }
        $writer = [IO.StreamWriter]::new($archive.CreateEntry($name).Open())
        try {
            $writer.Write("modLoader=`"javafml`" # native loader`nloaderVersion=`"[4,)`"`n[[mods]] # mandatory header`nmodId=`"$($Pin.id)`" # identity`nversion=`"$Version`" # exact version`n[[dependencies.$($Pin.id)]]`n    modId=`"neoforge`" # native dependency`n    type=`"required`"`n")
        } finally { $writer.Dispose() }
        if ($Pin.ContainsKey('requiredEntry') -and !$OmitHook) {
            $stream = $archive.CreateEntry($Pin.requiredEntry).Open()
            $stream.Dispose()
        }
    } finally { $archive.Dispose() }
    return $path
}
try {
    Check 'current bootstrap cannot claim hosted qualification' {
        if ($original.status -eq 'BOOTSTRAP_LOCAL_ONLY') {
            Reject { Read-NativeDependencyPins $lockPath $properties }
        } else { Read-NativeDependencyPins $lockPath $properties | Out-Null }
    }
    $valid = Clone $original
    $valid.status = 'HOSTED_INPUTS_READY'
    $bm = $valid.dependencies | Where-Object id -eq 'basemetals'
    $bm.state = 'PUBLISHED'
    $bm.mavenVersion = $bm.version
    $bm.modVersion = $bm.mavenVersion
    $bm.sha256 = $properties.basemetals_deobf_sha256
    $bm.source.commit = $properties.basemetals_source_commit
    $bm.source.branch = 'master-1.21.1-neo'
    Check 'synthetic complete public pin structure is accepted' { Test-Lock $valid | Out-Null }
    foreach ($id in 'basemetals','mmdlib','orespawn') {
        Check "$id hash drift is rejected" {
            $mutant = Clone $valid
            ($mutant.dependencies | Where-Object id -eq $id).sha256 = '0' * 64
            Reject { Test-Lock $mutant }
        }
        Check "$id unpublished state is rejected" {
            $mutant = Clone $valid
            ($mutant.dependencies | Where-Object id -eq $id).state = 'LOCAL_SOURCE_UNCOMMITTED'
            Reject { Test-Lock $mutant }
        }
        Check "$id cannot be omitted" {
            $mutant = Clone $valid
            $mutant.dependencies = @($mutant.dependencies | Where-Object id -ne $id)
            Reject { Test-Lock $mutant }
        }
    }
    Check 'public BM 4.0.0 cannot substitute for the 4.0.1 hook candidate' {
        $mutant = Clone $valid
        ($mutant.dependencies | Where-Object id -eq 'basemetals').version = '4.0.0.121012'
        Reject { Test-Lock $mutant }
    }
    Check 'an uncommitted BM source cannot qualify by its old baseline commit' {
        $mutant = Clone $valid
        ($mutant.dependencies | Where-Object id -eq 'basemetals').source.commit = $null
        Reject { Test-Lock $mutant }
    }
    Check 'duplicate dependency cannot replace a missing dependency' {
        $mutant = Clone $valid
        $mutant.dependencies[1] = Clone $mutant.dependencies[0]
        Reject { Test-Lock $mutant }
    }
    Check 'wrong native platform target is rejected' {
        $mutant = Clone $valid
        $mutant.target.neoforge = '52.1.0'
        Reject { Test-Lock $mutant }
    }
    foreach ($status in 'identical','ahead','behind','diverged','unknown') {
        Check "source ancestry status $status is handled strictly" {
            $expected = "repos/$($bm.source.repository)/compare/$($bm.source.commit)...$($bm.source.branch)"
            $expectedStatus = $status
            $compare = { param($uri)
                if ($uri -cne $expected) { throw "Wrong source ancestry query: got '$uri', expected '$expected'" }
                return $expectedStatus
            }.GetNewClosure()
            if ($status -in @('identical','ahead')) { Assert-NativeSourceAncestry $bm $compare }
            else { Reject { Assert-NativeSourceAncestry $bm $compare } }
        }
    }
    foreach ($id in 'basemetals','mmdlib','orespawn') {
        $pin = Clone ($valid.dependencies | Where-Object id -eq $id)
        $jar = New-TestJar $pin $pin.modVersion
        $pin.sha256 = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash
        Check "$id native metadata and exact bytes are accepted" { Assert-NativeDependencyArchive $jar $pin }
        Check "$id modified jar hash is rejected" {
            $bad = Clone $pin
            $bad.sha256 = '0' * 64
            Reject { Assert-NativeDependencyArchive $jar $bad }
        }
        Check "$id Forge jar is rejected even with its matching checksum" {
            $badJar = New-TestJar $pin $pin.modVersion -Forge
            $bad = Clone $pin
            $bad.sha256 = (Get-FileHash -LiteralPath $badJar -Algorithm SHA256).Hash
            Reject { Assert-NativeDependencyArchive $badJar $bad }
        }
        Check "$id wrong mod version is rejected even with its matching checksum" {
            $badJar = New-TestJar $pin '0.0.0'
            $bad = Clone $pin
            $bad.sha256 = (Get-FileHash -LiteralPath $badJar -Algorithm SHA256).Hash
            Reject { Assert-NativeDependencyArchive $badJar $bad }
        }
    }
    Check 'native BM jar without the migration hook is rejected' {
        $badJar = New-TestJar $bm $bm.modVersion -OmitHook
        $bad = Clone $bm
        $bad.sha256 = (Get-FileHash -LiteralPath $badJar -Algorithm SHA256).Hash
        Reject { Assert-NativeDependencyArchive $badJar $bad }
    }
} finally {
    @{scope='OFFLINE_GUARD_TESTS_NOT_JAVA_OR_RELEASE_QUALIFICATION'; results=@($results.ToArray())} |
        ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $root 'results.json') -Encoding utf8
}
Write-Output "PASS: $($results.Count) offline native dependency guard checks. No network, Java or Gradle run. Evidence: $root"
