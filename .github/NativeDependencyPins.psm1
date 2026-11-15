Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Read-NativeProperties([string]$Path) {
    $properties = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ($line -match '^\s*([^#!\s=]+)\s*=(.*)$') {
            $properties[$Matches[1]] = $Matches[2].Trim()
        }
    }
    return $properties
}

function Read-NativeDependencyPins([string]$LockPath, [hashtable]$Properties) {
    $lock = Get-Content -LiteralPath $LockPath -Raw | ConvertFrom-Json -AsHashtable
    if ($lock.schemaVersion -ne 1) { throw 'Unsupported native dependency lock schema' }
    if ($lock.status -ne 'HOSTED_INPUTS_READY') {
        throw "BOOTSTRAP_BLOCKED: $($lock.reason)"
    }
    $target = $lock.target
    if ($target.minecraft -ne '1.21.1' -or $target.neoforge -ne '21.1.265' -or
            $target.java -ne '21' -or $target.moddev -ne '2.0.148' -or $target.gradle -ne '9.8.0' -or
            $target.releaseBranch -ne 'master-1.21.1-neo') { throw 'Wrong native build target' }
    if ($Properties.minecraft_version -ne $target.minecraft -or $Properties.neo_version -ne $target.neoforge -or
            $Properties.java_version -ne $target.java -or $Properties.loader_name -ne 'neoforge' -or
            $Properties.loader_code -ne '2') { throw 'Dependency lock differs from the project target' }
    $ids = @($lock.dependencies | ForEach-Object { $_.id } | Sort-Object)
    if (($ids -join ',') -ne 'basemetals,mmdlib,orespawn') { throw 'Exactly three unique native dependencies are required' }
    $coordinates = @{
        basemetals = @('zone.moddev.mc.basemetals', 'BaseMetals', 'basemetals_version', 'basemetals_deobf_sha256')
        mmdlib = @('zone.moddev.mc.lib', 'mmdlib', 'mmdlib_version', 'mmdlib_sha256')
        orespawn = @('zone.moddev.mc.orespawn', 'OreSpawn', 'orespawn_version', 'orespawn_deobf_sha256')
    }
    foreach ($pin in $lock.dependencies) {
        $expected = $coordinates[$pin.id]
        if ($pin.state -ne 'PUBLISHED') { throw "Dependency $($pin.id) is not a published input" }
        if ($pin.group -cne $expected[0] -or $pin.artifact -cne $expected[1] -or
                $pin.versionProperty -cne $expected[2] -or $pin.hashProperty -cne $expected[3]) {
            throw "Unexpected coordinate or project property for $($pin.id)"
        }
        if ($pin.version -cne $Properties[$pin.versionProperty] -or
                $pin.sha256 -cne $Properties[$pin.hashProperty]) { throw "Project pin differs for $($pin.id)" }
        if ($pin.sha256 -cnotmatch '^[A-F0-9]{64}$' -or
                $pin.mavenVersion -notmatch '^\d+\.\d+\.\d+\.121012(?:\+Neo)?$' -or
                $pin.mavenVersion -cnotin @($pin.version, ($pin.version + '+Neo')) -or
                $pin.modVersion -cne $pin.mavenVersion -or
                [string]::IsNullOrWhiteSpace($pin.modVersion) -or
                $pin.modVersion -match '(?i)development|snapshot|unqualified') {
            throw "Missing exact public native artifact identity for $($pin.id)"
        }
        if ($pin.ContainsKey('source')) {
            $source = $pin.source
            if ($source.repository -cne "MinecraftModDevelopmentMods/$($pin.artifact)" -or
                    $source.commit -cnotmatch '^[a-f0-9]{40}$' -or
                    $source.branch -notmatch '^master-1\.21\.1(?:-neo|-neoforge)?$') {
                throw "Missing approved source ancestry pin for $($pin.id)"
            }
            $property = "$($pin.id)_source_commit"
            if (!$Properties.ContainsKey($property) -or $Properties[$property] -cne $source.commit) {
                throw "Project source pin differs for $($pin.id)"
            }
        } elseif ($pin.id -eq 'basemetals') { throw 'Base Metals hook requires an exact merged source pin' }
        if ($pin.id -eq 'basemetals' -and $pin.requiredEntry -cne 'zone/moddev/mc/basemetals/migration/LegacyWorldDataHook.class') {
            throw 'Base Metals migration-hook gate was removed'
        }
    }
    return $lock
}

function Assert-NativeSourceAncestry($Pin, [scriptblock]$Compare = {
    param($Uri)
    $status = & gh api $Uri --jq '.status'
    if ($LASTEXITCODE -ne 0) { throw 'GitHub source comparison failed' }
    return $status
}) {
    if (!$Pin.ContainsKey('source')) { return }
    $source = $Pin.source
    $uri = "repos/$($source.repository)/compare/$($source.commit)...$($source.branch)"
    $status = & $Compare $uri
    if ($status -notin @('identical', 'ahead')) {
        throw "Source $($source.commit) is not merged on $($source.branch): $status"
    }
}

function Assert-NativeDependencyArchive([string]$Path, $Pin) {
    if ((Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash -cne $Pin.sha256) {
        throw "SHA-256 mismatch for $($Pin.id)"
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $entry = $archive.GetEntry('META-INF/neoforge.mods.toml')
        if (!$entry -or $archive.GetEntry('META-INF/mods.toml')) { throw "Not a native NeoForge jar: $($Pin.id)" }
        $reader = [IO.StreamReader]::new($entry.Open())
        try { $metadata = $reader.ReadToEnd() } finally { $reader.Dispose() }
        $header = [regex]::Match($metadata, '(?ms)^[ \t]*\[\[mods\]\][ \t]*(?:#[^\r\n]*)?\r?\n(.*?)(?=^[ \t]*\[|\z)').Groups[1].Value
        $id = [regex]::Escape($Pin.id)
        $version = [regex]::Escape($Pin.modVersion)
        if ($metadata -match '\$\{[^}]+\}' -or
                $header -notmatch "(?m)^[ \t]*modId[ \t]*=[ \t]*`"$id`"[ \t]*(?:#[^\r\n]*)?\r?$" -or
                $header -notmatch "(?m)^[ \t]*version[ \t]*=[ \t]*`"$version`"[ \t]*(?:#[^\r\n]*)?\r?$" -or
                $metadata -notmatch '(?m)^[ \t]*modId[ \t]*=[ \t]*"neoforge"[ \t]*(?:#[^\r\n]*)?\r?$') {
            throw "Jar metadata differs for $($Pin.id)"
        }
        if ($Pin.ContainsKey('requiredEntry') -and !$archive.GetEntry($Pin.requiredEntry)) {
            throw 'Base Metals jar does not contain the approved migration hook'
        }
    } finally { $archive.Dispose() }
}

Export-ModuleMember -Function Read-NativeProperties, Read-NativeDependencyPins, Assert-NativeSourceAncestry, Assert-NativeDependencyArchive
