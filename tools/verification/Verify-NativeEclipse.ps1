param(
    [string]$ProjectRoot = (Join-Path $PSScriptRoot '../..'),
    [Parameter(Mandatory = $true)][string]$Evidence,
    [Parameter(Mandatory = $true)][string]$Compiler,
    [Parameter(Mandatory = $true)][string]$JavaHome
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$ProjectRoot = (Resolve-Path -LiteralPath $ProjectRoot).Path
$Evidence = [IO.Path]::GetFullPath($Evidence)
if (Test-Path -LiteralPath $Evidence) { throw 'Refusing to replace Eclipse evidence' }
if ((Get-Content -LiteralPath (Join-Path $JavaHome 'release') -Raw) -notmatch '(?m)^JAVA_VERSION="25(?:\.|\+|")') {
    throw 'The native Eclipse build requires Java 25'
}
$null = New-Item -ItemType Directory -Path $Evidence
$java = Join-Path $JavaHome 'bin/java.exe'
[xml]$classpath = Get-Content -LiteralPath (Join-Path $ProjectRoot '.classpath') -Raw
$report = [ordered]@{
    status='RUNNING'; project=$ProjectRoot
    classpathSha256=(Get-FileHash -LiteralPath (Join-Path $ProjectRoot '.classpath')).Hash
    compilerSha256=(Get-FileHash -LiteralPath $Compiler).Hash
    javaSha256=(Get-FileHash -LiteralPath $java).Hash
    compilations=[ordered]@{}
}
$outputs = @{}
try {
    foreach ($scope in 'main','test') {
        $sources = @()
        foreach ($entry in $classpath.SelectNodes('/classpath/classpathentry[@kind="src"]')) {
            $scopeAttribute = $entry.SelectSingleNode('attributes/attribute[@name="gradle_scope"]/@value')
            if ($scopeAttribute -and $scopeAttribute.Value -eq $scope) {
                $sources += @(Get-ChildItem -LiteralPath (Join-Path $ProjectRoot ([string]$entry.path)) -Recurse -File -Filter '*.java')
            }
        }
        if (-not $sources.Count) { throw "No Eclipse $scope sources" }
        $libraries = @()
        foreach ($entry in $classpath.SelectNodes('/classpath/classpathentry[@kind="lib"]')) {
            $scopes = $entry.SelectSingleNode('attributes/attribute[@name="gradle_used_by_scope"]/@value')
            if ($scopes -and $scopes.Value.Split(',') -notcontains $scope) { continue }
            $path = [string]$entry.path
            if (-not [IO.Path]::IsPathRooted($path)) { $path = Join-Path $ProjectRoot $path }
            if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing Eclipse library: $path" }
            $libraries += $path
        }
        if (-not $libraries.Count) { throw 'Generate the resolved Eclipse classpath first' }
        $output = Join-Path $Evidence $scope
        $null = New-Item -ItemType Directory -Path $output
        $outputs[$scope] = $output
        if ($scope -eq 'test') { $libraries += @($outputs.main, (Join-Path $ProjectRoot 'build/resources/main')) }
        $arguments = @('-25','-proc:none','-encoding','UTF-8','-warn:none','-classpath',
            ('"' + ($libraries -join ';') + '"'), '-d', ('"' + $output + '"'))
        $arguments += @($sources | Sort-Object FullName | ForEach-Object { '"' + $_.FullName + '"' })
        $argFile = Join-Path $Evidence "$scope.args"
        [IO.File]::WriteAllLines($argFile, $arguments, [Text.UTF8Encoding]::new($false))
        & $java -Xmx2G -jar $Compiler "@$argFile" *> (Join-Path $Evidence "$scope.log")
        $code = $LASTEXITCODE
        $report.compilations[$scope] = @{ exitCode=$code; sources=$sources.Count; argumentsSha256=(Get-FileHash -LiteralPath $argFile).Hash }
        if ($code -ne 0) { throw "Eclipse $scope compilation failed; inspect retained diagnostics" }
        $classes = @(Get-ChildItem -LiteralPath $output -Recurse -Filter '*.class' -File)
        if (-not $classes.Count) { throw "No Eclipse $scope classes" }
        foreach ($file in $classes) {
            $bytes = [IO.File]::ReadAllBytes($file.FullName)
            if ($bytes[6] * 256 + $bytes[7] -ne 69) { throw "Non-Java-25 class: $file" }
        }
        $report.compilations[$scope].classes = $classes.Count
    }
    $inventory = @()
    $expected = 0
    foreach ($file in Get-ChildItem -LiteralPath (Join-Path $ProjectRoot 'build/test-results/test') -Filter 'TEST-*.xml') {
        [xml]$suite = Get-Content -LiteralPath $file.FullName -Raw
        if ([int]$suite.testsuite.failures -or [int]$suite.testsuite.errors -or [int]$suite.testsuite.skipped) {
            throw 'The Gradle inventory includes failed or skipped tests'
        }
        $inventory += [string]$suite.testsuite.name
        $expected += [int]$suite.testsuite.tests
    }
    if (-not $expected) { throw 'Missing Gradle test inventory' }
    $runtime = @($outputs.test, $outputs.main, (Join-Path $ProjectRoot 'build/resources/main'),
            (Join-Path $ProjectRoot 'build/resources/test')) + $libraries
    $runtimeClasspath = $runtime -join ';'
    $bootstrap = Join-Path $ProjectRoot 'build/unit-bootstrap/forge-native-unit-bootstrap.jar'
    if (-not (Test-Path -LiteralPath $bootstrap -PathType Leaf)) { throw 'Missing native unit bootstrap' }
    $testArgs = @('-Xmx2G', ('"-javaagent:' + $bootstrap.Replace('\', '/') + '"'),
            ('"-Dbasepower.nativePackClasspath=' + $runtimeClasspath.Replace('\', '/') + '"'),
            '-cp', ('"' + $runtimeClasspath.Replace('\', '/') + '"'),
            'zone.moddev.mc.advantageworks.unit.EclipseUnitLauncher', [string]$expected) + $inventory
    $testArgFile = Join-Path $Evidence 'junit.args'
    [IO.File]::WriteAllLines($testArgFile, $testArgs, [Text.UTF8Encoding]::new($false))
    Push-Location $ProjectRoot
    try {
        & $java "@$testArgFile" *> (Join-Path $Evidence 'junit.log')
        $code = $LASTEXITCODE
    } finally { Pop-Location }
    $report['junit'] = @{ exitCode=$code; expectedTests=$expected; suites=$inventory }
    if ($code -ne 0) { throw 'Fresh Eclipse-output tests failed; inspect retained junit.log' }
    $summary = Get-Content -LiteralPath (Join-Path $Evidence 'junit.log') -Raw
    if (-not $summary.Contains("PASS: $expected tests; zero failures, ignored tests or assumptions")) {
        throw 'Missing exact fresh-output test count'
    }
    $report.status = 'PASS'
} catch {
    $report.status = 'FAIL'
    $report['error'] = $_.Exception.Message
    throw
} finally {
    $report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $Evidence 'ecj-results.json') -Encoding utf8
}
Write-Output 'PASS: both Eclipse source scopes compile and the complete test inventory passes on fresh ECJ output'
