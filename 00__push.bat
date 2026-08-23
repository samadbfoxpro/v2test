@echo off
chcp 65001 >nul
title GitHub Push Tool - Shadow VPN
color 0B

echo ========================================================
echo        🚀 Shadow VPN - Auto Push to GitHub
echo ========================================================
echo.

echo [1/3] Staging all changed files...
git add .
echo.

echo [2/3] Checking status and committing...
git commit -m "update: shadow vpn features, smart dns, legacy android conscious, glassmorphic dock, and ui updates"
echo.

echo [3/3] Pushing to GitHub (origin main)...
echo If a login popup appears, please complete authentication.
echo.
git push origin main

if %ERRORLEVEL% EQU 0 (
    echo.
    echo ========================================================
    echo      ✅ PUSH COMPLETED SUCCESSFULLY!
    echo ========================================================
) else (
    echo.
    echo ========================================================
    echo      ⚠️ Push with default connection failed.
    echo      Attempting push via local proxy (127.0.0.1:10808)...
    echo ========================================================
    echo.
    git -c http.proxy=socks5h://127.0.0.1:10808 push origin main
    
    if %ERRORLEVEL% EQU 0 (
        echo.
        echo ========================================================
        echo      ✅ PUSH COMPLETED VIA PROXY SUCCESSFULLY!
        echo ========================================================
    ) else (
        echo.
        echo ========================================================
        echo      ❌ Push failed. Please check internet / proxy.
        echo ========================================================
    )
)

echo.
pause
