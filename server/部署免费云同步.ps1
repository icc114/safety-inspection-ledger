$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

Write-Host "安全检查台账 v3.1 - 免费云同步部署" -ForegroundColor Cyan
Write-Host "本脚本只创建 Cloudflare Worker 和 D1 免费资源，不创建 R2 或付费套餐。" -ForegroundColor Yellow
Write-Host ""

if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
  throw "未检测到 Node.js。请先从 https://nodejs.org 安装 LTS 版本，然后重新双击本脚本。"
}

Write-Host "[1/5] 安装部署工具..." -ForegroundColor Cyan
& npm install
if ($LASTEXITCODE -ne 0) { throw "部署工具安装失败" }

Write-Host "[2/5] 登录 Cloudflare。浏览器打开后，请使用自己的免费账号确认授权..." -ForegroundColor Cyan
& npx wrangler login
if ($LASTEXITCODE -ne 0) { throw "Cloudflare 登录未完成" }

$databaseName = "safety-inspection-ledger"
Write-Host "[3/5] 检查免费 D1 数据库..." -ForegroundColor Cyan
$databaseListRaw = (& npx wrangler d1 list --json | Out-String)
$databaseList = $databaseListRaw | ConvertFrom-Json
$database = $databaseList | Where-Object { $_.name -eq $databaseName } | Select-Object -First 1
if (-not $database) {
  & npx wrangler d1 create $databaseName
  if ($LASTEXITCODE -ne 0) { throw "D1 数据库创建失败" }
  $databaseListRaw = (& npx wrangler d1 list --json | Out-String)
  $databaseList = $databaseListRaw | ConvertFrom-Json
  $database = $databaseList | Where-Object { $_.name -eq $databaseName } | Select-Object -First 1
}
if (-not $database.uuid) { throw "未能取得 D1 database_id" }

$configPath = Join-Path $PSScriptRoot "wrangler.jsonc"
$configText = Get-Content $configPath -Raw
$configText = [regex]::Replace($configText, '"database_id"\s*:\s*"[^"]+"', '"database_id": "' + $database.uuid + '"')
[System.IO.File]::WriteAllText($configPath, $configText, (New-Object System.Text.UTF8Encoding($false)))

Write-Host "[4/5] 初始化数据库..." -ForegroundColor Cyan
& npx wrangler d1 execute $databaseName --remote --file=schema.sql
if ($LASTEXITCODE -ne 0) { throw "数据库初始化失败" }

Write-Host "[5/5] 发布云同步服务..." -ForegroundColor Cyan
$deployOutput = (& npx wrangler deploy 2>&1 | Out-String)
Write-Host $deployOutput
if ($LASTEXITCODE -ne 0) { throw "云同步服务发布失败" }

$urlMatch = [regex]::Match($deployOutput, 'https://[^\s]+\.workers\.dev')
if (-not $urlMatch.Success) {
  throw "部署已执行，但没有从输出中找到 workers.dev 地址。请查看上方输出。"
}
$endpoint = $urlMatch.Value.TrimEnd('/')
$resultPath = Join-Path $PSScriptRoot "云同步地址.txt"
[System.IO.File]::WriteAllText($resultPath, "安全检查台账云同步地址：`r`n$endpoint`r`n", (New-Object System.Text.UTF8Encoding($false)))

Write-Host ""
Write-Host "部署完成：$endpoint" -ForegroundColor Green
Write-Host "请在安卓或 Windows 软件首页点击“设置云同步”，填入这个地址。" -ForegroundColor Green
Write-Host "地址也已保存到：$resultPath" -ForegroundColor Green
