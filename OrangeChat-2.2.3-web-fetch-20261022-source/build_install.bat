@echo off
setlocal
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "ADB=C:\Users\27227\AppData\Local\Android\Sdk\platform-tools\adb.exe"
set "PKG=me.rerere.rikkahub.debug"
set "APK=app\build\outputs\apk\debug\app-arm64-v8a-debug.apk"

echo === Build Debug APK ===
call gradlew.bat :app:assembleDebug
if errorlevel 1 (
    echo Build FAILED!
    pause
    exit /b 1
)
echo.
echo === Devices ===
"%ADB%" devices
echo.

REM IMPORTANT: only use install -r to preserve user data. NEVER uninstall.
REM If install -r fails with -99 / UPDATE_INCOMPATIBLE, signatures differ;
REM fix keystore config instead of uninstalling.
echo === Install (preserve data) ===
"%ADB%" install -r "%APK%"
if errorlevel 1 (
    echo.
    echo Install FAILED. If signature mismatch, unify keystore and retry.
    echo Do NOT uninstall - it wipes app data.
)
echo.
echo === Verify ===
"%ADB%" shell pm path %PKG%
echo.
echo Done.
endlocal