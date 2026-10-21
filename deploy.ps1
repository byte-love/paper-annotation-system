# 期刊论文管理系统 - 一键部署 (PowerShell)
# 在 backend 目录下运行: powershell -File ..\deploy.ps1

$Server = "127.0.0.1"
$User = "root"
$JarLocal = ".\target\journal-system-1.0.0.jar"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  期刊论文管理系统 - 一键部署" -ForegroundColor Cyan
Write-Host "  目标: ${Server}" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

if (-not (Test-Path $JarLocal)) {
    Write-Host "ERROR: $JarLocal not found" -ForegroundColor Red
    exit 1
}

Write-Host "[1/3] Upload jar (41MB)... enter server password" -ForegroundColor Yellow
scp -o StrictHostKeyChecking=no $JarLocal ${User}@${Server}:/tmp/journal-system-1.0.0.jar
if ($LASTEXITCODE -ne 0) { Write-Host "Upload failed" -ForegroundColor Red; exit 1 }
Write-Host "Upload OK" -ForegroundColor Green

Write-Host "[2/3] Deploy... enter server password" -ForegroundColor Yellow
$remoteCmd = @'
mkdir -p /opt/journal-system
OLD_PID=$(ps -ef | grep journal-system | grep -v grep | awk "{print \$2}")
if [ -n "$OLD_PID" ]; then kill $OLD_PID 2>/dev/null; sleep 2; echo "stopped: $OLD_PID"; fi
mv /tmp/journal-system-1.0.0.jar /opt/journal-system/
cd /opt/journal-system
if [ -f .env ]; then
  set -a
  . ./.env
  set +a
  echo "已加载 .env 配置"
else
  echo "WARNING: 未找到 /opt/journal-system/.env，「上传外网」将拿空密码连 数据库"
  echo "         参考 .env.example 创建该文件并填入密码（chmod 600）"
fi
nohup java -jar journal-system-1.0.0.jar --spring.profiles.active=prod > app.log 2>&1 &
sleep 5
ps -ef | grep journal-system | grep -v grep
ss -tlnp | grep 8080
'@

ssh -o StrictHostKeyChecking=no ${User}@${Server} $remoteCmd
if ($LASTEXITCODE -ne 0) { Write-Host "Deploy failed" -ForegroundColor Red; exit 1 }
Write-Host "Deploy OK" -ForegroundColor Green

Write-Host "[3/3] Verify..." -ForegroundColor Yellow
Start-Sleep 3
try {
    $r = Invoke-RestMethod "http://${Server}:8080/api/papers/collections" -TimeoutSec 5
    Write-Host "API OK: $r" -ForegroundColor Green
} catch {
    Write-Host "API may still be starting..." -ForegroundColor Yellow
}

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  DONE! http://${Server}:8080" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Cyan
