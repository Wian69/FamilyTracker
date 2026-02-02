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
$newContent = $content -replace $codePattern, { 
    $v = [int]$args.Groups[1].Value + 1
    "versionCode = $v" 
}

# 2. Update Version Name
$currentVersionName = [regex]::Match($content, $namePattern).Groups[1].Value
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

# 3. Git Commands
git add .
git commit -m "Release v$newVersionName"
git tag "v$newVersionName"
git push origin main
git push origin "v$newVersionName"

Write-Host "---------------------------------------------------"
Write-Host "✅ Pushed v$newVersionName to GitHub."
Write-Host "⏳ GitHub Actions is now building your APK..."
Write-Host "---------------------------------------------------"
Write-Host "1. Go to: https://github.com/YOUR_USER/FamilyTracker/releases"
Write-Host "2. Copy the 'app-debug.apk' link from the latest release."
Write-Host "3. Go to Firebase Console -> Realtime Database -> config"
Write-Host "4. Set 'latest_version_code' to the new code."
Write-Host "5. Set 'update_url' to the link you copied."
Write-Host "---------------------------------------------------"
