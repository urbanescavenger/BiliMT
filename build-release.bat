@echo off
setlocal

cd /d "%~dp0"

if "%~1"=="" (
  set "TARGET_ABI=armeabi-v7a"
) else (
  set "TARGET_ABI=%~1"
)

if "%ANDROID_HOME%"=="" (
  set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
)
if "%ANDROID_SDK_ROOT%"=="" (
  set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
)

echo Building BiliMT release APK...
echo Target ABI: %TARGET_ABI%
echo Android SDK: %ANDROID_HOME%
echo.

call "%~dp0gradlew.bat" :app:assembleRelease -PtargetAbi=%TARGET_ABI%
if errorlevel 1 goto failed

echo.
echo Release APK:
echo %USERPROFILE%\.gradle\bilitv-native-build\app\outputs\apk\release\app-release.apk
echo.
echo Cleaning local build markers from repository...
if exist ".gradle" rmdir /s /q ".gradle"
if exist ".kotlin" rmdir /s /q ".kotlin"
if exist "build" rmdir /s /q "build"
if exist "app\build" rmdir /s /q "app\build"

echo Done.
if not "%CI%"=="1" pause
exit /b 0

:failed
echo.
echo Build failed.
if not "%CI%"=="1" pause
exit /b 1
