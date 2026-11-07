-keep class com.boss.nexu.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
