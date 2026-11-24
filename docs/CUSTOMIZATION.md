# 站点控制、性能引擎、动态主题、过滤规则与油猴脚本指南

## 性能引擎与自定义性能模式（v0.3.0）

在起始页点击性能胶囊或打开「⋯ → 性能引擎」即可切换硬件级性能策略，所有开关均为真实操作系统/Chromium 引擎参数：

- **狂暴性能（Rampage）**：锁定屏幕最高刷新率（120–185Hz）+ Android 12+ **ADPF 大核突发助推**（`PerformanceHintManager`，仅在触控/滑动/导航开始时突发上报，自动衰减，待机零耗电）+ 最多 10 个热 WebView + 5 页 BFCache（10 分钟超时）+ 激进预加载（HTTPS 预连接 + Wi-Fi 预取 + Chromium Speculative Prerender）+ 单前台标签预光栅化（`OffscreenPreRaster`，切后台/内存紧张时立即关闭防 OOM）+ 128 路下载并发上限。
- **智能均衡（Balanced，默认）**：锁定最高刷新率 + 6 个热标签 + 3 页 BFCache + 智能预连 + 64 路下载并发，兼顾 185Hz 日常丝滑感与内存续航平衡。
- **持久省电（Eco）**：60Hz 省电模式 + 2 个热标签 + 1 页 BFCache + 关闭预加载 + 16 路下载并发，最大化续航。
- **无限自定义性能模式**：点击「+ 新建自定义性能模式」可自由组合全部 9 项硬件参数（含**全局 185Hz 抗卡盾**），支持随时编辑、一键切换与删除；数据保存在独立的 `perf_profiles` 配置中，升级兼容旧版 `display_mode` 设定。
- **实时帧率浮标（HUD）与诊断报告**：底部可随时启停零后台开销的实时帧率浮标（显示屏幕 Hz、渲染 FPS、P99 帧耗与 OEM 限频提醒）及查看性能诊断报告。

## 动态主题工坊（Material / Material You）

打开「⋯ → 外观主题」即可进入「主题工坊」，所有主题**零重启即时生效**（同步更新普通标签、下载面板与独立无痕窗口），并通过纯 Java HSV/WCAG 算法保证任意色相下的高可读对比度：

- **7 套精选预设**：**黑曜靛蓝**（默认）、**Material You 壁纸取色**（Android 12+ 实时读取系统 Monet `system_accent1` 壁纸色板，Android 10–11 优雅回退黑曜靛蓝）、**赛博翡翠**、**樱花粉霞**、**熔岩琥珀**、**极光紫罗兰**、**纯黑 OLED 极简**（`#000000` 纯黑画布）。
- **自定义调色台**：提供色相（Hue）、饱和度（Sat）、底色浸染强度（Surface Tint）三组实时滑块与 `#RRGGBB` Hex 输入框，上方实时预览底部胶囊、渐变进度条与按钮对比度。

## 广告过滤与规则中心（小白友好 + 极客兼容）

打开「⋯ → 广告过滤」进入全新规则中心：

- **一键更新全部规则**：顶部概览卡展示当前生效规则总数与上次更新时间，一键异步更新全部已启用的内置规则与在线订阅（下载失败自动保留旧规则）。
- **内置精选规则（中国 + 全球，7 套共 151,759 条）**：
  - **中国**：中文网站广告过滤（AdGuard 中文 + EasyList China）、中文弹窗与引流净化（CJX's Annoyance List，专治「打开 App」诱导与悬浮遮罩）。
  - **移动**：移动端广告联盟拦截（AdGuard Mobile Ads Optimized）、「打开 App」横幅净化（AdGuard Mobile App Banners）。
  - **全球**：EasyList 全球基础广告、EasyPrivacy 隐私追踪防护、Cookie 隐私弹窗净化（AdGuard Cookie Notices Optimized）。
  - 每一套列表均可独立开关（也在地址栏盾牌中提供快捷切换），引擎按组合指纹优先加载仅 **8.18 MB** 的二进制编译缓存（`filter-rules-v1.bin`）。
- **在线订阅规则**：
  - 点击「添加推荐订阅」可一键订阅 8 套经验证活跃的 2026 规则库（anti-AD、AdRules、EasyList China 纯净版、Peter Lowe's Ad Servers、HaGeZi Multi LIGHT、AdGuard 烦人元素全家桶、uBlock Origin 补充广告/隐私规则）。
  - 点击「+ 自定义订阅链接」可订阅任意 HTTP(S) 的 ABP/EasyList 文本规则源（自动校验防 HTML 伪装、限制 8 MB、阻止 HTTPS→HTTP 降级跳转，最多 32 份）。
