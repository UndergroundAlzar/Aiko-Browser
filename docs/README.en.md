# Aiko Browser

Aiko Browser is a lightweight, high-refresh-rate Android browser built with Java, native Android views, and the system WebView. It requires Android 10 (API 29) and targets API 36. The app currently uses a Chinese interface.

**[Download v0.2.0 APK](https://github.com/UndergroundAlzar/Aiko-Browser/releases/download/v0.2.0/Aiko-Browser-0.2.0.apk)** · [Release notes and SHA-256](https://github.com/UndergroundAlzar/Aiko-Browser/releases/tag/v0.2.0) · [简体中文](../README.md)

| Area | Implementation |
| --- | --- |
| High-refresh & WebView engine | 120–185Hz display-mode engine (Max / Auto / Power 60Hz) with Android 15+ ARR touch boost, automatic fullscreen video yield, and an opt-in zero-idle-overhead live frame-pacing HUD; async WebView startup, renderer warm-up, BFCache, adblock-checked HTTPS preconnect, and unmetered background prefetch |
| Navigation & UI | Obsidian dark UI, one-row bottom address bar (swipe to switch tabs, swipe up for tab sheet, auto-hide on scroll, edge gestures), start page with Speed Dial, zero-RAM Reading Shelf (optional MHTML offline archive), multi-engine search (Google, Bing, Baidu, DuckDuckGo, Startpage, Kagi, GitHub, custom), adaptive 3–8 warm WebViews, VSync-aligned scroll restore, Reader Mode, find in page, tab mute, and Print/PDF |
| Site controls | Shield sheet with Speed X-Ray telemetry (first-commit/finish timing, BFCache status, block count) and exact-host controls: ad filtering, 185Hz Ultra-Scroll Anti-Jank Shield, per-site Force Max-Hz, per-site JavaScript toggle, per-site dark mode override, desktop user agent, network image blocking, visual Element Picker, text zoom, and site reset |
| Filtering | Bundled EasyList, EasyPrivacy, and AdGuard Chinese snapshots with per-list toggles, binary compiled cache (`filter-rules-v1.bin`), selected ABP network/CSS rules, visual Element Picker, custom rule editor, and per-site pause |
| Userscripts | Source, local `.user.js`, and HTTPS import, URL matching, lifecycle timing, optional isolation, GM style injection, dedicated `userscript_values` storage (backward-compatible with v0.1.9), fast protocol/host guard, and incremental idle rebinding |
| Bookmarks & history | Background preload/save, live search, Speed Dial pinning, and standard Netscape HTML bookmark import/export |
| Downloads | One active file with up to 128 byte ranges, segment visualization, verified checkpoints, retry, and foreground browsing priority |
| Private browsing | A separate Android process (`:private`) and WebView data directory isolated from regular tabs |

Tap the shield icon to inspect site telemetry and filter diagnostics, toggle per-site overrides (including 185Hz Anti-Jank Shield, Force Max-Hz, JavaScript, and dark mode), launch the visual Element Picker, or toggle built-in filter lists independently. Filter hit traces keep up to twelve recent matches in memory for the current document only. Resetting a site restores both legacy `site_profiles`, v0.2.0 `site_extras`, and its filter state while keeping normal-tab cookies and history intact.

The downloader requires a strong ETag and valid byte-range support for parallel segments; otherwise it falls back to a single stream. Userscript isolation depends on the installed system WebView, and scripts with privileged grants start disabled after import. [Customization guide (Chinese)](CUSTOMIZATION.md)

Build on Windows with PowerShell 7, JDK 21, Android API 36, and Build Tools 36.0.0. Set `JAVA_HOME` and `ANDROID_HOME`, then run `pwsh -NoProfile -File ./build.ps1`. The script verifies pinned dependency hashes, runs R8 optimization and shrinking while keeping reflection boundaries, and signs the APK with a local key (`1,828,211` bytes for v0.2.0; upgrade-compatible with v0.1.0–v0.1.9 signed with the same key). [Build, signature, and tests (Chinese)](BUILD.md)

Host-side regression checks (including `FlagshipUnitChecks`) all pass on JDK 21 / Node.js. Measured **host-side benchmarks** comparing v0.1.9 to v0.2.0 on the same machine:

- `FilterRules.blockingRule()` 20-URL batch mean: `16.540 µs` → `0.815 µs/req` (**20.3×** faster; p95 `45.3 µs` → `1.1 µs`)
- `FilterRules` startup & heap: binary cache load `45.14 ms` cold / `23.47 ms` warm vs `322.75 ms` v0.1.9 cold text compile (**7.2× / 13.8×**); post-GC retained heap `40.67 MB` → `23.66 MB` (**-41.8%**)
- URL check & userscript guard: `HttpUrlUtil` mixed check `3,241.4 ns` → `41.7 ns` (**77.7×**); userscript 10-iframe burst p50 `38.7 µs` → `7.0 µs` (**5.5×**)

These numbers are host-side JVM/Node benchmarks. On-device Android UI verification and real-hardware 185Hz frame-pacing benchmarks are still pending physical device testing.

[Filter notices](../app/src/main/assets/filters/NOTICE.txt) · [AndroidX notices](../app/src/main/assets/notices/NOTICE.txt) · [Artwork gallery](art/README.md)
