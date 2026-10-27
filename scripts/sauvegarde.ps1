# Sauvegarde complète de la config du Fire TV dans un dossier daté
# Lancement : powershell -ExecutionPolicy Bypass -File sauvegarde.ps1

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
$root = Split-Path $PSScriptRoot -Parent
$dest = Join-Path $root ("sauvegardes\" + (Get-Date -Format "yyyy-MM-dd_HH-mm"))
New-Item -ItemType Directory -Force -Path $dest | Out-Null

Write-Host "Connexion a $DEVICE..."
adb connect $DEVICE | Out-Null
$state = (adb -s $DEVICE get-state 2>&1) -join " "
if ($state -notmatch "device") { Write-Host "Fire TV injoignable : $state" -ForegroundColor Red; exit 1 }

# 1. Config FireHome (2 profils : ordre, apps masquees, reglages, recents)
Write-Host "`n[1/4] Config FireHome..." -ForegroundColor Cyan
$r = (adb -s $DEVICE shell am broadcast -a com.axel.homeboard.EXPORT -n com.axel.homeboard/.BackupReceiver 2>&1) -join " "
if ($r -match 'data="B64:([A-Za-z0-9+/=]+)"') {
    $json = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($Matches[1]))
    [IO.File]::WriteAllText("$dest\firehome_backup.json", $json, (New-Object Text.UTF8Encoding $false))
    Write-Host "  OK" -ForegroundColor Green
} else {
    Write-Host "  Echec : $r" -ForegroundColor Yellow
}

# 2. Reglages systeme (DNS prive)
Write-Host "`n[2/4] Reglages systeme..." -ForegroundColor Cyan
$sys = [ordered]@{
    private_dns_mode      = ((adb -s $DEVICE shell settings get global private_dns_mode) -join "").Trim()
    private_dns_specifier = ((adb -s $DEVICE shell settings get global private_dns_specifier) -join "").Trim()
}
$sys | ConvertTo-Json | Set-Content "$dest\systeme.json" -Encoding UTF8
Write-Host "  DNS : $($sys.private_dns_mode) / $($sys.private_dns_specifier)"

# 3. Liste des apps installees (Appstore et installees a la main)
Write-Host "`n[3/4] Liste des apps..." -ForegroundColor Cyan
$lines = adb -s $DEVICE shell pm list packages -3 -i
$apps = foreach ($l in $lines) {
    if ($l -match 'package:(\S+)\s+installer=(\S+)') { [pscustomobject]@{ Paquet = $Matches[1]; Source = $Matches[2] } }
}
$apps | ConvertTo-Json | Set-Content "$dest\apps.json" -Encoding UTF8
Write-Host "  $(@($apps).Count) apps"

# 4. APK des apps installees a la main (SmartTube, FireHome...) : l'Appstore ne pourra pas les rendre
Write-Host "`n[4/4] APK installes a la main..." -ForegroundColor Cyan
New-Item -ItemType Directory -Force -Path "$dest\apk" | Out-Null
foreach ($a in $apps | Where-Object { $_.Source -eq "null" }) {
    $paths = @(adb -s $DEVICE shell pm path $a.Paquet | ForEach-Object { $_ -replace '^package:', '' })
    if ($paths.Count -eq 1) {
        adb -s $DEVICE pull $paths[0] "$dest\apk\$($a.Paquet).apk" | Out-Null
        Write-Host "  $($a.Paquet)"
    } else {
        Write-Host "  $($a.Paquet) ignore (APK en plusieurs morceaux)" -ForegroundColor DarkGray
    }
}

# Le debloat s'il a ete fait
foreach ($f in @("retires.txt", "debloat.ps1", "restore.ps1")) {
    if (Test-Path $f) { Copy-Item $f $dest }
}

Write-Host "`nSauvegarde terminee : $dest" -ForegroundColor Green
