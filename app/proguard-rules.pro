# ─── App Entry Points (Keep, Don't Obfuscate) ─────────────────────────────────
# VpnService — instantiated by Android system via AndroidManifest
-keep class com.jhopanstore.vpn.service.JhopanVpnService { *; }

# Main activity — entry point
-keep class com.jhopanstore.vpn.MainActivity { *; }

# ViewModel — referenced via reflection by Compose
-keep class com.jhopanstore.vpn.ui.MainViewModel { *; }

# ─── sing-box (libbox.aar) Core Protections ───────────────────────────────────
# libbox is a Go library via gomobile - keep all libbox classes intact
-keep class libbox.** { *; }
-keep interface libbox.** { *; }
-keepclassmembers class libbox.** { *; }

# Go mobile reflection — don't obfuscate Go bridge interfaces
-keep class go.** { *; }

# ─── Android Manifest & System Callbacks ──────────────────────────────────────
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keep class * extends android.app.Service
-keep class * extends android.app.Activity

# ─── Optimization & Shrinking ─────────────────────────────────────────────────
# Remove debug logs from release builds
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# Allow aggressive optimization
-optimizationpasses 5
-dontusemixedcaseclassnames


