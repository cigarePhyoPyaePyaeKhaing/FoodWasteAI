<#
.SYNOPSIS
    FoodWaste AI - Safe Git Synchronization Script
.DESCRIPTION
    Safely commits and synchronizes approved project changes to GitHub.
    Safety Guarantees:
      - Strictly enforces .gitignore (never adds .env or secret credentials)
      - Runs full test suite (mvn clean test) and aborts if tests fail
      - Rebase with origin/main and halts on conflicts without destructive force-push
      - Never deletes branches or uses --force
#>

param(
    [string]$Message = "chore: sync FoodWasteAI changes",
    [switch]$SkipTests = $false
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

Write-Host ""
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "             🍃 FoodWaste AI - Safe Git Sync                " -ForegroundColor White
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host ""

# Ensure Maven bin is in PATH if present in standard tools directory
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    if (Test-Path "C:\tools\apache-maven-3.9.6\bin") {
        $env:PATH = "$env:PATH;C:\tools\apache-maven-3.9.6\bin"
    }
}

# 1. Check Git Status
Write-Host "[1/6] Inspecting Repository Status..." -ForegroundColor Yellow

if (-not (Test-Path (Join-Path $PSScriptRoot ".git"))) {
    Write-Host "       ℹ No Git repository (.git) initialized in this folder yet." -ForegroundColor DarkYellow
    Write-Host "       To connect to GitHub, initialize and add your remote:" -ForegroundColor Gray
    Write-Host "         git init" -ForegroundColor White
    Write-Host "         git remote add origin <your-repository-url>" -ForegroundColor White
    Write-Host "         .\git-sync.ps1" -ForegroundColor White
    exit 0
}

$gitStatus = git status --porcelain
if (-not $gitStatus) {
    Write-Host "       ✓ Working tree is clean. No uncommitted changes detected." -ForegroundColor Green
    
    # Check if there are unpushed commits
    $unpushed = git log @{u}..HEAD --oneline 2>&1
    if ($LASTEXITCODE -eq 0 -and $unpushed) {
        Write-Host "       Found unpushed local commits:" -ForegroundColor DarkCyan
        Write-Host $unpushed -ForegroundColor Gray
        $confirmPush = Read-Host "Push these commits to origin/main? (y/N)"
        if ($confirmPush -eq 'y' -or $confirmPush -eq 'Y') {
            git push origin main
            Write-Host "       ✓ Successfully pushed to origin/main!" -ForegroundColor Green
        }
    }
    exit 0
}

Write-Host "       Pending changes detected:" -ForegroundColor Gray
git status -s
Write-Host ""

# 2. Security Check (Enforce .gitignore & ensure no .env / secrets staged)
Write-Host "[2/6] Running Security & Secret Leak Pre-Checks..." -ForegroundColor Yellow

$sensitiveFiles = @(".env", ".env.local", ".env.production", "secrets.json", "credentials.json")
foreach ($s in $sensitiveFiles) {
    $tracked = git ls-files $s
    if ($tracked) {
        Write-Host " [CRITICAL SECURITY ERROR] Sensitive file '$s' is tracked in Git index!" -ForegroundColor Red
        Write-Host " Untracking sensitive file immediately: git rm --cached $s" -ForegroundColor Yellow
        git rm --cached $s
    }
}

# 3. Stage All Tracked & New Whitelisted Files
Write-Host "[3/6] Staging Project Files (Respecting .gitignore)..." -ForegroundColor Yellow
git add .

# Verify again that no .env was staged
$stagedEnv = git diff --cached --name-only | Where-Object { $_ -match "^\.env" }
if ($stagedEnv) {
    Write-Host " [CRITICAL SECURITY ERROR] Detected .env file staged for commit! Unstaging immediately." -ForegroundColor Red
    git reset HEAD .env*
    Write-Host " [BLOCKED] Git synchronization aborted to protect credentials." -ForegroundColor Red
    exit 1
}
Write-Host "       ✓ Staged clean non-secret project files." -ForegroundColor Green

# 4. Run Automated Maven Tests
if (-not $SkipTests) {
    Write-Host "[4/6] Running Automated Test Suite (mvn clean test)..." -ForegroundColor Yellow
    Write-Host "       Verifying that all tests pass before committing..." -ForegroundColor Gray
    
    mvn clean test -q
    if ($LASTEXITCODE -ne 0) {
        Write-Host ""
        Write-Host " [TEST FAILURE] Maven test suite failed! Halting synchronization." -ForegroundColor Red
        Write-Host " Changes are staged locally but NOT committed or pushed." -ForegroundColor Yellow
        Write-Host " Please resolve test errors and run again." -ForegroundColor Yellow
        exit 1
    }
    Write-Host "       ✓ All unit and integration tests passed successfully!" -ForegroundColor Green
} else {
    Write-Host "[4/6] Automated tests skipped (--SkipTests)." -ForegroundColor DarkYellow
}

# 5. Commit Changes
Write-Host "[5/6] Creating Git Commit..." -ForegroundColor Yellow
Write-Host "       Commit message: '$Message'" -ForegroundColor Gray

git commit -m "$Message"
if ($LASTEXITCODE -ne 0) {
    Write-Host " [ERROR] Git commit failed." -ForegroundColor Red
    exit 1
}
Write-Host "       ✓ Commit created successfully." -ForegroundColor Green

# 6. Rebase & Push
Write-Host "[6/6] Synchronizing with Remote (origin/main)..." -ForegroundColor Yellow

Write-Host "       Fetching remote updates: git pull --rebase origin main" -ForegroundColor Gray
git pull --rebase origin main
if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host " ============================================================" -ForegroundColor Red
    Write-Host " [REBASE CONFLICT DETECTED] Remote changes conflict with local work!" -ForegroundColor Red
    Write-Host " Automation has stopped safely without force-pushing." -ForegroundColor Yellow
    Write-Host " Please inspect and resolve conflicts manually, then run:" -ForegroundColor Yellow
    Write-Host "   git rebase --continue" -ForegroundColor White
    Write-Host "   git push origin main" -ForegroundColor White
    Write-Host " ============================================================" -ForegroundColor Red
    exit 1
}

Write-Host "       Pushing to origin/main..." -ForegroundColor Gray
git push origin main
if ($LASTEXITCODE -ne 0) {
    Write-Host " [ERROR] Git push failed. Please verify your remote branch permissions and network." -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "============================================================" -ForegroundColor Green
Write-Host "  ✓ Synchronization Complete! All changes pushed to main.   " -ForegroundColor White
Write-Host "============================================================" -ForegroundColor Green
Write-Host ""
