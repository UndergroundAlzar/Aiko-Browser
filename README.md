# Aiko Browser

Aiko Browser 是一个使用系统 WebView 的轻量 Android 浏览器。界面用 Java 和 Android 原生视图编写。最低 Android 10（API 29），目标 API 36。

**[下载 v0.1.9 APK](https://github.com/UndergroundAlzar/Aiko-Browser/releases/download/v0.1.9/Aiko-Browser-0.1.9.apk)** · [发行说明与 SHA-256](https://github.com/UndergroundAlzar/Aiko-Browser/releases/tag/v0.1.9) · [English](docs/README.en.md)

## 功能

| 模块 | 实现 |
| --- | --- |
| **标签与导航** | 单行底部地址栏，首页和新标签页使用搜索或网址输入框。支持后台标签、最近关闭、阅读位置恢复和最多 5 个热 WebView |
| **站点控制** | 地址栏盾牌可切换浏览器标识、相对默认值调整字号、停载网络图片、查看过滤命中并恢复该站默认设置 |
| **过滤** | 内置 EasyList、EasyPrivacy 和 AdGuard 中文列表。支持局部 ABP 请求规则、站点 CSS、手写规则和每站暂停 |
| **用户脚本** | 从源码、UTF-8 `.user.js` 或 HTTPS 导入。支持网站匹配、运行时机、脚本隔离、GM 样式和键值存储 |
| **下载** | 单文件最多 128 路，显示分段地图、逐路速率和预计剩余时间。支持经校验的断点续传、单段重试和浏览优先 |
| **无痕** | 使用独立 Android 进程和 WebView 数据目录。普通标签的登录与浏览记录使用自己的目录 |

## 运行机制

- **站点配置**按精确主机名保存，字号与图片设置只应用到当前站点。桌面标识在新的导航开始前配置，自动跳转和表单提交会沿用原请求链，需要时可在到达目标页后刷新。恢复默认只还原该站点的浏览与过滤设置。
- **请求过滤**在后台编译内置和自定义规则。拦截记录只保留当前文档最近 12 条匹配，展示请求域名、规则来源和规则文本。记录不写入磁盘。WebView 请求回调的覆盖范围及受支持语法见[配置指南](docs/CUSTOMIZATION.md)。
- **脚本执行**按系统 WebView 能力选择注入模式。有授权的脚本要求隔离执行支持。导入时会预览适用网址和授权，默认停用。完整的兼容范围见[配置指南](docs/CUSTOMIZATION.md)。
- **分段下载**需要服务端正确返回 HTTP 字节范围和强 ETag。服务端不满足条件时使用单连接。默认按文件大小选择 1–8 路，用户可以为当前文件设置上限。当前版本管理一个下载任务。

## 构建与验证

Windows 构建需要 PowerShell 7、JDK 21、Android SDK Platform 36 和 Build Tools 36.0.0。设置 `JAVA_HOME` 和 `ANDROID_HOME` 后在仓库根目录运行：

```powershell
pwsh -NoProfile -File ./build.ps1
```

脚本校验固定版本的依赖摘要，并从源码生成本地签名 APK。发行包的签名指纹、校验方法、过滤与下载测试命令见[构建指南](docs/BUILD.md)。

「⋯ → 性能记录」显示系统 WebView 版本、显示刷新率和导航到可绘制的耗时。同机速度比较需固定 WebView、网络和缓存，并另外测实际呈现的帧。v0.1.9 已执行电脑端代码回归，Android 界面和同机性能仍需复测。

[规则列表许可](app/src/main/assets/filters/NOTICE.txt) · [AndroidX 许可](app/src/main/assets/notices/NOTICE.txt) · [美术画廊](docs/art/README.md)
