# Preserve manifest components, JS bridges, and WebKit cross-ClassLoader boundaries
# while enabling R8 bytecode optimization and unused-code shrinking.
-keep class app.aikobrowser.MainActivity { *; }
-keep class app.aikobrowser.PrivateActivity { *; }
-keep class app.aikobrowser.DownloadService { *; }
-keepattributes RuntimeVisibleAnnotations
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Protect AndroidX WebKit and Chromium support-library boundary reflection adapters.
-keep class org.chromium.support_lib_boundary.** { *; }
-keep,allowshrinking class androidx.webkit.** { *; }

-dontobfuscate
-dontwarn kotlin.**
-dontwarn org.jspecify.**
