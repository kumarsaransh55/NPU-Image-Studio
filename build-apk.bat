@echo off
rem Builds both APKs (system + bundled NeuroPilot runtime) and copies them to ..\APK
setlocal
set VERSION=1.4
cd /d "%~dp0"
if not exist "%USERPROFILE%\gtmp" mkdir "%USERPROFILE%\gtmp"
rem Works around "Unable to establish loopback connection" from Java's Unix-socket temp path on this PC.
set JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=%USERPROFILE%\gtmp
call "%~dp0gradlew.bat" :app:assembleSystemRelease :app:assembleBundledRelease
if errorlevel 1 exit /b 1
if not exist "..\APK" mkdir "..\APK"
copy /y "app\build\outputs\apk\system\release\app-system-release.apk" "..\APK\NPU-Image-Studio-v%VERSION%.apk" >nul
copy /y "app\build\outputs\apk\bundled\release\app-bundled-release.apk" "..\APK\NPU-Image-Studio-v%VERSION%-bundled-runtime.apk" >nul
echo.
echo APKs copied to %~dp0..\APK
