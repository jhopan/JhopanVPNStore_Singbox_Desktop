# VpnService subclass — instantiated by Android system via AndroidManifest
-keep class com.jhopanstore.vpn.service.JhopanVpnService { *; }

# Main entry point activity
-keep class com.jhopanstore.vpn.MainActivity { *; }

# ─── sing-box (libbox.aar) Core Protections ───────────────────────────────────
# libbox is a Go library via gomobile - keep all libbox classes intact
-keep class libbox.** { *; }
-keep interface libbox.** { *; }
-keepclassmembers class libbox.** { *; }
-keepclasseswithmembernames class libbox.** { 
    native <methods>; 
}

# Go mobile reflection — don't obfuscate Go bridge interfaces
-keep class go.** { *; }

# ─── Jetpack Compose & Material3 ──────────────────────────────────────────────
-keep class androidx.compose.** { *; }
-keep interface androidx.compose.** { *; }

# ─── Android System Classes ────────────────────────────────────────────────────
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keep class * extends android.app.Service
-keep class * extends android.app.Activity
-keep class * extends androidx.fragment.app.Fragment

# ─── Kotlin & Coroutines ──────────────────────────────────────────────────────
-keepclassmembers class ** {
    *** lambda*(...);
}
-keep class kotlin.reflect.** { *; }
-keep interface kotlin.reflect.** { *; }

# ─── Custom VPN Classes ───────────────────────────────────────────────────────
-keep class com.jhopanstore.vpn.** { *; }

# ─── Remove unused code ───────────────────────────────────────────────────────
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}

