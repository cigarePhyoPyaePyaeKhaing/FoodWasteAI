<#
.SYNOPSIS
    FoodWaste AI - Windows Development Startup Script
    Automatically starts FoodWaste AI with remote Aiven MySQL database connectivity.
.DESCRIPTION
    Verifies Java 17+ and Maven, checks environment/Aiven DB settings without exposing secrets,
    and runs the application.
#>

param(
    [int]$Port = 8088,
    [switch]$SkipBuild = $false,
    [switch]$NoBrowser = $false
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

Write-Host ""
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "             🍃 FoodWaste AI - Startup Manager              " -ForegroundColor White
Write-Host "   Predict -> Prevent -> Redistribute -> Reduce Waste      " -ForegroundColor DarkCyan
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host ""

# Ensure Maven bin is in PATH if present in standard tools directory
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    if (Test-Path "C:\tools\apache-maven-3.9.6\bin") {
        $env:PATH = "$env:PATH;C:\tools\apache-maven-3.9.6\bin"
    }
}

# 1. Verify Java Installation (JDK 17+)
Write-Host "[1/4] Checking Java Runtime..." -ForegroundColor Yellow
try {
    $javaVerOutput = java -version 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) {
        Write-Host " [ERROR] Java is not installed or not in PATH." -ForegroundColor Red
        Write-Host " Please install OpenJDK 17 or higher: https://adoptium.net/" -ForegroundColor Yellow
        exit 1
    }
    $verMatch = [regex]::Match($javaVerOutput, 'version "(.*?)"')
    $verString = if ($verMatch.Success) { $verMatch.Groups[1].Value } else { "17+" }
    Write-Host "       ✓ Java Runtime detected: $verString" -ForegroundColor Green
} catch {
    Write-Host " [ERROR] Could not invoke 'java'. Please ensure JDK 17+ is in PATH." -ForegroundColor Red
    exit 1
}

# 2. Verify Maven Installation
Write-Host "[2/4] Checking Maven Build Tool..." -ForegroundColor Yellow
try {
    $mvnVerOutput = mvn -version 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) {
        Write-Host " [ERROR] Maven is not installed or not in PATH." -ForegroundColor Red
        Write-Host " Please install Apache Maven: https://maven.apache.org/" -ForegroundColor Yellow
        exit 1
    }
    $mvnMatch = [regex]::Match($mvnVerOutput, 'Apache Maven ([\d\.]+)')
    $mvnVer = if ($mvnMatch.Success) { $mvnMatch.Groups[1].Value } else { "3.8+" }
    Write-Host "       ✓ Apache Maven detected: $mvnVer" -ForegroundColor Green
} catch {
    Write-Host " [ERROR] Could not invoke 'mvn'. Please ensure Maven is in PATH." -ForegroundColor Red
    exit 1
}

# 3. Verify Database Configuration (Aiven Cloud MySQL Remote)
Write-Host "[3/4] Validating Database Environment..." -ForegroundColor Yellow

# If .env exists in project root, read configuration safely without exposing secrets
$envFile = Join-Path $PSScriptRoot ".env"
$envExampleFile = Join-Path $PSScriptRoot ".env.example"

if (-not (Test-Path $envFile)) {
    if (Test-Path $envExampleFile) {
        Write-Host "       Notice: '.env' not found. Creating default '.env' from '.env.example'..." -ForegroundColor DarkYellow
        Copy-Item -Path $envExampleFile -Destination $envFile
        Write-Host "       ✓ Created '.env' template." -ForegroundColor Green
    }
}

# Read DB host setting from env or default
$dbHost = $env:DB_HOST
if (-not $dbHost -and (Test-Path $envFile)) {
    $hostLine = Get-Content $envFile | Where-Object { $_ -match "^DB_HOST=(.+)" }
    if ($hostLine) {
        $dbHost = ($hostLine -split "=", 2)[1].Trim()
    }
}
if (-not $dbHost) {
    $dbHost = "mysql-33833560-foodwasteai.h.aivencloud.com"
}

# Distinguish Aiven remote cloud database vs local
if ($dbHost -like "*aivencloud.com*" -or $dbHost -eq "mysql-33833560-foodwasteai.h.aivencloud.com") {
    Write-Host "       ✓ Configured for Remote Managed Aiven MySQL:" -ForegroundColor Green
    Write-Host "         - Host: $dbHost" -ForegroundColor Gray
    Write-Host "         - SSL Mode: REQUIRED (Enforced)" -ForegroundColor Gray
    Write-Host "         - Note: Aiven is fully managed in the cloud (no local MySQL daemon needed)." -ForegroundColor DarkGray
} else {
    Write-Host "       ✓ Database Host configured as: $dbHost" -ForegroundColor Green
}

# Check SWI-Prolog availability (Optional)
try {
    $prologTest = swipl --version 2>&1 | Out-String
    if ($LASTEXITCODE -eq 0) {
        Write-Host "       ✓ SWI-Prolog Engine: Active on PATH" -ForegroundColor Green
    } else {
        Write-Host "       ℹ SWI-Prolog not on PATH (Automatic Java Expert Rule Mirror will be used)" -ForegroundColor DarkGray
    }
} catch {
    Write-Host "       ℹ SWI-Prolog not on PATH (Automatic Java Expert Rule Mirror will be used)" -ForegroundColor DarkGray
}

# 4. Build and Start Application
Write-Host "[4/4] Starting FoodWaste AI Web Server..." -ForegroundColor Yellow

$jarPath = Join-Path $PSScriptRoot "target\foodwaste-ai.jar"

if (-not $SkipBuild -or -not (Test-Path $jarPath)) {
    Write-Host "       Packaging latest application build (mvn package -DskipTests)..." -ForegroundColor Gray
    mvn package -DskipTests -q
    if ($LASTEXITCODE -ne 0) {
        Write-Host " [ERROR] Maven build failed. Run 'mvn test' to inspect errors." -ForegroundColor Red
        exit 1
    }
    Write-Host "       ✓ Build completed successfully." -ForegroundColor Green
}

$appUrl = "http://localhost:$Port"
Write-Host ""
Write-Host "============================================================" -ForegroundColor Green
Write-Host "  🚀 FoodWaste AI is running at: $appUrl" -ForegroundColor White
Write-Host "  Default Admin:  Username: admin  | Password: admin123" -ForegroundColor DarkCyan
Write-Host "  Default Staff:  Username: staff  | Password: staff123" -ForegroundColor DarkCyan
Write-Host "  Press CTRL+C in this terminal to stop the server." -ForegroundColor Gray
Write-Host "============================================================" -ForegroundColor Green
Write-Host ""

# Optional browser launch
if (-not $NoBrowser) {
    Start-Job -ScriptBlock {
        Start-Sleep -Seconds 2
        Start-Process "http://localhost:$args[0]"
    } -ArgumentList $Port | Out-Null
}

# Execute Java JAR
$env:PORT = "$Port"
java -jar $jarPath
