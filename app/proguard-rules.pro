# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep DataStore preferences keys
-keep class dev.screenshoteditor.data.SettingsDataStore$** { *; }

# Keep Parcelable implementations
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Keep enum classes
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Strip verbose/debug/info/warning logging from release builds.
# Security Note: log messages can carry file names and exception details; release builds should not
# write them to logcat. Log.e/Log.wtf are kept so that genuine errors stay diagnosable.
# Requires the optimizing default config (proguard-android-optimize.txt), which build.gradle.kts uses.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}
