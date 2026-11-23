# 构建、校验与复测

## 校验发布的 APK

v0.1.7 的包名为 `app.aikobrowser`，版本号为 8，最低 Android API 29，目标 API 36。发行 APK 共 **1,696,842 字节**，与 0.1.6 使用同一签名，可覆盖安装。

| 项目 | SHA-256 |
| --- | --- |
| `Aiko-Browser-0.1.7.apk` | `c776ecfda4b9f01582dadfe3e5b398fad3420e87912aeb26522f9b0d6f879d34` |
| 签名证书 | `ae71c82831309c76ebf55c407d91afbba16776e97389bd73c5e8400fe9397fe4` |

在 PowerShell 中运行以下命令，可计算文件摘要并用 Android SDK 核对签名。

```powershell
Get-FileHash ./Aiko-Browser-0.1.7.apk -Algorithm SHA256
& "$env:ANDROID_HOME/build-tools/36.0.0/apksigner.bat" verify --verbose --print-certs ./Aiko-Browser-0.1.7.apk
```

## 在 Windows 上构建

安装 PowerShell 7、JDK 21、Android SDK Platform 36 和 Build Tools 36.0.0。将 `JAVA_HOME` 和 `ANDROID_HOME` 分别指向 JDK 与 SDK 根目录，在仓库根目录执行：

```powershell
pwsh -NoProfile -File ./build.ps1
```

生成的 `build/Aiko-Browser-0.1.7.apk` 使用本机的试用签名。保留 `build/local-preview.p12` 及其本地密码文件，即可给自己的构建签署后续更新。自行生成的密钥无法用于覆盖安装 GitHub 发行版。自定义目录可通过 `-JavaHome`、`-AndroidJar`、`-BuildTools` 和 `-OutputFile` 指定。已有签名文件可通过 `-SigningKey`、`-SigningAlias` 和环境变量 `AIKO_KEYSTORE_PASSWORD` 指定。脚本使用相同的密钥与密钥库密码。

## 在电脑上复测

将 JDK 21 的 `bin` 加入 PATH 后，在仓库根目录运行下面的 PowerShell 命令。下载测试只访问本机临时 HTTP 服务器。它会传输测试文件、强制结束一个测试子进程、恢复分段并核对最终字节。

```powershell
$classes = 'build/check'
$src = 'app/src/main/java/app/aikobrowser'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
javac --add-modules jdk.httpserver -encoding UTF-8 -d $classes "$src/RangeDownload.java" "$src/TransferPacer.java" "$src/DownloadRecord.java" "$src/FilterRules.java" "$src/ScrollPositions.java" testing/RangeDownloadCheck.java testing/FilterRulesCheck.java testing/ScrollPositionsCheck.java
java --add-modules jdk.httpserver -cp $classes app.aikobrowser.RangeDownloadCheck
java -cp $classes app.aikobrowser.FilterRulesCheck app/src/main/assets/filters
java -cp $classes app.aikobrowser.ScrollPositionsCheck
```

验证网页体验时，分别记录网页首屏、长列表滚动和恢复阅读位置，并注明设备、WebView 版本、刷新率与缓存状态。程序记录的“可绘制”时间不代表页面已全部加载。Speedometer 测量网页应用对模拟交互的响应，浏览器的触控和实际显示帧率还需单独测量。
