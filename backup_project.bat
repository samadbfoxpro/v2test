@echo off
chcp 65001 >nul
title پشتیبان‌گیری خودکار از سورس پروژه اندروید

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0backup.ps1"

echo.
pause
