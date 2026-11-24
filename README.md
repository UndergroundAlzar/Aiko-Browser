# Aiko Browser

Aiko Browser 是一个由 **Alzar** 开发的轻量高刷 Android 浏览器，基于系统 WebView、Java 与 Android 原生视图构建，采用动态 Material 暗夜设计系统与单手底部交互。最低 Android 10（API 29），目标 API 36。

**[下载 v0.3.0 APK](https://github.com/UndergroundAlzar/Aiko-Browser/releases/download/v0.3.0/Aiko-Browser-0.3.0.apk)** · [发行说明与 SHA-256](https://github.com/UndergroundAlzar/Aiko-Browser/releases/tag/v0.3.0) · [English](docs/README.en.md) · [开源仓库](https://github.com/UndergroundAlzar/Aiko-Browser)

## 功能

| 模块 | 实现 |
| --- | --- |
| **性能引擎与 185Hz 高刷** | 全新「性能引擎」提供 **狂暴性能 / 智能均衡 / 持久省电** 3 档内置模式 + **无限自定义性能模式**（可自由组合刷新率策略、Android 12+ ADPF 大核突发助推、2–12 热标签上限、0–5 页 BFCache、预加载策略、单前台预光栅化、1–128 路下载并发上限与全局 185Hz 抗卡盾）；支持 120–185Hz 面板解锁、Android 15+ ARR 触控提频、全屏视频自动让步与零后台开销实时帧率浮标（HUD） |
| **动态主题与交互界面** | Material / Material You 动态取色引擎（7 套精选预设含 Android 12+ 壁纸取色与纯黑 `#000000` OLED + 自定义色相/饱和度/底色浸染/Hex 调色台，零重启即时换肤并保障全色相 WCAG AA+ 对比度）；底部单行地址栏停靠零遮挡网页固定底栏与弹窗按钮、185Hz 离散切换滚动自动隐藏、左右滑动切标签、上滑开标签列表、屏幕边缘手势、38 枚统一光学线宽零分配矢量图标、多引擎搜索、首页快捷访问（Speed Dial）、0MB 稍后读、阅读模式、页内查找、标签静音、视频倍速控制器（0.5x–3.0x / 静音 / 画中画）、分享/复制链接自动净化与「关于 Aiko Browser」 |
| **广告过滤与规则中心** | 内置 7 套 2026 实时开源精选规则（**中国**：AdGuard 中文 + CJX 弹窗引流净化；**移动**：AdGuard 移动广告 + 打开 App 横幅净化；**全球**：EasyList + EasyPrivacy + AdGuard Cookie 隐私弹窗净化，合计 **151,759 条**有效规则），支持单列表独立开关、一键在线更新全部规则、**在线订阅规则引擎**（内置 8 套中国+全球推荐订阅一键添加 + 任意自定义 ABP/EasyList 链接）、小白友好快速域屏蔽/放行、可视点选屏蔽（Element Picker）、手写 ABP 规则与化妆品作用域去重二进制缓存 |
| **油猴脚本与 Greasy Fork** | 支持 **Greasy Fork / Sleazy Fork / ScriptCat / OpenUserJS / GitHub Raw `.user.js` 自动拦截与一键弹窗安装/覆盖更新**（展示站点匹配与权限徽章、源码预览，覆盖更新自动保留原有 `GM_setValue` 存储数据）；管理面板直达 Greasy Fork 并展示当前页生效脚本；兼容手动粘贴/本地 `.user.js`/HTTPS 导入、隔离执行、独立持久化存储（100% 兼容旧版迁移）与快速域名守卫 |
| **站点控制与 X-Ray** | 地址栏盾牌打开「站点盾牌与极速 X-Ray」（首帧/完成耗时、BFCache 命中与拦截统计），按精确主机名配置广告过滤、185Hz 抗卡盾、站点强制高刷、站点脚本 JS、网页暗黑、桌面标识、网络图片、点选屏蔽、站点字号与一键恢复默认 |
| **书签与历史** | 后台异步加载，支持实时关键词搜索、固定到首页快捷访问，以及标准 Netscape HTML 书签导入与导出 |
| **分段下载** | 单文件最多 128 路（受当前性能模式并发上限调节），显示分段地图、逐路速率和预计剩余时间。支持经校验的断点续传、单段重试和浏览优先 |
| **独立无痕** | 使用独立 Android 进程（`:private`）和 WebView 数据目录，与普通标签的登录、书签和浏览记录物理隔离 |

## 运行机制

- **真实硬件性能调度**：`PerfProfiles` 与 `DisplayModeEngine` / `WebViewPerf` / `DownloadService` 深度联动——刷新率在保持当前物理分辨率与 HDR 能力的前提下匹配目标模式，Android 12+ 可选事件驱动突发式 ADPF `PerformanceHintManager` 助推（仅在触控/导航突发上报并自动回落，待机零耗电），单前台标签预光栅化在切换后台或内存紧张时立即关闭防 OOM。
- **动态取色与无遮挡底栏**：`AikoTheme.Palette` 纯 Java 派生 OLED 深色调色板，在任意色相下保证 `TEXT`/`TEXT_MUTED`/`ACCENT` 达到 WCAG AA+ 可读性，Android 12+ 读取系统 Monet `system_accent1` 色板、Android 10–11 优雅回退；`GestureChrome.computeChromeLayout` 实测底栏高度，确保网页 `position: fixed; bottom: 0` 与弹窗操作栏绝不被遮挡。
- **7 合 1 过滤引擎与二进制缓存**：7 套内置规则与用户在线订阅（`sub-<id>.txt`）统一后台编译，利用化妆品 `Scope` 去重将 151,759 条规则的二进制缓存压缩至仅 **8.18 MB**（比 v0.2.0 的 3 列表缓存更小），指纹校验涵盖全部内置列表、在线订阅与自定义规则。
- **油猴脚本与 Greasy Fork 拦截**：在主框架导航、重定向与下载回调中安全识别 `.user.js`（跳过代码托管 `/blob/` 网页视图、拦截 HTML 伪装页、安全编码重定向地址），一键安装并启用，同名更新原位保留 `userscript_values` 数据；保留对 `v0.1.9`–`v0.2.0` 全部本地用户数据的 100% 覆盖升级兼容。

## 构建与验证

Windows 构建需要 PowerShell 7、JDK 21、Android SDK Platform 36 和 Build Tools 36.0.0。设置 `JAVA_HOME` 和 `ANDROID_HOME` 后在仓库根目录运行：

```powershell
pwsh -NoProfile -File ./build.ps1
```

脚本校验固定版本的 AndroidX 依赖摘要，启用 R8 优化与裁剪（保留反射边界与调试类名，自动为 API 29–30 外联 API 31+ 调用），并从源码生成本地签名 APK（v0.3.0 为 **2,447,119 字节** / 2.33 MiB，含全部 7 套 2026 过滤规则，可直接覆盖安装相同签名的 v0.1.0–v0.2.0）。发行包的签名指纹、校验方法、全套测试与基准命令见[构建指南](docs/BUILD.md)。

v0.3.0 已通过电脑端全部 8 项回归测试（`FlagshipUnitChecks` 扩展至 11 组单测，覆盖 `PerfProfiles`、`UrlCleaner`、`UserScript.isUserScriptUrl`、`AikoTheme.Palette` 360° 对比度、`CustomFilterList`、`VideoBooster` 与 `GestureChrome` 8 状态机），同环境电脑端基准测试（JDK 21 / Node.js，对比 v0.1.9）结果如下：

- **过滤规则匹配（20 URL 批次均值）**：3 列表基线 `16.540 µs` → `0.551 µs/req`（**30.0×**）；v0.3.0 全 7 列表（151,759 条规则）**`0.422 µs/req`**（p50 `0.4 µs` / p95 `0.8 µs`）
- **过滤引擎缓存与内存**：全 7 列表常驻堆仅 **`24.71 MB`**（相比 v0.1.9 的 3 列表 `40.67 MB` 仍 **-39.2%**），二进制缓存仅 **`8.18 MB`**（热载 `20.35 ms`；3 列表 `7.08 MB`，冷载 `35.24 ms` / 热载 `19.35 ms`）
- **URL 校验与油猴快速守卫**：`HttpUrlUtil` 混合校验 `3,119.1 ns` → `43.4 ns`（**71.9×**）；油猴 10-iframe 突发注入 p50 `38.4 µs` → `6.9 µs`（**5.6×**）

以上数据为电脑端主机基准；「⋯ → 性能引擎 / 性能诊断」与实时帧率浮标可用于实机观测。Android 185Hz 实机硬件帧率与 ADPF 表现仍有待实体设备持续复测。

**作者**：Alzar · [GitHub 仓库](https://github.com/UndergroundAlzar/Aiko-Browser) · [规则列表许可](app/src/main/assets/filters/NOTICE.txt) · [AndroidX 许可](app/src/main/assets/notices/NOTICE.txt) · [配置指南](docs/CUSTOMIZATION.md) · [美术画廊](docs/art/README.md)
