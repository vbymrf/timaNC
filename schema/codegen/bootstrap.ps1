[CmdletBinding()]
param(
    [ValidateSet('All', 'Jdk', 'Go', 'Protoc', 'Buf', 'OpenApiGenerator', 'Gradle')]
    [string[]]$Tool = @('All')
)

. (Join-Path $PSScriptRoot 'Common.ps1')

$names = if ($Tool -contains 'All') {
    @('Jdk', 'Go', 'Protoc', 'Buf', 'OpenApiGenerator', 'Gradle')
}
else {
    $Tool
}

foreach ($name in $names) {
    $path = Ensure-CodegenTool $name
    Write-Host "$name $($script:Versions[$name].Version): $path"
}
