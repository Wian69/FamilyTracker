# Helper to Update Firebase with new Version
param(
    [string]$UpdateUrl
)

# Configuration
$DB_URL = "https://familiy-tracker-default-rtdb.firebaseio.com/config.json"
# We read the current version code from build.gradle.kts to know what to set
$gradleContent = Get-Content "app/build.gradle.kts" -Raw
$versionCodeMatch = [regex]::Match($gradleContent, 'versionCode\s*=\s*(\d+)')
$currentVersionCode = if ($versionCodeMatch.Success) { [int]$versionCodeMatch.Groups[1].Value } else { 0 }

Write-Host "Current App Version Code: $currentVersionCode" -ForegroundColor Cyan

# Interactive Prompt if URL not passed
if ([string]::IsNullOrWhiteSpace($UpdateUrl)) {
    $UpdateUrl = Read-Host "Please paste the 'app-debug.apk' download link from GitHub"
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
