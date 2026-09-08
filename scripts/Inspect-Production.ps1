param(
    [Parameter(Mandatory=$true)][string]$ConfigPath,
    [switch]$Backup
)
$ErrorActionPreference = 'Stop'
$settings = @{}
Get-Content -LiteralPath $ConfigPath | ForEach-Object {
    if ($_ -match '^\s*(DB_[A-Z_]+)\s*=\s*(.*)$') {
        $settings[$matches[1]] = $matches[2].Trim().Trim('"').Trim("'")
    }
}
if ($settings['DB_HOST'] -ne 'foodwasteai-free-foodwasteai.h.aivencloud.com' -or
    $settings['DB_NAME'] -ne 'foodwaste_ai' -or !$settings['DB_PASSWORD']) {
    throw 'Configuration does not identify the requested production database.'
}
$mysqlBin = 'C:\Program Files\MySQL\MySQL Server 8.0\bin'
$artifactDirectory = Join-Path $PSScriptRoot ('..\scratch\production-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $artifactDirectory | Out-Null
$tables = @('users','food_items','sales','waste_records','inventory_transactions','predictions','prediction_items','recommendations','redistributions','redistribution_recipients')
$connectionArguments = @(
    ('--host=' + $settings['DB_HOST'])
    ('--port=' + $settings['DB_PORT'])
    ('--user=' + $settings['DB_USER'])
    '--ssl-mode=REQUIRED'
    '--default-character-set=utf8mb4'
)
$previousPassword = $env:MYSQL_PWD
try {
    $env:MYSQL_PWD = $settings['DB_PASSWORD']
    $counts = ($tables | ForEach-Object { "SELECT '$_' AS table_name, COUNT(*) AS row_count FROM $_;" }) -join "`n"
    & "$mysqlBin\mysql.exe" @connectionArguments --batch foodwaste_ai --execute=$counts | Set-Content -LiteralPath (Join-Path $artifactDirectory 'row-counts.tsv') -Encoding utf8
    if ($LASTEXITCODE -ne 0) { throw 'Production row-count inspection failed.' }
    $schema = ($tables | ForEach-Object { "SHOW CREATE TABLE $_;" }) -join "`n"
    & "$mysqlBin\mysql.exe" @connectionArguments --batch foodwaste_ai --execute=$schema | Set-Content -LiteralPath (Join-Path $artifactDirectory 'schema.tsv') -Encoding utf8
    if ($LASTEXITCODE -ne 0) { throw 'Production schema inspection failed.' }
    if ($Backup) {
        $dumpPath = Join-Path $artifactDirectory 'foodwaste_ai.sql'
        & "$mysqlBin\mysqldump.exe" @connectionArguments --single-transaction --routines --triggers --no-tablespaces --set-gtid-purged=OFF --result-file=$dumpPath foodwaste_ai
        if ($LASTEXITCODE -ne 0 -or (Get-Item -LiteralPath $dumpPath).Length -eq 0) { throw 'Backup export failed.' }
        Get-FileHash -LiteralPath $dumpPath -Algorithm SHA256 | Select-Object Algorithm,Hash | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $artifactDirectory 'backup-checksum.json')
        # This export must be restored and checked in the disposable database
        # before any production deletion. This script never deletes records.
    }
    Write-Output "Inspection artifacts: $artifactDirectory"
    Get-Content -LiteralPath (Join-Path $artifactDirectory 'row-counts.tsv')
} finally {
    $env:MYSQL_PWD = $previousPassword
    $settings.Clear()
}
