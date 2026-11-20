<#
.SYNOPSIS
Pair, connect, build, and install AR Touchpad over Android Wireless debugging.
.EXAMPLE
.\scripts\install-wireless.ps1 -PairAddress 192.168.1.10:37123 -Address 192.168.1.10:40001
.EXAMPLE
.\scripts\install-wireless.ps1
#>
[CmdletBinding()]
param(
    [string]$PairAddress,
    [string]$Address,
    [string]$Serial,
    [switch]$SkipBuild,
    [switch]$Launch,
    [ValidateSet('prod', 'debug', 'benchmark')]
    [string]$Variant = 'debug',
    [string]$AdbPath
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot

if (-not $AdbPath) {
    $adbCommand = Get-Command adb -ErrorAction SilentlyContinue
    if ($adbCommand) { $AdbPath = $adbCommand.Source }
    else {
        $sdkRoots = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk")
        foreach ($sdkRoot in $sdkRoots) {
            if ($sdkRoot -and (Test-Path -LiteralPath "$sdkRoot\platform-tools\adb.exe")) {
                $AdbPath = "$sdkRoot\platform-tools\adb.exe"
                break
            }
        }
    }
}
if (-not $AdbPath) { throw 'adb not found. Install Android SDK Platform-Tools or supply -AdbPath.' }

function Invoke-AdbChecked {
    param([string[]]$AdbArguments)
    $output = & $AdbPath @AdbArguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $($output -join [Environment]::NewLine)" }
    return $output
}

if ($PairAddress) {
    # adb prompts for the short-lived pairing code; it is never saved in this repo.
    & $AdbPath pair $PairAddress
    if ($LASTEXITCODE -ne 0) { throw 'Pairing failed. Reopen Pair device with pairing code and try again.' }
}
if ($Address) {
    $connection = Invoke-AdbChecked @('connect', $Address)
    Write-Host ($connection -join [Environment]::NewLine)
    if (($connection -join ' ') -notmatch '(already )?connected to') {
        throw 'Connection failed. Use the IP:port on the main Wireless debugging screen, not the pairing port.'
    }
    if (-not $Serial) { $Serial = $Address }
}

$devices = @(Invoke-AdbChecked @('devices', '-l') | ForEach-Object {
    if ($_ -match '^(\S+)\s+device(?:\s|$)') { $Matches[1] }
})
if (-not $Serial) {
    # TLS/mDNS serials and host:port connections are wireless; never pick a USB device.
    $wireless = @($devices | Where-Object { $_ -match ':\d+$|\._adb-tls-connect\._tcp\.?$' })
    if ($wireless.Count -eq 1) { $Serial = $wireless[0] }
    elseif ($wireless.Count -gt 1) {
        throw "Multiple wireless devices found: $($wireless -join ', '). Select one with -Serial."
    } else {
        & $AdbPath mdns services
        throw 'No wireless device connected. Enable Wireless debugging on the same Wi-Fi, then use -PairAddress IP:PAIR_PORT -Address IP:CONNECT_PORT. If already paired, use only -Address.'
    }
}
if ($Serial -notmatch ':\d+$|\._adb-tls-connect\._tcp\.?$') {
    throw 'Select a wireless host:port or _adb-tls-connect._tcp serial.'
}
if ($Serial -notin $devices) { throw "Device $Serial is not online/authorized. Check adb devices -l." }

Push-Location $projectRoot
try {
    if (-not $SkipBuild) {
        if (-not $env:ANDROID_HOME -and (Test-Path -LiteralPath "$env:LOCALAPPDATA\Android\Sdk")) {
            $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
        }
        $buildTask = ':app:assemble' + [char]::ToUpperInvariant($Variant[0]) + $Variant.Substring(1)
        & "$projectRoot\gradlew.bat" $buildTask
        if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed; nothing was installed.' }
    }
    $apk = Join-Path $projectRoot "app\build\outputs\apk\$Variant\app-$Variant.apk"
    if (-not (Test-Path -LiteralPath $apk)) { throw "APK not found: $apk. Run without -SkipBuild." }
    $result = Invoke-AdbChecked @('-s', $Serial, 'install', '-r', $apk)
    Write-Host ($result -join [Environment]::NewLine)
    if (($result -join ' ') -notmatch '\bSuccess\b') {
        throw 'Install did not report success. Existing app data has not been removed.'
    }
    if ($Launch) {
        $packageId = 'au.com.michaelcarey.artouchpad' + $(if ($Variant -eq 'prod') { '' } else { ".$Variant" })
        $result = Invoke-AdbChecked @('-s', $Serial, 'shell', 'am', 'start', '--display', '0', '-n', "$packageId/au.com.michaelcarey.artouchpad.MainActivity")
        Write-Host ($result -join [Environment]::NewLine)
        if (($result -join ' ') -match 'Error:|Exception') { throw 'App launch failed.' }
    }
    Write-Host "Installed AR Touchpad $Variant on $Serial."
} finally {
    Pop-Location
}
