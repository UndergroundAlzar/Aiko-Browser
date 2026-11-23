# 构建、校验与复测

## 校验发布的 APK

v0.1.9 的包名为 `app.aikobrowser`，版本号为 10，最低 Android API 29，目标 API 36。发行 APK 共 **1,742,196 字节**，可以覆盖安装相同签名的旧版。

| 项目 | SHA-256 |
| --- | --- |
| `Aiko-Browser-0.1.9.apk` | `93da1637d6d4510d1e39efdc24a20e2a17f2ca2624463f259384d3582b32a5ce` |
| 签名证书 | `ae71c82831309c76ebf55c407d91afbba16776e97389bd73c5e8400fe9397fe4` |

在 PowerShell 中运行以下命令，可计算文件摘要并用 Android SDK 核对签名。

```powershell
Get-FileHash ./Aiko-Browser-0.1.9.apk -Algorithm SHA256
& "$env:ANDROID_HOME/build-tools/36.0.0/apksigner.bat" verify --verbose --print-certs ./Aiko-Browser-0.1.9.apk
```

## 在 Windows 上构建

安装 PowerShell 7、JDK 21、Android SDK Platform 36 和 Build Tools 36.0.0。将 `JAVA_HOME` 和 `ANDROID_HOME` 分别指向 JDK 与 SDK 根目录，在仓库根目录执行：

```powershell
pwsh -NoProfile -File ./build.ps1
```

当前源码生成 `build/Aiko-Browser-0.1.9.apk`。构建会在 `build/deps` 缓存 AndroidX WebKit 等依赖，并按固定 SHA-256 校验。APK 包含使用到的运行时类。[第三方许可](../app/src/main/assets/notices/NOTICE.txt)

默认使用本机的试用签名。保留 `build/local-preview.p12` 及其本地密码文件，即可给自己的构建签署后续更新。自行生成的密钥无法用于覆盖安装 GitHub 发行版。自定义目录可通过 `-JavaHome`、`-AndroidJar`、`-BuildTools` 和 `-OutputFile` 指定。已有签名文件可通过 `-SigningKey`、`-SigningAlias` 和环境变量 `AIKO_KEYSTORE_PASSWORD` 指定。脚本使用相同的密钥与密钥库密码。

## 在电脑上复测

将 JDK 21 的 `bin` 加入 PATH 后，在仓库根目录运行下面的 PowerShell 命令。下载测试只访问本机临时 HTTP 服务器。它会传输测试文件、强制结束一个测试子进程、恢复分段并核对最终字节。

```powershell
$classes = 'build/check'
$src = 'app/src/main/java/app/aikobrowser'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$javaSources = @(
    "$src/RangeDownload.java", "$src/TransferPacer.java", "$src/DownloadRecord.java",
    "$src/FilterRules.java", "$src/CustomFilterList.java", "$src/ScrollPositions.java",
    "$src/FilterTrace.java", "$src/SiteProfile.java", 'testing/SiteDiagnosticsCheck.java',
    "$src/UserScript.java", "$src/UserScriptInput.java", 'testing/RangeDownloadCheck.java',
    'testing/FilterRulesCheck.java', 'testing/ScrollPositionsCheck.java', 'testing/UserCustomizationCheck.java'
)
javac --add-modules jdk.httpserver -encoding UTF-8 -d $classes @javaSources
java --add-modules jdk.httpserver -cp $classes app.aikobrowser.RangeDownloadCheck
java -cp $classes app.aikobrowser.FilterRulesCheck app/src/main/assets/filters
java -cp $classes app.aikobrowser.ScrollPositionsCheck
java -cp $classes app.aikobrowser.UserCustomizationCheck
java -cp $classes app.aikobrowser.SiteDiagnosticsCheck
node testing/userscript_runtime_check.mjs # 可选，需 Node.js
```

验证网页体验时，分别记录网页首屏、长列表滚动和恢复阅读位置，并注明设备、WebView 版本、刷新率与缓存状态。程序记录的“可绘制”时间不代表页面已全部加载。Speedometer 测量网页应用对模拟交互的响应，浏览器的触控和实际显示帧率还需单独测量。
