# Aiko Browser

Aiko Browser 是一个使用系统 WebView 的轻量高刷 Android 浏览器。界面用 Java 和 Android 原生视图编写，采用暗夜主题与单手底部交互。最低 Android 10（API 29），目标 API 36。

**[下载 v0.2.0 APK](https://github.com/UndergroundAlzar/Aiko-Browser/releases/download/v0.2.0/Aiko-Browser-0.2.0.apk)** · [发行说明与 SHA-256](https://github.com/UndergroundAlzar/Aiko-Browser/releases/tag/v0.2.0) · [English](docs/README.en.md)

## 功能

| 模块 | 实现 |
| --- | --- |
| **高刷与内核加速** | 120–185Hz 刷新率引擎（极速高刷 / 自适应 / 省电 60Hz），支持 Android 15+ ARR 触控提频、全屏视频自动让步与可选零后台开销实时帧率浮标（HUD）；集成 WebView 异步启动、渲染进程预热、BFCache 前进后退缓存、过滤感知预连接与 Wi-Fi 后台预取 |
| **标签与导航** | 底部单行地址栏（支持左右滑动切标签、上滑开标签列表、滚动自动隐藏与屏幕边缘手势）、多引擎搜索（Google / Bing / 百度 / DuckDuckGo / Startpage / Kagi / GitHub / 自定义）、首页快捷访问（Speed Dial）、0MB 内存稍后读（可选 MHTML 离线快照）、自适应 3–8 个热 WebView、VSync 对齐阅读位置恢复、阅读模式、页内查找、标签静音与打印/PDF |
| **站点控制** | 地址栏盾牌打开「站点盾牌与极速 X-Ray」（首帧/完成耗时、BFCache 命中与拦截统计），按精确主机名配置广告过滤、185Hz 抗卡盾、站点强制高刷、站点脚本 JS、网页暗黑、桌面标识、网络图片、点选屏蔽、站点字号与一键恢复默认 |
| **过滤** | 内置 EasyList、EasyPrivacy 和 AdGuard 中文列表（支持单列表独立开关），二进制规则缓存与热点索引加速。支持常见 ABP 请求规则、站点 CSS、可视点选屏蔽、手写规则与每站暂停 |
| **用户脚本** | 从源码、UTF-8 `.user.js` 或 HTTPS 导入。支持网站匹配、运行时机、脚本隔离、GM 样式、独立持久化存储（兼容旧版迁移）与快速域名守卫 |
| **书签与历史** | 后台异步加载，支持实时关键词搜索、固定到首页快捷访问，以及标准 Netscape HTML 书签导入与导出 |
| **下载** | 单文件最多 128 路，显示分段地图、逐路速率和预计剩余时间。支持经校验的断点续传、单段重试和浏览优先 |
| **无痕** | 使用独立 Android 进程（`:private`）和 WebView 数据目录。普通标签的登录与浏览记录使用自己的目录 |

## 运行机制

- **高刷与渲染管线**：`DisplayModeEngine` 在保持当前物理分辨率与 HDR 能力的前提下匹配目标刷新率模式，全屏播放视频时自动退回系统自适应以匹配片源帧率；`WebViewPerf` 在可用 WebView 版本上启用异步初始化、BFCache、挂起渲染进程自动恢复，以及仅在通过广告过滤后才触发的 HTTPS 预连接/非计费网络预取。
- **站点配置与扩展项**：基础配置（`site_profiles`）与 v0.2.0 扩展项（`site_extras`：185Hz 抗卡盾、站点强制高刷、站点 JS 开关、网页暗黑覆盖）均按精确主机名保存，升级兼容旧版数据。桌面标识在新的导航开始前配置；恢复默认会同时还原该站点的浏览、扩展项与过滤设置，不清除普通标签的登录与历史。
- **请求过滤与二进制缓存**：规则在后台编译并异步写入二进制缓存（`filter-rules-v1.bin`），后续冷启动直接校验指纹后载入。拦截记录只保留当前文档最近 12 条匹配，不写入磁盘。单列表开关、点选屏蔽及受支持语法见[配置指南](docs/CUSTOMIZATION.md)。
- **脚本执行与存储迁移**：按系统 WebView 能力选择注入模式，有授权的脚本要求隔离执行支持，导入后默认停用。v0.2.0 将脚本键值存储拆分至 `userscript_values` 并自动兼容读取 v0.1.9 旧数据，结合主机名快速守卫与分帧增量刷新减少子帧与滚动开销。完整的兼容范围见[配置指南](docs/CUSTOMIZATION.md)。
- **分段下载**：需要服务端正确返回 HTTP 字节范围和强 ETag，否则退回单连接。默认按文件大小选择 1–8 路，用户可为当前文件设置上限。当前版本管理一个活动下载任务。

## 构建与验证

Windows 构建需要 PowerShell 7、JDK 21、Android SDK Platform 36 和 Build Tools 36.0.0。设置 `JAVA_HOME` 和 `ANDROID_HOME` 后在仓库根目录运行：

```powershell
pwsh -NoProfile -File ./build.ps1
```

脚本校验固定版本的 AndroidX 依赖摘要，启用 R8 优化与裁剪（保留反射边界与调试类名），并从源码生成本地签名 APK（v0.2.0 为 1,828,211 字节，可覆盖安装相同签名的 v0.1.0–v0.1.9）。发行包的签名指纹、校验方法、全套测试与基准命令见[构建指南](docs/BUILD.md)。

v0.2.0 已通过电脑端全部回归测试（含新增的 `FlagshipUnitChecks`），同环境电脑端基准测试（JDK 21 / Node.js，对比 v0.1.9）实测结果如下：

- **过滤规则匹配（20 URL 批次均值）**：`16.540 µs` → `0.815 µs/req`（**20.3×**，p95 `45.3 µs` → `1.1 µs`）
- **过滤引擎载入与内存**：冷启二进制缓存载入 `45.14 ms` / 热载 `23.47 ms`（对比 v0.1.9 文本编译 `322.75 ms` 为 **7.2× / 13.8×**）；常驻堆内存 `40.67 MB` → `23.66 MB`（**-41.8%**）
- **URL 校验与油猴快速守卫**：`HttpUrlUtil` 混合校验 `3,241.4 ns` → `41.7 ns`（**77.7×**）；油猴 10-iframe 突发注入 p50 `38.7 µs` → `7.0 µs`（**5.5×**）

以上数据为电脑端主机基准；「⋯ → 性能记录」与实时帧率浮标可用于实机观测。Android 真机界面与 185Hz 实机硬件帧率指标仍有待实体设备复测。

[规则列表许可](app/src/main/assets/filters/NOTICE.txt) · [AndroidX 许可](app/src/main/assets/notices/NOTICE.txt) · [美术画廊](docs/art/README.md)
