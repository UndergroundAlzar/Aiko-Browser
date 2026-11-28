# Aiko Browser 0.5.1

Aiko Browser 是基于 Android 系统 WebView 的 Java 浏览器，支持 Android 10（API 29）及以上版本。原生界面使用中文，集成网页双语翻译、油猴脚本、按站点过滤和分片下载。

[下载 v0.5.1 APK](https://github.com/UndergroundAlzar/Aiko-Browser/releases/download/v0.5.1/Aiko-Browser-0.5.1.apk) · [SHA256SUMS.txt](https://github.com/UndergroundAlzar/Aiko-Browser/releases/download/v0.5.1/SHA256SUMS.txt) · [发行说明](https://github.com/UndergroundAlzar/Aiko-Browser/releases/tag/v0.5.1) · [English](docs/README.en.md)

## 功能与设置

| 功能 | 使用方式 |
| --- | --- |
| [首页与稍后读](docs/CUSTOMIZATION.md#home) | 原生首页和新标签页默认只显示搜索框。关闭「原生页仅显示搜索框」即可显示快捷访问与书架，并打开保存的 MHTML 离线快照。 |
| [巴别塔翻译](docs/CUSTOMIZATION.md#babel) | 输入 `/babel` 开启双语阅读。流式页面上的段落停止改动 240 ms 后重新送译，过期的译文和错误回包会被丢弃。支持五种排版、划词翻译和输入框反译。 |
| [站点过滤](docs/CUSTOMIZATION.md#filtering) | 从地址栏盾牌为当前站点开关过滤。能识别来源站点的 Service Worker 请求也使用站点规则和例外，来源未知的请求放行。 |
| [下载队列](docs/CUSTOMIZATION.md#downloads) | 文件依次下载，单文件可设置最多 128 路分片。下载列表显示当前任务、等待队列和最近 30 条完成记录。 |
| [性能设置](docs/CUSTOMIZATION.md#performance) | 在支持的 Android 12+ 设备上，交互期间用实际 UI 帧耗时向系统提供性能提示。HTTPS 预连接使用 WebView 自身支持的接口。 |
| [备份与恢复](docs/CUSTOMIZATION.md#backup) | 默认便携备份排除 Babel API 密钥与自定义请求头。迁移这些凭据时选用密码加密备份。支持导入旧版 v1 文件，书架可随包迁移可用的离线快照。 |
| [脚本与网页工具](docs/CUSTOMIZATION.md#tools) | 可从网址或本地文件安装油猴脚本，使用网页调试台查看 DOM、Console 和网络请求。 |

## 安装与验证

安装前，计算 APK 的 SHA-256，与同一发行版校验清单中的文件名和摘要核对。覆盖升级要求 Android 接受新包的签名。Windows 下可用 PowerShell 7、JDK 21、Android SDK Platform 36 和 Build Tools 36.0.0 从源码构建。[构建、签名与测试指南](docs/BUILD.md)

历史真机数据来自 **v0.5.0**。OnePlus PMS110 在 185 Hz 显示模式下记录过 140 ms 冷启动（`am start -W`）。v0.5.1 暂无新的手机测量值。[v0.5.0 测量记录](https://github.com/UndergroundAlzar/Aiko-Browser/blob/v0.5.0/README.md)

作者 Alzar · [像素画廊](docs/art/README.md) · [过滤规则许可](app/src/main/assets/filters/NOTICE.txt) · [AndroidX 许可](app/src/main/assets/notices/NOTICE.txt)
