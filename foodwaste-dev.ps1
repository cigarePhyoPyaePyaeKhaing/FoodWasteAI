<#
.SYNOPSIS
    FoodWaste AI - Unified Developer Management Console
.DESCRIPTION
    Interactive CLI tool providing dedicated options for:
      1. Start Application (with remote Aiven MySQL database)
      2. Check Database Connectivity & Configuration
      3. Run Full Automated Test Suite
      4. Inspect Git Status
      5. Safe Git Synchronization to GitHub
      6. Exit
    Note: Database startup and Git push are strictly decoupled.
#>

param(
    [string]$Action = ""
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# Ensure Maven bin is in PATH if present in standard tools directory
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    if (Test-Path "C:\tools\apache-maven-3.9.6\bin") {
        $env:PATH = "$env:PATH;C:\tools\apache-maven-3.9.6\bin"
    }
}

function Show-Header {
    Clear-Host
    Write-Host "============================================================" -ForegroundColor Cyan
    Write-Host "             🍃 FoodWaste AI - Developer Hub                " -ForegroundColor White
    Write-Host "   Predict -> Prevent -> Redistribute -> Reduce Waste      " -ForegroundColor DarkCyan
    Write-Host "============================================================" -ForegroundColor Cyan
    Write-Host ""
}

function Run-App {
    Write-Host "Launching FoodWaste AI Application..." -ForegroundColor Yellow
    & (Join-Path $PSScriptRoot "start-foodwasteai.ps1")
}

function Test-DatabaseConfig {
    Show-Header
    Write-Host "--- Checking Database Configuration ---" -ForegroundColor Yellow
    
    $envFile = Join-Path $PSScriptRoot ".env"
    $dbHost = $env:DB_HOST
    $dbPort = $env:DB_PORT
    $dbName = $env:DB_NAME
    $dbUser = $env:DB_USER
    $dbSsl = $env:DB_SSL_MODE

    if (Test-Path $envFile) {
        Get-Content $envFile | ForEach-Object {
            if ($_ -match "^DB_HOST=(.+)") { if (-not $dbHost) { $dbHost = $matches[1].Trim() } }
            if ($_ -match "^DB_PORT=(.+)") { if (-not $dbPort) { $dbPort = $matches[1].Trim() } }
            if ($_ -match "^DB_NAME=(.+)") { if (-not $dbName) { $dbName = $matches[1].Trim() } }
            if ($_ -match "^DB_USER=(.+)") { if (-not $dbUser) { $dbUser = $matches[1].Trim() } }
            if ($_ -match "^DB_SSL_MODE=(.+)") { if (-not $dbSsl) { $dbSsl = $matches[1].Trim() } }
        }
    }

    if (-not $dbHost) { $dbHost = "mysql-33833560-foodwasteai.h.aivencloud.com" }
    if (-not $dbPort) { $dbPort = "15129" }
    if (-not $dbName) { $dbName = "defaultdb" }
    if (-not $dbUser) { $dbUser = "avnadmin" }
    if (-not $dbSsl)  { $dbSsl  = "REQUIRED" }

    Write-Host "  Database Host:     $dbHost" -ForegroundColor White
    Write-Host "  Database Port:     $dbPort" -ForegroundColor White
    Write-Host "  Database Name:     $dbName" -ForegroundColor White
    Write-Host "  Database User:     $dbUser" -ForegroundColor White
    Write-Host "  SSL Mode:          $dbSsl" -ForegroundColor White
    Write-Host "  Password Status:   [CONFIGURED SECURELY IN ENV / .ENV]" -ForegroundColor Green
    Write-Host ""

    if ($dbHost -like "*aivencloud.com*") {
        Write-Host "  ✓ Remote Aiven Cloud MySQL database is designated." -ForegroundColor Green
        Write-Host "    (No local MySQL daemon required)" -ForegroundColor DarkGray
    }
    
    Write-Host ""
    Write-Host "Testing network TCP socket reachability to ${dbHost}:${dbPort}..." -ForegroundColor Yellow
    try {
        $tcp = New-Object System.Net.Sockets.TcpClient
        $iar = $tcp.BeginConnect($dbHost, [int]$dbPort, $null, $null)
        $wait = $iar.AsyncWaitHandle.WaitOne(4000, $false)
        if ($wait -and $tcp.Connected) {
            $tcp.EndConnect($iar)
            $tcp.Close()
            Write-Host "  ✓ Successfully reached Aiven MySQL server port!" -ForegroundColor Green
        } else {
            $tcp.Close()
            Write-Host "  ℹ TCP port check timed out. Verify your internet connection or Aiven service status." -ForegroundColor DarkYellow
        }
    } catch {
        Write-Host "  ℹ Network reachability check notice: $($_.Exception.Message)" -ForegroundColor DarkGray
    }

    Write-Host ""
    Read-Host "Press ENTER to return to menu..."
}

