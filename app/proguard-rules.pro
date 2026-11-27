# Layanan shell yang dimuat Shizuku lewat nama kelas, dan antarmuka AIDL-nya.
-keep class com.nex.fps.ShellService { *; }
-keep class com.nex.fps.IUserService** { *; }

# API Shizuku
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-dontwarn rikka.**
-dontwarn moe.shizuku.**

-keepattributes *Annotation*
