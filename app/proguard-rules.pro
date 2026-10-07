#  保留全包
-keep class com.batterywhitelist.** { *; }
-keep interface com.batterywhitelist.** { *; }
-keep enum com.batterywhitelist.** { *; }
-keep class android.support.v4.content.FileProvider { *; }
-keep class * extends android.support.v4.content.FileProvider { *; }
# 忽略 Kotlin 和 AndroidX 的警告
-dontwarn kotlin.**
-dontwarn org.jetbrains.**
-dontwarn androidx.**
-dontwarn io.github.libxposed.**