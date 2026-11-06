param([Parameter(Mandatory = $true)][string]$ReferenceRoot)
$ErrorActionPreference = 'Stop'

# Source from the pre-rebrand 1.16.5 commit c310d0ca63ad9a8408dd4bba75172afafa5c71b7.
$java = 'java/zone/moddev/mc/advantageworks/client'
$pins = [ordered]@{
    "$java/ChildRendererGeometryProbe.java" = 'B56A5F683FCC1312D3B749BEC11C57099ECB73E5C87C953EF55050EAE3320650'
    "$java/ClientResourceProbe.java" = '014E12FFFD8B1E52FF32FA55ABCD6A1708094AF00D85AFB2F1424D448E349453'
    "$java/MachineGuiLayoutProbe.java" = '8D89215AEFFDDC96A7175AE84F315DFF009D6E0F0FC1942C986E626B5806AFFF'
    "$java/OilRenderProbe.java" = 'E99F2D29D58F5388406FB29F419D68FF02C3C9EB51A44018C9A3440E83F37777'
    "$java/RecipeBookGroupingProbe.java" = 'E30466B80CB0496E637B3C6AAC9D490EF55C8E0FE0EBC5ACAEF7EF90D8E9C1B1'
    "$java/TankVisualProbe.java" = '666F6D8FC5F1B925665E019E4F38DAB90766A04CB64C9C256AD3BAC9BE6CF327'
    'resources/META-INF/mods.toml' = 'BF7BF1A6A02D00B0ADF604C6659533C2C792E19B888430EDDAC3C3BBBC48F5A5'
    'resources/gui-label-expectations.json' = 'EC0B66B926053E90C216D856B5F33C19D5B7E92DBC4C9E2E25812B401AEBAC0E'
}
$identity = @{ poweradvantage = 'basepower'; steamadvantage = 'basesteam'; electricadvantage = 'baseelectric' }
foreach ($relative in $pins.Keys) {
    $original = Join-Path $ReferenceRoot $relative
    if ((Get-FileHash -LiteralPath $original).Hash -cne $pins[$relative]) {
        throw "Unexpected pre-rebrand source: $relative"
    }
    $expected = [IO.File]::ReadAllText($original)
    $expected = [regex]::Replace($expected, '(?<!cyano\.)(?<!com\.mcmoddev\.)\b(poweradvantage|steamadvantage|electricadvantage)\b',
        { param($match) $identity[$match.Value] })
    $expected = $expected.Replace('2.4.7.116051', '2.4.8.116051').Replace('2.2.8.116051', '2.2.9.116051')
    $actual = [IO.File]::ReadAllText((Join-Path $PSScriptRoot $relative))
    if ($actual.Replace("`r`n", "`n") -cne $expected.Replace("`r`n", "`n")) {
        throw "Probe contract changed beyond the namespace and version: $relative"
    }
}
Write-Output "PASS: $($pins.Count) pinned client probe inputs retain their assertions after the rename."
