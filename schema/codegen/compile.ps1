[CmdletBinding()]
param()

. (Join-Path $PSScriptRoot 'Common.ps1')

$go = Ensure-CodegenTool 'Go'
$java = Ensure-CodegenTool 'Jdk'
$gradle = Ensure-CodegenTool 'Gradle'
$javaHome = Split-Path -Parent (Split-Path -Parent $java)
$oldJavaHome = $env:JAVA_HOME

try {
    $env:JAVA_HOME = $javaHome

    $goModules = @(Get-ChildItem -Recurse -File -Filter 'go.mod' (Join-Path $script:RepoRoot 'gen\go'))
    foreach ($module in $goModules) {
        Push-Location $module.DirectoryName
        try {
            & $go test ./...
            if ($LASTEXITCODE -ne 0) {
                throw "Go compile check failed in '$($module.DirectoryName)'."
            }
        }
        finally {
            Pop-Location
        }
    }

    Push-Location (Join-Path $script:RepoRoot 'gen\kotlin')
    try {
        & $gradle --no-daemon compileKotlinMetadata compileKotlinJvm
        if ($LASTEXITCODE -ne 0) {
            throw "Kotlin compile check failed."
        }
    }
    finally {
        Pop-Location
    }
}
finally {
    $env:JAVA_HOME = $oldJavaHome
}

Write-Host 'Generated-source compile harness completed successfully.'
