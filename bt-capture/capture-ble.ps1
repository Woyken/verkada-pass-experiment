#!/usr/bin/env pwsh
<#
.SYNOPSIS
  Verkada BLE capture — fully automated single-shot workflow.

.USAGE
  # Default: do everything automatically
  .\capture-ble.ps1

  # Manual steps (for advanced use)
  .\capture-ble.ps1 pre
  .\capture-ble.ps1 post
  .\capture-ble.ps1 analyze [path]
#>

param([string]$Action = "auto", [string]$LogFile = "", [int]$WaitSeconds = 20)

$CaptureDir = $PSScriptRoot

function Write-Step([string]$msg, [string]$color = "Cyan") {
    Write-Host "`n$msg" -ForegroundColor $color
}

function Step-Pre {
    Write-Step "=== [1/4] Enabling HCI snoop logging ==="

    adb shell settings put secure bluetooth_hci_log 1
    adb shell settings put global bluetooth_hci_log 1

    Write-Host "Restarting Bluetooth to start a fresh log file..."
    adb shell svc bluetooth disable
    Start-Sleep -Seconds 3
    adb shell svc bluetooth enable
    Start-Sleep -Seconds 2

    $val = (adb shell settings get secure bluetooth_hci_log).Trim()
    if ($val -eq "1") {
        Write-Host "HCI logging ON" -ForegroundColor Green
    } else {
        Write-Host "WARNING: HCI logging might not be active (got: $val)" -ForegroundColor Yellow
        Write-Host "  Enable manually: Settings > Developer Options > Enable Bluetooth HCI snoop log" -ForegroundColor Yellow
    }
}

function Step-Wait([int]$seconds) {
    Write-Step "=== [2/4] Your turn — do the thing on the phone ===" "Yellow"
    Write-Host ""
    Write-Host "  >> Open Verkada Pass, trigger unlock at the door (or interact with watch)" -ForegroundColor White
    Write-Host ""

    $bar = 40
    for ($i = $seconds; $i -ge 0; $i--) {
        $filled = [int](($seconds - $i) / $seconds * $bar)
        $empty  = $bar - $filled
        $pct    = [int](($seconds - $i) / $seconds * 100)
        $prog   = ('#' * $filled) + ('-' * $empty)
        Write-Host -NoNewline "`r  [$prog] $pct% — ${i}s remaining   "
        if ($i -gt 0) { Start-Sleep -Seconds 1 }
    }
    Write-Host "`r  [########################################] 100% -- done!          "
    Write-Host ""
}

function Step-Post {
    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $zipFile   = Join-Path $CaptureDir "bugreport-$timestamp.zip"
    $logFile   = Join-Path $CaptureDir "btsnooz_hci-$timestamp.log"

    Write-Step "=== [3/4] Capturing bugreport (30-60s) ==="
    adb bugreport $zipFile
    if (-not (Test-Path $zipFile)) {
        Write-Host "ERROR: bugreport failed" -ForegroundColor Red; return $null
    }
    Write-Host "Bugreport: $([math]::Round((Get-Item $zipFile).Length/1MB, 1)) MB"

    Write-Host "Extracting btsnooz_hci.log..."
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip   = [System.IO.Compression.ZipFile]::OpenRead($zipFile)
    $entry = $zip.Entries | Where-Object { $_.FullName -match "btsnooz_hci|btsnoop_hci" } | Select-Object -First 1
    if (-not $entry) {
        Write-Host "ERROR: No btsnoop log in bugreport. Contents matching 'bt':" -ForegroundColor Red
        $zip.Entries | Where-Object { $_.FullName -match "bluetooth|btsnoop|btsnooz" } | Select-Object -First 10 -ExpandProperty FullName
        $zip.Dispose(); return $null
    }
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $logFile, $true)
    $zip.Dispose()
    Write-Host "Log: $logFile ($([math]::Round((Get-Item $logFile).Length/1KB, 0)) KB)" -ForegroundColor Green
    return $logFile
}

function Step-Analyze([string]$path) {
    if (-not $path -or -not (Test-Path $path)) {
        $path = Get-ChildItem $CaptureDir -Filter "btsnooz_hci-*.log" |
                Sort-Object LastWriteTime -Descending |
                Select-Object -First 1 -ExpandProperty FullName
        if (-not $path) { Write-Host "No log file found." -ForegroundColor Red; return }
        Write-Host "Using most recent log: $path"
    }

    Write-Step "=== [4/4] Analyzing ===" "Green"
    python (Join-Path $CaptureDir "analyze_verkada_ble.py") $path

    Write-Host ""
    Write-Host "Open in Wireshark for full visual inspection:" -ForegroundColor Cyan
    Write-Host "  & 'C:\Program Files\Wireshark\Wireshark.exe' '$path'"
    Write-Host ""
    Write-Host "Useful filters:" -ForegroundColor Cyan
    Write-Host "  btatt                        -- all GATT"
    Write-Host "  btatt.opcode == 0x52         -- Write Command (char 1001, reader->phone)"
    Write-Host "  btatt.opcode == 0x0b         -- Read Response (char 2000, 80-byte payload)"
    Write-Host "  btatt.opcode == 0x12         -- Write Request (central mode payload)"
    Write-Host "  btcommon.eir_ad.entry.company_id == 0x004c  -- iBeacon adverts"
}

# ── Entry point ───────────────────────────────────────────────────────────────
switch ($Action.ToLower()) {
    "auto" {
        Step-Pre
        Step-Wait -seconds $WaitSeconds
        $log = Step-Post
        if ($log) { Step-Analyze -path $log }
    }
    "pre"     { Step-Pre }
    "post"    { $log = Step-Post; if ($log) { Write-Host "Run: .\capture-ble.ps1 analyze '$log'" } }
    "analyze" { Step-Analyze -path $LogFile }
    default {
        Write-Host "Usage:"
        Write-Host "  .\capture-ble.ps1                    # Full auto (20s window)"
        Write-Host "  .\capture-ble.ps1 -WaitSeconds 60    # Full auto with 60s window"
        Write-Host "  .\capture-ble.ps1 pre                # Enable logging + restart BT"
        Write-Host "  .\capture-ble.ps1 post               # Pull + extract log"
        Write-Host "  .\capture-ble.ps1 analyze            # Analyze most recent log"
    }
}

