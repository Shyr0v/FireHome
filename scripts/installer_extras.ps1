# Installe SmartTube (YouTube sans pub) et remet le navigateur Silk sur le Fire TV
# Lancement : powershell -ExecutionPolicy Bypass -File installer_extras.ps1

$DEVICE = $env:FIRETV   # IP du Fire TV : variable FIRETV, sinon firehome.firetv dans local.properties
if (-not $DEVICE) {
    $lp = Join-Path (Split-Path $PSScriptRoot -Parent) "local.properties"
    if (Test-Path $lp) {
        $m = Select-String -Path $lp -Pattern '^firehome\.firetv=(.+)$' | Select-Object -First 1
        if ($m) { $DEVICE = $m.Matches[0].Groups[1].Value.Trim() }
    }
}
if (-not $DEVICE) { Write-Host "IP du Fire TV inconnue : ajoute firehome.firetv=IP:5555 dans local.properties" -ForegroundColor Red; exit 1 }
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

Write-Host "Connexion a $DEVICE..."
adb connect $DEVICE | Out-Null

# --- 1. Navigateur Silk (retire pendant le debloat) ---
Write-Host "`n[1/2] Navigateur Internet (Silk)..." -ForegroundColor Cyan
$out = (adb -s $DEVICE shell cmd package install-existing com.amazon.cloud9 2>&1) -join " "
Write-Host "  $out"

# --- 2. SmartTube depuis le GitHub officiel (yuliskov/SmartTube) ---
Write-Host "`n[2/2] SmartTube..." -ForegroundColor Cyan
$releases = Invoke-RestMethod "https://api.github.com/repos/yuliskov/SmartTube/releases?per_page=30" -Headers @{ "User-Agent" = "FireHome" }
$apks = $releases | ForEach-Object {
    $r = $_
    $_.assets | Where-Object { $_.name -like "*.apk" -and $_.name -match "armeabi-v7a" } |
        ForEach-Object { [pscustomobject]@{ Name = $_.name; Url = $_.browser_download_url; Pre = $r.prerelease } }
}
$asset = $apks | Where-Object { -not $_.Pre -and $_.Name -match "stable" } | Select-Object -First 1
if (-not $asset) { $asset = $apks | Where-Object { $_.Name -match "stable" } | Select-Object -First 1 }
if (-not $asset) { $asset = $apks | Select-Object -First 1 }
if (-not $asset) { Write-Host "  Aucun APK trouve sur GitHub." -ForegroundColor Red; exit 1 }

Write-Host "  Telechargement de $($asset.Name)..."
Invoke-WebRequest $asset.Url -OutFile "smarttube.apk"
Write-Host "  Installation sur le Fire TV..."
adb -s $DEVICE install -r smarttube.apk
Remove-Item "smarttube.apk"

Write-Host "`nTermine ! Relance installer.bat (a la racine du projet) pour mettre a jour FireHome." -ForegroundColor Green
