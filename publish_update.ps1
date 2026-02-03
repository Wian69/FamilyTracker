# Script to Automate Release
param (
    [string]$VersionName = "Patch" # "Patch" or specific version like "1.3"
)

$gradleFile = "app/build.gradle.kts"
$content = Get-Content $gradleFile

# Regex to find versionCode and versionName
$codePattern = 'versionCode\s*=\s*(\d+)'
$namePattern = 'versionName\s*=\s*"([\d\.]+)"'

# 1. Update Version Code
if (($content -join "`n") -match $codePattern) {
    $v = [int]$Matches[1] + 1
    $newContent = $content -replace $codePattern, "versionCode = $v"
    Set-Content $gradleFile $newContent
    $content = Get-Content $gradleFile # Reload content for subsequent steps
}
else {
    $newContent = $content
}

# 2. Update Version Name
$currentVersionName = [regex]::Match(($content -join "`n"), $namePattern).Groups[1].Value
$parts = $currentVersionName.Split('.')
if ($VersionName -eq "Patch") {
    $last = [int]$parts[$parts.Length - 1] + 1
    $parts[$parts.Length - 1] = $last
    $newVersionName = $parts -join '.'
}
else {
    $newVersionName = $VersionName
}

$newContent = $newContent -replace $namePattern, "versionName = `"$newVersionName`""

Set-Content $gradleFile $newContent

Write-Host "Updated to Version $newVersionName" -ForegroundColor Green

# 2.5 Verify Build Success
Write-Host "Verifying build stability (Release)..." -ForegroundColor Cyan
./gradlew assembleRelease
if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ Build FAILED. Aborting push to ensure GitHub remains stable." -ForegroundColor Red
    exit
}
Write-Host "✅ Build Success!" -ForegroundColor Green

# 3. Git Commands
git add .
git commit -m "Release v$newVersionName"
git tag "v$newVersionName"
git push origin HEAD
git push origin "v$newVersionName"

Write-Host "---------------------------------------------------"
Write-Host "✅ Pushed v$newVersionName to GitHub."
Write-Host "⏳ GitHub Actions is now building your APK..."
Write-Host "---------------------------------------------------"
Write-Host "---------------------------------------------------"
Write-Host "✅ Pushed v$newVersionName to GitHub."
Write-Host "⏳ GitHub Actions is now building your APK..."
Write-Host "---------------------------------------------------"

# 4. Automate Firebase Update
Write-Host "Waiting 5 minutes for GitHub Actions build to complete..." -ForegroundColor Yellow
# Start-Sleep -Seconds 300

Write-Host "Updating Firebase Config..." -ForegroundColor Cyan
./update_firebase_config.ps1 -VersionName $newVersionName

Write-Host "---------------------------------------------------"
Write-Host "🚀 Release Process Complete!"
Write-Host "Once the GitHub Action finishes, the app will update for users."
Write-Host "---------------------------------------------------"
