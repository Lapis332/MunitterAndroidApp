# No JavaScript interface is exposed. Keep only framework callbacks that R8
# cannot discover through normal references.
-keepclassmembers class * extends android.webkit.WebChromeClient {
    public *;
}
