#  保留全包
-keep class com.batterywhitelist.** { *; }
-keep interface com.batterywhitelist.** { *; }
-keep enum com.batterywhitelist.** { *; }

# 忽略 Kotlin 和 AndroidX 的警告
-dontwarn kotlin.**
-dontwarn org.jetbrains.**
-dontwarn androidx.**
-dontwarn io.github.libxposed.**