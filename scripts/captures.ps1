# Prend une capture de l'écran du Fire TV et l'enregistre dans docs/
# Lancement : powershell -ExecutionPolicy Bypass -File scripts\captures.ps1 accueil
#   (le nom est optionnel ; navigue sur la TV puis relance pour chaque écran)

param([string]$Nom = ("capture-" + (Get-Date -Format "HHmmss")))
$DEVICE = $env:FIRETV   # IP du Fire TV : variable FIRETV, sinon firehome.firetv dans local.properties
if (-not $DEVICE) {
    $lp = Join-Path (Split-Path $PSScriptRoot -Parent) "local.properties"
    if (Test-Path $lp) {
        $m = Select-String -Path $lp -Pattern '^firehome\.firetv=(.+)$' | Select-Object -First 1
        if ($m) { $DEVICE = $m.Matches[0].Groups[1].Value.Trim() }
    }
}
if (-not $DEVICE) { Write-Host "IP du Fire TV inconnue : ajoute firehome.firetv=IP:5555 dans local.properties" -ForegroundColor Red; exit 1 }
$docs = Join-Path (Split-Path $PSScriptRoot -Parent) "docs"
New-Item -ItemType Directory -Force -Path $docs | Out-Null

adb connect $DEVICE | Out-Null
adb -s $DEVICE shell screencap -p /sdcard/firehome_capture.png
adb -s $DEVICE pull /sdcard/firehome_capture.png "$docs\$Nom.png" | Out-Null
adb -s $DEVICE shell rm /sdcard/firehome_capture.png
Write-Host "Capture enregistree : docs\$Nom.png" -ForegroundColor Green
