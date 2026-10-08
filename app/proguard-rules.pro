# 印迹 Release · R8 规则
# 开源仓：混淆不保密源码，以体积与执行效率为主；保留行号便于崩溃还原。

# ---- 崩溃还原 ----
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes *Annotation*,Signature,Exceptions,InnerClasses,EnclosingMethod

# ---- Kotlin / Compose（常规） ----
-dontwarn org.jetbrains.annotations.**
-keepclassmembers class **$WhenMappings { <fields>; }
-keepclassmembernames class kotlinx.** { volatile <fields>; }

# ---- Penly crypto-core（见 penly/README：宿主混淆必须 keep） ----
-keep class com.beyondguo.penly.crypto.** { *; }
-dontwarn com.beyondguo.penly.crypto.**

# ---- 高德 Map+Search+Location（3dmap-location-search 聚合包） ----
-keep class com.amap.api.** { *; }
-keep class com.amap.api.services.** { *; }
-keep class com.autonavi.** { *; }
-keep class com.amap.location.** { *; }
-dontwarn com.amap.api.**
-dontwarn com.amap.api.map2d.**
-dontwarn com.amap.api.mapcore.**
-dontwarn com.amap.api.maps.**
-dontwarn com.autonavi.**
-dontwarn com.amap.location.**
# 聚合包可选/平台相关类（R8 missing_rules）
-dontwarn com.amap.ams.gnss.GnssSoftLocator
-dontwarn net.jafama.FastMath

# ---- Media3 / ExoPlayer ----
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ---- Room（entity / dao 反射） ----
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ---- Biometric ----
-keep class androidx.biometric.** { *; }

# ---- 应用自身：Manifest / 启动入口 / 应用类 ----
-keep class com.beyondguo.imprints.MainActivity { *; }
-keep class com.beyondguo.imprints.ImprintsApp { *; }
-keep class com.beyondguo.imprints.** extends android.app.Application { *; }
-keep class com.beyondguo.imprints.backup.** { *; }

# WorkManager 入口（若有）
-keep class * extends androidx.work.ListenableWorker { *; }

# ---- 通用：勿因 R8 误删 JNI / 反射（保守） ----
-keepclasseswithmembers,includedescriptorclasses class * {
    native <methods>;
}
