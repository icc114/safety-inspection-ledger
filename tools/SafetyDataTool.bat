@echo off
chcp 65001 >nul
setlocal
if "%~1"=="" goto usage
java "%~dp0SafetyDataTool.java" %*
exit /b %errorlevel%

:usage
echo 安全检查台账 PC 数据迁移工具
echo.
echo SafetyDataTool.bat info 文件.safetydata
echo SafetyDataTool.bat verify 文件.safetydata
echo SafetyDataTool.bat extract 文件.safetydata 输出目录
echo SafetyDataTool.bat pack 已解包目录 输出文件.safetydata
echo.
echo 需要安装 JDK 17 或更高版本。
pause
exit /b 2
