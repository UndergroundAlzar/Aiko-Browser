# 构建、校验与复测

## 校验发布的 APK

v0.3.0 的包名为 `app.aikobrowser`，版本号（`versionCode`）为 12，最低 Android API 29，目标 API 36，作者 **Alzar**（[GitHub 仓库](https://github.com/UndergroundAlzar/Aiko-Browser)）。发行 APK 共 **2,447,119 字节**（2.33 MiB，内置全部 7 套 2026 实时 CN + 全球广告过滤规则），签名沿用此前版本，可以直接覆盖安装相同签名的 v0.1.0–v0.2.0（完整保留全部本地书签、历史、标签页、站点配置、稍后读、自定义规则与油猴脚本数据）。

| 项目 | SHA-256 |
| --- | --- |
| `Aiko-Browser-0.3.0.apk` | `8e1d7a596abba08434c733d21e200e2706025922ba87906e8b30e9891927a74c` |
| 签名证书 | `ae71c82831309c76ebf55c407d91afbba16776e97389bd73c5e8400fe9397fe4` |

在 PowerShell 中运行以下命令，可计算文件摘要并用 Android SDK 核对签名：

```powershell
Get-FileHash ./Aiko-Browser-0.3.0.apk -Algorithm SHA256
& "$env:ANDROID_HOME/build-tools/36.0.0/apksigner.bat" verify --verbose --print-certs ./Aiko-Browser-0.3.0.apk
```

## 在 Windows 上构建

安装 PowerShell 7、JDK 21、Android SDK Platform 36 和 Build Tools 36.0.0。将 `JAVA_HOME` 和 `ANDROID_HOME` 分别指向 JDK 与 SDK 根目录，在仓库根目录执行：

```powershell
pwsh -NoProfile -File ./build.ps1
```

当前源码生成 `build/Aiko-Browser-0.3.0.apk`。构建会在 `build/deps` 缓存 AndroidX WebKit（1.18.0）等依赖，并按固定 SHA-256 校验。[第三方许可](../app/src/main/assets/notices/NOTICE.txt) · [内置规则许可](../app/src/main/assets/filters/NOTICE.txt)

- **R8 优化、裁剪与 API 29–32 安全外联**：默认以 `--release --min-api 29` 运行 R8 优化与裁剪（配合 `androidx-keep.pro` 与 WebKit AAR 的 `proguard.txt`），保留全部 Manifest 组件、`RuntimeVisibleAnnotations` / `@JavascriptInterface` 及 AndroidX WebKit / Chromium 反射边界，使用 `-dontobfuscate` 保持堆栈可读；R8 API Outliner 自动外联 API 30+/31+ 调用（`PerformanceHintManager`、`WindowInsets.Type`），`dexdump` 验证零未受保护的 API 30+/33+ 字节码调用。v0.3.0 发行包为单 `classes.dex`（未压缩 `547,440` 字节，压缩 `263,324` 字节，`371` 个类，`3,317` 个方法，仅占 65k 方法上限的 `5.1%`）。
- **签名说明**：默认使用本机的试用签名。保留 `build/local-preview.p12` 及其本地 `.dpapi` 密码文件，即可给自己的构建签署后续更新。自行生成的密钥无法用于覆盖安装 GitHub 发行版。自定义目录可通过 `-JavaHome`、`-AndroidJar`、`-BuildTools` 和 `-OutputFile` 指定；已有签名文件可通过 `-SigningKey`、`-SigningAlias` 和环境变量 `AIKO_KEYSTORE_PASSWORD` 指定（脚本使用相同的密钥与密钥库密码）。

## 在电脑上复测与跑分

将 JDK 21 的 `bin` 加入 PATH 后，在仓库根目录运行下面的 PowerShell 命令。下载测试只访问本机临时 HTTP 服务器，会传输测试文件、强制结束测试子进程、恢复分段并核对最终字节。

```powershell
$classes = 'build/check'
$src = 'app/src/main/java/app/aikobrowser'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$javaSources = @(
    "$src/RangeDownload.java", "$src/TransferPacer.java", "$src/DownloadRecord.java",
    "$src/FilterRules.java", "$src/CustomFilterList.java", "$src/ScrollPositions.java",
    "$src/FilterTrace.java", "$src/SiteProfile.java", "$src/HttpUrlUtil.java",
    "$src/UrlCleaner.java", "$src/VideoBooster.java",
    "$src/UserScript.java", "$src/UserScriptInput.java",
    'testing/RangeDownloadCheck.java', 'testing/FilterRulesCheck.java',
    'testing/ScrollPositionsCheck.java', 'testing/UserCustomizationCheck.java',
    'testing/SiteDiagnosticsCheck.java', 'testing/FilterRulesBench.java'
)
javac --add-modules jdk.httpserver -encoding UTF-8 -d $classes @javaSources
java --add-modules jdk.httpserver -cp $classes app.aikobrowser.RangeDownloadCheck
java -cp $classes app.aikobrowser.FilterRulesCheck app/src/main/assets/filters
java -cp $classes app.aikobrowser.ScrollPositionsCheck
java -cp $classes app.aikobrowser.UserCustomizationCheck
java -cp $classes app.aikobrowser.SiteDiagnosticsCheck
java -cp $classes app.aikobrowser.FilterRulesBench app/src/main/assets/filters
node testing/userscript_runtime_check.mjs
node testing/userscript_iframe_bench.mjs app/src/main/assets/userscripts/runtime.js
```

### 运行 `FlagshipUnitChecks`

`testing/FlagshipUnitChecks.java` 现覆盖 **11 组核心单元测试**：`HttpUrlUtil`、`UrlCleaner`（全球+国内追踪参数净化与功能参数/编码/锚点保护）、`PerfProfiles`（狂暴/均衡/省电预设与全项钳位/内置保护）、`UserScript.isUserScriptUrl` / `isUserScriptFilename` / `UserScriptInput.safeRemoteAddress`（Greasy Fork 及编码重定向一致性）、`AikoTheme.Palette`（6 套预设 + 360° 色相 × 7 种底色浸染 + 极端种子 WCAG AA+ 对比度 + `#FFFFFF` Hex）、`CustomFilterList.normalizeDomainForRule` & `VideoBooster`、`SearchEngineCatalog`、`DisplayModeEngine.selectModeId`、`GestureChrome.computeChromeLayout`（全 8 状态视口/底栏/IME/自动隐藏/全屏）、`FilterRules` 全 7 列表二进制缓存往返与损坏保护，以及 `BrowserLibrary` 的 v0.1.9 兼容性与 Netscape HTML 导入导出。由于部分模块引用 `android.jar` 及 `org.json`，运行前需提供一个包含 `org.json` 的 JAR：

```powershell
$flagship = 'build/check-flagship'
$androidJar = "$env:ANDROID_HOME/platforms/android-36/android.jar"
$jsonJar = 'path/to/org-json.jar' # 提供 org.json.* 实现
New-Item -ItemType Directory -Path $flagship -Force | Out-Null
javac -encoding UTF-8 -cp "$classes;$jsonJar;$androidJar" -d $flagship `
    "$src/SearchEngineCatalog.java" "$src/DisplayModeEngine.java" "$src/PerfProfiles.java" `
    "$src/BrowserLibrary.java" "$src/AikoTheme.java" "$src/BrowserIcon.java" "$src/GestureChrome.java" `
    'testing/FlagshipUnitChecks.java'
java -cp "$flagship;$classes;$jsonJar;$androidJar" app.aikobrowser.FlagshipUnitChecks app/src/main/assets/filters
```

仓库同时提供了一键测试与主机基准脚本 `testing/run_benchmarks.ps1`（支持 `-ToolchainRoot`、`-SkipDownloadTest`、`-IncludeChromiumSmoke`），以及面向已连接 Android 设备或模拟器的安装/启动冒烟脚本 `testing/emulator_benchmark_smoke.ps1`。

## 电脑端基准参考（v0.1.9 → v0.3.0）

以下均为同一台开发机在 **JDK 21 / Node.js 电脑端主机环境**下使用 `FilterRulesBench` 与 `userscript_iframe_bench.mjs` 测得的数据（同时列出 3 列表同口径对照与 v0.3.0 全 7 列表实测），不代表实机 Android 渲染帧率：

| 指标（电脑端主机基准） | v0.1.9 (3 列表) | v0.3.0 (3 列表同口径) | v0.3.0 (全 7 列表 · 151,759 条) |
| --- | ---: | ---: | ---: |
| 有效规则总数（网络 / 隐藏） | ~134,818 | 134,196 (`117,460` / `16,736`) | **151,759** (`121,272` / `30,487`) |
| `FilterRules` 文本编译（冷 / 热中位） | `322.75` / `121.22 ms` | `291.88` / `86.78 ms` | *(同进程已预热)* `118.47` / **`107.40 ms`** |
| `FilterRules` 二进制缓存体积 | — | **`7.08 MB`**（较 v0.2.0 降 22.7%） | **`8.18 MB`**（仍小于 v0.2.0 3 列表 `9.16 MB`） |
| `FilterRules` 二进制缓存加载（冷 / 热） | —（每次 `322.75 ms`） | **`35.24 ms`** / **`19.35 ms`** | `20.60 ms` / **`20.35 ms`** |
| `FilterRules` 常驻堆内存（GC 后） | `40.67 MB` | **`22.14 MB`**（**-45.6%**） | **`24.71 MB`**（较 v0.1.9 3 列表仍 **-39.2%**） |
| `blockingRule()` 20 URL 批次均值 | `16.540 µs/req` | **`0.551 µs/req`**（**30.0×**） | **`0.422 µs/req`**（p50 `0.4` / p95 `0.8` / p99 `0.9 µs`） |
| `siteKey()` / `css()` 查表 | `216.1 ns` / `0.700 µs` | `105.5 ns` / `0.400 µs` | `105.5 ns` / **`0.300 µs`**（+16 站点 CSS LRU） |
| URL 校验（`HttpUrlUtil` 混合） | `3,119.1 ns` | **`43.4 ns`**（**71.9×**） | **`43.4 ns`**（有效 `139.2` / 无效 `41.2 ns`） |
| 油猴 10-iframe 突发注入 p50 / p95 | `38.4` / `63.3 µs` | **`6.9`** / **`9.1 µs`**（**5.6× / 7.0×**） | **`6.9`** / **`9.1 µs`**（不匹配顶层 `1.2 µs`） |

验证实机网页与 185Hz / ADPF 体验时，需在真实 Android 设备上固定 WebView 版本、网络和缓存状态，分别记录首屏、长列表滚动、实时帧率浮标（HUD）和阅读位置恢复表现。程序记录的“可绘制”时间不代表页面已全部加载；185Hz 实机硬件帧率指标仍有待实体设备持续复测。
