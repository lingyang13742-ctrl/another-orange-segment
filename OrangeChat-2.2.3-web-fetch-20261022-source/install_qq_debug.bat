@echo off
REM 一键卸载并安装最新 debug APK，用于排查设备上跑旧代码的问题。
setlocal
set "ADB=C:\Users\diant\AppData\Local\Android\Sdk\platform-tools\adb.exe"
set "PKG=me.rerere.rikkahub.debug"
set "APK=app\build\outputs\apk\debug\app-arm64-v8a-debug.apk"

echo === 设备列表 ===
"%ADB%" devices
echo.

echo === 当前设备上的安装信息 ===
"%ADB%" shell pm path %PKG%
"%ADB%" shell dumpsys package %PKG% | findstr /C:"versionCode" /C:"versionName" /C:"lastUpdateTime" /C:"firstInstallTime" /C:"codePath"
echo.

echo === 卸载旧包 ===
"%ADB%" uninstall %PKG%
echo.

echo === 安装新 APK ===
"%ADB%" install -r "%APK%"
echo.

echo === 安装后再次确认 ===
"%ADB%" shell pm path %PKG%
"%ADB%" shell dumpsys package %PKG% | findstr /C:"versionCode" /C:"lastUpdateTime"
echo.
echo 完成。现在到 App 里重新打开 QQ Bot 开关，再观察 logcat。
endlocal
pause
