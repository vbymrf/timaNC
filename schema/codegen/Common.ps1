Set-StrictMode -Version 3.0
$ErrorActionPreference = 'Stop'

$script:SchemaRoot = Split-Path -Parent $PSScriptRoot
$script:RepoRoot = Split-Path -Parent $script:SchemaRoot
$script:ToolsRoot = Join-Path $script:SchemaRoot '.tools'
$script:Versions = Import-PowerShellDataFile (Join-Path $PSScriptRoot 'versions.psd1')

function Get-CommandOutput {
    param([string]$Path, [string[]]$Arguments)

    $previousErrorAction = $ErrorActionPreference
    try {
        # java -version writes normal version output to stderr; native stderr
        # must not become a PowerShell terminating error.
        $ErrorActionPreference = 'Continue'
        $output = & $Path @Arguments 2>&1 | Out-String
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorAction
    }
    if ($exitCode -ne 0) {
        throw "'$Path $($Arguments -join ' ')' failed:`n$output"
    }
    return $output.Trim()
}

function Test-ExpectedVersion {
    param([string]$Name, [string]$Path)

    $expected = [regex]::Escape($script:Versions[$Name].Version)
    $oldJavaHome = $env:JAVA_HOME
    try {
        if ($Name -eq 'Gradle') {
            $java = Get-PortableToolPath 'Jdk'
            if (Test-Path $java) {
                $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $java)
            }
        }
        switch ($Name) {
            'Jdk'    { $actual = Get-CommandOutput $Path @('-version') }
            'Go'     { $actual = Get-CommandOutput $Path @('version') }
            'Protoc' { $actual = Get-CommandOutput $Path @('--version') }
            'Buf'    { $actual = Get-CommandOutput $Path @('--version') }
            'Gradle' { $actual = Get-CommandOutput $Path @('--version') }
            default  { return $false }
        }
    }
    finally {
        $env:JAVA_HOME = $oldJavaHome
    }
    return $actual -match $expected
}

function Get-PortableToolPath {
    param([string]$Name)

    $version = $script:Versions[$Name].Version
    $toolHome = Join-Path $script:ToolsRoot "$($Name.ToLowerInvariant())-$version"
    switch ($Name) {
        'Jdk'              { return Join-Path $toolHome 'bin\java.exe' }
        'Go'               { return Join-Path $toolHome 'bin\go.exe' }
        'Protoc'           { return Join-Path $toolHome 'bin\protoc.exe' }
        'Buf'              { return Join-Path $toolHome 'buf.exe' }
        'OpenApiGenerator' { return Join-Path $toolHome 'openapi-generator-cli.jar' }
        'Gradle'           { return Join-Path $toolHome 'bin\gradle.bat' }
        default            { throw "Unknown tool '$Name'." }
    }
}

function Resolve-CodegenTool {
    param([Parameter(Mandatory = $true)][string]$Name)

    $portable = Get-PortableToolPath $Name
    if (Test-Path $portable) {
        if ($Name -eq 'OpenApiGenerator' -or (Test-ExpectedVersion $Name $portable)) {
            return (Resolve-Path $portable).Path
        }
        throw "Portable $Name has an unexpected version: $portable"
    }

    if ($Name -eq 'OpenApiGenerator') {
        return $null
    }

    $commandName = switch ($Name) {
        'Jdk' { 'java.exe' }
        'Go' { 'go.exe' }
        'Protoc' { 'protoc.exe' }
        'Buf' { 'buf.exe' }
        'Gradle' { 'gradle.bat' }
    }
    $command = Get-Command $commandName -ErrorAction SilentlyContinue
    if ($null -ne $command -and (Test-ExpectedVersion $Name $command.Source)) {
        return $command.Source
    }
    return $null
}