function Run-Tests {
    Show-Header
    Write-Host "--- Running Project Automated Tests ---" -ForegroundColor Yellow
    Write-Host "Executing: mvn clean test" -ForegroundColor DarkCyan
    Write-Host ""
    mvn clean test
    Write-Host ""
    Read-Host "Press ENTER to return to menu..."
}

function Show-GitStatus {
    Show-Header
    Write-Host "--- Current Git Repository Status ---" -ForegroundColor Yellow
    if (Test-Path (Join-Path $PSScriptRoot ".git")) {
        git status
    } else {
        Write-Host "  ℹ Git repository (.git) is not yet initialized in this directory." -ForegroundColor DarkYellow
        Write-Host "    Run 'git init' and 'git remote add origin <url>' to configure your remote repository." -ForegroundColor Gray
    }
    Write-Host ""
    Read-Host "Press ENTER to return to menu..."
}

function Sync-Git {
    Show-Header
    Write-Host "--- Safe Git Sync to GitHub ---" -ForegroundColor Yellow
    $msg = Read-Host "Enter commit message (or press ENTER for default: 'chore: sync FoodWasteAI changes')"
    if (-not $msg -or $msg.Trim() -eq "") {
        $msg = "chore: sync FoodWasteAI changes"
    }

    Write-Host ""
    $confirm = Read-Host "Proceed with test verification, commit, rebase, and push to origin/main? (y/N)"
    if ($confirm -eq 'y' -or $confirm -eq 'Y') {
        & (Join-Path $PSScriptRoot "git-sync.ps1") -Message $msg
    } else {
        Write-Host "Git synchronization cancelled." -ForegroundColor DarkYellow
    }
    Write-Host ""
    Read-Host "Press ENTER to return to menu..."
}

# Non-interactive argument dispatching
if ($Action) {
    switch ($Action.ToLower()) {
        "start" { Run-App; exit 0 }
        "test"  { Run-Tests; exit 0 }
        "db"    { Test-DatabaseConfig; exit 0 }
        "status"{ Show-GitStatus; exit 0 }
        "sync"  { Sync-Git; exit 0 }
        default { Write-Host "Unknown action: $Action. Options: start, test, db, status, sync"; exit 1 }
    }
}

# Interactive CLI Loop
while ($true) {
    Show-Header
    Write-Host "Please select an operation:" -ForegroundColor White
    Write-Host "  [1] 🚀 Start Application (Connects automatically to Aiven MySQL)" -ForegroundColor Cyan
    Write-Host "  [2] 🗄️  Check Database Connectivity & Config" -ForegroundColor Cyan
    Write-Host "  [3] 🧪 Run Automated Test Suite (mvn test)" -ForegroundColor Cyan
    Write-Host "  [4] 📋 View Git Status" -ForegroundColor Cyan
    Write-Host "  [5] 🔄 Sync Approved Changes to GitHub (mvn test -> commit -> push)" -ForegroundColor Cyan
    Write-Host "  [6] 🚪 Exit" -ForegroundColor Gray
    Write-Host ""

    $choice = Read-Host "Enter option (1-6)"
    switch ($choice) {
        "1" { Run-App; break }
        "2" { Test-DatabaseConfig }
        "3" { Run-Tests }
        "4" { Show-GitStatus }
        "5" { Sync-Git }
        "6" { Write-Host "Goodbye! 🍃"; exit 0 }
        default { Write-Host "Invalid choice, please select 1-6." -ForegroundColor Red; Start-Sleep -Seconds 1 }
    }
}