- **我的自定义规则**：
  - **快速屏蔽 / 放行一个网址**：输入域名（默认预填当前站点），一键点「屏蔽该域」（`||host^`）或「放行该域」（`@@||host^`），无需学习语法。
  - **点选网页元素（Element Picker）**：直接点击页面广告位高亮选区，支持「扩大选区」「预览隐藏」「保存屏蔽」，自动安全校验并写入自定义规则。
  - **高级 ABP 原文编辑**：完整支持手动编写最多 2,000 条 / 128,000 字符自定义规则，含 `@@` 例外、`$document`、`$badfilter`、`##` 与 `#@#`（保存时逐行语法校验并提示行号）。

## 站点控制与极速 X-Ray

点击地址栏左侧盾牌进入「站点盾牌与极速 X-Ray」，顶部实时显示协议、首帧/完成耗时、BFCache 命中与拦截次数。以下单站配置按**精确主机名**保存（兼容 `site_profiles` 与 `site_extras`）：

- **广告过滤 / 185Hz 抗卡盾 / 站点强制高刷 / 站点脚本 JS / 网页暗黑（三态）/ 桌面标识 / 网络图片 / 点选屏蔽 / 站点字号（75%–200%） / 拦截详情 / 恢复默认**。
- 「拦截详情」仅在内存中保留当前文档最近 12 条命中的请求域名与规则来源，不写入磁盘。「恢复默认」同时还原该站的浏览、扩展项与过滤开关，不清除普通标签登录与历史。

## 油猴脚本与 Greasy Fork 一键安装

- **自动识别 `.user.js` 一键安装与更新**：在 **Greasy Fork**（点击「⋯ → 油猴脚本 → 前往 Greasy Fork 发现脚本」直达）、Sleazy Fork、ScriptCat、OpenUserJS 或 GitHub Raw 页面点击任意脚本的「安装此脚本」（或从网址导入 `.user.js`），Aiko 会自动拦截并弹出**「发现油猴脚本 / 发现脚本更新」**确认面板：
  - 清晰展示脚本名称、版本、作者、中文描述、生效站点（`@match` / `@include`）、`@grant` 权限徽章（高危权限显式警示）与可展开源码预览。
  - 点击**「一键安装并启用」**即刻安装生效；若已安装同名脚本，点击**「立即覆盖更新（保留脚本数据）」**将原位更新脚本源码并 **100% 保留原有 `GM_setValue` 数据**；也可随时选择「普通下载」。
- **当前页生效状态与手动导入**：油猴管理面板顶部直接展示当前网页已生效的脚本列表，同时保留「+ 导入 / 粘贴脚本」支持粘贴源码、本地 UTF-8 `.user.js` 文件（上限 512 KiB）和 HTTPS 地址导入。
- **安全边界与存储迁移**：支持 `GM_info`、`GM_log`、`GM_addStyle`、`GM_getValue`、`GM_setValue`、`GM_deleteValue`、`GM_listValues` 及 `GM.*` 形式（单脚本最多 128 键 / 32,768 字符），独立存储于 `userscript_values` 并自动兼容读取 v0.1.9 旧数据。为保障最小权限安全，依赖 `GM_xmlhttpRequest`、`GM_cookie`、远程 `@require` 或 `@resource` 的脚本会在解析时明确提示不支持原因（可改为普通下载保存）。

```javascript
// ==UserScript==
// @name         Aiko 样式测试
// @match        https://example.com/*
// @grant        GM_addStyle
// @run-at       document-end
// ==/UserScript==
GM_addStyle('h1 { color: #e84f79; }');
```

## 视频倍速、链接净化、书签、手势与关于

- **视频倍速控制器**：在网页中打开「⋯ → 视频倍速」，按需检测当前页 HTML5 `<video>` 元素（含 Shadow DOM 与同源 iframe），一键切换 `0.5x / 0.75x / 1.0x / 1.25x / 1.5x / 2.0x / 2.5x / 3.0x` 倍速、静音与画中画，恢复 `1.0x` 自动卸载监听、空闲零开销。
- **分享 / 复制链接自动净化**：默认开启（可在「⋯ → 链接净化」切换），复制或分享链接时自动剥离 `utm_*`、`fbclid`、`gclid`、`si`、`spm`、`spm_id_from`、`vd_source`、`share_*`、`bd_vid` 等海内外追踪参数，完整保留视频 ID、页码、时间戳与锚点。
- **搜索、快捷访问、稍后读、书签与手势**：支持 7 种内置搜索引擎 + 自定义 `%s` 模板；首页快捷访问最多 16 个；0MB 稍后读支持可选 MHTML 离线快照；书签支持实时搜索与 Netscape HTML 导入导出；「⋯ → 手势与隐栏」可独立开关滚动自动隐栏与屏幕边缘手势，底部地址栏支持左右滑动切标签与上滑开标签列表；「⋯ → 关于 Aiko」查看版本、作者 **Alzar**、GitHub 开源仓库、WebView 内核/屏幕刷新率摘要与完整开源许可致谢。

[Tampermonkey 元数据和 GM 接口](https://www.tampermonkey.net/documentation.php) · [AndroidX WebView 注入接口](https://developer.android.com/reference/androidx/webkit/WebViewCompat)
