[CmdletBinding()]
param(
    [string] $RepoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path,
    [string] $ToolchainRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..\work\android-toolchain')).Path,
    [string] $ClassesDir,
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

$ownsClasses = -not $ClassesDir
if ($ownsClasses) { $ClassesDir = Join-Path $env:TEMP ('aiko-bench-classes-' + [Guid]::NewGuid().ToString('N')) }
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
        "$src/RangeDownload.java", "$src/TransferPacer.java", "$src/DownloadRecord.java", "$src/DownloadQueue.java",
        "$src/FilterRules.java", "$src/CustomFilterList.java", "$src/ScrollPositions.java",
        "$src/FilterTrace.java", "$src/SiteProfile.java",
        "$src/UserScript.java", "$src/UserScriptInput.java",
        "$testing/RangeDownloadCheck.java", "$testing/DownloadQueueCheck.java", "$testing/FilterRulesCheck.java",
        "$testing/ScrollPositionsCheck.java", "$testing/UserCustomizationCheck.java",
        "$testing/SiteDiagnosticsCheck.java", "$testing/FilterRulesBench.java"
    )
    $httpUtil = Join-Path $src 'HttpUrlUtil.java'
    if (Test-Path -LiteralPath $httpUtil) { $javaSources.Add($httpUtil) }
    $urlCleaner = Join-Path $src 'UrlCleaner.java'
    if (Test-Path -LiteralPath $urlCleaner) { $javaSources.Add($urlCleaner) }
    $videoBooster = Join-Path $src 'VideoBooster.java'
    if (Test-Path -LiteralPath $videoBooster) { $javaSources.Add($videoBooster) }
    $pageToolkit = Join-Path $src 'PageToolkit.java'
    if (Test-Path -LiteralPath $pageToolkit) { $javaSources.Add($pageToolkit) }
    $webDevTools = Join-Path $src 'WebDevTools.java'
    $androidJarEarly = Join-Path $ToolchainRoot 'platform-36_r02/android-36/android.jar'
    if ((Test-Path -LiteralPath $webDevTools) -and (Test-Path -LiteralPath $androidJarEarly)) {
        $javaSources.Add($webDevTools)
    }

    Run-Timed "javac host checks & bench" {
        if (Test-Path -LiteralPath $androidJarEarly) {
            & $javac --add-modules jdk.httpserver -encoding UTF-8 -cp $androidJarEarly -d $ClassesDir @javaSources
        } else {
            & $javac --add-modules jdk.httpserver -encoding UTF-8 -d $ClassesDir @javaSources
        }
    }

    Run-Timed "DownloadQueueCheck" {
        & $java -cp $ClassesDir app.aikobrowser.DownloadQueueCheck
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
    $perfSrc = Join-Path $src 'PerfProfiles.java'
    $catalogSrc = Join-Path $src 'SearchEngineCatalog.java'
    $libSrc = Join-Path $src 'BrowserLibrary.java'
    $themeSrc = Join-Path $src 'AikoTheme.java'
    $iconSrc = Join-Path $src 'BrowserIcon.java'
    $gestureSrc = Join-Path $src 'GestureChrome.java'
    $menuCfgSrc = Join-Path $src 'MenuConfig.java'
    $parentDir = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
    $androidJar = Join-Path $ToolchainRoot 'platform-36_r02/android-36/android.jar'
    $jsonJar = Join-Path $parentDir 'work/aiko-test-json.jar'
    if ((Test-Path -LiteralPath $flagshipTest) -and (Test-Path -LiteralPath $httpUtil) `
            -and (Test-Path -LiteralPath $displaySrc) -and (Test-Path -LiteralPath $perfSrc) `
            -and (Test-Path -LiteralPath $catalogSrc) `
            -and (Test-Path -LiteralPath $gestureSrc) -and (Test-Path -LiteralPath $themeSrc) `
            -and (Test-Path -LiteralPath $iconSrc) -and (Test-Path -LiteralPath $androidJar) `
            -and (Test-Path -LiteralPath $jsonJar)) {
        $flagshipClasses = Join-Path $ClassesDir 'flagship'
        New-Item -ItemType Directory -Path $flagshipClasses -Force | Out-Null
        $flagCp = "$ClassesDir;$jsonJar;$androidJar"
        $flagshipSources = [System.Collections.Generic.List[string]]@(
            $catalogSrc, $displaySrc, $perfSrc, $libSrc, $themeSrc, $iconSrc, $gestureSrc, $flagshipTest
        )
        if (Test-Path -LiteralPath $menuCfgSrc) { $flagshipSources.Add($menuCfgSrc) }
        Run-Timed "javac FlagshipUnitChecks" {
            & $javac -encoding UTF-8 -cp $flagCp -d $flagshipClasses @flagshipSources
        }
        Run-Timed "FlagshipUnitChecks" {
            & $java -cp "$flagshipClasses;$flagCp" app.aikobrowser.FlagshipUnitChecks (Join-Path $RepoRoot 'app/src/main/assets/filters')
        }
    }

    # v0.4.0 BabelEngineCheck (pure Java + loopback HTTP server)
    $babelCheck = Join-Path $testing 'BabelEngineCheck.java'
    $babelConfigSrc = Join-Path $src 'BabelConfig.java'
    $babelEngineSrc = Join-Path $src 'BabelEngine.java'
    if ((Test-Path -LiteralPath $babelCheck) -and (Test-Path -LiteralPath $babelConfigSrc) `
            -and (Test-Path -LiteralPath $babelEngineSrc) -and (Test-Path -LiteralPath $androidJar) `
            -and (Test-Path -LiteralPath $jsonJar)) {
        $babelClasses = Join-Path $ClassesDir 'babel'
        New-Item -ItemType Directory -Path $babelClasses -Force | Out-Null
        $babelCp = "$ClassesDir;$jsonJar;$androidJar"
        Run-Timed "javac BabelEngineCheck" {
            & $javac --add-modules jdk.httpserver -encoding UTF-8 -cp $babelCp -d $babelClasses $babelConfigSrc $babelEngineSrc $babelCheck
        }
        Run-Timed "BabelEngineCheck" {
            & $java --add-modules jdk.httpserver -cp "$babelClasses;$babelCp" app.aikobrowser.BabelEngineCheck
        }
    }

    # v0.4.0 Core Platform Engines Check (QrEngine, BangCatalog, BackupManager, StartPageConfig, SiteProfile, SiteExtras, SiteFilter, WebViewPerf, VideoBooster)
    $v040Check = Join-Path $testing 'V040EnginesCheck.java'
    $startCfgSrc = Join-Path $src 'StartPageConfig.java'
    $bangSrc = Join-Path $src 'BangCatalog.java'
    $backupSrc = @((Join-Path $src 'BackupManager.java'), (Join-Path $src 'BackupEnvelope.java'), (Join-Path $src 'BackupPreferences.java'))
    $qrSrc = Join-Path $src 'QrEngine.java'
    $siteExtrasSrc = Join-Path $src 'SiteExtras.java'
    $siteFilterSrc = Join-Path $src 'SiteFilter.java'
    $wvPerfSrc = Join-Path $src 'WebViewPerf.java'
    if ((Test-Path -LiteralPath $v040Check) -and (Test-Path -LiteralPath $startCfgSrc) `
            -and (Test-Path -LiteralPath $bangSrc) -and (Test-Path -LiteralPath $backupSrc[0]) `
            -and (Test-Path -LiteralPath $qrSrc) -and (Test-Path -LiteralPath $androidJar) `
            -and (Test-Path -LiteralPath $jsonJar)) {
        $v040Classes = Join-Path $ClassesDir 'v040'
        New-Item -ItemType Directory -Path $v040Classes -Force | Out-Null
        $depsDir = Join-Path $RepoRoot 'build/deps'
        $extraJars = [System.Collections.Generic.List[string]]@()
        $coreAarPath = Join-Path $depsDir 'core-1.1.0.aar'
        $webkitAarPath = Join-Path $depsDir 'webkit-1.18.0.aar'
        if ((Test-Path -LiteralPath $coreAarPath) -and (Test-Path -LiteralPath $webkitAarPath)) {
            $coreJarTmp = Join-Path $v040Classes 'core-dep.jar'
            $webkitJarTmp = Join-Path $v040Classes 'webkit-dep.jar'
            foreach ($pair in @(@($coreAarPath, $coreJarTmp), @($webkitAarPath, $webkitJarTmp))) {
                $z = [IO.Compression.ZipFile]::OpenRead($pair[0])
                try {
                    $en = $z.GetEntry('classes.jar')
                    if ($en) { [IO.Compression.ZipFileExtensions]::ExtractToFile($en, $pair[1], $true) }
                } finally { $z.Dispose() }
            }
            $extraJars.Add($coreJarTmp)
            $extraJars.Add($webkitJarTmp)
            $annJar = Join-Path $depsDir 'annotation-jvm-1.8.1.jar'
            if (Test-Path -LiteralPath $annJar) { $extraJars.Add($annJar) }
            $jspecJar = Join-Path $depsDir 'jspecify-1.0.0.jar'
            if (Test-Path -LiteralPath $jspecJar) { $extraJars.Add($jspecJar) }
            $ktJar = Join-Path $depsDir 'kotlin-stdlib-2.1.20.jar'
            if (Test-Path -LiteralPath $ktJar) { $extraJars.Add($ktJar) }
            $flagshipRef = Join-Path $ClassesDir 'flagship'
            $prefixCp = if (Test-Path -LiteralPath $flagshipRef) { @($flagshipRef, $ClassesDir) } else { @($ClassesDir) }
            $v040CompileCp = ($prefixCp + @($androidJar) + $extraJars.ToArray()) -join ';'
            $v040RunCp = (@($v040Classes) + $prefixCp + @($jsonJar, $androidJar) + $extraJars.ToArray()) -join ';'
            Run-Timed "javac V040EnginesCheck" {
                & $javac -encoding UTF-8 -cp $v040CompileCp -d $v040Classes $startCfgSrc $bangSrc $backupSrc $qrSrc $siteExtrasSrc $siteFilterSrc $wvPerfSrc $v040Check
            }
            Run-Timed "V040EnginesCheck" {
                & $java -cp $v040RunCp app.aikobrowser.V040EnginesCheck
            }
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

    $babelDomCheck = Join-Path $testing 'babel_dom_check.mjs'
    $chromeHeadless = if ($env:AIKO_CHROMIUM) { $env:AIKO_CHROMIUM } else { Join-Path $parentDir 'work/chrome-test/chrome-headless-shell-win64/chrome-headless-shell.exe' }
    if ((Test-Path -LiteralPath $babelDomCheck) -and (Test-Path -LiteralPath $chromeHeadless)) {
        Run-Timed "babel_dom_check.mjs" {
            & node $babelDomCheck
        }
    }

    if ($IncludeChromiumSmoke) {
        Run-Timed "userscript_chromium_smoke.mjs" { & node (Join-Path $PSScriptRoot 'userscript_chromium_smoke.mjs') }
    }
} finally {
    if ($ownsClasses -and -not $KeepClasses -and (Test-Path -LiteralPath $ClassesDir)) {
        $target = (Resolve-Path -LiteralPath $ClassesDir).Path
        $tempRoot = (Resolve-Path -LiteralPath $env:TEMP).Path.TrimEnd('\')
        if ([IO.Path]::GetDirectoryName($target) -eq $tempRoot -and [IO.Path]::GetFileName($target) -match '^aiko-bench-classes-[0-9a-f]{32}$') {
            Remove-Item -LiteralPath $target -Recurse -Force
        }
    }
}
