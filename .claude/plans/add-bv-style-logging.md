# Plan: Add BV-style logging to BiliTVNative

## Goal
Mirror the logging approach used in the BV source so BiliTVNative gets structured Kotlin logging plus automatic crash-log capture, without pulling in Firebase or other BV-only services.

## What BV does
1. Uses `io.github.oshai:kotlin-logging-jvm` for a `KotlinLogging.logger { }` API.
2. Binds SLF4J to Android logs via `com.gitlab.mvysny.slf4j:slf4j-handroid`.
3. In `BVApp.onCreate()` sets `HandroidLoggerAdapter.DEBUG = BuildConfig.DEBUG` and installs `LogCatcherUtil`.
4. `LogCatcherUtil` sets an uncaught-exception handler that dumps the last 10,000 logcat lines to a file, along with device/app info, and keeps only the newest 10 crash/manual log files.

## Changes for BiliTVNative

### 1. Dependencies
- `gradle/libs.versions.toml`: add `logging = 7.0.7` and `slf4j-android-mvysny = 2.0.13`, plus the two library entries.
- `app/build.gradle.kts`: add `implementation(libs.logging)` and `implementation(libs.slf4j.android.mvysny)`.

### 2. Enable BuildConfig
- `app/build.gradle.kts`: add `buildConfig = true` inside `buildFeatures` so `BuildConfig.DEBUG` is available for the SLF4J adapter.

### 3. New `LogCatcherUtil`
- Create `app/src/main/java/com/kirin/mt/core/util/LogCatcherUtil.kt` adapted from BV:
  - `installLogCatcher()` clears logcat, installs an uncaught-exception handler, and prunes old files.
  - `logLogcat(manual: Boolean = false)` dumps up to 10,000 logcat lines into `filesDir/crash_logs/logs_<manual|crash>_<timestamp>.log`.
  - Writes device info (app version, Android version, device/model/manufacturer/etc.).
  - Writes app info/prefs summary where available (login state, API type, quality prefs, etc.).
  - Keeps at most 10 manual and 10 crash log files.
- Because BiliTVNative does not have Firebase, the Firebase-specific logging extensions (`KLoggerExtends.kt`) are omitted.

### 4. Initialize in application
- `app/src/main/java/com/kirin/mt/BiliTvApplication.kt`:
  - Import `HandroidLoggerAdapter` and `LogCatcherUtil`.
  - Set `HandroidLoggerAdapter.DEBUG = BuildConfig.DEBUG` before other initialization.
  - Call `LogCatcherUtil.installLogCatcher()`.

### 5. ProGuard / R8 rules
- `app/proguard-rules.pro`: add BV’s `-dontwarn` rules for `ch.qos.logback.**` so R8 does not complain about classes referenced by kotlin-logging but unused on Android.

### 6. Convert existing `android.util.Log` calls (optional first pass)
- The files currently using `android.util.Log` can keep working; they are compatible with logcat capture. We will leave them as-is for now to keep the change focused. Future refactors can migrate them to `KotlinLogging`.

## Verification
- Cloud CI build must pass after the change (no missing classes, no R8 errors).
- A manual crash test would confirm the uncaught-exception handler writes a `logs_crash_*.log` file, but that requires a runtime device/emulator; CI compilation is the primary check we can do here.

## Out of scope
- Firebase Crashlytics / analytics integration.
- Replacing every existing `android.util.Log` call in one sweep.
- A UI for exporting/sharing log files.
