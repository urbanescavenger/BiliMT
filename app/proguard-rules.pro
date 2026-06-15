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

# Optional desktop/server integrations referenced by the library but unused on Android.
-dontwarn com.huaban.analysis.jieba.**
-dontwarn java.beans.**
-dontwarn java.lang.management.**
