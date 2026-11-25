<div align="center">

<img src="docs/art/night-train.webp" alt="Aiko Browser — 夜行线" width="100%" />

<br />

# Aiko Browser

**轻至 2.53 MB，快至 185Hz —— 为旗舰高刷屏与重度网页玩家打造的纯 Java Android 浏览器**

[![Latest Release](https://img.shields.io/github/v/release/UndergroundAlzar/Aiko-Browser?style=flat-square&color=7C9BFF&label=Release)](https://github.com/UndergroundAlzar/Aiko-Browser/releases/latest)
[![APK Size](https://img.shields.io/badge/APK-2.53%20MB-2DD4BF?style=flat-square)](https://github.com/UndergroundAlzar/Aiko-Browser/releases/download/v0.4.0/Aiko-Browser-0.4.0.apk)
[![Android](https://img.shields.io/badge/Android-10%2B%20(API%2029–36)-3DDC84?style=flat-square&logo=android&logoColor=white)](https://github.com/UndergroundAlzar/Aiko-Browser/releases/latest)
[![Refresh Rate](https://img.shields.io/badge/Display-Up%20to%20185Hz-F472B6?style=flat-square)](#实测性能与基准)
[![Pure Java](https://img.shields.io/badge/Stack-Pure%20Java%20·%20Zero%20Bloat-FBBF24?style=flat-square)](#构建与验证)
[![Tests](https://img.shields.io/badge/Checks-13%2F13%20PASS-34D399?style=flat-square)](docs/BUILD.md)

<p>
  <a href="https://github.com/UndergroundAlzar/Aiko-Browser/releases/download/v0.4.0/Aiko-Browser-0.4.0.apk"><strong>立即下载 v0.4.0 APK</strong></a>
  &nbsp;·&nbsp;
  <a href="https://github.com/UndergroundAlzar/Aiko-Browser/releases/tag/v0.4.0">发行说明与校验</a>
  &nbsp;·&nbsp;
  <a href="docs/CUSTOMIZATION.md">深度配置指南</a>
  &nbsp;·&nbsp;
  <a href="docs/BUILD.md">构建与基准</a>
  &nbsp;·&nbsp;
  <a href="docs/README.en.md">English</a>
  &nbsp;·&nbsp;
  <a href="docs/art/README.md">像素画廊</a>
</p>

</div>

---

## 为什么是 Aiko？

大多数手机浏览器不是堆满信息流与开屏广告，就是简陋得连油猴脚本和现代翻译都跑不起来。**Aiko Browser** 由 **Alzar** 打造，基于系统 WebView 与纯 Java 原生 Android 视图从零雕琢——不依赖臃肿框架、无任何商业遥测，在仅 **2.53 MB** 的体积内，将旗舰高刷性能、桌面级油猴生态、沉浸式 AI 翻译与高自由度定制融为一体。

- **真·轻快高刷**：OnePlus 185Hz 真机实测冷启动 **159–166 ms**，943 帧连续高速滚动 **0 次丢 VSync**，Java 堆仅 **23.2 MB**。
- **开箱即用，上限极高**：免费免 Key 的 **巴别塔（Babel）** 沉浸双语翻译、完整 **Tampermonkey** 级油猴引擎、**15 万+** 内置中英文广告规则，同时全部支持极客级自定义。
- **单手友好，零遮挡美学**：全动态 Material / Material You 取色与 `#000000` 纯黑 OLED，实测高度停靠底栏绝不遮挡网页固定按钮，单手滑切标签一气呵成。
- **数据完全属于你**：独立无痕进程（`:private`）物理隔离，支持一键导出/恢复单个 `.aiko` 全量备份包，换机重装秒级满血复活。

---

## 核心特性

### 1. 巴别塔（Babel）沉浸式双语翻译
打破语言隔阂，兼顾“免折腾直用”与“自接大模型上限”：
- **三层引擎 + 无感降级**：默认 **Google 免费免 Key 直连**，内置国内免翻墙的 **微软必应（Edge 同款神经翻译）** 与 **有道备用**；选 Google 时若遇网络不通，自动无缝降级至微软必应，绝不卡白框。
- **自定义 AI API 全参数调优**：支持 **OpenAI 兼容格式**（New API / OneAPI / DeepSeek / OpenRouter 等）与 **Gemini 原生格式**，支持多套配置档位、**根据端点和 Key 一键拉取模型列表**（亦可自由输入模型标识）与连通测试；完整开放 **最大并发数（1–16）**、**单次最大 Token（128–32768）**、**温度（0.0–1.5）**、单批字数/条数、超时秒数及 5 套内置/自定义 Prompt 模板。
- **省 Token 与封神交互**：视口 + 1.5 屏懒加载与 4,096 条会话级哈希缓存，自动跳过代码块与公式；提供 5 种双语排版样式（柔色双语 / 主题竖线 / 虚线 / 模糊点击揭晓 / 纯译文替换）、**划词即译 + 朗读**，以及外网回帖/聊天神器 **输入框三连空格原地反译**。

### 2. 油猴完全体 & 离线移动版 DevTools
把手机浏览器的可玩性拉齐桌面端：
- **Greasy Fork 真·一键安装**：自动跳过 Greasy Fork / Sleazy Fork 的“请先安装扩展”拦截弹窗，首次点击「安装此脚本」即呼出主题化安装卡片；支持同名脚本**原位覆盖更新并 100% 保留 `GM_setValue` 数据**，同时支持本地 `.user.js` 文件关联打开、粘贴与网址导入。
- **Tampermonkey 级完整运行时**：支持隔离世界（`JavaScriptExecutionWorld`）与三层降级兼容，完整提供 `GM_xmlhttpRequest`（带 `@connect` 授权弹窗与跨源重定向安全清洗）、`@require` / `@resource` 安装时本地磁盘缓存、`GM_addValueChangeListener` 跨标签实时同步、`GM_registerMenuCommand` 面板内直接点击触发、以及全脚本一键检查更新。
- **100% 离线 WebDevTools**：内置零外链悬浮 `DEV` 调试台，随时在手机上查看 **Console（含 JS REPL）**、**Network（fetch/XHR）**、**Storage（Cookie/LocalStorage 读写）** 与 **Elements DOM 检查器**。

### 3. 185Hz 旗舰性能引擎 & 128 路高速下载
面向旗舰高刷硬件的真实系统级调度，绝非心理安慰开关：
- **狂暴 / 均衡 / 省电 + 无限自定义性能模式**：真实联动刷新率锁定（最高 185Hz / 自适应 / 60Hz）、Android 12+ 突发式 **ADPF 大核助推**（待机零耗电）、Android 15+ ARR 触控提频、热标签保活上限（狂暴 12 / 均衡 6 / 省电 3）、BFCache 页数、预加载/预握手（Speculative Preconnect）、单前台预光栅化与零后台开销实时帧率浮标（HUD）。
- **128 线程可视化分片下载**：单文件最高 128 路并发分段（受性能模式调控），新增**实时速率曲线 Sparkline**、分片并行地图、已校验断点续传与浏览优先让路机制，并支持与网页媒体嗅探无缝联动。

### 4. 7 合 1 中国 + 全球广告过滤与站点盾牌 X-Ray
- **开箱 152,033 条有效规则**：内置 7 套实时活跃开源规则（AdGuard 中文、CJX 弹窗引流净化、AdGuard 移动广告、打开 App 横幅净化、EasyList、EasyPrivacy、AdGuard Cookie 隐私弹窗），二进制编译缓存仅 **8.34 MB**，热载仅 **22 ms**。
- **小白友好 + 极客兼容**：支持 8 套推荐订阅（`anti-AD`、`AdRules`、`HaGeZi` 等）一键订阅、自定义 ABP 订阅链接、一键屏蔽/放行域名、可视化 **点选屏蔽（Element Picker）与盾牌内一键撤销**、以及防反广告弹窗 Scriptlets。
- **站点盾牌 X-Ray**：按精确主机名实时查看首帧/完成耗时与拦截明细，独立配置广告过滤、185Hz 抗卡盾、强制高刷、JS、暗黑模式、桌面标识、解除禁止复制、强制双指缩放、定时自动刷新（15s–5m）、自定义站点 CSS 及单站点 Cookie/存储一键重置。

### 5. 极致自由定制、媒体套件与效率神器
- **自定义首页 & 新标签页（秒开预热）**：独立或同步指定首页与新标签页网址（内置 Aiko 原生、**iTab**、WeTab、MTab、空白页预设或任意网址），支持后台预热 1 个隐形新标签，打开 iTab 零白屏闪现。
- **Material / Material You 动态工坊 & AMOLED 护眼**：7 套旗舰暗夜预设（含 Android 12+ 壁纸取色与 `#000000` 纯黑 OLED）+ 自由色相/饱和度/浸染调色台，零重启即时生效；新增定时日夜切换、深夜极暗/暖色温护眼滤镜，以及导入手机本地 `.ttf / .otf` 字体。
- **单手手势与超级交互**：底栏支持自定义主页/巴别塔键与**悬浮胶囊形态**、底栏左右滑动切标签、上滑展开网格/列表双视图标签页（含搜索与置顶）；长按链接支持后台静默打开与 **Arc 风格半屏交互式小窗预览（Peek）**。

<details>
<summary><strong>展开查看全部 20+ 进阶实用工具清单</strong></summary>

| 分类 | 功能细节 |
| :--- | :--- |
| **媒体与看图** | 全屏视频原生手势（左亮度 / 右音量 / 横滑进度 / 长按临时 `2.0x` 倍速）、锁屏媒体通知后台听声、网页 `.mp4`/`.m3u8`/`.mp3` 音视频嗅探、185Hz 沉浸看图相册（双指缩放 + 批量保存）、真·全页网页长截图（带可选网址与二维码尾标） |
| **效率与导航** | 20+ `!bang` 瞬搜（`!gh`、`!gf`、`!b`、`!ld` 等 + 自定义指令）、地址栏 `/命令`（`/babel`、`/devtools`、`/gallery`、`/qr` 等）、剪贴板智能感知胶囊、纯 Java 主题色二维码生成与相册扫码、Android 原生 TTS 网页朗读（`0.8x–2.0x`）、PWA 桌面独立全屏应用化 |
| **防护与安全** | 防跳转 App 卫士（拦截强跳并按站点记忆）、一键解除网页禁止复制/右键/选中、链接分享自动去追踪参数净化、表单草稿防丢失保护、WebView 渲染进程 OOM 崩溃自动无损自愈、独立 `:private` 无痕进程、`.aiko` 全量一键加密结构备份与恢复 |

</details>

---

## 实测性能与基准

### 真机 185Hz 硬件实测
测试机型：**OnePlus PMS110**（`SM8850` / Android 17 / 1272×2772 @ **185Hz** `modeId 6` / WebView Canary `160.0.8181.0`）

| 实测指标 | 实机表现 | 说明 |
| :--- | :---: | :--- |
| **应用冷启动**（`am start -W`） | **`159–166 ms`** | 完整加载 7 套规则二进制缓存 |
| **185Hz 高速连续滚动**（`dumpsys gfxinfo`） | **`943` 帧 · 丢 VSync `0`** | 抖动仅 4 帧（**`0.42%`**），VSync `5.405 ms` |
| **滚动帧耗时分位**（CPU / GPU） | **p95 `5 ms` · p99 `6 ms`** | GPU 渲染 `p95 = 2 ms` / `p99 = 3 ms` |
| **运行时内存占用**（`dumpsys meminfo`） | **`23.2 MB` Java Heap** | Native `25.8 MB` · Total PSS `193.6 MB` |
| **广告过滤实战**（内置 7 套 + `anti-AD`） | **`172,031` 条有效规则** | Greasy Fork 广告 100% 清除，0 异常日志 |

### 主机端微基准与回归（`v0.4.0` · JDK 21 / Node.js / 无头 Chromium）
全套 **13/13** 自动化测试（含 `BabelEngineCheck`、`V040EnginesCheck`、`babel_dom_check.mjs`、`userscript_chromium_smoke.mjs` 及真实用户脚本端到端测试）全部通过：

| 核心引擎基准 | 实测数据 | 对比基线（`v0.1.9`） |
| :--- | :---: | :---: |
| **7 套规则网络匹配**（151,979 条，p50） | **`0.400 µs / req`** | 提速 **30×+** |
| **页面 CSS 隐藏规则查表**（p50） | **`0.200 µs`** | + 站点级 LRU 缓存 |
| **7 套规则二进制缓存**（体积 / 热载） | **`8.34 MB` / `22.42 ms`** | 常驻堆降低 **~39%** |
| **URL 合法性混合校验**（`HttpUrlUtil`） | **`43.4 ns`** | 提速 **71.9×** |
| **油猴 10-iframe 突发守卫**（p50） | **`7.0 µs`** | 提速 **5.5×** |

---

## 快速安装与升级

1. 前往 [Releases 页面](https://github.com/UndergroundAlzar/Aiko-Browser/releases/latest) 下载 **`Aiko-Browser-0.4.0.apk`**（`2.53 MB`）。
2. 系统要求 **Android 10（API 29）及以上**，无需 Root，普通安装即可。
3. **平滑覆盖升级**：所有官方发行版均沿用同一签名证书（`ae71c828...`），从 `v0.1.7`–`v0.3.1` 直接覆盖安装即可 **100% 保留** 主题、性能模式、书签、历史、过滤订阅、油猴脚本及脚本存储数据。

```text
APK SHA-256   d23cb60d3e7f694e27390a6597e6804bfca113a86f3e86020b74cdb8b6c765b8
CERT SHA-256  ae71c82831309c76ebf55c407d91afbba16776e97389bd73c5e8400fe9397fe4
```

---

## 构建与验证

无 Gradle 依赖膨胀，在 Windows 上安装 PowerShell 7、JDK 21、Android SDK Platform 36 与 Build Tools 36.0.0，配置 `JAVA_HOME` 和 `ANDROID_HOME` 后直接编译：

```powershell
# 一键编译、R8 优化、ZIP 路径规范化校验并签名
pwsh -NoProfile -File ./build.ps1

# 运行全部 13 项主机单测、微基准与无头 Chromium 烟雾测试
pwsh -NoProfile -File ./testing/run_benchmarks.ps1 -IncludeChromiumSmoke
```

完整的签名校验、DEX 体积说明与各项测试细节请见 **[构建与验证指南](docs/BUILD.md)**，功能详解请见 **[深度配置指南](docs/CUSTOMIZATION.md)**。

---

## 像素画廊

<div align="center">
<table>
  <tr>
    <td align="center" width="50%">
      <a href="docs/art/window-seat.webp"><img src="docs/art/window-seat.webp" alt="窗边的位置" width="100%" /></a>
      <br /><sub>窗边的位置 · 沉浸阅读与巴别塔</sub>
    </td>
    <td align="center" width="50%">
      <a href="docs/art/signal-room.webp"><img src="docs/art/signal-room.webp" alt="信号室" width="100%" /></a>
      <br /><sub>信号室 · 185Hz 调度与 128 路分片引擎</sub>
    </td>
  </tr>
</table>
</div>

---

<div align="center">

**作者**：**Alzar** · [GitHub 仓库](https://github.com/UndergroundAlzar/Aiko-Browser) · [规则列表许可](app/src/main/assets/filters/NOTICE.txt) · [AndroidX 许可](app/src/main/assets/notices/NOTICE.txt) · [美术画廊](docs/art/README.md)

<sub>Built with pure Java, zero bloat, and obsessive care for 185Hz smoothness.</sub>

</div>
