<div align="center">

<img src="art/night-train.webp" alt="Aiko Browser — Night Train" width="100%" />

<br />

# Aiko Browser

**2.53 MB Pure Java. Up to 185Hz. A flagship high-refresh Android browser built for power users.**

[![Latest Release](https://img.shields.io/github/v/release/UndergroundAlzar/Aiko-Browser?style=flat-square&color=7C9BFF&label=Release)](https://github.com/UndergroundAlzar/Aiko-Browser/releases/latest)
[![APK Size](https://img.shields.io/badge/APK-2.53%20MB-2DD4BF?style=flat-square)](https://github.com/UndergroundAlzar/Aiko-Browser/releases/download/v0.4.0/Aiko-Browser-0.4.0.apk)
[![Android](https://img.shields.io/badge/Android-10%2B%20(API%2029–36)-3DDC84?style=flat-square&logo=android&logoColor=white)](https://github.com/UndergroundAlzar/Aiko-Browser/releases/latest)
[![Refresh Rate](https://img.shields.io/badge/Display-Up%20to%20185Hz-F472B6?style=flat-square)](#real-device--host-benchmarks)
[![Pure Java](https://img.shields.io/badge/Stack-Pure%20Java%20·%20Zero%20Bloat-FBBF24?style=flat-square)](#build--verification)
[![Tests](https://img.shields.io/badge/Checks-13%2F13%20PASS-34D399?style=flat-square)](BUILD.md)

<p>
  <a href="https://github.com/UndergroundAlzar/Aiko-Browser/releases/download/v0.4.0/Aiko-Browser-0.4.0.apk"><strong>Download v0.4.0 APK</strong></a>
  &nbsp;·&nbsp;
  <a href="https://github.com/UndergroundAlzar/Aiko-Browser/releases/tag/v0.4.0">Release Notes &amp; SHA-256</a>
  &nbsp;·&nbsp;
  <a href="../README.md">简体中文</a>
  &nbsp;·&nbsp;
  <a href="CUSTOMIZATION.md">Configuration Guide (ZH)</a>
  &nbsp;·&nbsp;
  <a href="BUILD.md">Build &amp; Benchmarks (ZH)</a>
  &nbsp;·&nbsp;
  <a href="art/README.md">Artwork</a>
</p>

</div>

---

## Why Aiko Browser?

Created by **Alzar**, **Aiko Browser** is a lightweight, zero-telemetry Android browser crafted directly in pure Java, native Android views, and the system WebView (Android 10+ / API 29–36; UI currently in Chinese). Inside a **2.53 MB** single-DEX APK, it delivers desktop-caliber extensibility and obsessive 185Hz smoothness without any framework bloat.

- **Real Hardware 185Hz Pacing**: Verified on a OnePlus 185Hz flagship at **159–166 ms** cold start, **943** fast-scroll frames with **0 missed VSyncs** (`0.42%` jank), and **23.2 MB** Java heap.
- **Babel (巴别塔) Immersive Translator**: Free zero-key Google Translate, seamless CN-direct Microsoft/Edge fallback, and fully tunable custom OpenAI-compatible / Gemini AI APIs with automatic model-list fetching and triple-space input back-translation.
- **Complete Tampermonkey Runtime**: Isolated-world execution with `GM_xmlhttpRequest` (`@connect`), `@require`/`@resource` local caching, live `GM_registerMenuCommand` entries, cross-tab `GM_addValueChangeListener`, and 1-tap Greasy Fork installation.
- **Sovereign Customization & Data Ownership**: Custom Homepage/New Tab (with instant iTab preload), dynamic Material You + `#000000` OLED theming, isolated `:private` process, and 1-tap `.aiko` full backup & restore.

---

## Feature Highlights

### 1. Babel (巴别塔) Immersive Bilingual Translator
- **Multi-Engine + Auto-Fallback**: Free **Google Translate** (default), CN-direct **Microsoft/Edge Translate** with automatic failover when Google is unreachable, and **Youdao** backup.
- **Tunable Custom AI APIs**: OpenAI-compatible relays & native Gemini endpoints, multi-profile presets, **1-tap auto-fetch model list** (plus custom model IDs), tunable **Max Concurrency (1–16)**, **Max Tokens (128–32768)**, **Temperature (0.0–1.5)**, batch size/chars, timeout, and 5 prompt templates.
- **Smart Viewport & Input UX**: `IntersectionObserver` lazy translation + 4,096-entry session cache, strict `code`/`pre`/`svg`/`math` exclusions, 5 bilingual styles, selection popup + TTS, and **triple-space in-place input back-translation**.

### 2. Full Userscript Engine & Offline Mobile DevTools
- **True 1-Tap Greasy Fork Install**: Automatically bypasses Greasy Fork's extension prompt modal; preserves `GM_setValue` data on in-place updates; supports local `.user.js` intents, paste, and HTTPS URLs.
- **Complete GM API Surface**: `GM_xmlhttpRequest` / `@connect` approval UI (with cross-origin redirect credential stripping), `@require` / `@resource` disk caching, `GM_addValueChangeListener`, clickable `GM_registerMenuCommand` menu items, and 1-tap update checks.
- **Built-in Offline WebDevTools**: Zero-dependency floating Shadow DOM drawer with Console + JS REPL, Network inspector, Cookie/LocalStorage editor, and tap-to-inspect DOM Elements.

### 3. 185Hz Performance Engine & 128-Lane Downloads
- **Rampage / Balanced / Eco + Unlimited Custom Profiles**: Controls refresh lock (up to 185Hz), Android 12+ burst-only ADPF (`PerformanceHintManager`), Android 15+ ARR touch boost, active tab tiering (12 / 6 / 3), BFCache depth, speculative DNS+TLS preconnect, and live frame-pacing HUD.
- **128-Segment Turbo Downloader**: Parallel ranged downloads with verified checkpoints, live speed sparkline, segment map, browsing priority pacing, and media sniffer integration.

### 4. 7-in-1 CN + Global Adblock & Site Shield X-Ray
- **152,033 Active Bundled Rules**: AdGuard Chinese, CJX Annoyance, AdGuard Mobile Ads, Mobile App Banners, EasyList, EasyPrivacy, and AdGuard Cookie Notices compiled into an **8.34 MB** binary cache (`22.4 ms` warm load).
- **Subscriptions & Visual Controls**: 8 curated CN+Global feeds (e.g. `anti-AD`, `AdRules`, `HaGeZi`), custom ABP URLs, visual Element Picker with **1-tap undo inside Site Shield**, anti-adblock scriptlets, and per-host Speed X-Ray controls (unlock copy, force pinch zoom, auto-refresh timer, custom CSS, 1-tap site data reset).

### 5. Flagship UX, Media & Privacy Extras
- **Custom Home / New Tab**: Native Start Page or any URL (**iTab**, WeTab, MTab, Blank) with 1-tab hidden preload for zero-flash opening.
- **Navigation & Media**: Customizable bottom bar & floating capsule style, swipe-bar tab switching, Arc-style half-screen **Peek preview**, PWA standalone desktop shortcuts, 185Hz native **Image Gallery**, full-page **Long Screenshot**, fullscreen video gestures (brightness/volume/seek/long-press `2.0x`), lockscreen **Background Audio**, **App-Jump Guard**, 20+ `!bang` & `/command` shortcuts, Clipboard Smart Capsule, AMOLED night comfort filter, custom `.ttf`/`.otf` fonts, pure-Java themed QR generator/decoder, Android TTS Read Aloud, Form Draft protection, Renderer OOM crash recovery, and **1-tap `.aiko` backup/restore**.

---

## Real-Device & Host Benchmarks

### Physical 185Hz Device (OnePlus PMS110 · SM8850 · Android 17 · WebView Canary 160)
| Metric | Result | Notes |
| :--- | :---: | :--- |
| **Cold Start** (`am start -W`) | **`159–166 ms`** | With full 7-list binary filter cache |
| **185Hz Fast Scroll** (`dumpsys gfxinfo`) | **`943` frames · `0` missed VSyncs** | Only 4 janky frames (**`0.42%`**) at `5.405 ms` VSync |
| **Frame Pacing** (CPU / GPU) | **p95 `5 ms` · p99 `6 ms`** | GPU `p95 = 2 ms` / `p99 = 3 ms` |
| **Memory** (`dumpsys meminfo`) | **`23.2 MB` Java Heap** | Native `25.8 MB` · Total PSS `193.6 MB` |
| **Live Adblock** (7 bundled + `anti-AD`) | **`172,031` active rules** | Zero ads on Greasy Fork; zero app errors |

### Host Benchmarks & Verification (`v0.4.0` · JDK 21 / Node.js / Headless Chromium)
All **13/13** automated test & benchmark suites pass:
- **7-list network rule match** (151,979 rules): **`0.400 µs/req`** p50 (**30×+** vs `v0.1.9`)
- **Cosmetic CSS lookup**: **`0.200 µs`** p50 · Binary cache **`8.34 MB`** (warm load `22.42 ms`)
- **URL check (`HttpUrlUtil`)**: **`43.4 ns`** (**71.9×**) · **Userscript 10-iframe guard**: **`7.0 µs`** p50 (**5.5×**)

---

## Build & Verification

Requires Windows PowerShell 7, JDK 21, Android SDK Platform 36, and Build Tools 36.0.0 (`JAVA_HOME` and `ANDROID_HOME` set):

```powershell
pwsh -NoProfile -File ./build.ps1
pwsh -NoProfile -File ./testing/run_benchmarks.ps1 -IncludeChromiumSmoke
```

```text
APK SHA-256   d23cb60d3e7f694e27390a6597e6804bfca113a86f3e86020b74cdb8b6c765b8
CERT SHA-256  ae71c82831309c76ebf55c407d91afbba16776e97389bd73c5e8400fe9397fe4
```

---

<div align="center">

**Author**: **Alzar** · [GitHub](https://github.com/UndergroundAlzar/Aiko-Browser) · [Filter Notices](../app/src/main/assets/filters/NOTICE.txt) · [AndroidX Notices](../app/src/main/assets/notices/NOTICE.txt) · [Artwork Gallery](art/README.md)

</div>
