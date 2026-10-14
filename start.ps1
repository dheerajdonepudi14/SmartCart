# One-command local setup for Smart Cart (Windows PowerShell).
#   .\start.ps1            build images and start everything with Docker
#   .\start.ps1 -Dev       also install backend/frontend dependencies locally (for IDE / running without Docker)
#   .\start.ps1 -Stop      stop the stack (data is kept)
param([switch]$Dev, [switch]$Stop)

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

if ($Stop) {
    docker compose down
    exit $LASTEXITCODE
}

function Need($cmd, $hint) {
    if (-not (Get-Command $cmd -ErrorAction SilentlyContinue)) {
        Write-Host "Missing '$cmd'. $hint" -ForegroundColor Red
        exit 1
    }
}

Need docker 'Install Docker Desktop: https://www.docker.com/products/docker-desktop/'
docker info *> $null
if ($LASTEXITCODE -ne 0) {
    Write-Host 'Docker Desktop is not running. Start it, wait for "Engine running", then re-run this script.' -ForegroundColor Red
    exit 1
}

if (-not (Test-Path .env)) {
    Copy-Item .env.example .env
    Write-Host 'Created .env from .env.example (add your Razorpay keys there to take payments).' -ForegroundColor Yellow
}

if ($Dev) {
    Need mvn 'Install Maven 3.9+ and Java 17+.'
    Need npm 'Install Node.js 20.9+.'
    Write-Host '==> Backend: downloading dependencies and running tests' -ForegroundColor Cyan
    Push-Location backend; mvn -B -q verify; $code = $LASTEXITCODE; Pop-Location
    if ($code -ne 0) { exit $code }
    Write-Host '==> Frontend: installing dependencies' -ForegroundColor Cyan
    Push-Location frontend; npm ci; $code = $LASTEXITCODE; Pop-Location
    if ($code -ne 0) { exit $code }
}

Write-Host '==> Building and starting db, mailpit, backend, frontend' -ForegroundColor Cyan
docker compose up --build -d
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host '==> Waiting for the storefront' -ForegroundColor Cyan
$deadline = (Get-Date).AddMinutes(5)
do {
    Start-Sleep -Seconds 3
    try { $ok = (Invoke-WebRequest http://localhost:3000 -UseBasicParsing -TimeoutSec 3).StatusCode -eq 200 } catch { $ok = $false }
} until ($ok -or (Get-Date) -gt $deadline)

if (-not $ok) {
    Write-Host 'Frontend did not come up in 5 minutes. Check: docker compose logs backend frontend' -ForegroundColor Red
    exit 1
}

$port = (Select-String -Path .env -Pattern '^BACKEND_PORT=(\d+)' | ForEach-Object { $_.Matches[0].Groups[1].Value })
if (-not $port) { $port = 8100 }
Write-Host ''
Write-Host 'Smart Cart is running:' -ForegroundColor Green
Write-Host '  Storefront     http://localhost:3000'
Write-Host "  Swagger UI     http://localhost:$port/swagger-ui.html"
Write-Host '  Email inbox    http://localhost:8025   (every email the app sends lands here)'
Write-Host '  Admin login    see ADMIN_EMAIL / ADMIN_PASSWORD in .env'
Write-Host '  Stop           .\start.ps1 -Stop'
Start-Process http://localhost:3000
