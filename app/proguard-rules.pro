-keep class com.batterywhitelist.** { *; }


-dontwarn kotlin.**
-dontwarn org.jetbrains.**
-dontwarn androidx.**


-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}