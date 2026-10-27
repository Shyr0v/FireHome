# Remet en place les paquets retirés ou désactivés par debloat.ps1 (liste dans retires.txt)
# Lancement : powershell -ExecutionPolicy Bypass -File restore.ps1
# À la main : adb shell cmd package install-existing <paquet>  (retiré)
#             adb shell pm enable <paquet>                     (désactivé)

$DEVICE = $env:FIRETV   # IP du Fire TV : variable FIRETV, sinon firehome.firetv dans local.properties
if (-not $DEVICE) {
    $lp = Join-Path (Split-Path $PSScriptRoot -Parent) "local.properties"
    if (Test-Path $lp) {
        $m = Select-String -Path $lp -Pattern '^firehome\.firetv=(.+)$' | Select-Object -First 1
        if ($m) { $DEVICE = $m.Matches[0].Groups[1].Value.Trim() }
    }
}
if (-not $DEVICE) { Write-Host "IP du Fire TV inconnue : ajoute firehome.firetv=IP:5555 dans local.properties" -ForegroundColor Red; exit 1 }

if (-not (Test-Path "retires.txt")) { Write-Host "retires.txt introuvable." -ForegroundColor Red; exit 1 }

adb connect $DEVICE | Out-Null
$lines = Get-Content "retires.txt" | Where-Object { $_.Trim() } | Sort-Object -Unique

foreach ($l in $lines) {
    $pkg = ($l -split ";")[0].Trim()
    # Les deux commandes sont sans effet si elles ne s'appliquent pas
    $a = (adb -s $DEVICE shell cmd package install-existing $pkg 2>&1) -join " "
    $b = (adb -s $DEVICE shell pm enable $pkg 2>&1) -join " "
    if ($a -match "installed" -or $b -match "enabled") { Write-Host "  [OK] $pkg" -ForegroundColor Green }
    else { Write-Host "  [??] $pkg : $a / $b" -ForegroundColor Yellow }
}

Remove-Item "retires.txt"
Write-Host "`nTout est restauré. Redémarre le Fire TV : adb -s $DEVICE reboot"
