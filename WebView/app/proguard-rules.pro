# Add project specific ProGuard rules here.
# For more details, see
#   https://developer.android.com/guide/developing/tools/proguard.html

# ---------------------------------------------------------------------------
# WebView shell keep-rules. Release builds run R8 (minify + resource shrink),
# so anything reached only reflectively or from JavaScript must be kept.
# ---------------------------------------------------------------------------

# Any current or future @JavascriptInterface methods must survive obfuscation,
# or window.<name>.method() calls from the page break at runtime.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# WebView client callbacks are invoked by the framework via the WebView.
-keep class * extends android.webkit.WebViewClient { *; }
-keep class * extends android.webkit.WebChromeClient { *; }

# App config constants are set by the build pipeline and read at runtime; keep
# them intact so shrinking never drops a value the app depends on.
-keep class com.BHG.webview.AppConfig { *; }

# Keep line numbers for readable crash traces in released builds.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
