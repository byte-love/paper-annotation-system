#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import paramiko
import os
import sys

# 设置 Windows 控制台编码
if sys.platform == 'win32':
    import io
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8')

# 服务器配置
HOST = '127.0.0.1'
PORT = 22
USERNAME = 'root'
PASSWORD = os.environ.get('SSH_PASSWORD', '')

if not PASSWORD:
    print("错误: 请设置环境变量 SSH_PASSWORD")
    print("使用方法: SSH_PASSWORD='your_password' python auto_deploy.py")
    sys.exit(1)

# 本地文件路径
LOCAL_JAR = 'backend/target/journal-system-1.0.0.jar'
LOCAL_SCRIPT = 'deploy.sh'

print("=" * 60)
print("期刊论文管理系统 - 自动部署脚本")
print("=" * 60)

# 检查本地文件
if not os.path.exists(LOCAL_JAR):
    print(f"错误: 找不到 JAR 文件: {LOCAL_JAR}")
    sys.exit(1)

if not os.path.exists(LOCAL_SCRIPT):
    print(f"错误: 找不到部署脚本: {LOCAL_SCRIPT}")
    sys.exit(1)

print(f"\n[OK] 本地文件检查完成")
print(f"  JAR: {LOCAL_JAR} ({os.path.getsize(LOCAL_JAR) / 1024 / 1024:.1f} MB)")
print(f"  脚本: {LOCAL_SCRIPT}")

try:
    # 连接服务器
    print(f"\n正在连接服务器 {HOST}...")
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(HOST, PORT, USERNAME, PASSWORD, timeout=10)
    print("[OK] SSH 连接成功")
    
    # 创建 SFTP 客户端
    sftp = ssh.open_sftp()
    
    # 上传 JAR 文件
    print(f"\n正在上传 JAR 文件...")
    remote_jar = '/tmp/journal-system-1.0.0.jar'
    sftp.put(LOCAL_JAR, remote_jar)
    print(f"[OK] JAR 已上传至 {remote_jar}")
    
    # 上传部署脚本
    print(f"\n正在上传部署脚本...")
    remote_script = '/tmp/deploy.sh'
    sftp.put(LOCAL_SCRIPT, remote_script)
    print(f"[OK] 脚本已上传至 {remote_script}")
    
    # 查找旧部署位置（决定 .env 放哪，与 deploy.sh 的查找逻辑一致）
    print(f"\n正在查找旧部署位置...")
    stdin, stdout, stderr = ssh.exec_command('find /opt /home /root /usr/local -name "journal-system*.jar" 2>/dev/null | head -1')
    old_jar = stdout.read().decode().strip()

    if old_jar:
        deploy_dir = os.path.dirname(old_jar)
        print(f"[OK] 找到旧部署: {old_jar}")
    else:
        deploy_dir = '/opt/journal-system'
        print(f"  未找到旧部署，将使用默认路径 {deploy_dir}")

    # 写 .env（数据库密码等，不入版本库）
    # 密码从本机环境变量读取；未设置时跳过，应用能启动但「上传外网」连不上 150
    sql_password = os.environ.get('EXTERNAL_SQL_PASSWORD', '')
    if sql_password:
        print(f"\n正在写入 {deploy_dir}/.env ...")
        ssh.exec_command(f'mkdir -p {deploy_dir}')[1].read()
        env_content = (
            f"EXTERNAL_SQL_HOST={os.environ.get('EXTERNAL_SQL_HOST', '127.0.0.1')}\n"
            f"EXTERNAL_SQL_PORT={os.environ.get('EXTERNAL_SQL_PORT', '1433')}\n"
            f"EXTERNAL_SQL_USER={os.environ.get('EXTERNAL_SQL_USER', 'sa')}\n"
            f"EXTERNAL_SQL_PASSWORD={sql_password}\n"
            f"EXTERNAL_SQL_DATABASE={os.environ.get('EXTERNAL_SQL_DATABASE', 'JournalSystem')}\n"
        )
        with sftp.open(f'{deploy_dir}/.env', 'w') as f:
            f.write(env_content)
        sftp.chmod(f'{deploy_dir}/.env', 0o600)
        print(f"[OK] .env 已写入（权限 600），密码未回显")
    else:
        print(f"\n[!] 未设置 EXTERNAL_SQL_PASSWORD，跳过 .env")
        print(f"    应用可正常启动，但「上传外网」无法连接 数据库")
        print(f"    需要时用法: EXTERNAL_SQL_PASSWORD='xxx' SSH_PASSWORD='xxx' python auto_deploy.py")

    sftp.close()

    # 检查 Java 版本
    print(f"\n检查 Java 环境...")
    stdin, stdout, stderr = ssh.exec_command('java -version 2>&1 | head -1')
    java_version = stdout.read().decode().strip()
    print(f"  {java_version}")
    
    # 执行部署脚本
    print(f"\n执行部署脚本...")
    print("-" * 60)
    stdin, stdout, stderr = ssh.exec_command('cd /tmp && chmod +x deploy.sh && ./deploy.sh')
    
    # 实时输出部署日志
    for line in stdout:
        print(line.strip())
    
    # 检查错误输出
    error_output = stderr.read().decode().strip()
    if error_output:
        print(f"\n警告信息:\n{error_output}")
    
    print("-" * 60)
    
    # 验证服务状态
    print(f"\n验证服务状态...")
    stdin, stdout, stderr = ssh.exec_command('ps aux | grep "journal-system" | grep -v grep')
    process_info = stdout.read().decode().strip()
    
    if process_info:
        print("[OK] 服务运行中:")
        print(f"  {process_info}")
        print(f"\n[OK] 部署完成！")
        print(f"  访问地址: http://127.0.0.1:8080")
    else:
        print("[ERROR] 服务未运行，请检查日志")
    
    ssh.close()
    
except paramiko.AuthenticationException:
    print(f"\n[ERROR] 认证失败，请检查用户名和密码")
    sys.exit(1)
except paramiko.SSHException as e:
    print(f"\n[ERROR] SSH 连接错误: {e}")
    sys.exit(1)
except Exception as e:
    print(f"\n[ERROR] 部署失败: {e}")
    import traceback
    traceback.print_exc()
    sys.exit(1)
