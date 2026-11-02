param([string[]] $GradleArguments = @())
$ErrorActionPreference = 'Stop'

# Use the same audited tasks as CI; never strip or repack a published jar here.
Push-Location $PSScriptRoot
try {
    & ./gradlew.bat clean check build javadoc verifyReleaseDependencies verifyReleaseArtifacts `
        writeReleaseChecksums verifyEclipseProductionClasspath @GradleArguments
    exit $LASTEXITCODE
} finally { Pop-Location }
