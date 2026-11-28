# Aiko Browser 0.5.1 构建与验证

[中文首页](../README.md) · [English overview](README.en.md) · [使用指南](CUSTOMIZATION.md)

## 校验发行 APK

应用包名为 `app.aikobrowser`，版本为 0.5.1（versionCode 16），最低 Android 10 / API 29，目标 API 36。把同一发行版的 APK 与校验清单下载到同一目录。[v0.5.1 APK](https://github.com/UndergroundAlzar/Aiko-Browser/releases/download/v0.5.1/Aiko-Browser-0.5.1.apk) · [SHA256SUMS.txt](https://github.com/UndergroundAlzar/Aiko-Browser/releases/download/v0.5.1/SHA256SUMS.txt)

```powershell
$apk = 'Aiko-Browser-0.5.1.apk'
Get-FileHash -Algorithm SHA256 -LiteralPath $apk
Get-Content -LiteralPath ./SHA256SUMS.txt | Select-String -SimpleMatch $apk
& "$env:ANDROID_HOME/build-tools/36.0.0/apksigner.bat" verify --verbose --print-certs $apk
```

文件摘要须与校验清单中该 APK 的摘要完全一致。最后一条命令验证签名并打印签名证书指纹，可与已信任的发行包核对。

## Windows 源码构建

准备 PowerShell 7、JDK 21、Android SDK Platform 36 和 Build Tools 36.0.0。将 `JAVA_HOME` 指向 JDK、`ANDROID_HOME` 指向 SDK，在仓库根目录运行下列命令。首次构建需要网络访问 Google Maven 和 Maven Central，以下载脚本锁定并校验摘要的依赖。

```powershell
pwsh -NoProfile -File ./build.ps1
```

默认产物为 `build/Aiko-Browser-0.5.1.apk`。脚本使用 aapt2、javac、R8、zipalign 和 apksigner，并检查 APK 内资源路径、过滤规则与 Babel 等必需资源。[构建脚本](../build.ps1)

默认本地签名密钥在 `build/local-preview.p12`。请保留自己的签名密钥，以便覆盖升级本地构建。已有密钥可通过 `-SigningKey`、`-SigningAlias` 及环境变量 `AIKO_KEYSTORE_PASSWORD` 指定。也可用 `-JavaHome`、`-AndroidJar`、`-BuildTools` 或 `-OutputFile` 指定工具及输出位置。

## 主机回归与 Chromium 检查

先完成一次构建，让 `build/deps` 包含 AndroidX 依赖。准备带全局 `fetch` 和 `WebSocket` 的 Node.js（例如 22.4+）及 Windows 版 Chromium headless shell。测试脚本查找以下路径，均相对仓库根目录。[测试入口](../testing/run_benchmarks.ps1)

| 用途 | 默认位置 |
| --- | --- |
| JDK 21 | `../work/android-toolchain/jdk*/bin/javac.exe`，子目录名须以 `jdk` 开头 |
| Android 平台 JAR | `../work/android-toolchain/platform-36_r02/android-36/android.jar` |
| JVM 上的 org.json 实现 | 将 `org.json:json:20240303` 的 JAR 放到 `../work/aiko-test-json.jar`。可从 [Maven Central](https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar) 获取。 |
| Chromium | `../work/chrome-test/chrome-headless-shell-win64/chrome-headless-shell.exe`，或令 `AIKO_CHROMIUM` 指向该可执行文件的绝对路径 |

```powershell
pwsh -NoProfile -File ./testing/run_benchmarks.ps1 -IncludeChromiumSmoke
```

`-ToolchainRoot` 可覆盖上表中 `android-toolchain` 的位置。JSON 与 Chromium 另按各自的路径查找。部分套件缺少依赖时会被跳过，阅读运行输出，确认需要的套件均出现并返回 `exit=0`。

| 核心检查 | 验证内容 |
| --- | --- |
| `DownloadQueueCheck`、`RangeDownloadCheck` | 队列顺序、旧任务迁移、完成记录，以及本地 HTTP 服务的分片下载与续传。使用 `-SkipDownloadTest` 会跳过后者。 |
| `V040EnginesCheck` | 起始页设置持久化、Service Worker 来源与规则例外、v1 / 加密备份往返、快照迁移及错误处理。 |
| `babel_dom_check.mjs` | 无头 Chromium 上的逐段刷新、乱序回包、可见区懒翻译、五种排版及关闭后的 DOM 清理。 |
| `userscript_chromium_smoke.mjs` | 严格 CSP 页面中的脚本执行，随 `-IncludeChromiumSmoke` 启用。 |

单独复查网页行为可运行 `node ./testing/babel_dom_check.mjs`。JVM 和桌面 Chromium 覆盖主机端逻辑，手机刷新率、启动耗时、ADPF 采样和 Android 系统 WebView 的具体表现需在对应设备上测量。
