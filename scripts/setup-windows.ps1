#Requires -Version 5.1
<#
.SYNOPSIS
  Interactive TIMA Phase 1 Windows host tooling installer / verifier.
.NOTES
  Entry: scripts\setup-windows.bat
  Log:   scripts\setup-windows-log.txt (PASS lines only)
#>
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Continue'

$ScriptDir = $PSScriptRoot
$RepoRoot = Split-Path -Parent $ScriptDir
$LogPath = Join-Path $ScriptDir 'setup-windows-log.txt'
$AndroidSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$GradleHome = Join-Path $env:LOCALAPPDATA 'Programs\Gradle'
$GradleVersion = '8.14.3'
$GradleDir = Join-Path $GradleHome "gradle-$GradleVersion"
$AvdName = 'Tima_API_34'

$Packages = [ordered]@{
    '1' = @{ Id = '1'; Key = 'DockerDesktop'; Title = 'Docker Desktop' }
    '2' = @{ Id = '2'; Key = 'JDK17'; Title = 'JDK 17 (Microsoft OpenJDK)' }
    '3' = @{ Id = '3'; Key = 'Git'; Title = 'Git' }
    '4' = @{ Id = '4'; Key = 'GitHubCLI'; Title = 'GitHub CLI (gh)' }
    '5' = @{ Id = '5'; Key = 'WindowsSDK'; Title = 'Windows SDK (makeappx)' }
    '6' = @{ Id = '6'; Key = 'Gradle'; Title = "Gradle $GradleVersion+" }
    '7' = @{ Id = '7'; Key = 'AndroidSDK'; Title = 'Android SDK base' }
    '8' = @{ Id = '8'; Key = 'AndroidAVD'; Title = "Android system-image + AVD $AvdName" }
    '9' = @{ Id = '9'; Key = 'CodegenTools'; Title = 'Codegen tools (schema/.tools)' }
}

function Write-Info([string]$Message) {
    Write-Host $Message -ForegroundColor Cyan
}

function Write-Ok([string]$Message) {
    Write-Host $Message -ForegroundColor Green
}

function Write-Fail([string]$Message) {
    Write-Host $Message -ForegroundColor Red
}

function Write-PassLog {
    param(
        [Parameter(Mandatory)][string]$Id,
        [Parameter(Mandatory)][string]$Key,
        [Parameter(Mandatory)][string]$Detail
    )
    $stamp = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
    $line = "[$stamp] PASS $Id $Key  $Detail"
    Add-Content -Path $LogPath -Value $line -Encoding UTF8
    Write-Ok "LOG: $line"
}

function Refresh-PathFromMachine {
    $machine = [Environment]::GetEnvironmentVariable('Path', 'Machine')
    $user = [Environment]::GetEnvironmentVariable('Path', 'User')
    $env:Path = @($machine, $user) -join ';'
}

