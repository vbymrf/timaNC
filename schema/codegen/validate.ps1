[CmdletBinding()]
param()

. (Join-Path $PSScriptRoot 'Common.ps1')

$go = Ensure-CodegenTool 'Go'
$buf = Ensure-CodegenTool 'Buf'

Push-Location (Join-Path $script:SchemaRoot 'checks')
try {
    & $go run .
    if ($LASTEXITCODE -ne 0) {
        throw 'JSON Schema/OpenAPI contract checks failed.'
    }
}
finally {
    Pop-Location
}

Push-Location $script:SchemaRoot
try {
    & $buf lint
    if ($LASTEXITCODE -ne 0) {
        throw 'Protobuf lint failed.'
    }
}
finally {
    Pop-Location
}

Write-Host 'Machine-readable contract checks completed successfully.'
