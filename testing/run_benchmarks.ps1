[CmdletBinding()]
param(
    [string] $RepoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path,
    [string] $ToolchainRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..\work\android-toolchain')).Path,
    [string] $ClassesDir = (Join-Path $env:TEMP ('aiko-bench-classes-' + [Guid]::NewGuid().ToString('N'))),
    [switch] $SkipDownloadTest,
    [switch] $IncludeChromiumSmoke,
    [switch] $KeepClasses
)
$ErrorActionPreference = 'Stop'
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
$jdk = (Get-ChildItem -Directory $ToolchainRoot -Filter 'jdk*' | Select-Object -First 1).FullName
$javac = Join-Path $jdk 'bin/javac.exe'
$java = Join-Path $jdk 'bin/java.exe'
$src = Join-Path $RepoRoot 'app/src/main/java/app/aikobrowser'
$testing = $PSScriptRoot

New-Item -ItemType Directory -Path $ClassesDir -Force | Out-Null

function Run-Timed {
    param([string]$Label, [scriptblock]$Action)
    $sw = [Diagnostics.Stopwatch]::StartNew()
    Write-Output ">>> $Label"
    & $Action
    $code = $LASTEXITCODE
    $sw.Stop()
    Write-Output "<<< $Label | exit=$code | $($sw.ElapsedMilliseconds)ms"
    if ($code -ne 0) { throw "$Label failed with exit code $code" }
}

try {
    $javaSources = [System.Collections.Generic.List[string]]@(
        "$src/RangeDownload.java", "$src/TransferPacer.java", "$src/DownloadRecord.java",
        "$src/FilterRules.java", "$src/CustomFilterList.java", "$src/ScrollPositions.java",
        "$src/FilterTrace.java", "$src/SiteProfile.java",
        "$src/UserScript.java", "$src/UserScriptInput.java",
        "$testing/RangeDownloadCheck.java", "$testing/FilterRulesCheck.java",
        "$testing/ScrollPositionsCheck.java", "$testing/UserCustomizationCheck.java",
        "$testing/SiteDiagnosticsCheck.java", "$testing/FilterRulesBench.java"
    )
    $httpUtil = Join-Path $src 'HttpUrlUtil.java'
    if (Test-Path -LiteralPath $httpUtil) { $javaSources.Add($httpUtil) }

    Run-Timed "javac host checks & bench" {
        & $javac --add-modules jdk.httpserver -encoding UTF-8 -d $ClassesDir @javaSources
    }

    if (-not $SkipDownloadTest) {
        Run-Timed "RangeDownloadCheck" {
            & $java --add-modules jdk.httpserver -cp $ClassesDir app.aikobrowser.RangeDownloadCheck
        }
    }
    Run-Timed "FilterRulesCheck" {
        & $java -cp $ClassesDir app.aikobrowser.FilterRulesCheck (Join-Path $RepoRoot 'app/src/main/assets/filters')
    }
    Run-Timed "ScrollPositionsCheck" {
        & $java -cp $ClassesDir app.aikobrowser.ScrollPositionsCheck
    }
    Run-Timed "UserCustomizationCheck" {
        & $java -cp $ClassesDir app.aikobrowser.UserCustomizationCheck
    }
    Run-Timed "SiteDiagnosticsCheck" {
        & $java -cp $ClassesDir app.aikobrowser.SiteDiagnosticsCheck
    }
    Run-Timed "FilterRulesBench" {
        & $java -cp $ClassesDir app.aikobrowser.FilterRulesBench (Join-Path $RepoRoot 'app/src/main/assets/filters')
    }

    $flagshipTest = Join-Path $testing 'FlagshipUnitChecks.java'
    $displaySrc = Join-Path $src 'DisplayModeEngine.java'
    $catalogSrc = Join-Path $src 'SearchEngineCatalog.java'
    $libSrc = Join-Path $src 'BrowserLibrary.java'
    $parentDir = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
    $androidJar = Join-Path $ToolchainRoot 'platform-36_r02/android-36/android.jar'
    $jsonJar = Join-Path $parentDir 'work/aiko-test-json.jar'
    if ((Test-Path -LiteralPath $flagshipTest) -and (Test-Path -LiteralPath $httpUtil) `
            -and (Test-Path -LiteralPath $displaySrc) -and (Test-Path -LiteralPath $catalogSrc) `
            -and (Test-Path -LiteralPath $androidJar) -and (Test-Path -LiteralPath $jsonJar)) {
        $flagshipClasses = Join-Path $ClassesDir 'flagship'
        New-Item -ItemType Directory -Path $flagshipClasses -Force | Out-Null
        $flagCp = "$ClassesDir;$jsonJar;$androidJar"
        Run-Timed "javac FlagshipUnitChecks" {
            & $javac -encoding UTF-8 -cp $flagCp -d $flagshipClasses $catalogSrc $displaySrc $libSrc $flagshipTest
        }
        Run-Timed "FlagshipUnitChecks" {
            & $java -cp "$flagshipClasses;$flagCp" app.aikobrowser.FlagshipUnitChecks (Join-Path $RepoRoot 'app/src/main/assets/filters')
        }
    }

    $runtimeJs = Join-Path $RepoRoot 'app/src/main/assets/userscripts/runtime.js'
    $runtimeCheck = Join-Path $RepoRoot 'testing/userscript_runtime_check.mjs'
    if (Test-Path -LiteralPath $runtimeCheck) {
        Run-Timed "userscript_runtime_check.mjs" {
            & node $runtimeCheck
        }
    }
    Run-Timed "userscript_iframe_bench.mjs" {
        & node (Join-Path $testing 'userscript_iframe_bench.mjs') $runtimeJs
    }

    if ($IncludeChromiumSmoke) {
        $parent = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
        $smoke = Join-Path $parent 'work/userscript_chromium_smoke.mjs'
        if (Test-Path -LiteralPath $smoke) {
            $before = @(Get-ChildItem -Directory (Join-Path $parent 'work') -Filter 'aiko-chromium-*' -ErrorAction SilentlyContinue | ForEach-Object Name)
            Push-Location $parent
            try {
                Run-Timed "userscript_chromium_smoke.mjs" { & node $smoke }
            } finally {
                Pop-Location
                $after = @(Get-ChildItem -Directory (Join-Path $parent 'work') -Filter 'aiko-chromium-*' -ErrorAction SilentlyContinue)
                foreach ($d in $after) {
                    if ($d.Name -notin $before) { Remove-Item -Recurse -Force $d.FullName -ErrorAction SilentlyContinue }
                }
            }
        }
    }
} finally {
    if (-not $KeepClasses -and (Test-Path -LiteralPath $ClassesDir)) {
        Remove-Item -Recurse -Force $ClassesDir -ErrorAction SilentlyContinue
    }
}