function Add-UserPathEntry {
    param([Parameter(Mandatory)][string]$Entry)
    $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
    if ([string]::IsNullOrWhiteSpace($userPath)) {
        $parts = @()
    }
    else {
        $parts = $userPath -split ';' | Where-Object { $_ -and $_.Trim() }
    }
    $normalized = $Entry.TrimEnd('\')
    $exists = $parts | Where-Object { $_.TrimEnd('\') -ieq $normalized }
    if (-not $exists) {
        $parts += $Entry
        [Environment]::SetEnvironmentVariable('Path', ($parts -join ';'), 'User')
    }
    Refresh-PathFromMachine
    if (-not (($env:Path -split ';') | Where-Object { $_.TrimEnd('\') -ieq $normalized })) {
        $env:Path = "$Entry;$env:Path"
    }
}

function Invoke-WingetInstall {
    param(
        [Parameter(Mandatory)][string]$PackageId,
        [string]$Override
    )
    Refresh-PathFromMachine
    $winget = Get-Command winget -ErrorAction SilentlyContinue
    if (-not $winget) {
        Write-Fail 'winget not found. Install App Installer from Microsoft Store.'
        return $false
    }
    $args = @(
        'install', '--id', $PackageId,
        '-e', '--accept-package-agreements', '--accept-source-agreements',
        '--disable-interactivity'
    )
    if ($Override) {
        $args += @('--override', $Override)
    }
    Write-Info ("winget " + ($args -join ' '))
    & winget @args
    $code = $LASTEXITCODE
    # 0 = installed/ok, -1978335189 (0x8A15002B) often already installed
    if ($code -eq 0 -or $code -eq -1978335189 -or $code -eq -1978335135) {
        Refresh-PathFromMachine
        return $true
    }
    Write-Fail "winget exit code: $code"
    return $false
}

function Find-MakeAppx {
    $kits = Join-Path ${env:ProgramFiles(x86)} 'Windows Kits\10\bin'
    if (-not (Test-Path $kits)) { return $null }
    $found = Get-ChildItem -Path $kits -Recurse -Filter 'makeappx.exe' -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending |
        Select-Object -First 1
    if ($found) { return $found.FullName }
    return $null
}

function Get-SdkManager {
    $candidates = @(
        (Join-Path $AndroidSdk 'cmdline-tools\latest\bin\sdkmanager.bat'),
        (Join-Path $AndroidSdk 'cmdline-tools\bin\sdkmanager.bat')
    )
    foreach ($c in $candidates) {
        if (Test-Path $c) { return $c }
    }
    $any = Get-ChildItem -Path (Join-Path $AndroidSdk 'cmdline-tools') -Recurse -Filter 'sdkmanager.bat' -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($any) { return $any.FullName }
    return $null
}

function Get-AvdManager {
    $sdkManager = Get-SdkManager
    if (-not $sdkManager) { return $null }
    $dir = Split-Path -Parent $sdkManager
    $avd = Join-Path $dir 'avdmanager.bat'
    if (Test-Path $avd) { return $avd }
    return $null
}

function Accept-AndroidLicenses {
    $sdkManager = Get-SdkManager
    if (-not $sdkManager) {
        Write-Fail 'sdkmanager not found; cannot accept licenses.'
        return $false
    }
    Write-Info 'Accepting Android SDK licenses...'
    $yes = ("y`n" * 20)
    $yes | & cmd /c "`"$sdkManager`" --sdk_root=`"$AndroidSdk`" --licenses"
    return $true
}

function Install-AndroidPackages {
    param([Parameter(Mandatory)][string[]]$Packages)
    $sdkManager = Get-SdkManager
    if (-not $sdkManager) {
        Write-Fail 'sdkmanager not found.'
        return $false
    }
    Write-Info ("sdkmanager install: " + ($Packages -join ', '))
    $quoted = ($Packages | ForEach-Object { '"{0}"' -f $_ }) -join ' '
    $cmd = '"{0}" --sdk_root="{1}" --install {2}' -f $sdkManager, $AndroidSdk, $quoted
    & cmd /c $cmd
    return ($LASTEXITCODE -eq 0)
}

function Ensure-AndroidCmdlineTools {
    if (Get-SdkManager) { return $true }

    New-Item -ItemType Directory -Force -Path $AndroidSdk | Out-Null
    $zipUrl = 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip'
    $zipPath = Join-Path $env:TEMP 'android-cmdline-tools.zip'
    $extract = Join-Path $env:TEMP 'android-cmdline-tools-extract'
    Write-Info "Downloading Android cmdline-tools..."
    Invoke-WebRequest -Uri $zipUrl -OutFile $zipPath -UseBasicParsing
    if (Test-Path $extract) { Remove-Item $extract -Recurse -Force }
    Expand-Archive -Path $zipPath -DestinationPath $extract -Force
    $dst = Join-Path $AndroidSdk 'cmdline-tools\latest'
    New-Item -ItemType Directory -Force -Path (Split-Path $dst) | Out-Null
    if (Test-Path $dst) { Remove-Item $dst -Recurse -Force }
    $sourceRoot = Get-ChildItem $extract -Directory | Select-Object -First 1
    if (-not $sourceRoot) {
        Write-Fail 'cmdline-tools zip layout unexpected.'
        return $false
    }
    # Zip contains a "cmdline-tools" folder; place its contents under cmdline-tools\latest
    if ($sourceRoot.Name -eq 'cmdline-tools') {
        Move-Item $sourceRoot.FullName $dst
    }
    else {
        New-Item -ItemType Directory -Force -Path $dst | Out-Null
        Move-Item (Join-Path $extract '*') $dst
    }
    Add-UserPathEntry (Join-Path $AndroidSdk 'platform-tools')
    Add-UserPathEntry (Join-Path $AndroidSdk 'emulator')
    $env:ANDROID_HOME = $AndroidSdk
    [Environment]::SetEnvironmentVariable('ANDROID_HOME', $AndroidSdk, 'User')
    return [bool](Get-SdkManager)
}

# --- Verify helpers return @{ Ok = bool; Detail = string } ---

function Test-DockerDesktop {
    Refresh-PathFromMachine
    try {
        $client = & docker version --format '{{.Client.Version}}' 2>$null
        if (-not $client) {
            return @{ Ok = $false; Detail = 'docker client not available (is Docker Desktop installed/running?)' }
        }
        $compose = & docker compose version 2>$null
        if (-not $compose) {
            return @{ Ok = $false; Detail = "docker client $client but compose missing" }
        }
        return @{ Ok = $true; Detail = "Docker $client; $compose" }
    }
    catch {
        return @{ Ok = $false; Detail = $_.Exception.Message }
    }
}

function Test-JDK17 {
    Refresh-PathFromMachine
    $java = Get-Command java -ErrorAction SilentlyContinue
    if (-not $java) {
        $candidates = @(
            'C:\Program Files\Microsoft\jdk-*\bin\java.exe',
            'C:\Program Files\Eclipse Adoptium\jdk-17*\bin\java.exe',
            'C:\Program Files\Java\jdk-17*\bin\java.exe'
        )
        foreach ($pattern in $candidates) {
            $hit = Get-Item $pattern -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($hit) {
                $java = $hit
                break
            }
        }
    }
    if (-not $java) {
        return @{ Ok = $false; Detail = 'java not found' }
    }
    $exe = if ($java.Source) { $java.Source } else { $java.FullName }
    $ver = & $exe -version 2>&1 | Out-String
    if ($ver -match 'version\s+"?17[\."]') {
        $first = ($ver -split "`n" | Select-Object -First 1).Trim()
        return @{ Ok = $true; Detail = $first }
    }
    return @{ Ok = $false; Detail = "JDK 17 required; got: $(($ver -split "`n" | Select-Object -First 1).Trim())" }
}

function Test-Git {
    Refresh-PathFromMachine
    $git = Get-Command git -ErrorAction SilentlyContinue
    if (-not $git) { return @{ Ok = $false; Detail = 'git not found' } }
    $v = (& git --version 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -and -not $v) { return @{ Ok = $false; Detail = 'git --version failed' } }
    return @{ Ok = $true; Detail = $v }
}

function Test-GitHubCLI {
    Refresh-PathFromMachine
    $gh = Get-Command gh -ErrorAction SilentlyContinue
    if (-not $gh) { return @{ Ok = $false; Detail = 'gh not found' } }
    $v = (& gh --version 2>&1 | Select-Object -First 1 | Out-String).Trim()
    return @{ Ok = $true; Detail = $v }
}

function Test-WindowsSDK {
    $makeappx = Find-MakeAppx
    if (-not $makeappx) {
        return @{ Ok = $false; Detail = 'makeappx.exe not found under Windows Kits\10\bin' }
    }
    return @{ Ok = $true; Detail = $makeappx }
}

function Test-Gradle {
    Refresh-PathFromMachine
    $gradleBat = Join-Path $GradleDir 'bin\gradle.bat'
    $gradleCmd = Get-Command gradle -ErrorAction SilentlyContinue
    $exe = $null
    if ($gradleCmd) { $exe = $gradleCmd.Source }
    elseif (Test-Path $gradleBat) { $exe = $gradleBat }

    if (-not $exe) {
        return @{ Ok = $false; Detail = "gradle not found (expected $gradleBat or PATH)" }
    }
    $out = & $exe -v 2>&1 | Out-String
    if ($out -match 'Gradle\s+(\d+)\.(\d+)') {
        $major = [int]$Matches[1]
        $minor = [int]$Matches[2]
        if ($major -gt 8 -or ($major -eq 8 -and $minor -ge 14)) {
            $line = ($out -split "`n" | Where-Object { $_ -match 'Gradle\s+\d' } | Select-Object -First 1).Trim()
            return @{ Ok = $true; Detail = "$line ($exe)" }
        }
        return @{ Ok = $false; Detail = "Gradle 8.14+ required; got $major.$minor" }
    }
    return @{ Ok = $false; Detail = 'could not parse gradle -v' }
}

function Test-AndroidSDK {
    $adb = Join-Path $AndroidSdk 'platform-tools\adb.exe'
    $emu = Join-Path $AndroidSdk 'emulator\emulator.exe'
    $plat34 = Join-Path $AndroidSdk 'platforms\android-34'
    $bt34 = Join-Path $AndroidSdk 'build-tools\34.0.0'
    $missing = @()
    if (-not (Test-Path $adb)) { $missing += 'platform-tools' }
    if (-not (Test-Path $emu)) { $missing += 'emulator' }
    if (-not (Test-Path $plat34)) { $missing += 'platforms;android-34' }
    if (-not (Test-Path $bt34) -and -not (Test-Path (Join-Path $AndroidSdk 'build-tools\35.0.0'))) {
        $missing += 'build-tools 34/35'
    }
    if (-not (Get-SdkManager)) { $missing += 'cmdline-tools' }
    if ($missing.Count -gt 0) {
        return @{ Ok = $false; Detail = 'missing: ' + ($missing -join ', ') }
    }
    $adbVer = (& $adb version 2>&1 | Select-Object -First 1 | Out-String).Trim()
    return @{ Ok = $true; Detail = "SDK=$AndroidSdk; $adbVer" }
}

function Test-AndroidAVD {
    $emu = Join-Path $AndroidSdk 'emulator\emulator.exe'
    $img = Join-Path $AndroidSdk 'system-images\android-34\google_apis\x86_64'
    if (-not (Test-Path $img)) {
        return @{ Ok = $false; Detail = 'system-image android-34 google_apis x86_64 missing' }
    }
    if (-not (Test-Path $emu)) {
        return @{ Ok = $false; Detail = 'emulator.exe missing' }
    }
    $avds = & $emu -list-avds 2>&1 | ForEach-Object { $_.Trim() } | Where-Object { $_ }
    if ($avds -contains $AvdName) {
        return @{ Ok = $true; Detail = "AVD $AvdName present; image ok" }
    }
    return @{ Ok = $false; Detail = "AVD $AvdName not in: $($avds -join ', ')" }
}

function Test-CodegenTools {
    $versionsPath = Join-Path $RepoRoot 'schema\codegen\versions.psd1'
    if (-not (Test-Path $versionsPath)) {
        return @{ Ok = $false; Detail = "missing $versionsPath" }
    }
    $versions = Import-PowerShellDataFile $versionsPath
    $toolsRoot = Join-Path $RepoRoot 'schema\.tools'
    if (-not (Test-Path $toolsRoot)) {
        return @{ Ok = $false; Detail = 'schema\.tools missing (run bootstrap.ps1)' }
    }

    # Layout matches schema/codegen/Common.ps1 Get-PortableToolPath (Name.ToLowerInvariant())
    function Get-CodegenPortablePath([string]$Name) {
        $ver = $versions[$Name].Version
        $home = Join-Path $toolsRoot ("{0}-{1}" -f $Name.ToLowerInvariant(), $ver)
        switch ($Name) {
            'Jdk' { return Join-Path $home 'bin\java.exe' }
            'Go' { return Join-Path $home 'bin\go.exe' }
            'Protoc' { return Join-Path $home 'bin\protoc.exe' }
            'Buf' { return Join-Path $home 'buf.exe' }
            'OpenApiGenerator' { return Join-Path $home 'openapi-generator-cli.jar' }
            'Gradle' { return Join-Path $home 'bin\gradle.bat' }
            default { return $null }
        }
    }

    $checks = @()
    foreach ($name in @('Jdk', 'Go', 'Protoc', 'Buf', 'OpenApiGenerator', 'Gradle')) {
        $path = Get-CodegenPortablePath $name
        if (-not (Test-Path $path)) {
            return @{ Ok = $false; Detail = "missing $name $($versions[$name].Version) at $path" }
        }
        $checks += "$name@$($versions[$name].Version)"
    }
    return @{ Ok = $true; Detail = ($checks -join ', ') }
}

function Invoke-Verify {
    param(
        [Parameter(Mandatory)][hashtable]$Pkg,
        [switch]$WriteLog
    )
    Write-Info "VERIFY $($Pkg.Id) $($Pkg.Title)..."
    $result = switch ($Pkg.Key) {
        'DockerDesktop' { Test-DockerDesktop }
        'JDK17' { Test-JDK17 }
        'Git' { Test-Git }
        'GitHubCLI' { Test-GitHubCLI }
        'WindowsSDK' { Test-WindowsSDK }
        'Gradle' { Test-Gradle }
        'AndroidSDK' { Test-AndroidSDK }
        'AndroidAVD' { Test-AndroidAVD }
        'CodegenTools' { Test-CodegenTools }
        default { @{ Ok = $false; Detail = "unknown package $($Pkg.Key)" } }
    }
    if ($result.Ok) {
        Write-Ok "PASS: $($result.Detail)"
        if ($WriteLog) {
            Write-PassLog -Id $Pkg.Id -Key $Pkg.Key -Detail $result.Detail
        }
    }
    else {
        Write-Fail "FAIL: $($result.Detail)"
    }
    return [bool]$result.Ok
}

function Install-DockerDesktop {
    return Invoke-WingetInstall -PackageId 'Docker.DockerDesktop'
}

function Install-JDK17 {
    return Invoke-WingetInstall -PackageId 'Microsoft.OpenJDK.17'
}

function Install-Git {
    return Invoke-WingetInstall -PackageId 'Git.Git'
}

function Install-GitHubCLI {
    return Invoke-WingetInstall -PackageId 'GitHub.cli'
}

function Install-WindowsSDK {
    # Prefer a known winget id; fall back to search if needed
    $ids = @(
        'Microsoft.WindowsSDK.10.0.22621',
        'Microsoft.WindowsSDK.10.0.26100',
        'Microsoft.WindowsSDK'
    )
    foreach ($id in $ids) {
        Write-Info "Trying winget package $id ..."
        if (Invoke-WingetInstall -PackageId $id) {
            if (Find-MakeAppx) { return $true }
        }
    }
    Write-Fail 'Windows SDK winget install did not yield makeappx.exe. Install "Windows Software Development Kit" via Visual Studio Installer if needed.'
    return $false
}

function Install-Gradle {
    New-Item -ItemType Directory -Force -Path $GradleHome | Out-Null
    if (-not (Test-Path (Join-Path $GradleDir 'bin\gradle.bat'))) {
        $zipUrl = "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip"
        $zipPath = Join-Path $env:TEMP "gradle-$GradleVersion-bin.zip"
        Write-Info "Downloading Gradle $GradleVersion..."
        Invoke-WebRequest -Uri $zipUrl -OutFile $zipPath -UseBasicParsing
        Write-Info "Extracting to $GradleHome ..."
        Expand-Archive -Path $zipPath -DestinationPath $GradleHome -Force
    }
    $bin = Join-Path $GradleDir 'bin'
    if (-not (Test-Path (Join-Path $bin 'gradle.bat'))) {
        Write-Fail "gradle.bat missing after extract under $GradleDir"
        return $false
    }
    Add-UserPathEntry $bin
    return $true
}

function Install-AndroidSDK {
    if (-not (Ensure-AndroidCmdlineTools)) { return $false }
    Accept-AndroidLicenses | Out-Null
    $pkgs = @(
        'platform-tools',
        'emulator',
        'platforms;android-34',
        'platforms;android-35',
        'build-tools;34.0.0',
        'build-tools;35.0.0'
    )
    if (-not (Install-AndroidPackages -Packages $pkgs)) {
        Write-Fail 'sdkmanager package install reported failure.'
        # still continue to verify — some packages may already be present
    }
    Add-UserPathEntry (Join-Path $AndroidSdk 'platform-tools')
    Add-UserPathEntry (Join-Path $AndroidSdk 'emulator')
    $env:ANDROID_HOME = $AndroidSdk
    [Environment]::SetEnvironmentVariable('ANDROID_HOME', $AndroidSdk, 'User')
    return $true
}

function Install-AndroidAVD {
    if (-not (Ensure-AndroidCmdlineTools)) { return $false }
    Accept-AndroidLicenses | Out-Null
    $imgPkg = 'system-images;android-34;google_apis;x86_64'
    Install-AndroidPackages -Packages @($imgPkg) | Out-Null

    $emu = Join-Path $AndroidSdk 'emulator\emulator.exe'
    if (Test-Path $emu) {
        $existing = & $emu -list-avds 2>&1 | ForEach-Object { $_.Trim() }
        if ($existing -contains $AvdName) {
            Write-Ok "AVD $AvdName already exists."
            return $true
        }
    }

    $avdManager = Get-AvdManager
    if (-not $avdManager) {
        Write-Fail 'avdmanager not found.'
        return $false
    }
    Write-Info "Creating AVD $AvdName..."
    # --force overwrites; echo no to "Do you wish to create a custom hardware profile?"
    "no" | & cmd /c "`"$avdManager`" create avd -n $AvdName -k `"$imgPkg`" -d pixel_6 --force"
    if ($LASTEXITCODE -ne 0) {
        Write-Fail "avdmanager exit code $LASTEXITCODE"
        return $false
    }
    return $true
}

function Install-CodegenTools {
    $bootstrap = Join-Path $RepoRoot 'schema\codegen\bootstrap.ps1'
    if (-not (Test-Path $bootstrap)) {
        Write-Fail "missing $bootstrap"
        return $false
    }
    Write-Info "Running $bootstrap ..."
    Push-Location $RepoRoot
    try {
        & $bootstrap
        if ($null -ne $LASTEXITCODE -and $LASTEXITCODE -ne 0) {
            Write-Fail "bootstrap.ps1 exit code $LASTEXITCODE"
            return $false
        }
        return $true
    }
    catch {
        Write-Fail ("bootstrap.ps1 failed: " + $_.Exception.Message)
        return $false
    }
    finally {
        Pop-Location
    }
}

function Invoke-Install {
    param([Parameter(Mandatory)][hashtable]$Pkg)
    Write-Info "INSTALL $($Pkg.Id) $($Pkg.Title)..."
    $ok = switch ($Pkg.Key) {
        'DockerDesktop' { Install-DockerDesktop }
        'JDK17' { Install-JDK17 }
        'Git' { Install-Git }
        'GitHubCLI' { Install-GitHubCLI }
        'WindowsSDK' { Install-WindowsSDK }
        'Gradle' { Install-Gradle }
        'AndroidSDK' { Install-AndroidSDK }
        'AndroidAVD' { Install-AndroidAVD }
        'CodegenTools' { Install-CodegenTools }
        default {
            Write-Fail "unknown package $($Pkg.Key)"
            $false
        }
    }
    if (-not $ok) {
        Write-Fail "Install step reported failure for $($Pkg.Key)."
    }
    Write-Info 'Re-checking after install...'
    Refresh-PathFromMachine
    [void](Invoke-Verify -Pkg $Pkg -WriteLog)
}

function Invoke-VerifyAll {
    Write-Info 'VERIFY ALL (1-9)...'
    foreach ($key in $Packages.Keys) {
        [void](Invoke-Verify -Pkg $Packages[$key] -WriteLog)
        Write-Host ''
    }
    Write-Info "Log file: $LogPath"
}

function Show-MainMenu {
    Clear-Host
    Write-Host '========================================' -ForegroundColor Yellow
    Write-Host ' TIMA Phase 1 — Windows tooling setup' -ForegroundColor Yellow
    Write-Host '========================================' -ForegroundColor Yellow
    Write-Host "Repo: $RepoRoot"
    Write-Host "Log:  $LogPath (PASS only)"
    Write-Host ''
    foreach ($key in $Packages.Keys) {
        $p = $Packages[$key]
        Write-Host ("  {0}. {1}" -f $p.Id, $p.Title)
    }
    Write-Host '  0. Verify ALL (write PASS lines to log)'
    Write-Host '  Q. Quit'
    Write-Host ''
}

function Show-SubMenu([hashtable]$Pkg) {
    Write-Host ''
    Write-Host ("--- {0}. {1} ---" -f $Pkg.Id, $Pkg.Title) -ForegroundColor Yellow
    Write-Host '  1. Install'
    Write-Host '  2. Verify installation'
    Write-Host '  0. Back'
    Write-Host ''
}

function Start-SetupUi {
    Refresh-PathFromMachine
    while ($true) {
        Show-MainMenu
        $choice = (Read-Host 'Select package number').Trim()
        if ($choice -match '^[Qq]$') {
            Write-Info 'Bye.'
            return
        }
        if ($choice -eq '0') {
            Invoke-VerifyAll
            Read-Host 'Press Enter to continue'
            continue
        }
        if (-not $Packages.Contains($choice)) {
            Write-Fail 'Unknown selection.'
            Start-Sleep -Seconds 1
            continue
        }
        $pkg = $Packages[$choice]
        while ($true) {
            Show-SubMenu $pkg
            $sub = (Read-Host 'Select action').Trim()
            if ($sub -eq '0') { break }
            if ($sub -eq '1') {
                Invoke-Install -Pkg $pkg
                Read-Host 'Press Enter to continue'
            }
            elseif ($sub -eq '2') {
                [void](Invoke-Verify -Pkg $pkg -WriteLog)
                Read-Host 'Press Enter to continue'
            }
            else {
                Write-Fail 'Unknown action.'
                Start-Sleep -Seconds 1
            }
        }
    }
}

Start-SetupUi
