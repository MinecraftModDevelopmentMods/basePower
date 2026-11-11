param([switch]$StaticOnly, [switch]$CrossVersionEvidenceRoots, [string]$WorkspaceRoot)
$ErrorActionPreference = 'Stop'
$tokens = $null
$errors = $null
$script = Join-Path $PSScriptRoot 'run.ps1'
$ast = [Management.Automation.Language.Parser]::ParseFile($script, [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw ($errors | Out-String) }
# Load helper definitions only. Never execute the runner or start Java here.
foreach ($definition in $ast.FindAll({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] }, $false)) {
    . ([scriptblock]::Create($definition.Extent.Text))
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$checks = 1
function Assert-Throws([scriptblock]$Action) {
    $threw = $false
    try { & $Action } catch { $threw = $true }
    if (-not $threw) { throw 'Expected invalid native metadata to be rejected.' }
    $script:checks++
}
function Assert-GuiEvidenceRoot([Management.Automation.Language.ScriptBlockAst]$RunnerAst) {
    $assignments = @($RunnerAst.FindAll({ param($node) $node -is [Management.Automation.Language.AssignmentStatementAst] }, $true))
    $projects = @($assignments | Where-Object { $_.Left.Extent.Text -eq '$project' })
    $runtimes = @($assignments | Where-Object { $_.Left.Extent.Text -eq '$runtime' })
    if ($projects.Count -ne 1 -or $runtimes.Count -ne 1) { throw 'GUI evidence needs one unambiguous project/runtime assignment.' }
    $projectExpression = [regex]::Replace($projects[0].Right.Extent.Text, '\s+', '')
    if ($projectExpression -cne "(Resolve-Path(Join-Path`$PSScriptRoot'../..')).Path") {
        throw 'GUI evidence must be based at the runner project root, not a build directory.'
    }
    $runtimeExpression = [regex]::Replace($runtimes[0].Right.Extent.Text, '\s+', '')
    if (-not $runtimeExpression.StartsWith("Join-Path`$project('run-client-resource-runs/'+", [StringComparison]::Ordinal)) {
        throw 'GUI captures must use project/run-client-resource-runs, outside Gradle clean output.'
    }
    $variables = @($runtimes[0].Right.FindAll({ param($node) $node -is [Management.Automation.Language.VariableExpressionAst] }, $true))
    if (@($variables | Where-Object { $_.VariablePath.UserPath -ne 'project' }).Count) {
        throw 'GUI evidence suffix must not introduce a different root variable.'
    }
    $literals = @($runtimes[0].Right.FindAll({ param($node) $node -is [Management.Automation.Language.StringConstantExpressionAst] }, $true))
    foreach ($literal in $literals) {
        if ($literal.Value -ceq 'run-client-resource-runs/') { continue }
        if ($literal.Value -match '[\\/:]|\.\.') { throw 'GUI evidence suffix contains a path or traversal component.' }
    }
}

function Test-GuiEvidenceRootContract([string]$RunnerSource) {
    $rootTokens = $null; $rootErrors = $null
    $runnerAst = [Management.Automation.Language.Parser]::ParseInput($RunnerSource, [ref]$rootTokens, [ref]$rootErrors)
    if ($rootErrors.Count) { throw 'GUI evidence source must parse before its path is audited.' }
    Assert-GuiEvidenceRoot $runnerAst
    $script:checks++
    foreach ($replacement in "'build/client-resource-runs/'", "'run-client-resource-runs/../build/'", "'C:/build/client-resource-runs/'") {
        $broken = $RunnerSource.Replace("'run-client-resource-runs/'", $replacement)
        if ($broken -ceq $RunnerSource) { throw 'Evidence-root negative control did not mutate the source.' }
        $brokenAst = [Management.Automation.Language.Parser]::ParseInput($broken, [ref]$rootTokens, [ref]$rootErrors)
        if ($rootErrors.Count) { throw 'Evidence-root negative control must remain valid PowerShell.' }
        Assert-Throws { Assert-GuiEvidenceRoot $brokenAst }
    }
    $broken = $RunnerSource.Replace("'../..'", "'../../build'")
    if ($broken -ceq $RunnerSource) { throw 'Project-root negative control did not mutate the source.' }
    $brokenAst = [Management.Automation.Language.Parser]::ParseInput($broken, [ref]$rootTokens, [ref]$rootErrors)
    if ($rootErrors.Count) { throw 'Project-root negative control must remain valid PowerShell.' }
    Assert-Throws { Assert-GuiEvidenceRoot $brokenAst }
}

Test-GuiEvidenceRootContract (Get-Content -LiteralPath $script -Raw)
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$build = Get-Content -LiteralPath (Join-Path $projectRoot 'build.gradle') -Raw
if (-not $build.Contains("'test-rig/profiles/native12111/dev-core.json'") -or
    $build.Contains("'test-rig/profiles/native1211/dev-core.json'")) {
    throw 'The production combined launch must default to native12111, not rely on a CLI profile override.'
}
$checks++

if ($CrossVersionEvidenceRoots) {
    $workspace = if ($WorkspaceRoot) { (Resolve-Path -LiteralPath $WorkspaceRoot).Path }
        else { (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../../..')).Path }
    foreach ($line in 'PA110','PA112','PA113','PA114','PA115','PA116') {
        $runner = Join-Path $workspace ($line + '/PowerAdvantage/test-rig/client-resources/run.ps1')
        if (-not (Test-Path -LiteralPath $runner -PathType Leaf)) { throw "Cross-version GUI contract requires all six runners; missing $runner" }
        Test-GuiEvidenceRootContract (Get-Content -LiteralPath $runner -Raw)
    }
    Write-Output 'PASS: all six GUI evidence roots are outside build; old-build, traversal, absolute-build and changed-project controls rejected.'
}

$native = @'
modLoader="javafml"
loaderVersion="[61,)" # native Forge
[[mods]] # mandatory header
modId="basepower" # inline comment
version="2.4.15.121111" # native candidate
[[dependencies.basepower]]
modId="minecraft"
mandatory=true
versionRange="[1.21.11]"
'@
Assert-NativeMetadata $native 'basepower'
$checks++
foreach ($old in '2.4.4.113021','2.4.5.114041','2.4.5.115021','2.4.7.116051','2.4.14.121011','${version}') {
    Assert-Throws { Assert-NativeMetadata ($native.Replace('2.4.15.121111',$old)) 'basepower' }
}
Assert-Throws { Assert-NativeMetadata ($native.Replace('[61,)','[52,)')) 'basepower' }
Assert-Throws { Assert-NativeMetadata $native 'baseelectric' }
Assert-Throws { Assert-NativeMetadata '' 'basepower' }
foreach ($id in 'basesteam','baseelectric') {
    $expansion = $native.Replace('basepower', $id).Replace('2.4.15.121111','2.2.16.121111')
    Assert-NativeMetadata $expansion $id
    $checks++
    Assert-Throws { Assert-NativeMetadata ($expansion.Replace('2.2.16.121111','2.2.6.115021')) $id }
    Assert-Throws { Assert-NativeMetadata ($expansion.Replace('2.2.16.121111','2.2.15.121011')) $id }
}
foreach ($dependency in @{ basemetals='3.1.2.121111'; orespawn='4.1.0.121111' }.GetEnumerator()) {
    $metadata = $native.Replace('basepower', $dependency.Key).Replace('2.4.15.121111', $dependency.Value)
    Assert-NativeMetadata $metadata $dependency.Key
    $checks++
    Assert-Throws { Assert-NativeMetadata ($metadata.Replace($dependency.Value, $dependency.Value.Replace('121111','121011'))) $dependency.Key }
}
$environment = @{ MC_VERSION = '1.21.11'; FORGE_VERSION = '61.1.0'; MCP_MAPPINGS = 'official_1.21.11'; MCP_VERSION = '20251223.124241'; target = 'fmluserdevclient' }
$attributes = @{ 'org.eclipse.jdt.launching.PROGRAM_ARGUMENTS' = '--main net.minecraftforge.bootstrap.ForgeBootstrap --cache D:\native\1.21.11-61.1.0 -- --launchTarget forge_userdev_client --gameDir .'; 'org.eclipse.jdt.launching.MAIN_TYPE' = 'net.minecraftforge.launcher.Main' }
Assert-NativeLaunch $environment $attributes
$checks++
foreach ($key in 'MC_VERSION','FORGE_VERSION','MCP_MAPPINGS','MCP_VERSION','target') {
    $invalid = $environment.Clone()
    $invalid[$key] = 'legacy'
    Assert-Throws { Assert-NativeLaunch $invalid $attributes }
}
foreach ($argument in '--server localhost','--port 25565','--gameDir D:\protected','--world protected','--cache D:\native\1.14.4-28.2.26','--cache D:\native\1.21.1-52.1.0','--assetIndex {asset_index}','--assetsDir {assets_root}') {
    $invalid = $attributes.Clone()
    $invalid['org.eclipse.jdt.launching.PROGRAM_ARGUMENTS'] += ' ' + $argument
    Assert-Throws { Assert-NativeLaunch $environment $invalid }
}
$invalid = $attributes.Clone()
$invalid['org.eclipse.jdt.launching.MAIN_TYPE'] = 'net.minecraft.client.main.Main'
Assert-Throws { Assert-NativeLaunch $environment $invalid }
Assert-NativeArchive 'D:\native\forge-1.21.11-61.1.0.jar'
$checks++
foreach ($path in 'D:\PA114\client.jar','D:\PA113\client.jar','D:\PA116\client.jar','D:\PA1211\client.jar','D:\native\forge-1.16.5-36.2.34.jar','D:\native\forge-1.14.4-28.2.26.jar','D:\native\client-extra-1.13.2.jar','D:\native\forge-1.21.1-52.1.0.jar','D:\native\client-extra-1.20.6.jar') {
    Assert-Throws { Assert-NativeArchive $path }
}
if ((Quote-Argument 'D:\native path\') -ne '"D:\native path\\"') { throw 'Trailing-backslash command-line quoting changed.' }
$checks++
if ((Quote-Argument 'text"quoted') -ne '"text\"quoted"') { throw 'Embedded-quote command-line quoting changed.' }
$checks++
$starts = @($ast.FindAll({ param($node) $node -is [Management.Automation.Language.CommandAst] -and $node.GetCommandName() -eq 'Start-Process' }, $true))
if ($starts.Count -ne 1 -or $starts[0].Extent.Text -notmatch '-WindowStyle Hidden') { throw 'Native helper must launch hidden.' }
$checks++
if ($StaticOnly) {
    Write-Output "PASS: $checks static launcher parser, target guards and quoting checks. Native candidate/launch checks NOT RUN. No Java/client/world was started."
    return
}
$project = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$power = Join-Path $project 'build/libs/basePower-2.4.15.121111.jar'
Assert-NativeMetadata (Read-JarMetadata $power) 'basepower'
$checks++
[xml]$launch = Get-Content -LiteralPath (Join-Path $project 'runClientAllAdvantage.launch') -Raw
$actualAttributes = @{}
foreach ($entry in $launch.launchConfiguration.stringAttribute) { $actualAttributes[$entry.key] = $entry.value }
$actualEnvironment = @{}
foreach ($map in $launch.launchConfiguration.mapAttribute) {
    if ($map.key -eq 'org.eclipse.debug.core.environmentVariables') {
        foreach ($entry in $map.mapEntry) { $actualEnvironment[$entry.key] = $entry.value }
    }
}
if ($actualAttributes['org.eclipse.jdt.launching.PROGRAM_ARGUMENTS'] -match '\{(?:asset_index|assets_root)\}') {
    throw 'Generated combined Eclipse launch still contains unresolved native asset placeholders; concrete asset index/directory are required for manual handoff.'
}
Assert-NativeLaunch $actualEnvironment $actualAttributes
$checks++
$found = @{}
foreach ($entry in ($launch.launchConfiguration.listAttribute | Where-Object { $_.key -eq 'org.eclipse.jdt.launching.CLASSPATH' }).listEntry) {
    [xml]$classpath = $entry.value
    $path = $classpath.runtimeClasspathEntry.externalArchive
    if (-not $path -or [IO.Path]::GetExtension($path) -ne '.jar') { continue }
    $metadata = Read-JarMetadata $path
    $header = if ($metadata) { [regex]::Match($metadata, '(?ms)^\[\[mods\]\]\s*(.*?)(?=^\[|\z)').Groups[1].Value }
    foreach ($id in 'basemetals','orespawn','basesteam','baseelectric') {
        if ($header -match ('(?m)^modId\s*=\s*"' + $id + '"[ \t]*(?:#[^\r\n]*)?\r?$')) {
            Assert-NativeMetadata $metadata $id
            $found[$id] = $true
            $checks++
        }
    }
}
if ($found.Count -ne 4) { throw 'The actual combined launch must expose all four native dependency/expansion candidates.' }
Write-Output "PASS: $checks launcher parser, metadata, native input and quoting checks. No Java/client/world was started."
