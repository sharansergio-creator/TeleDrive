# Gradle Build Lock Fix Script
# Location: D:\TeleDrive\android-app\fix-gradle-lock.ps1
# Usage: .\fix-gradle-lock.ps1

Write-Host "`n======================================" -ForegroundColor Cyan
Write-Host "  GRADLE BUILD LOCK FIX UTILITY" -ForegroundColor Cyan
Write-Host "======================================`n" -ForegroundColor Cyan

# Step 1: Stop Gradle daemon
Write-Host "[1/5] Stopping Gradle daemon..." -ForegroundColor Yellow
Set-Location "D:\TeleDrive\android-app"
.\gradlew.bat --stop | Out-Null
Write-Host "      ✓ Daemon stopped" -ForegroundColor Green

# Step 2: Wait for processes to release
Write-Host "[2/5] Waiting for process cleanup..." -ForegroundColor Yellow
Start-Sleep -Seconds 2
Write-Host "      ✓ Wait complete" -ForegroundColor Green

# Step 3: Kill any remaining Java processes
Write-Host "[3/5] Killing Java processes..." -ForegroundColor Yellow
$javaProcesses = Get-Process | Where-Object {$_.ProcessName -like "*java*"}
if ($javaProcesses) {
    $javaProcesses | Stop-Process -Force -ErrorAction SilentlyContinue
    Write-Host "      ✓ $($javaProcesses.Count) Java process(es) terminated" -ForegroundColor Green
} else {
    Write-Host "      ✓ No Java processes running" -ForegroundColor Green
}

# Step 4: Wait for file locks to release
Start-Sleep -Seconds 2

# Step 5: Delete build directory
Write-Host "[4/5] Deleting build directory..." -ForegroundColor Yellow
if (Test-Path "D:\TeleDrive\android-app\app\build") {
    Remove-Item -Path "D:\TeleDrive\android-app\app\build" -Recurse -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 1

    if (Test-Path "D:\TeleDrive\android-app\app\build") {
        Write-Host "      ⚠ WARNING: Some files still locked!" -ForegroundColor Red
        Write-Host "      Try closing Android Studio and run again" -ForegroundColor Yellow
        exit 1
    } else {
        Write-Host "      ✓ Build directory removed" -ForegroundColor Green
    }
} else {
    Write-Host "      ✓ Build directory already clean" -ForegroundColor Green
}

# Step 6: Run clean build
Write-Host "[5/5] Running clean build..." -ForegroundColor Yellow
$cleanOutput = .\gradlew.bat clean --console=plain 2>&1
if ($cleanOutput -match "BUILD SUCCESSFUL") {
    Write-Host "      ✓ Clean build successful" -ForegroundColor Green
} else {
    Write-Host "      ✗ Clean build failed" -ForegroundColor Red
    Write-Host $cleanOutput
    exit 1
}

# Success summary
Write-Host "`n======================================" -ForegroundColor Green
Write-Host "  ✓ BUILD LOCK FIXED SUCCESSFULLY" -ForegroundColor Green
Write-Host "======================================`n" -ForegroundColor Green

Write-Host "You can now run:" -ForegroundColor Cyan
Write-Host "  .\gradlew.bat assembleDebug" -ForegroundColor White
Write-Host ""

