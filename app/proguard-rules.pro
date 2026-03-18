# VpnService subclass — instantiated by Android system via AndroidManifest
-keep class com.jhopanstore.vpn.service.JhopanVpnService { *; }

# Main entry point activity
-keep class com.jhopanstore.vpn.MainActivity { *; }

# DialerController anonymous class — passed to Go bridge across JNI boundary
-keep class * implements libXray.DialerController { *; }

# JNI bridge — native method signatures must not be renamed by R8
-keepclassmembers class com.jhopanstore.vpn.core.Tun2socksManager {
    private native <methods>;
}

# libXray (Go mobile bridge) — jangan obfuscate class JNI/reflection dari .aar
-keep class go.** { *; }
-keep class libXray.** { *; }
-keepclassmembers class libXray.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
