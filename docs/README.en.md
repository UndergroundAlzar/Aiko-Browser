# Aiko Browser 0.5.1

Aiko Browser is a Java browser for Android 10 (API 29) and later. It uses the system WebView and a native Chinese-language interface. It includes bilingual translation, userscripts, site filtering and resumable downloads.

[Download the v0.5.1 APK](https://github.com/UndergroundAlzar/Aiko-Browser/releases/download/v0.5.1/Aiko-Browser-0.5.1.apk) · [SHA256SUMS.txt](https://github.com/UndergroundAlzar/Aiko-Browser/releases/download/v0.5.1/SHA256SUMS.txt) · [Release notes](https://github.com/UndergroundAlzar/Aiko-Browser/releases/tag/v0.5.1) · [简体中文](../README.md)

## Features and controls

| Feature | How it works |
| --- | --- |
| [Home and reading shelf](CUSTOMIZATION.md#home) | Native home and new tabs open with a single search field. In **主页与定制**, turn off **原生页仅显示搜索框** to show shortcuts and the reading shelf. Shelf entries can include an offline MHTML snapshot. |
| [Babel translation](CUSTOMIZATION.md#babel) | Enter `/babel` for bilingual reading. On streaming pages, each changed paragraph waits for 240 ms of inactivity before translation. Stale replies are discarded. Choose from five display styles, translate selections or translate text in an input field. |
| [Site filtering](CUSTOMIZATION.md#filtering) | Use the address-bar shield for per-site settings. Service Worker requests use site rules and exceptions when the source site is identifiable. Requests from an unknown source pass through. |
| [Downloads](CUSTOMIZATION.md#downloads) | Files download one at a time in queue order. A single file can use up to 128 parallel byte ranges when the server supports them. Open **下载列表** for the active file, waiting files and the last 30 completions. |
| [Display and preconnect](CUSTOMIZATION.md#performance) | On supported Android 12+ devices, short interaction windows sample actual UI-thread frame work for Android performance hints. HTTPS preconnect uses the installed WebView’s supported APIs. |
| [Backup and restore](CUSTOMIZATION.md#backup) | Portable backups omit Babel API keys and extra request headers. Choose password encryption to migrate them. Restore supports older v1 backups and relocates available offline reading snapshots. |
| [Userscripts and page tools](CUSTOMIZATION.md#tools) | Install scripts from a URL or a local file. The built-in developer tools inspect the DOM, console output and page requests. |

## Install, build and verify

Before installation, compare the APK’s SHA-256 with its entry in the release checksum file. Android requires a compatible signing certificate for an in-place update. To build on Windows, use PowerShell 7, JDK 21, Android SDK Platform 36 and Build Tools 36.0.0. With `JAVA_HOME` and `ANDROID_HOME` set, run `pwsh -NoProfile -File ./build.ps1`. The host suite also needs Node.js, a JVM JSON library and Chromium for browser checks. [Build instructions, test paths and verification commands (Chinese)](BUILD.md)

**Historical device result from v0.5.0.** A OnePlus PMS110 recorded a 140 ms cold start with `am start -W` in 185 Hz display mode. No new phone measurement is available for v0.5.1. [v0.5.0 measurements](https://github.com/UndergroundAlzar/Aiko-Browser/blob/v0.5.0/README.md)

Author Alzar · [Artwork gallery](art/README.md) · [Filter list notices](../app/src/main/assets/filters/NOTICE.txt) · [AndroidX notices](../app/src/main/assets/notices/NOTICE.txt)
