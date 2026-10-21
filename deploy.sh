#!/bin/bash
# 期刊系统部署脚本 - 生产环境

echo "===== 期刊论文管理系统部署脚本 ====="

# 1. 查找旧的部署位置
echo "正在查找旧部署..."
OLD_JAR=$(find /opt /home /root /usr/local -name "journal-system*.jar" 2>/dev/null | head -1)

if [ -n "$OLD_JAR" ]; then
    DEPLOY_DIR=$(dirname "$OLD_JAR")
    echo "找到旧部署: $OLD_JAR"
else
    # 默认部署到 /opt
    DEPLOY_DIR="/opt/journal-system"
    echo "未找到旧部署，使用默认路径: $DEPLOY_DIR"
fi

# 2. 检查 Java 版本
echo ""
echo "检查 Java 环境..."
java -version 2>&1 | head -1

# 3. 停止旧服务
echo ""
echo "停止旧服务..."
OLD_PID=$(ps aux | grep "journal-system.*\.jar" | grep -v grep | awk '{print $2}')
if [ -n "$OLD_PID" ]; then
    echo "发现运行中的进程 PID: $OLD_PID"
    kill -15 $OLD_PID
    sleep 3
    # 强制杀死（如果还在运行）
    if ps -p $OLD_PID > /dev/null; then
        kill -9 $OLD_PID
        echo "强制停止进程"
    fi
    echo "服务已停止"
else
    echo "未发现运行中的服务"
fi

# 4. 备份旧版本
if [ -f "$DEPLOY_DIR/journal-system-1.0.0.jar" ]; then
    echo ""
    echo "备份旧版本..."
    cp "$DEPLOY_DIR/journal-system-1.0.0.jar" "$DEPLOY_DIR/journal-system-1.0.0.jar.bak.$(date +%Y%m%d%H%M%S)"
fi

# 5. 创建部署目录
mkdir -p "$DEPLOY_DIR"
mkdir -p "$DEPLOY_DIR/logs"

# 6. 复制新版本
echo ""
echo "部署新版本到 $DEPLOY_DIR ..."
cp journal-system-1.0.0.jar "$DEPLOY_DIR/"
chmod +x "$DEPLOY_DIR/journal-system-1.0.0.jar"

# 7. 加载环境变量（数据库密码等，不入版本库）
cd "$DEPLOY_DIR"
if [ -f .env ]; then
    set -a
    . ./.env
    set +a
    echo "已加载 .env 配置"
else
    echo "⚠ 未找到 $DEPLOY_DIR/.env，「上传外网」功能将无法连接 数据库"
    echo "  参考 .env.example 创建该文件并填入密码"
fi

# 8. 启动服务
echo ""
echo "启动服务 (生产环境 prod profile)..."
nohup java -jar journal-system-1.0.0.jar --spring.profiles.active=prod > logs/app.log 2>&1 &

sleep 5

# 9. 检查启动状态
NEW_PID=$(ps aux | grep "journal-system.*\.jar" | grep -v grep | awk '{print $2}')
if [ -n "$NEW_PID" ]; then
    echo ""
    echo "✓ 服务启动成功！"
    echo "  PID: $NEW_PID"
    echo "  部署目录: $DEPLOY_DIR"
    echo "  访问地址: http://127.0.0.1:8080"
    echo "  日志文件: $DEPLOY_DIR/logs/app.log"
    echo ""
    echo "查看实时日志: tail -f $DEPLOY_DIR/logs/app.log"
else
    echo ""
    echo "✗ 服务启动失败，请查看日志: $DEPLOY_DIR/logs/app.log"
    exit 1
fi
