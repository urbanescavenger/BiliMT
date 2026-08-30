# OpenCC4J builds converters and segment implementations through reflection.
# R8 can otherwise remove/rewrite no-arg constructors, which crashes after
# switching to Hong Kong/Taiwan text conversion in release builds.
-keepclassmembers class com.github.houbb.** {
    public <init>();
}

# Keep all app-side UI classes (Settings, Compose, etc.) so R8 does not
# prune reflection-sensitive lambdas in LazyColumn items. Symptoms of
# over-aggressive R8 here include "LazyList items after the 15th row
# disappear" or "SettingsToggleRow / SettingsOptionRow not rendering".
-keep class com.kirin.mt.** { *; }
-keep class com.kirin.mt.ui.** { *; }
-keepclassmembers class com.kirin.mt.ui.** { *; }
-keep class com.kirin.mt.ui.settings.** { *; }
-keepclassmembers class com.kirin.mt.ui.settings.** { *; }

# kotlin-logging / SLF4J / logback classes referenced but unused on Android.
-dontwarn ch.qos.logback.classic.Level
-dontwarn ch.qos.logback.classic.Logger
-dontwarn ch.qos.logback.classic.LoggerContext
-dontwarn ch.qos.logback.classic.spi.ILoggingEvent
-dontwarn ch.qos.logback.classic.spi.LogbackServiceProvider
-dontwarn ch.qos.logback.classic.spi.LoggingEvent

# Optional desktop/server integrations referenced by the library but unused on Android.
-dontwarn com.huaban.analysis.jieba.**
-dontwarn java.beans.**
-dontwarn java.lang.management.**

# Firebase 组件注册链:Crashlytics 等组件经 ComponentDiscovery 元数据/ServiceLoader 反射
# 装配。AGP 9 R8 full mode 会把只被元数据引用的大小类判定为未引用而删除,release 包
# (minify)在 FirebaseCrashlytics.getInstance() 抛 "FirebaseCrashlytics component is
# not present"(debug 不混淆无此问题,alpha.8 实测)。整包保护避开逐类排查。
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.tasks.** { *; }
-dontwarn com.google.firebase.**

# NewPipeExtractor fork (libre-tube) — uses reflection for extractors/playlist
# builders, ships generated protobuf (com.google.protobuf.*) and a JS engine
# (rhino) for n-decrypt/signature. Keep the whole package so R8 does not strip
# extractors referenced only by ServiceList or remove protobuf message classes.
-keep class org.schabi.newpipe.** { *; }
-keep class org.schabi.newpipe.extractor.** { *; }
# Generated protobuf messages (NewPipe bundles protobuf-java).
-keepclassmembers class com.google.protobuf.** {
    public static ** parseFrom(**);
    public static ** getDefaultInstance();
    *** newBuilder();
}
-dontwarn org.schabi.newpipe.**

