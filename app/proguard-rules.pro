# ============================================================================
# EmojiExporter ProGuard 规则
# ============================================================================

# ---------- 通用：保留调试信息，便于 crash 定位 ----------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault, Exceptions

# ---------- 1. LSPosed / Xposed 入口 ----------
# HookEntry 是 xposed java_init.list 声明的入口，必须保留类名和无参构造
-keep class top.brokestar.emojiexporter.lsposed.HookEntry { <init>(); }
# module.prop / scope.list 等资源已由 AGP 打包，不参与混淆
-keep class top.brokestar.emojiexporter.lsposed.** { *; }

# ---------- 2. IPC ContentProvider ----------
-keep class top.brokestar.emojiexporter.lsposed.IpcBridge { <init>(); }

# ---------- 3. 反射用到的 QQ 内部类（避免被误删，虽非本工程类但 ProGuard 不会动外部类，此处仅保险） ----------
# Reflect.kt 全部走 java.lang.reflect，不依赖编译期类名，无需 keep
# KavaRef 引用了 java.lang.reflect.AnnotatedType（API 35 加入 SDK），低版本缺失，忽略警告
-dontwarn java.lang.reflect.AnnotatedType

# ---------- 4. YukiHookAPI ----------
-keep class com.highcapable.yukihookapi.** { *; }
-keep @com.highcapable.yukihookapi.annotation.core.annotation.YukiHookInject class * { *; }
-keepclassmembers class * {
    @com.highcapable.yukihookapi.annotation.core.annotation.* <methods>;
}
-dontwarn com.highcapable.yukihookapi.**

# ---------- 5. KavaRef ----------
-keep class com.kavaref.** { *; }
-dontwarn com.kavaref.**

# ---------- 6. nanohttpd ----------
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**

# ---------- 7. OkHttp / Okio ----------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
# OkHttp 使用反射加载平台特定 TLS
-keep class okhttp3.internal.platform.** { *; }

# ---------- 8. Coil ----------
-keep class coil.** { *; }
-keep class coil.core.** { *; }
# Coil 的 kotlin metadata
-keepclassmembers class coil.MapExtraData { *; }
-dontwarn coil.**

# ---------- 9. Kotlin 协程 / 反射 / 元数据 ----------
-keepclassmembers class kotlin.coroutines.jvm.internal.BaseContinuationImpl { *; }
-keepclassmembers @kotlin.Metadata class * { *; }
-dontwarn kotlin.**
# 保留 Kotlin Metadata，反射库依赖
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault
-keep @kotlin.Metadata class * { *; }

# ---------- 10. AndroidX 保留（通常自带 consumer rules，此处补强） ----------
-keep class androidx.appcompat.** { *; }
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**
-dontwarn org.jetbrains.annotations.**

# ---------- 11. QQ ContentProvider 调用（AUTHORITY 字符串保留） ----------
-keepclassmembers class top.brokestar.emojiexporter.data.LsposedBridge {
    public static final java.lang.String AUTHORITY_QQ;
}

# ---------- 12. libsu (root shell) ----------
-keep class com.topjohnwu.superuser.** { *; }
-dontwarn com.topjohnwu.superuser.**

# ---------- 13. JNI / native（本项目无，占位） ----------
# -keepclasseswithmembernames class * { native <methods>; }

# ---------- 14. 数据类（JSON 序列化/Parcelable 保留字段名） ----------
-keepclassmembers class top.brokestar.emojiexporter.data.** { <fields>; }
-keepclassmembers class top.brokestar.emojiexporter.export.** { <fields>; }

# ---------- 15. 优化项 ----------
# 合并重复字符串（减小体积）
-optimizationpasses 5
-allowaccessmodification
-overloadaggressively
# 关闭一些会导致反射问题的优化
-dontoptimize
