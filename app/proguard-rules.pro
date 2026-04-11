-keepattributes JavascriptInterface
-keepclassmembers class com.hermes.app.WebAppInterface {
    @android.webkit.JavascriptInterface <methods>;
}

-keep class com.hermes.app.WebAppInterface { *; }

# WebView
-keepclassmembers class * extends android.webkit.WebViewClient {
    public void *(android.webkit.WebView, java.lang.String, android.graphics.Bitmap);
    public boolean *(android.webkit.WebView, java.lang.String);
    public void *(android.webkit.WebView, java.lang.String);
}

# Keep Kotlin metadata
-keep class kotlin.Metadata { *; }
