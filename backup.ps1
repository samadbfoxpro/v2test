# Auto Backup Script for Android Studio Project
$projectDir = (Get-Item -Path $PSScriptRoot).FullName
$projectName = (Get-Item -Path $PSScriptRoot).Name
$timestamp = Get-Date -Format 'yyyy-MM-dd_HH-mm-ss'

$backupDir = Join-Path $projectDir '_backups'
if (-not (Test-Path $backupDir)) {
    New-Item -ItemType Directory -Path $backupDir | Out-Null
}

$zipFileName = "${projectName}_backup_${timestamp}.zip"
$zipFilePath = Join-Path $backupDir $zipFileName

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " 📦 پشتیبان‌گیری از پروژه: $projectName" -ForegroundColor White
Write-Host " ⏰ زمان ایجاد: $timestamp" -ForegroundColor Gray
Write-Host "==========================================================" -ForegroundColor Cyan

# Create temporary staging directory
$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("bak_" + [System.Guid]::NewGuid().ToString().Substring(0, 8))
New-Item -ItemType Directory -Path $tempDir | Out-Null

try {
    Write-Host "1/3 در حال جمع‌آوری سورس‌ها و فایل‌های تنظیمات..." -ForegroundColor Yellow

    $excludedFolders = @('build', '.gradle', '.idea', '.git', '_backups', '.gemini', 'captures', '.cxx')

    Get-ChildItem -Path $projectDir | ForEach-Object {
        $itemName = $_.Name
        if ($_.PSIsContainer) {
            if ($excludedFolders -notcontains $itemName) {
                $targetSubDir = Join-Path $tempDir $itemName
                robocopy $_.FullName $targetSubDir /E /XD build .gradle .idea .git captures .cxx /XF *.hprof *.apk *.aab *.log /NFL /NDL /NJH /NJS /nc /ns /np | Out-Null
            }
        } else {
            if ($itemName -notmatch '\.(zip|hprof|apk|aab|log)$') {
                Copy-Item -Path $_.FullName -Destination $tempDir -Force
            }
        }
    }

    Write-Host "2/3 در حال فشرده‌سازی و ساخت فایل ZIP نهایی..." -ForegroundColor Yellow
    Compress-Archive -Path (Join-Path $tempDir '*') -DestinationPath $zipFilePath -CompressionLevel Optimal -Force

    $sizeMB = [math]::Round(((Get-Item $zipFilePath).Length / 1MB), 2)

    Write-Host ""
    Write-Host "✅ پشتیبان‌گیری با موفقیت کامل شد!" -ForegroundColor Green
    Write-Host ""
    Write-Host " 📁 نام فایل: $zipFileName" -ForegroundColor Cyan
    Write-Host " 📊 حجم آرشیو: $sizeMB MB" -ForegroundColor Cyan
    Write-Host " 📍 مسیر ذخیره: $zipFilePath" -ForegroundColor White
    Write-Host ""
}
catch {
    Write-Host "❌ خطا در انجام پشتیبان‌گیری: $_" -ForegroundColor Red
}
finally {
    Remove-Item -Path $tempDir -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host "==========================================================" -ForegroundColor Cyan
