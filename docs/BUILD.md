# 构建、校验与基准测试指南（v0.4.0）

> 返回主页：[简体中文 README](../README.md) · [English README](README.en.md) · [深度配置指南](CUSTOMIZATION.md)

---

## 一、校验官方发布的 APK

**Aiko Browser v0.4.0**（包名 `app.aikobrowser`，`versionCode = 14`，最低 Android 10 / API 29，目标 API 36，作者 **Alzar**）共 **2,656,083 字节**（`2.53 MiB`），包含全部 7 套 2026 实时中英文广告过滤规则、巴别塔翻译脚本与完整油猴运行时。签名沿用自 `v0.1.7` 起的同一证书，可直接覆盖安装任意历史官方版本并保留 100% 本地用户数据。

| 校验项 | SHA-256 指纹 |
| :--- | :--- |
| **`Aiko-Browser-0.4.0.apk`** | `d23cb60d3e7f694e27390a6597e6804bfca113a86f3e86020b74cdb8b6c765b8` |
| **官方签名证书** | `ae71c82831309c76ebf55c407d91afbba16776e97389bd73c5e8400fe9397fe4` |

在 PowerShell 中执行以下命令即可核对文件完整性与 APK v3 签名：

```powershell
Get-FileHash ./Aiko-Browser-0.4.0.apk -Algorithm SHA256
& "$env:ANDROID_HOME/build-tools/36.0.0/apksigner.bat" verify --verbose --print-certs ./Aiko-Browser-0.4.0.apk
```

---

## 二、从源码构建（Windows / 零 Gradle）

### 环境要求
- PowerShell 7 (`pwsh`)
- JDK 21（`JAVA_HOME`）
- Android SDK Platform 36 & Build Tools 36.0.0（`ANDROID_HOME`）

### 一键编译并签名

```powershell
pwsh -NoProfile -File ./build.ps1
```

默认产物输出至 `build/Aiko-Browser-0.4.0.apk`。支持参数：`-JavaHome`、`-AndroidJar`、`-BuildTools`、`-SigningKey`、`-SigningAlias`、`-OutputFile`（也可通过环境变量 `AIKO_KEYSTORE_PASSWORD` 提供密码）。

### 构建管线内置安全门禁
1. **依赖摘要锁死**：自动在 `build/deps` 缓存并按 SHA-256 校验 `androidx.webkit:1.18.0`、`androidx.core:1.1.0` 等依赖（见 [AndroidX 许可](../app/src/main/assets/notices/NOTICE.txt) 与 [规则许可](../app/src/main/assets/filters/NOTICE.txt)）。
2. **R8 优化与 API 外联**：以 `--release --min-api 29` 运行 R8（结合 `aapt2 --proguard`、`androidx-keep.pro` 与 WebKit AAR 规则），保留 Manifest 组件（含 `MediaPlaybackService`）与 `@JavascriptInterface` 桥接边界，自动外联 API 30+/31+ 系统调用。
3. **Windows `aapt2` 路径规范化与签名后断言**：自动修正 Windows `aapt2` 产生的反斜杠资源路径（`\` → `/`），并在签名后强制校验 **0 反斜杠**、`resources.arsc` 未压缩（`STORED`），以及 `assets/babel/babel.js`、`assets/userscripts/runtime.js` 与全部 7 套 `.txt.gz` 规则完整存在。

> **签名提示**：自行构建时脚本会生成本地密钥（`build/local-preview.p12`），保留该密钥即可持续覆盖安装自己的构建；不同签名之间无法直接覆盖安装官方 GitHub 发行版。

---

## 三、运行全套自动化测试与微基准（13 项）

仓库内置了一键全量回归脚本 `testing/run_benchmarks.ps1`，覆盖 JVM 单元测试、微基准、Node.js 运行时验证与真实无头 Chromium 严格 CSP 端到端测试：

```powershell
# 运行全部 13 项测试与基准（推荐）
pwsh -NoProfile -File ./testing/run_benchmarks.ps1 -IncludeChromiumSmoke

