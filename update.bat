@echo off
REM 期刊论文管理系统 - 一键更新部署
REM 使用: 在 backend 目录下运行 ..\update.bat

echo ========================================
echo   期刊论文管理系统 - 一键更新部署
echo ========================================
echo.

set SERVER=127.0.0.1
set USER=root
set JAVA=/usr/lib/jvm/java-11-openjdk-11.0.23.0.9-2.el7_9.x86_64/bin/java

REM 1. 重新打包
echo [1/4] 重新打包...
call mvn package -DskipTests -q
if errorlevel 1 (
    echo ❌ 打包失败
    exit /b 1
)
echo ✅ 打包完成

REM 2. 上传到服务器
echo.
echo [2/4] 上传到服务器（输入服务器密码）...
scp target\journal-system-1.0.0.jar %USER%@%SERVER%:/tmp/
if errorlevel 1 (
    echo ❌ 上传失败
    exit /b 1
)
echo ✅ 上传完成

REM 3. 停止旧进程
echo.
echo [3/4] 停止旧进程（输入服务器密码）...
ssh %USER%@%SERVER% "ps -ef | grep journal-system | grep -v grep | awk '{print $2}' | xargs -r kill 2>/dev/null; sleep 2; echo stopped"

REM 4. 启动新版本
echo.
echo [4/4] 启动新版本（输入服务器密码）...
ssh %USER%@%SERVER% "mv /tmp/journal-system-1.0.0.jar /opt/journal-system/ && nohup %JAVA% -jar /opt/journal-system/journal-system-1.0.0.jar --spring.profiles.active=prod > /opt/journal-system/app.log 2>&1 & sleep 5 && ps -ef | grep journal-system | grep -v grep && ss -tlnp | grep 8080"

if errorlevel 1 (
    echo ❌ 启动失败
    exit /b 1
)

echo.
echo ========================================
echo   ✅ 更新完成！
echo   访问: http://%SERVER%:8080
echo ========================================
pause
