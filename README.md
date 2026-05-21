# verkada-pass-experiment

Research notes and a small UV-based test client for authorized Verkada Pass login, remote unlock, and BLE central/peripheral experiments.

This repo intentionally excludes proprietary APK files, decompiled app output, and cloned third-party repos.

---

## Android App

A clean third-party Android app (`verkada-pass-app/`) that reproduces the Verkada Pass login flow and door unlock.

**[⬇ Download latest APK](https://github.com/Woyken/verkada-pass-experiment/releases/tag/android-latest)**  
*(Built automatically by GitHub Actions on every change)*

### Features
- Magic-link login (paste the URL from your email)
- Session persistence — skip login on next launch
- Door list with one-tap unlock
- **Home screen widget** — 1×1 widget per door, tap to unlock
- **Quick Settings tiles** — up to 4 tiles, each assigned to a door

### Setup
1. Install the APK from the release link above
2. Open the app → enter your work email → wait for the magic-link email
3. Copy the full magic-link URL and paste it into the app
4. Doors load automatically

### Widget & Tiles
- **Widget**: long-press home screen → Widgets → Verkada Pass → pick a door during setup
- **Quick Tiles**: pull down notification shade → edit tiles → add "Door Tile 1–4"; tap an unassigned tile to pick a door, or use Account → Quick Settings Tiles inside the app

---

## Python Test Client

See [`verkada-pass-test-client/README.md`](verkada-pass-test-client/README.md).

## Research Notes

- [`verkada-pass-bluetooth.md`](verkada-pass-bluetooth.md) — BLE unlock flow, confirmed server-side zero-proximity validation
