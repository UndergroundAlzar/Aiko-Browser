# 构建、校验与复测

## 校验发布的 APK

v0.2.0 的包名为 `app.aikobrowser`，版本号（`versionCode`）为 11，最低 Android API 29，目标 API 36。发行 APK 共 **1,828,211 字节**，签名沿用此前版本，可以直接覆盖安装相同签名的 v0.1.0–v0.1.9。

| 项目 | SHA-256 |
| --- | --- |
| `Aiko-Browser-0.2.0.apk` | `a6fc36a6caf37a83d36d7ab4d0bcf8d3799ae48b41fa3c18a4740746bd23ed90` |
| 签名证书 | `ae71c82831309c76ebf55c407d91afbba16776e97389bd73c5e8400fe9397fe4` |

在 PowerShell 中运行以下命令，可计算文件摘要并用 Android SDK 核对签名：

```powershell
Get-FileHash ./Aiko-Browser-0.2.0.apk -Algorithm SHA256
& "$env:ANDROID_HOME/build-tools/36.0.0/apksigner.bat" verify --verbose --print-certs ./Aiko-Browser-0.2.0.apk
```

## 在 Windows 上构建

安装 PowerShell 7、JDK 21、Android SDK Platform 36 和 Build Tools 36.0.0。将 `JAVA_HOME` 和 `ANDROID_HOME` 分别指向 JDK 与 SDK 根目录，在仓库根目录执行：

```powershell
pwsh -NoProfile -File ./build.ps1
```

当前源码生成 `build/Aiko-Browser-0.2.0.apk`。构建会在 `build/deps` 缓存 AndroidX WebKit（1.18.0）等依赖，并按固定 SHA-256 校验。[第三方许可](../app/src/main/assets/notices/NOTICE.txt)

- **R8 优化与裁剪**：v0.2.0 默认以 `--release` 运行 R8 字节码优化与无用代码裁剪（配合 `androidx-keep.pro` 与 WebKit AAR 的 `proguard.txt`），保留全部 Manifest 组件、`RuntimeVisibleAnnotations` / `@JavascriptInterface` 及 AndroidX WebKit / Chromium 跨类加载器反射边界，同时使用 `-dontobfuscate` 保持堆栈可读。v0.2.0 发行包为单 `classes.dex`（未压缩 `416,268` 字节，`325` 个类，`2,871` 个方法）。
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

### 运行新增的 `FlagshipUnitChecks`

`testing/FlagshipUnitChecks.java` 覆盖 `HttpUrlUtil`、`SearchEngineCatalog`、`DisplayModeEngine.selectModeId`、`FilterRules` 二进制缓存往返与损坏保护，以及 `BrowserLibrary` 的 v0.1.9 兼容性、搜索、快捷访问固定与 Netscape HTML 导入导出。由于 `BrowserLibrary` 与 `DisplayModeEngine` 依赖 `android.jar` 及 `org.json`（标准 JVM 无内置 `org.json` 实现），运行前需提供一个包含 `org.json` 的 JAR（例如 `json.jar`）：

```powershell
$flagship = 'build/check-flagship'
$androidJar = "$env:ANDROID_HOME/platforms/android-36/android.jar"
$jsonJar = 'path/to/org-json.jar' # 提供 org.json.* 实现
New-Item -ItemType Directory -Path $flagship -Force | Out-Null
javac -encoding UTF-8 -cp "$classes;$jsonJar;$androidJar" -d $flagship `
    "$src/SearchEngineCatalog.java" "$src/DisplayModeEngine.java" "$src/BrowserLibrary.java" `
    'testing/FlagshipUnitChecks.java'
java -cp "$flagship;$classes;$jsonJar;$androidJar" app.aikobrowser.FlagshipUnitChecks app/src/main/assets/filters
```

仓库同时提供了一键测试与主机基准脚本 `testing/run_benchmarks.ps1`（可通过 `-ToolchainRoot` 指定工具链目录、`-SkipDownloadTest` 跳过下载测试），以及面向已连接 Android 设备或模拟器的安装/启动冒烟脚本 `testing/emulator_benchmark_smoke.ps1`。

## 电脑端基准参考（v0.1.9 → v0.2.0）

以下均为同一台开发机在 **JDK 21 / Node.js 电脑端主机环境**下使用 `FilterRulesBench` 与 `userscript_iframe_bench.mjs` 测得的数据，不代表实机 Android 渲染帧率：

| 指标（电脑端主机基准） | v0.1.9 | v0.2.0 | 变化 |
| --- | ---: | ---: | ---: |
| `FilterRules` 冷启动文本编译 | `322.75 ms` | `266.38 ms` | 1.21×（`build()` `190.2 → 25.6 ms`） |
| `FilterRules` 二进制缓存加载（新增） | —（每次 `322.75 ms`） | `45.14 ms` 冷 / `23.47 ms` 热 | **7.2× 冷 / 13.8× 热**（`9.16 MB`，指纹精确一致） |
| `FilterRules` 常驻堆内存（GC 后） | `40.67 MB` | `23.66 MB` | **-17.01 MB（-41.8%）** |
| `blockingRule()` 20 URL 批次均值 | `16.540 µs/req` | `0.815 µs/req` | **20.3×**（p50 `7.5 → 0.4 µs`，p95 `45.3 → 1.1 µs`，p99 `57.6 → 1.3 µs`） |
| `siteKey()` / `css()` 查表 | `216.1 ns` / `0.700 µs` | `104.3 ns` / `0.400 µs` | 2.07× / 1.75×（另加 16 站点 CSS LRU 缓存） |
| URL 校验（`HttpUrlUtil` 对比旧实现） | 有效 `328.0` / 无效 `6,401.6` / 混合 `3,241.4 ns` | `130.1` / `40.5` / `41.7 ns` | **2.5× / 158.1× / 77.7×** |
| 油猴 10-iframe 突发注入（4.4 KB 配置） | p50 `38.7` / p95 `65.7 µs` | p50 `7.0` / p95 `9.4 µs` | **5.5× / 7.0×** |
| 油猴不匹配顶层页面快速退出 | p50 `6.0` / p95 `7.5 µs` | p50 `1.2` / p95 `1.3 µs` | **5.0× / 5.8×** |

验证实机网页与 185Hz 体验时，需在真实 Android 设备上固定 WebView 版本、网络和缓存状态，分别记录首屏、长列表滚动、实时帧率浮标（HUD）和阅读位置恢复表现。程序记录的“可绘制”时间不代表页面已全部加载；v0.2.0 的真机界面与 185Hz 实机硬件帧率数据仍有待真机测试补充。
