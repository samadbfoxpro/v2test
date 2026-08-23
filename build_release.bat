@echo off
setlocal

cd /d "%~dp0"

title Shadow VPN - Release Builder

echo ========================================================
echo        Shadow VPN - Build Signed Release APK
echo ========================================================
echo.

set "JAVA_HOME=C:\Program Files\Android\Android Studio1\jbr"
if not exist "%JAVA_HOME%\bin\java.exe" (
    if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" (
        set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
    )
)

echo [*] Using Java: %JAVA_HOME%
set "PATH=%JAVA_HOME%\bin;%PATH%"
echo [*] Building Release APK, please wait...
echo.

call gradlew.bat assembleRelease

if %ERRORLEVEL% equ 0 (
    echo.
    echo ========================================================
    echo  [SUCCESS] Release APK built successfully!
    echo ========================================================
    echo.
    echo APK Location:
    echo %~dp0app\build\outputs\apk\release\app-release.apk
    echo.
) else (
    echo.
    echo ========================================================
    echo  [ERROR] Build failed! Check the errors above.
    echo ========================================================
    echo.
)

echo.
pause
