# Restaure une sauvegarde sur le Fire TV (meme stick reinitialise, ou un nouveau)
# Lancement : powershell -ExecutionPolicy Bypass -File restaurer.ps1
#             (prend la sauvegarde la plus recente, ou : -Dossier "..\sauvegardes\2026-10-27_15-00")

param([string]$Dossier = "")
$DEVICE = $env:FIRETV   # IP du Fire TV : variable FIRETV, sinon firehome.firetv dans local.properties
if (-not $DEVICE) {
    $lp = Join-Path (Split-Path $PSScriptRoot -Parent) "local.properties"
    if (Test-Path $lp) {
        $m = Select-String -Path $lp -Pattern '^firehome\.firetv=(.+)$' | Select-Object -First 1
        if ($m) { $DEVICE = $m.Matches[0].Groups[1].Value.Trim() }
    }
}
if (-not $DEVICE) { Write-Host "IP du Fire TV inconnue : ajoute firehome.firetv=IP:5555 dans local.properties" -ForegroundColor Red; exit 1 }
Set-Location $PSScriptRoot

if (-not $Dossier) {
    $last = Get-ChildItem (Join-Path (Split-Path $PSScriptRoot -Parent) "sauvegardes") -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1
    if (-not $last) { Write-Host "Aucune sauvegarde trouvee dans le dossier sauvegardes." -ForegroundColor Red; exit 1 }
    $Dossier = $last.FullName
}
Write-Host "Sauvegarde utilisee : $Dossier"

Write-Host "Connexion a $DEVICE (accepte l'autorisation sur la TV si demande)..."
adb connect $DEVICE | Out-Null
$state = (adb -s $DEVICE get-state 2>&1) -join " "
if ($state -notmatch "device") { Write-Host "Fire TV injoignable : $state" -ForegroundColor Red; exit 1 }

# 1. Apps installees a la main (FireHome, SmartTube...)
Write-Host "`n[1/5] Reinstallation des APK..." -ForegroundColor Cyan
Get-ChildItem "$Dossier\apk\*.apk" -ErrorAction SilentlyContinue | ForEach-Object {
    $out = (adb -s $DEVICE install -r $_.FullName 2>&1) -join " "
    Write-Host "  $($_.BaseName) : $(if ($out -match 'Success') { 'OK' } else { $out })"
}

# 2. Config FireHome
Write-Host "`n[2/5] Config FireHome..." -ForegroundColor Cyan
if (Test-Path "$Dossier\firehome_backup.json") {
    $json = [IO.File]::ReadAllText("$Dossier\firehome_backup.json")
    $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($json))
    $r = (adb -s $DEVICE shell am broadcast -a com.axel.homeboard.IMPORT -n com.axel.homeboard/.BackupReceiver --es data $b64 2>&1) -join " "
    if ($r -match 'data="([^"]*)"') { Write-Host "  $($Matches[1])" } else { Write-Host "  $r" }
}

# 3. DNS prive
Write-Host "`n[3/5] DNS prive..." -ForegroundColor Cyan
if (Test-Path "$Dossier\systeme.json") {
    $sys = Get-Content "$Dossier\systeme.json" -Raw | ConvertFrom-Json
    if ($sys.private_dns_mode -and $sys.private_dns_mode -ne "null") {
        adb -s $DEVICE shell settings put global private_dns_mode $sys.private_dns_mode
        if ($sys.private_dns_specifier -and $sys.private_dns_specifier -ne "null") {
            adb -s $DEVICE shell settings put global private_dns_specifier $sys.private_dns_specifier
        }
        Write-Host "  $($sys.private_dns_mode) / $($sys.private_dns_specifier)"
    }
}

# 4. Redirection du bouton Accueil + relance de FireHome
Write-Host "`n[4/5] Bouton Accueil..." -ForegroundColor Cyan
adb -s $DEVICE shell am force-stop com.axel.homeboard
adb -s $DEVICE shell settings put secure enabled_accessibility_services com.axel.homeboard/com.axel.homeboard.HomeRedirectService
adb -s $DEVICE shell settings put secure accessibility_enabled 1
adb -s $DEVICE shell am start -n com.axel.homeboard/.MainActivity | Out-Null
Write-Host "  OK"

# 5. Apps a reinstaller depuis l'Appstore
Write-Host "`n[5/5] Apps manquantes..." -ForegroundColor Cyan
if (Test-Path "$Dossier\apps.json") {
    $saved = Get-Content "$Dossier\apps.json" -Raw | ConvertFrom-Json
    $now = adb -s $DEVICE shell pm list packages -3 | ForEach-Object { $_ -replace '^package:', '' }
    $missing = $saved | Where-Object { $now -notcontains $_.Paquet }
    if ($missing) {
        Write-Host "  A reinstaller depuis l'Appstore :" -ForegroundColor Yellow
        $missing | ForEach-Object { Write-Host "   - $($_.Paquet)" }
    } else { Write-Host "  Rien a reinstaller" -ForegroundColor Green }
}

if (Test-Path "$Dossier\debloat.ps1") {
    Write-Host "`nPour refaire le nettoyage : powershell -ExecutionPolicy Bypass -File `"$Dossier\debloat.ps1`""
}
Write-Host "`nRestauration terminee." -ForegroundColor Green
