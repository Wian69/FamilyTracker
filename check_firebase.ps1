param(
    [string]$Url = "https://familiy-tracker-default-rtdb.firebaseio.com/"
)

$configUrl = "$($Url.TrimEnd('/'))/config.json"
$rootUrl = "$($Url.TrimEnd('/'))/.json?shallow=true"

Write-Host "Checking Firebase URL: $Url" -ForegroundColor Cyan

# Check Config Node directly
try {
    Write-Host "1. Reading '$configUrl'..."
    $config = Invoke-RestMethod -Uri $configUrl -Method Get -ErrorAction Stop
    if ($config -eq $null) {
        Write-Host "❌ 'config' node is EMPTY or NULL." -ForegroundColor Red
    }
    else {
        Write-Host "✅ Found 'config' data:" -ForegroundColor Green
        Write-Host ($config | ConvertTo-Json)
    }
}
catch {
    Write-Host "❌ Failed to read 'config'. Error: $($_.Exception.Message)" -ForegroundColor Red
}

Write-Host "------------------------------------------------"

# Check Root Keys (to see where data is)
try {
    Write-Host "2. Reading Root Keys..."
    $root = Invoke-RestMethod -Uri $rootUrl -Method Get -ErrorAction Stop
    if ($root -ne $null) {
        Write-Host "Keys found at Root:" -ForegroundColor Yellow
        $root.PSObject.Properties.Name | ForEach-Object { Write-Host " - $_" }
    }
    else {
        Write-Host "Root returns null (Database might be completely empty)." -ForegroundColor Red
    }
}
catch {
    Write-Host "❌ Failed to read Root. Error: $($_.Exception.Message)" -ForegroundColor Red
}
