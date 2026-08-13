@echo off
chcp 65001 >nul
title 安全检查台账 - 部署免费云同步
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0部署免费云同步.ps1"
echo.
pause
