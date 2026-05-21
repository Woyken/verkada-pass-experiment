#!/usr/bin/env pwsh
<#
.SYNOPSIS
  Quick Verkada BLE capture — NO Bluetooth restart.
  Just pulls the bugreport immediately after you've done the unlock.

.USAGE
  1. Make sure HCI logging is enabled on your phone (Developer Options)
  2. Open Verkada Pass app
  3. Walk to door, do the BLE unlock (reader turns green)
  4. IMMEDIATELY run: .\capture-quick.ps1
#>

param([int]$WaitSeconds = 0)

$CaptureDir = $PSScriptRoot
$ts = Get-Date -Format "yyyyMMdd-HHmmss"

Write-Host "`n=== Verkada Quick BLE Capture ===" -ForegroundColor Cyan

# Optionally wait (if you want to run this BEFORE doing the unlock)
if ($WaitSeconds -gt 0) {
    Write-Host "`nWaiting $WaitSeconds seconds — go do the unlock NOW!" -ForegroundColor Yellow
    for ($i = $WaitSeconds; $i -ge 0; $i--) {
        Write-Host "`r  $i seconds remaining...  " -NoNewline
        Start-Sleep -Seconds 1
    }
    Write-Host ""
}

Write-Host "`nPulling bugreport (this takes ~2 min)..." -ForegroundColor White
$bugzip = Join-Path $CaptureDir "bugreport-quick-$ts.zip"
adb bugreport $bugzip 2>&1 | Out-Null

if (-not (Test-Path $bugzip)) {
    Write-Host "ERROR: bugreport failed" -ForegroundColor Red
    exit 1
}
Write-Host "  Saved: $bugzip ($(((Get-Item $bugzip).Length / 1MB).ToString('0.0')) MB)" -ForegroundColor Green

# Extract btsnoop logs
Write-Host "`nExtracting btsnoop HCI logs..." -ForegroundColor White
$extractDir = Join-Path $CaptureDir "extract-$ts"
Expand-Archive -Path $bugzip -DestinationPath $extractDir -Force

$logs = Get-ChildItem -Path $extractDir -Recurse -Filter "btsnooz_hci*" | Sort-Object Length -Descending
if ($logs.Count -eq 0) {
    Write-Host "  No btsnoop logs found in bugreport!" -ForegroundColor Red
    exit 1
}

$mainLog = $null
foreach ($log in $logs) {
    $dest = Join-Path $CaptureDir "quick-$ts-$($log.Name)"
    Copy-Item $log.FullName $dest
    Write-Host "  Extracted: $($log.Name) ($($log.Length) bytes)" -ForegroundColor Green
    if (-not $mainLog) { $mainLog = $dest }
}

# Also grab bluetooth_manager dump from the bugreport text
$bugTxt = Get-ChildItem -Path $extractDir -Recurse -Filter "bugreport-*.txt" | Select-Object -First 1
if ($bugTxt) {
    $btDump = Join-Path $CaptureDir "bt-dump-$ts.txt"
    Select-String -Path $bugTxt.FullName -Pattern "DUMP OF SERVICE bluetooth" -Context 0,500 |
        ForEach-Object { $_.Context.PostContext } | Set-Content $btDump
    Write-Host "  BT manager dump: $btDump" -ForegroundColor Green
}

# Cleanup extract dir
Remove-Item $extractDir -Recurse -Force -ErrorAction SilentlyContinue

# Run analyzer
Write-Host "`n=== Analyzing capture ===" -ForegroundColor Cyan
if ($mainLog -and (Test-Path (Join-Path $CaptureDir "analyze_verkada_ble.py"))) {
    python (Join-Path $CaptureDir "analyze_verkada_ble.py") $mainLog
} else {
    Write-Host "  Run manually: python analyze_verkada_ble.py <logfile>" -ForegroundColor Yellow
}

Write-Host "`n=== Done ===" -ForegroundColor Green
