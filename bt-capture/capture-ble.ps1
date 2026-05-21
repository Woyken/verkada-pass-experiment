#!/usr/bin/env pwsh
<#
.SYNOPSIS
  Verkada BLE capture workflow for ADB-connected Pixel 9 Pro.

.USAGE
  # Step 1 — Before going to the door (run once per session)
  .\capture-ble.ps1 pre

  # Step 2 — After coming back from the door
  .\capture-ble.ps1 post

  # Step 3 — Analyze the capture (optional, Wireshark is better)
  .\capture-ble.ps1 analyze [path-to-btsnooz_hci.log]
#>

param([string]$Action = "help", [string]$LogFile = "")

$CaptureDir = $PSScriptRoot

function Step-Pre {
    Write-Host "`n=== PRE-SESSION: Preparing BLE capture ===" -ForegroundColor Cyan

    # Ensure HCI logging is enabled
    Write-Host "[1/4] Enabling HCI snoop logging..."
    adb shell settings put secure bluetooth_hci_log 1
    adb shell settings put global bluetooth_hci_log 1

    # Cold-restart Bluetooth so the log file starts fresh
    Write-Host "[2/4] Restarting Bluetooth (clears old log)..."
    adb shell svc bluetooth disable
    Start-Sleep -Seconds 3
    adb shell svc bluetooth enable
    Start-Sleep -Seconds 2

    # Verify logging is active
    $val = adb shell settings get secure bluetooth_hci_log
    if ($val.Trim() -eq "1") {
        Write-Host "[3/4] HCI logging confirmed ON" -ForegroundColor Green
    } else {
        Write-Host "[3/4] WARNING: HCI logging may not be active (got: $val)" -ForegroundColor Yellow
        Write-Host "      Enable manually: Settings > Developer Options > 'Enable Bluetooth HCI snoop log'" -ForegroundColor Yellow
    }

    Write-Host "[4/4] Ready! Now:" -ForegroundColor Green
    Write-Host "  1. Open Verkada Pass app on the phone"
    Write-Host "  2. Walk to the door and trigger unlock (hold phone close OR tap button)"
    Write-Host "  3. Come back and run:  .\capture-ble.ps1 post"
    Write-Host ""
}

function Step-Post {
    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $zipFile  = Join-Path $CaptureDir "bugreport-$timestamp.zip"
    $logFile  = Join-Path $CaptureDir "btsnooz_hci-$timestamp.log"

    Write-Host "`n=== POST-SESSION: Extracting BLE capture ===" -ForegroundColor Cyan

    Write-Host "[1/3] Capturing bugreport (30-60s)..."
    adb bugreport $zipFile
    if (-not (Test-Path $zipFile)) {
        Write-Host "ERROR: bugreport failed" -ForegroundColor Red; return
    }
    Write-Host "      Bugreport saved: $zipFile ($([math]::Round((Get-Item $zipFile).Length/1MB, 1)) MB)"

    Write-Host "[2/3] Extracting btsnooz_hci.log..."
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($zipFile)
    $entry = $zip.Entries | Where-Object { $_.FullName -match "btsnooz_hci|btsnoop_hci" } | Select-Object -First 1
    if (-not $entry) {
        Write-Host "ERROR: No btsnoop log found in bugreport" -ForegroundColor Red
        Write-Host "       Entries matching 'bt': " + ($zip.Entries | Where-Object { $_.FullName -match "bt" } | Select-Object -First 5 -ExpandProperty FullName)
        $zip.Dispose(); return
    }
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $logFile, $true)
    $zip.Dispose()
    Write-Host "      Log extracted: $logFile ($([math]::Round((Get-Item $logFile).Length/1KB, 0)) KB)"

    Write-Host "[3/3] Quick stats..."
    python (Join-Path $CaptureDir "parse_btsnoop.py") $logFile

    Write-Host ""
    Write-Host "=== NEXT STEPS ===" -ForegroundColor Green
    Write-Host "  Open in Wireshark:"
    Write-Host "    wireshark $logFile"
    Write-Host ""
    Write-Host "  Wireshark display filters for Verkada traffic:"
    Write-Host "    All GATT (ATT layer):           btatt"
    Write-Host "    ATT Write Commands (char 1001): btatt.opcode == 0x52"
    Write-Host "    ATT Read Responses (char 2000): btatt.opcode == 0x0b"
    Write-Host "    BLE advertisements:             btle.advertising_header"
    Write-Host "    iBeacon (Apple mfg 0x004C):     btcommon.eir_ad.entry.company_id == 0x004c"
    Write-Host ""
    Write-Host "  Or run the analyzer:"
    Write-Host "    .\capture-ble.ps1 analyze $logFile"
    Write-Host ""
}

function Step-Analyze {
    param([string]$path)
    if (-not $path -or -not (Test-Path $path)) {
        # Find most recent log
        $path = Get-ChildItem $CaptureDir -Filter "btsnooz_hci-*.log" | Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName
        if (-not $path) { Write-Host "No log file found. Run 'post' first."; return }
        Write-Host "Using most recent log: $path"
    }
    python (Join-Path $CaptureDir "analyze_verkada_ble.py") $path
}

switch ($Action.ToLower()) {
    "pre"     { Step-Pre }
    "post"    { Step-Post }
    "analyze" { Step-Analyze -path $LogFile }
    default   {
        Write-Host "Usage:"
        Write-Host "  .\capture-ble.ps1 pre       # Before going to the door"
        Write-Host "  .\capture-ble.ps1 post      # After returning, pulls + extracts log"
        Write-Host "  .\capture-ble.ps1 analyze   # Parse Verkada GATT traffic"
    }
}
