![Aiko on a night train, with the city lights reflected in the windows.](art/night-train.webp)

# Aiko Browser

**[Download the Android APK · v0.1.7](https://github.com/UndergroundAlzar/Aiko-Browser/releases/download/v0.1.7/Aiko-Browser-0.1.7.apk)** · [Release notes and checksum](https://github.com/UndergroundAlzar/Aiko-Browser/releases/tag/v0.1.7) · [简体中文](../README.md)

Aiko is a lightweight Android browser built on the system WebView. It opens to a single search and address field. Save a link in a background tab and it loads when you select it. For large downloads, a live map shows which parts of the file have arrived.

**Android 10 or later** · **1.70 MB** · Installs as a regular APK. The app currently uses a Chinese interface.

| Action | Where to find it |
| --- | --- |
| Edit the full address | Tap the domain in the bottom bar |
| Open a link later | Long-press a regular link and select the background-tab action |
| Open a new tab | Long-press the tab counter. A tap opens the tab list, including recently closed pages |
| Pause filtering on one site | Tap the shield next to its address |
| View downloads | Long-press the three-dot menu. Tap the segment map to inspect a byte range |

The downloader picks up to eight parallel ranges by default. You can set a per-file limit of **1–128**. Verified range transfers require a server that supports byte ranges and supplies a strong ETag. Aiko saves checkpoints and can retry individual segments. Browser-first mode briefly yields to a foreground navigation. The alternative keeps the download at full speed. This version manages one file at a time.

EasyList, EasyPrivacy and AdGuard Chinese snapshots ship with the app. You can update them from the tools menu. The filter supports selected network and site-specific cosmetic rules. A private window runs in a separate process with its own WebView data directory. [Filter notices](../app/src/main/assets/filters/NOTICE.txt)

The host-side download suite has exercised 128 simultaneous transfers and verified the entire 128 MiB output. An earlier build recorded **46.0 ± 1.0** in one user-submitted Speedometer 3.1 run on a OnePlus Ace 7 Pro. A comparable v0.1.7 run is still pending. [Windows build and test guide](BUILD.md)

| Window Seat | Signal Room |
| :---: | :---: |
| [![Aiko reading by a rainy window.](art/window-seat.webp)](art/window-seat.webp) | [![Aiko at a railway signal desk.](art/signal-room.webp)](art/signal-room.webp) |
