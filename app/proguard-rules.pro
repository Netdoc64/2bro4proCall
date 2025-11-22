# 2bro4Call ProGuard Rules für Release-Builds

# WebRTC - Verhindere Obfuscation von nativen Methoden
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# OkHttp & OkIO
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Firebase
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# Security-Crypto (EncryptedSharedPreferences)
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# Kotlin Coroutines (falls später hinzugefügt)
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Material Components
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**

# RecyclerView
-keep class androidx.recyclerview.widget.** { *; }

# JSON Parsing (org.json)
-keepattributes *Annotation*
-keepclassmembers class * {
    @org.json.** <methods>;
}

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep custom Application class
-keep public class com.x2bro4pro.bro4call.** { *; }

# ViewBinding
-keep class * implements androidx.viewbinding.ViewBinding {
    public static *** bind(android.view.View);
    public static *** inflate(android.view.LayoutInflater);
}

# Remove Logging in Release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
