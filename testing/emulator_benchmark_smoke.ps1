<#
Isolated AikoQA emulator cold-start, functional smoke, and v0.1.9 -> v0.2.0 upgrade-install check.
HARD SAFETY: Uses isolated ADB port 5038 and ONLY targets emulator-5568 after verifying AVD == AikoQA.
NEVER touches default port 5037, physical devices, or any reboot command.
#>
[CmdletBinding()]
param(
    [string] $Apk,
    [string] $UpgradeFromApk,
    [int] $ColdStartRuns = 5,
    [string] $ToolchainRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..\work\android-toolchain')).Path,
    [int] $AdbPort = 5038,
    [string] $Serial = 'emulator-5568',
    [switch] $SkipFixtureNav
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)

if ($Serial -notmatch '^emulator-\d+$') {
    throw "Refusing to target non-emulator serial: $Serial"
}
$adb = (Resolve-Path -LiteralPath (Join-Path $ToolchainRoot 'platform-tools/adb.exe')).Path

function Emu {
    & $adb -P $AdbPort -s $Serial @args
    if ($LASTEXITCODE -ne 0) { throw "Emulator adb failed: $($args -join ' ')" }
}

$avd = (Emu shell getprop ro.boot.qemu.avd_name | Out-String).Trim()
if ($avd -ne 'AikoQA') {
    throw "Expected dedicated AikoQA emulator on port $AdbPort ($Serial), got '$avd'"
}
Write-Output "Verified target: $Serial (AVD=$avd, port=$AdbPort)"

if ($UpgradeFromApk) {
    Write-Output "=== UPGRADE SEED: installing baseline $UpgradeFromApk ==="
    Emu shell pm uninstall app.aikobrowser 2>$null | Out-Null
    Emu install -g $UpgradeFromApk
    Emu shell am start -W -n app.aikobrowser/.MainActivity | Out-Null
    Start-Sleep -Seconds 2
    Emu shell am start -W -a android.intent.action.VIEW -d 'http://10.0.2.2:18769/a' -n app.aikobrowser/.MainActivity | Out-Null
    Start-Sleep -Seconds 2
    Emu shell am force-stop app.aikobrowser
    Write-Output "Seeded baseline v0.1.9 state; checking upgrade install..."
}

if ($Apk) {
    Write-Output "=== INSTALLING APK (upgrade-compatible -r -g): $Apk ==="
    Emu install -r -g $Apk
}

Emu shell input keyevent KEYCODE_WAKEUP | Out-Null
Emu shell wm dismiss-keyguard | Out-Null

# Cold-start benchmark via am start -W
$totals = @()
$waits = @()
Write-Output "=== COLD START BENCHMARK ($ColdStartRuns runs) ==="
for ($i = 1; $i -le $ColdStartRuns; $i++) {
    Emu shell am force-stop app.aikobrowser
    Start-Sleep -Milliseconds 700
    $out = (Emu shell am start -W -n app.aikobrowser/.MainActivity | Out-String)
    $tot = if ($out -match 'TotalTime:\s*(\d+)') { [int]$Matches[1] } else { -1 }
    $wait = if ($out -match 'WaitTime:\s*(\d+)') { [int]$Matches[1] } else { -1 }
    $totals += $tot
    $waits += $wait
    Write-Output "  run $i : TotalTime=${tot}ms WaitTime=${wait}ms"
    Start-Sleep -Milliseconds 800
}
$sortedTot = $totals | Sort-Object
$medTot = $sortedTot[[int]($sortedTot.Count / 2)]
Write-Output "COLD_START_SUMMARY runs=$ColdStartRuns median_total=${medTot}ms min=$($sortedTot[0])ms max=$($sortedTot[-1])ms"

# Check no fatal crash in logcat
$crashes = (Emu shell "logcat -d -s AndroidRuntime:E" | Out-String)
if ($crashes -match 'app\.aikobrowser') {
    throw "FATAL crash detected in logcat for app.aikobrowser:`n$crashes"
}
Write-Output "PASS zero AndroidRuntime crashes"

# Capture gfxinfo + meminfo snapshot
Emu shell dumpsys gfxinfo app.aikobrowser reset | Out-Null
if (-not $SkipFixtureNav) {
    Emu shell am start -W -a android.intent.action.VIEW -d 'http://10.0.2.2:18769/a' -n app.aikobrowser/.MainActivity | Out-Null
    Start-Sleep -Seconds 3
    for ($s = 0; $s -lt 4; $s++) {
        Emu shell input swipe 270 850 270 250 180 | Out-Null
        Start-Sleep -Milliseconds 350
    }
}
$gfx = (Emu shell dumpsys gfxinfo app.aikobrowser | Out-String)
Write-Output "=== GFXINFO SUMMARY ==="
($gfx -split "`n" | Where-Object { $_ -match 'Total frames rendered|Janky frames|50th percentile|90th percentile|95th percentile|99th percentile' }) | ForEach-Object { Write-Output "  $($_.Trim())" }

$mem = (Emu shell dumpsys meminfo app.aikobrowser | Out-String)
Write-Output "=== MEMINFO SUMMARY ==="
($mem -split "`n" | Where-Object { $_ -match 'TOTAL PSS:|TOTAL:' }) | Select-Object -First 2 | ForEach-Object { Write-Output "  $($_.Trim())" }

$disp = (Emu shell dumpsys display | Out-String)
Write-Output "=== DISPLAY ACTIVE MODE ==="
($disp -split "`n" | Where-Object { $_ -match 'mActiveDisplayMode|mBaseDisplayInfo.*fps' }) | Select-Object -First 2 | ForEach-Object { Write-Output "  $($_.Trim())" }
