# Helper to Update Firebase with new Version
param(
    [string]$UpdateUrl,
    [string]$VersionName
)

# Configuration
$DB_URL = "https://familiy-tracker-default-rtdb.firebaseio.com/config.json"
$REPO_OWNER = "Wian69"
$REPO_NAME = "FamilyTracker"
$APK_NAME = "app-debug.apk"

# We read the current version info from build.gradle.kts if not provided
$gradleContent = Get-Content "app/build.gradle.kts" -Raw

# 1. Get Version Code
$versionCodeMatch = [regex]::Match($gradleContent, 'versionCode\s*=\s*(\d+)')
$currentVersionCode = if ($versionCodeMatch.Success) { [int]$versionCodeMatch.Groups[1].Value } else { 0 }

# 2. Get Version Name (if not passed param)
if ([string]::IsNullOrWhiteSpace($VersionName)) {
    $versionNameMatch = [regex]::Match($gradleContent, 'versionName\s*=\s*"([\d\.]+)"')
    if ($versionNameMatch.Success) { 
        $VersionName = $versionNameMatch.Groups[1].Value 
    }
}

Write-Host "Current App Version Code: $currentVersionCode" -ForegroundColor Cyan
Write-Host "Current App Version Name: $VersionName" -ForegroundColor Cyan

# 3. Construct URL if missing
if ([string]::IsNullOrWhiteSpace($UpdateUrl)) {
    if (-not [string]::IsNullOrWhiteSpace($VersionName)) {
        # Format: https://github.com/{owner}/{repo}/releases/download/v{tag}/{filename}
        $UpdateUrl = "https://github.com/$REPO_OWNER/$REPO_NAME/releases/download/v$VersionName/$APK_NAME"
        Write-Host "Auto-constructed GitHub URL: $UpdateUrl" -ForegroundColor Yellow
    }
    else {
        # Interactive Fallback
        $UpdateUrl = Read-Host "Please paste the 'app-debug.apk' download link from GitHub"
    }
}

if ([string]::IsNullOrWhiteSpace($UpdateUrl)) {
    Write-Host "Error: URL is required." -ForegroundColor Red
    exit
}

# Payload
$payload = @{
    latest_version_code = $currentVersionCode
    update_url          = $UpdateUrl
} | ConvertTo-Json

# Send to Firebase
Try {
    $response = Invoke-RestMethod -Uri $DB_URL -Method Put -Body $payload -ContentType "application/json"
    Write-Host "✅ Firebase Updated Successfully!" -ForegroundColor Green
    Write-Host "Users with version code < $currentVersionCode will now see the update dialog."
    Write-Host "New Config: "
    Write-Host ($response | ConvertTo-Json)
}
Catch {
    Write-Host "❌ Failed to update Firebase. Verify your Database Rules allow write access or set it manually in the console." -ForegroundColor Red
    Write-Host "Error: $_"
}
