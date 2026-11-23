# Aiko Browser

Aiko Browser is a lightweight Android browser built with Java, native Android views, and the system WebView. It requires Android 10 (API 29) and targets API 36. The app currently uses a Chinese interface.

**[Download v0.1.9 APK](https://github.com/UndergroundAlzar/Aiko-Browser/releases/download/v0.1.9/Aiko-Browser-0.1.9.apk)** · [Release notes and SHA-256](https://github.com/UndergroundAlzar/Aiko-Browser/releases/tag/v0.1.9) · [简体中文](../README.md)

| Area | Implementation |
| --- | --- |
| Navigation | One-row bottom address bar, search-only start page, background tabs, recently closed tabs, and up to five warm WebViews |
| Site settings | Exact-host desktop user agent, text zoom, network image blocking, recent filter matches, and a site reset |
| Filtering | Bundled EasyList, EasyPrivacy and AdGuard Chinese snapshots, selected ABP network and site CSS rules, a custom rule editor, and per-site pause |
| Userscripts | Source, local `.user.js` and HTTPS import, URL matching, lifecycle timing, optional isolation, style injection, and script-local GM storage |
| Downloads | One active file with up to 128 byte ranges, segment visualization, verified checkpoints, retry, and foreground browsing priority |
| Private browsing | A separate Android process and WebView data directory |

Tap the shield for site settings and filter diagnostics. The view shows the request host, matched rule, and list source for up to twelve recent matches. It keeps those records in memory for the current document. Text zoom and network image settings are applied to the destination site. A user agent chosen at navigation start follows that request and its redirects.

The downloader requires a strong ETag and a valid byte-range response for parallel segments. Otherwise it uses a single stream. Userscript support depends on the installed WebView. Scripts with privileged grants need isolated execution and start disabled after import. [Customization guide](CUSTOMIZATION.md)

Build on Windows with PowerShell 7, JDK 21, API 36 and Build Tools 36.0.0. Set `JAVA_HOME` and `ANDROID_HOME`, then run `pwsh -NoProfile -File ./build.ps1`. The build checks dependency hashes and signs the APK with a local key. [Build, signature, and tests](BUILD.md)

Host-side regression tests cover rule attribution, script matching, range downloads, and restart recovery. Android layout and on-device performance for v0.1.9 are still pending.

[Filter notices](../app/src/main/assets/filters/NOTICE.txt) · [AndroidX notices](../app/src/main/assets/notices/NOTICE.txt) · [Artwork gallery](art/README.md)
