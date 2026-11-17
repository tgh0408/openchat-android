param(
    [Parameter(Mandatory = $true)]
    [string]$Serial
)

$ErrorActionPreference = "Stop"
$package = "com.im.openchat"
$command = Get-Command adb -ErrorAction SilentlyContinue
$adb = if ($command) { $command.Source } else { $null }
if (-not $adb) {
    foreach ($sdk in @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)) {
        if ($sdk -and (Test-Path -LiteralPath "$sdk/platform-tools/adb.exe")) {
            $adb = "$sdk/platform-tools/adb.exe"
            break
        }
    }
}
if (-not $adb) { throw "adb was not found. Configure ANDROID_SDK_ROOT or PATH." }

function Invoke-DeviceAdb {
    param([string[]]$AdbArguments)
    & $adb -s $Serial @AdbArguments
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed (exit $LASTEXITCODE)."
    }
}

$state = Invoke-DeviceAdb -AdbArguments @("get-state")
if ("$state".Trim() -ne "device") { throw "The selected device is not ready." }
$installed = Invoke-DeviceAdb -AdbArguments @("shell", "pm", "path", $package)
if (-not ($installed -match "^package:")) { throw "OpenChat is not installed on this device." }

# Clear a persistent debug wait only when it targets this app.
$debugApp = Invoke-DeviceAdb -AdbArguments @("shell", "settings", "get", "global", "debug_app")
if ("$debugApp".Trim() -eq $package) {
    Invoke-DeviceAdb -AdbArguments @("shell", "am", "clear-debug-app")
}

Invoke-DeviceAdb -AdbArguments @("shell", "am", "force-stop", $package)
Invoke-DeviceAdb -AdbArguments @(
    "shell", "am", "start", "-W", "-n", "$package/.MainActivity",
    "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER"
)
Write-Host "Started the installed OpenChat APK without requesting a debugger. App data was not cleared."
