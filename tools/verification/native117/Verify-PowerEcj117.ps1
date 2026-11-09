param(
    [string]$ProjectRoot = (Join-Path $PSScriptRoot '../../..'),
    [Parameter(Mandatory = $true)][string]$Evidence,
    [string]$Compiler = 'C:/Users/johnb/.p2/pool/plugins/org.eclipse.jdt.core.compiler.batch_3.46.0.v20260528-0407.jar',
    [string]$Java17Home = 'C:/Users/johnb/.jdks/temurin-17.0.1+12/jdk-17.0.1+12',
    [string]$Java16Home = 'C:/Users/johnb/.gradle/jdks/eclipse_foundation-16-amd64-windows.2'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$ProjectRoot = (Resolve-Path -LiteralPath $ProjectRoot).Path
$Evidence = [IO.Path]::GetFullPath($Evidence)
if ($Evidence -eq $ProjectRoot -or $Evidence.StartsWith($ProjectRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or
    $ProjectRoot.StartsWith($Evidence.TrimEnd('\') + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'ECJ evidence must be a separate directory outside the Power checkout'
}
if (Test-Path -LiteralPath $Evidence) { throw 'Refusing to overwrite ECJ evidence' }
$null = New-Item -ItemType Directory -Path $Evidence

function Get-Pin([string]$Path) {
    $file = Get-Item -LiteralPath $Path
    [ordered]@{ path = $file.FullName; sha256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash }
}
function Save-Report {
    $json = $report | ConvertTo-Json -Depth 15
    [IO.File]::WriteAllText((Join-Path $Evidence 'ecj-results.json'), $json + "`n", [Text.UTF8Encoding]::new($false))
}
function Get-TreePins([string[]]$Roots) {
    $pins = @()
    foreach ($root in $Roots) {
        if (!(Test-Path -LiteralPath $root)) { continue }
        $pins += @(Get-ChildItem -LiteralPath $root -Recurse -File | Sort-Object FullName | ForEach-Object { Get-Pin $_.FullName })
    }
    return $pins
}
function Assert-Unchanged($Pins) {
    foreach ($pin in $Pins) {
        if (!(Test-Path -LiteralPath $pin.path) -or (Get-FileHash -LiteralPath $pin.path -Algorithm SHA256).Hash -ne $pin.sha256) {
            throw "Input or protected output changed during ECJ qualification: $($pin.path)"
        }
    }
}
function Test-ClassFiles([string]$Directory) {
    $files = @(Get-ChildItem -LiteralPath $Directory -Recurse -File -Filter '*.class')
    if (!$files.Count) { throw "ECJ emitted no classes in $Directory" }
    foreach ($file in $files) {
        $bytes = [IO.File]::ReadAllBytes($file.FullName)
        if ($bytes.Length -lt 8 -or $bytes[0] -ne 0xCA -or $bytes[1] -ne 0xFE -or $bytes[2] -ne 0xBA -or $bytes[3] -ne 0xBE) {
            throw "Invalid class file: $($file.FullName)"
        }
        if (($bytes[6] * 256 + $bytes[7]) -ne 60) { throw "Non-Java-16 ECJ output: $($file.FullName)" }
    }
    return $files.Count
}
function Invoke-Ecj([string]$Name, [string[]]$Sources, [string[]]$Libraries) {
    $output = Join-Path $Evidence $Name
    $null = New-Item -ItemType Directory -Path $output
    $xmlLog = Join-Path $Evidence "$Name.xml"
    # Java 16 uses the JDK module image; keep forbidden/discouraged accesses fatal.
    $arguments = @('-16', '-proc:none', '-encoding', 'UTF-8', '-err:+forbidden,discouraged', '--system',
        ('"' + $Java16Home.Replace('\', '/') + '"'), '-classpath',
        ('"' + (($Libraries -replace '\\', '/') -join ';') + '"'), '-d',
        ('"' + $output.Replace('\', '/') + '"'), '-log', ('"' + $xmlLog.Replace('\', '/') + '"'))
    $arguments += @($Sources | ForEach-Object { '"' + $_.Replace('\', '/') + '"' })
    $argFile = Join-Path $Evidence "$Name.args"
    [IO.File]::WriteAllLines($argFile, $arguments, [Text.UTF8Encoding]::new($false))
    & $java17 -Xmx2G -jar $Compiler "@$argFile" *> (Join-Path $Evidence "$Name-compile.log")
    $exitCode = $LASTEXITCODE
    $entry = [ordered]@{ exitCode = $exitCode; sourceCount = $Sources.Count; output = $output; log = "$Name-compile.log" }
    $report.compilations[$Name] = $entry
    if ($exitCode -ne 0) { throw "$Name ECJ compilation failed; retain its log and XML diagnostics" }
    [xml]$diagnostics = Get-Content -LiteralPath $xmlLog -Raw
    $problems = @($diagnostics.SelectNodes('//*[@severity]'))
    $entry.errors = @($problems | Where-Object { $_.GetAttribute('severity') -ieq 'ERROR' }).Count
    $entry.warnings = @($problems | Where-Object { $_.GetAttribute('severity') -ieq 'WARNING' }).Count
    if ($entry.errors) { throw "$Name ECJ XML contains errors" }
    $entry.classCount = Test-ClassFiles $output
    return $output
}

$report = [ordered]@{ status = 'RUNNING'; project = $ProjectRoot; evidence = $Evidence
    scope = 'Pinned Power 1.17.1 candidate; fresh ECJ main/test/helper outputs and worldless Java 16 JUnit; no Gradle or Minecraft launch'
    compiler = $null; toolchains = $null; libraries = @(); sources = @(); gradleInventory = @()
    compilations = [ordered]@{}; junit = $null; negativeControl = $null; junitControls = @(); capabilityBootstrap = $null
    productionSourceCommit = 'a6fc3050b0e92e89abf49bf18bbbfeb87fb3a1eb'; protectedBefore = @(); protectedAfter = @() }
try {
    $properties = ConvertFrom-StringData -StringData (Get-Content -LiteralPath (Join-Path $ProjectRoot 'gradle.properties') -Raw)
    if ($properties.mod_id -ne 'basepower' -or $properties.mod_version -ne '2.4.9.117011' -or
        $properties.minecraft_version -ne '1.17.1' -or $properties.forge_version -ne '37.1.1' -or $properties.java_version -ne '16') {
        throw 'This verifier requires the pinned Power 1.17.1 candidate'
    }
    $report.properties = Get-Pin (Join-Path $ProjectRoot 'gradle.properties')
    & git -C $ProjectRoot diff --quiet $report.productionSourceCommit -- src/main src/test test-rig/unit-bootstrap
    if ($LASTEXITCODE -ne 0) { throw 'Production, tests, or bootstrap differ from the pinned source commit' }
    $untracked = @(& git -C $ProjectRoot ls-files --others --exclude-standard -- src/main src/test test-rig/unit-bootstrap)
    if ($LASTEXITCODE -ne 0 -or $untracked.Count) { throw 'Untracked production/test/bootstrap inputs are not permitted' }
    $protectedRoots = @('build/classes', 'build/libs', 'build/libs-dev', 'build/resources', 'build/unit-bootstrap', 'bin') |
        ForEach-Object { Join-Path $ProjectRoot $_ }
    $report.protectedBefore = @(Get-TreePins $protectedRoots)
    $resourceInputs = @(Get-TreePins @((Join-Path $ProjectRoot 'src/main/resources'), (Join-Path $ProjectRoot 'src/test/resources')))
    $report.resources = $resourceInputs
    $report.legacyVerifier = Get-Pin (Join-Path $ProjectRoot 'tools/verification/Verify-EclipseCompilation.ps1')
    $report.verifier = Get-Pin $PSCommandPath
    $report.compiler = Get-Pin $Compiler
    if ($report.compiler.sha256 -ne 'D0D43F8E2D7003E5EFED612E2CBB5F01870043397D8F1BBE536FD9128F4FCBF7') { throw 'ECJ compiler pin mismatch' }
    $java17 = Join-Path $Java17Home 'bin/java.exe'
    $java16 = Join-Path $Java16Home 'bin/java.exe'
    $jar16 = Join-Path $Java16Home 'bin/jar.exe'
    $report.toolchains = [ordered]@{ compilerJava = (Get-Pin $java17); runtimeJava = (Get-Pin $java16)
        java17Release = (Get-Pin (Join-Path $Java17Home 'release')); java16Release = (Get-Pin (Join-Path $Java16Home 'release'))
        systemModules = (Get-Pin (Join-Path $Java16Home 'lib/modules')); jarTool = (Get-Pin $jar16) }
    if ((Get-Content -LiteralPath (Join-Path $Java16Home 'release') -Raw) -notmatch 'JAVA_VERSION="16.0.2"') {
        throw 'ECJ production system image must be Java 16.0.2'
    }
    $negativeSource = Join-Path $PSScriptRoot 'RestrictedJdkReference117.java'
    $negativeLog = Join-Path $Evidence 'restricted-jdk-negative.log'
    & $java17 -jar $Compiler -16 -proc:none '-err:+forbidden,discouraged' --system $Java16Home `
        -d (Join-Path $Evidence 'negative') $negativeSource *> $negativeLog
    $negativeExit = $LASTEXITCODE
    $report.negativeControl = [ordered]@{ source = (Get-Pin $negativeSource); exitCode = $negativeExit
        log = 'restricted-jdk-negative.log'; expected = 'sun.security.x509.X500Name is not accessible' }
    if ($negativeExit -eq 0 -or (Get-Content -LiteralPath $negativeLog -Raw) -notmatch 'X500Name is not accessible') {
        throw 'Restricted-JDK negative control did not fail for the expected native module-access reason'
    }
    [xml]$classpath = Get-Content -LiteralPath (Join-Path $ProjectRoot '.classpath') -Raw
    $report.classpath = Get-Pin (Join-Path $ProjectRoot '.classpath')
    $mainLibraries = @()
    $testLibraries = @()
    foreach ($entry in @($classpath.classpath.classpathentry | Where-Object { $_.kind -eq 'lib' })) {
        $file = if ([IO.Path]::IsPathRooted([string]$entry.path)) { [IO.Path]::GetFullPath([string]$entry.path) }
            else { [IO.Path]::GetFullPath((Join-Path $ProjectRoot ([string]$entry.path))) }
        if ([IO.Path]::GetFileName($file) -match '^(?:basePower|PowerAdvantage).*\.jar$') { throw 'Power output jar cannot satisfy its own ECJ compilation' }
        $scopeNode = $entry.SelectSingleNode('attributes/attribute[@name="gradle_used_by_scope"]/@value')
        $scope = if ($scopeNode) { $scopeNode.Value } else { '' }
        $pin = Get-Pin $file
        $pin.scope = $scope
        $report.libraries += $pin
        if (!$scope -or $scope.Split(',') -contains 'main') { $mainLibraries += $file }
        if (!$scope -or $scope.Split(',') -contains 'test') { $testLibraries += $file }
    }
    foreach ($dependency in @(@{ prefix = 'BaseMetals-'; hash = $properties.basemetals_deobf_sha256 },
        @{ prefix = 'OreSpawn-'; hash = $properties.orespawn_deobf_sha256 })) {
        $matching = @($report.libraries | Where-Object { [IO.Path]::GetFileName($_.path).StartsWith($dependency.prefix) })
        if ($matching.Count -ne 1 -or $matching[0].sha256 -ne $dependency.hash) { throw "Typed dependency pin mismatch: $($dependency.prefix)" }
    }
    $sources = @{}
    foreach ($set in @('main', 'test')) {
        $sources[$set] = @(Get-ChildItem -LiteralPath (Join-Path $ProjectRoot "src/$set/java") -Recurse -File -Filter '*.java' | Sort-Object FullName | ForEach-Object { $_.FullName })
        $report.sources += @($sources[$set] | ForEach-Object { Get-Pin $_ })
    }
    $classes = @()
    $expectedCases = @()
    $expected = 0
    foreach ($xml in @(Get-ChildItem -LiteralPath (Join-Path $ProjectRoot 'build/test-results/test') -File -Filter 'TEST-*.xml' | Sort-Object Name)) {
        [xml]$inventory = Get-Content -LiteralPath $xml.FullName -Raw
        if ([int]$inventory.testsuite.failures -or [int]$inventory.testsuite.errors -or [int]$inventory.testsuite.skipped) { throw 'Gradle suite inventory contains failed/skipped tests' }
        $classes += [string]$inventory.testsuite.name
        $expected += [int]$inventory.testsuite.tests
        $expectedCases += @($inventory.testsuite.testcase | ForEach-Object { $_.classname + '#' + $_.name })
        $report.gradleInventory += Get-Pin $xml.FullName
    }
    if ($classes.Count -ne 43 -or $expected -ne 201 -or $sources.test.Count -ne 43 -or $sources.main.Count -ne 144) { throw 'Unexpected Power 1.17.1 source/test inventory' }
    if (@($expectedCases | Sort-Object -Unique).Count -ne $expected) { throw 'Duplicate or missing baseline test identities' }
    $main = Invoke-Ecj 'main' $sources.main $mainLibraries
    $test = Invoke-Ecj 'test' $sources.test (@($main) + $testLibraries)
    $helperSource = Join-Path $PSScriptRoot 'AuditedPowerJUnit117.java'
    $controlSource = Join-Path $PSScriptRoot 'AuditedPowerJUnit117Control.java'
    $report.helperSources = @((Get-Pin $helperSource), (Get-Pin $controlSource))
    $helper = Invoke-Ecj 'helper' @($helperSource, $controlSource) $testLibraries
    $bootstrapSources = @(Get-ChildItem -LiteralPath (Join-Path $ProjectRoot 'test-rig/unit-bootstrap') -File -Filter '*.java' |
        Sort-Object FullName | ForEach-Object { $_.FullName })
    if ($bootstrapSources.Count -ne 1) { throw 'Unexpected capability bootstrap source inventory' }
    $report.bootstrapSources = @($bootstrapSources | ForEach-Object { Get-Pin $_ })
    $bootstrapClasses = Invoke-Ecj 'bootstrap' $bootstrapSources $testLibraries
    $bootstrapManifest = Join-Path $Evidence 'bootstrap-manifest.mf'
    [IO.File]::WriteAllText($bootstrapManifest, "Manifest-Version: 1.0`nPremain-Class: zone.moddev.mc.advantageworks.unit.ForgeCapabilityBootstrap`n`n", [Text.UTF8Encoding]::new($false))
    $capabilityBootstrap = Join-Path $Evidence 'forge-native-unit-bootstrap.jar'
    & $jar16 cfm $capabilityBootstrap $bootstrapManifest -C $bootstrapClasses . *> (Join-Path $Evidence 'bootstrap-jar.log')
    if ($LASTEXITCODE -ne 0) { throw 'Fresh capability bootstrap jar creation failed' }
    $report.capabilityBootstrap = Get-Pin $capabilityBootstrap
    foreach ($control in @('Assumed', 'Ignored')) {
        $controlXml = Join-Path $Evidence "control-$control.xml"
        & $java16 -cp ((@($helper) + $testLibraries) -join ';') zone.moddev.mc.poweradvantage.verification.native117.AuditedPowerJUnit117 `
            $controlXml 1 ('zone.moddev.mc.poweradvantage.verification.native117.AuditedPowerJUnit117Control$' + $control) *> (Join-Path $Evidence "control-$control.log")
        $controlExit = $LASTEXITCODE
        [xml]$controlResult = Get-Content -LiteralPath $controlXml -Raw
        $report.junitControls += [ordered]@{ name = $control; exitCode = $controlExit; xml = (Get-Pin $controlXml) }
        if ($controlExit -eq 0 -or $controlResult.testsuite.passed -ne 'false' -or [int]$controlResult.testsuite.failures -ne 0 -or
            ($control -eq 'Assumed' -and ([int]$controlResult.testsuite.assumptions -ne 1 -or [int]$controlResult.testsuite.tests -ne 1)) -or
            ($control -eq 'Ignored' -and ([int]$controlResult.testsuite.ignored -ne 1 -or [int]$controlResult.testsuite.tests -ne 0))) {
            throw "JUnit $control negative control was not rejected with structured evidence"
        }
    }
    $junitXml = Join-Path $Evidence 'junit.xml'
    $runtime = @($main, $test, $helper, (Join-Path $ProjectRoot 'build/resources/main'), (Join-Path $ProjectRoot 'build/resources/test')) + $testLibraries
    $report.runtime = $runtime
    Push-Location $ProjectRoot
    try {
        & $java16 "-javaagent:$capabilityBootstrap" -Xmx2G -cp ($runtime -join ';') zone.moddev.mc.poweradvantage.verification.native117.AuditedPowerJUnit117 $junitXml $expected @classes *> (Join-Path $Evidence 'junit.log')
        $junitExit = $LASTEXITCODE
    } finally { Pop-Location }
    $report.junit = [ordered]@{ exitCode = $junitExit; expected = $expected; suites = $classes; log = 'junit.log'; xml = 'junit.xml' }
    if ($junitExit -ne 0) { throw 'Fresh ECJ JUnit execution failed' }
    [xml]$junit = Get-Content -LiteralPath $junitXml -Raw
    if ($junit.testsuite.passed -ne 'true' -or [int]$junit.testsuite.tests -ne $expected -or
        [int]$junit.testsuite.failures -or [int]$junit.testsuite.ignored -or [int]$junit.testsuite.assumptions) { throw 'Strict ECJ test counts failed' }
    $actualCases = @($junit.testsuite.testcase | ForEach-Object {
        if ($_.finished -ne 'true') { throw 'A started ECJ test did not finish' }
        $_.classname + '#' + $_.name
    })
    if ($actualCases.Count -ne $expected -or @($actualCases | Sort-Object -Unique).Count -ne $expected -or
        @(Compare-Object ($expectedCases | Sort-Object) ($actualCases | Sort-Object)).Count) {
        throw 'Fresh ECJ test identities differ from the complete Gradle inventory'
    }
    $report.junit.identities = $actualCases
    $report.protectedAfter = @(Get-TreePins $protectedRoots)
    if (($report.protectedBefore | ConvertTo-Json -Depth 5 -Compress) -cne ($report.protectedAfter | ConvertTo-Json -Depth 5 -Compress)) {
        throw 'Protected Gradle/Eclipse output inventory changed during standalone verification'
    }
    $pins = @($report.sources) + @($report.libraries) + @($report.helperSources) + @($report.negativeControl.source) +
        @($report.bootstrapSources) + @($report.capabilityBootstrap) + @($report.gradleInventory) + @($report.resources) +
        @($report.compiler) + @($report.classpath) + @($report.properties) + @($report.verifier) + @($report.legacyVerifier) + @($report.toolchains.Values)
    Assert-Unchanged $pins
    $report.status = 'PASS'
} catch {
    $report.status = 'FAIL'
    $report.error = $_.ToString()
    throw
} finally { Save-Report }
Get-Content -LiteralPath (Join-Path $Evidence 'junit.log') -Tail 8
Write-Output "ECJ PASS: $($sources.main.Count) main, $($sources.test.Count) test sources; $expected tests; Java 16 class files"