# 跳过耗时 ~28s 的本地 HTTP 128 路下载压力测试，快速验证其余套件
pwsh -NoProfile -File ./testing/run_benchmarks.ps1 -SkipDownloadTest -IncludeChromiumSmoke
```

### 测试套件一览

| # | 测试套件 | 覆盖范围 |
| :-: | :--- | :--- |
| 1 | `RangeDownloadCheck` | 128 路并发、断点续传、强杀恢复、ETag 校验、503 重试、浏览优先让路 |
| 2 | `FilterRulesCheck` | 3 列表基线、7 列表 151,979 条有效规则、作用域例外与 CSS 安全清洗 |
| 3 | `ScrollPositionsCheck` | 同 URL 不同历史条目滚动位置恢复与前进分支丢弃 |
| 4 | `UserCustomizationCheck` | 自定义 ABP 规则、Tampermonkey `@grant`/`@connect`/`@require`/`@resource`、`PageToolkit`、`WebDevTools` |
| 5 | `SiteDiagnosticsCheck` | 站点位掩码编码、拦截记录来源归因与内存边界 |
| 6 | `FilterRulesBench` | 3 列表 & 7 列表编译/缓存/匹配/堆内存与 `HttpUrlUtil` 微基准 |
| 7 | `FlagshipUnitChecks` | 11 组核心单测：`PerfProfiles`、`UrlCleaner`、`AikoTheme.Palette` 360° WCAG、`DisplayModeEngine`、`GestureChrome`、`BrowserLibrary` 等 |
| 8 | `BabelEngineCheck` | 巴别塔多引擎配置钳位、OpenAI/Gemini 模型列表解析、Google→Bing 国内无感降级、并发门控、LRU 缓存与 28 并发压测 |
| 9 | `V040EnginesCheck` | 纯 Java ISO 18004 二维码编码/解码往返与 OLED 对比度、`BangCatalog`、`BackupManager` 路径穿越防护与往返、`StartPageConfig`、`SiteFilter` 异步撤销、`WebViewPerf`、`VideoBooster` |
| 10 | `userscript_runtime_check.mjs` | 油猴执行时机、存储桥接、`GM_xmlhttpRequest`、`GM_addValueChangeListener`、`GM_registerMenuCommand` |
| 11 | `userscript_iframe_bench.mjs` | 10-iframe 突发注入守卫微基准 |
| 12 | `babel_dom_check.mjs` | 真实无头 Chromium 严格 CSP 下的 `IntersectionObserver`、代码块跳过、5 种双语样式、开关重译、划词与三连空格反译 |
| 13 | `userscript_chromium_smoke.mjs` | 真实无头 Chromium 隔离世界 CSP 注入与真实 `.user.js` 端到端冒烟 |

---

## 四、实测基准参考

### 1. 真机实测（OnePlus PMS110 · SM8850 · Android 17 · 185Hz · WebView Canary 160）

| 项目 | 实测结果 |
| :--- | :--- |
| 冷启动耗时（`am start -W`） | **`159–166 ms`** |
| 185Hz 连续快速滑动（`dumpsys gfxinfo`） | `943` 帧 · 丢 VSync **`0`** · 抖动 `4` 帧 (**`0.42%`**) · CPU `p95=5ms / p99=6ms` · GPU `p99=3ms` |
| 运行内存（`dumpsys meminfo`） | `Java Heap 23.2 MB` · `Native Heap 25.8 MB` · `TOTAL PSS 193.6 MB` |
| 实机有效过滤规则 | 内置 7 套 **`152,033` 条** → 订阅 `anti-AD` 后 **`172,031` 条**（编译缓存 `9.58 MB`） |

### 2. 主机端同口径微基准（`v0.1.9` → `v0.4.0`，JDK 21 / Node.js）

| 指标 | v0.1.9（3 列表） | v0.4.0（全 7 列表 · 151,979 条） |
| :--- | ---: | ---: |
| `FilterRules` 二进制缓存体积 / 热载 | —（每次 `322.75 ms`） | **`8.34 MB`** / **`22.42 ms`** |
| `FilterRules` 常驻堆内存（GC 后） | `40.67 MB` | **`24.97 MB`**（**-38.6%**） |
| `blockingRule()` 匹配耗时（p50 / 均值） | `16.540 µs/req` | **`0.400 µs`** / **`0.669 µs`** |
| `css()` 隐藏规则查表（p50） | `0.700 µs` | **`0.200 µs`** |
| `HttpUrlUtil` 混合校验 | `3,119.1 ns` | **`43.4 ns`**（**71.9×**） |
| 油猴 10-iframe 突发守卫（p50） | `38.4 µs` | **`7.0 µs`**（**5.5×**） |