function Get-ExpectedChecksum {
    param([hashtable]$Metadata)

    if ($Metadata.ContainsKey('Checksum')) {
        if ($Metadata.Checksum -notmatch '(?i)^[a-f0-9]{64}$') {
            throw "Invalid pinned SHA-256 for $($Metadata.FileName)."
        }
        return $Metadata.Checksum.ToLowerInvariant()
    }

    $content = (Invoke-WebRequest -UseBasicParsing -Uri $Metadata.ChecksumUri).Content
    $text = if ($content -is [byte[]]) {
        [Text.Encoding]::UTF8.GetString($content)
    }
    else {
        [string]$content
    }
    $escapedFileName = [regex]::Escape($Metadata.FileName)
    $matchingLine = ($text -split "`r?`n" | Where-Object { $_ -match $escapedFileName } | Select-Object -First 1)
    if ($matchingLine -and $matchingLine -match '(?i)\b([a-f0-9]{64})\b') {
        return $Matches[1].ToLowerInvariant()
    }
    if ($text -match '(?i)^\s*([a-f0-9]{64})\s*$') {
        return $Matches[1].ToLowerInvariant()
    }
    throw "Could not parse SHA-256 for $($Metadata.FileName) from $($Metadata.ChecksumUri)."
}

function Install-CodegenTool {
    param([Parameter(Mandatory = $true)][string]$Name)

    $metadata = $script:Versions[$Name]
    $destination = Split-Path -Parent (Get-PortableToolPath $Name)
    if ($Name -in @('Jdk', 'Go', 'Protoc', 'Gradle')) {
        $destination = Join-Path $script:ToolsRoot "$($Name.ToLowerInvariant())-$($metadata.Version)"
    }
    $downloads = Join-Path $script:ToolsRoot 'downloads'
    New-Item -ItemType Directory -Force -Path $downloads | Out-Null
    $archive = Join-Path $downloads $metadata.FileName
    $expected = Get-ExpectedChecksum $metadata

    if (-not (Test-Path $archive) -or (Get-FileHash -Algorithm SHA256 $archive).Hash.ToLowerInvariant() -ne $expected) {
        Write-Host "Downloading $Name $($metadata.Version)..."
        $curl = Get-Command 'curl.exe' -ErrorAction SilentlyContinue
        if ($curl) {
            & $curl.Source --location --fail --connect-timeout 30 --max-time 600 --output $archive $metadata.Uri
            if ($LASTEXITCODE -ne 0) {
                throw "curl failed downloading $($metadata.Uri)."
            }
        }
        else {
            Invoke-WebRequest -UseBasicParsing -TimeoutSec 300 -Uri $metadata.Uri -OutFile $archive
        }
    }
    $actual = (Get-FileHash -Algorithm SHA256 $archive).Hash.ToLowerInvariant()
    if ($actual -ne $expected) {
        Remove-Item -Force $archive
        throw "SHA-256 mismatch for $($metadata.FileName): expected $expected, got $actual."
    }

    $toolHome = Join-Path $script:ToolsRoot "$($Name.ToLowerInvariant())-$($metadata.Version)"
    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $toolHome
    New-Item -ItemType Directory -Force -Path $toolHome | Out-Null

    if ($metadata.FileName.EndsWith('.zip')) {
        $temporary = Join-Path $script:ToolsRoot ".$($Name.ToLowerInvariant())-extract"
        Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $temporary
        Expand-Archive -Path $archive -DestinationPath $temporary
        $entries = @(Get-ChildItem $temporary)
        $source = if ($entries.Count -eq 1 -and $entries[0].PSIsContainer) { $entries[0].FullName } else { $temporary }
        Copy-Item -Recurse -Force (Join-Path $source '*') $toolHome
        Remove-Item -Recurse -Force $temporary
    }
    elseif ($Name -eq 'Buf') {
        Copy-Item -Force $archive (Join-Path $toolHome 'buf.exe')
    }
    elseif ($Name -eq 'OpenApiGenerator') {
        Copy-Item -Force $archive (Join-Path $toolHome 'openapi-generator-cli.jar')
    }
    else {
        throw "Unsupported package type for $Name."
    }

    $resolved = Resolve-CodegenTool $Name
    if (-not $resolved) {
        throw "Installed $Name but could not validate it."
    }
    return $resolved
}

function Ensure-CodegenTool {
    param([Parameter(Mandatory = $true)][string]$Name)

    $resolved = Resolve-CodegenTool $Name
    if ($resolved) {
        return $resolved
    }
    return Install-CodegenTool $Name
}
