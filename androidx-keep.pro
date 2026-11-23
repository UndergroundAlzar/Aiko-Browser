# Preserve application components and behavior while keeping the WebKit dependency small.
-keep class app.aikobrowser.** { *; }
-dontoptimize
-dontobfuscate
-dontwarn kotlin.**
