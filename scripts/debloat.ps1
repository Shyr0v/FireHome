# Debloat Fire TV Stick 4K (2e gen, Fire OS 8)
# Lancement : powershell -ExecutionPolicy Bypass -File debloat.ps1
#
# Retire les paquets pour l'utilisateur 0 (pm uninstall --user 0),
# ou les désactive (pm disable-user) quand Fire OS bloque la suppression.
# Rien n'est supprimé du système : restore.ps1 remet tout en place.
# Chaque paquet retiré est noté dans retires.txt.

$DEVICE = $env:FIRETV   # IP du Fire TV : variable FIRETV, sinon firehome.firetv dans local.properties
if (-not $DEVICE) {
    $lp = Join-Path (Split-Path $PSScriptRoot -Parent) "local.properties"
    if (Test-Path $lp) {
        $m = Select-String -Path $lp -Pattern '^firehome\.firetv=(.+)$' | Select-Object -First 1
        if ($m) { $DEVICE = $m.Matches[0].Groups[1].Value.Trim() }
    }
}
if (-not $DEVICE) { Write-Host "IP du Fire TV inconnue : ajoute firehome.firetv=IP:5555 dans local.properties" -ForegroundColor Red; exit 1 }

# --- Sans risque : télémétrie, pubs, apps Amazon inutiles --------------
$SAFE = @(
    # Télémétrie / collecte
    "com.amazon.tv.acr",                       # reconnaissance de ce que tu regardes
    "com.amazon.device.telemetry.emitter",
    "com.amazon.device.metrics",
    "com.amazon.wirelessmetrics.service",
    "com.amazon.perfcollection",
    "com.amazon.device.logmanager",
    "com.amazon.device.crashmanager",
    "com.amazon.neo.minerva.logcat",
    # Pubs / ID publicitaire
    "com.amazon.hybridadidservice",
    "com.amazon.ftvads.deeplinking",
    "com.amazon.sneakpeek",                    # vidéos auto sur l'accueil
    # Apps inutiles
    "com.amazon.shoptv.client",
    "com.amazon.shoptv.firetv.client",
    "com.amazon.minitv.android.app",
    "com.amazon.gamehub",
    "com.amazon.bueller.photos",
    "com.amazon.bueller.music",
    "com.amazon.ods.kindleconnect",
    "com.amazon.smarthomemapviewapp",
    "com.amazon.tv.website_launcher",
    "com.amazon.tv.earlyaccess",
    "com.amazon.tv.releasenotes",
    "com.amazon.storm.lightning.tutorial",
    "com.amazon.tmm.tutorial"
)

# --- Optionnel : demandé un par un ---------------------------------------
$OPTIONAL = [ordered]@{
    "com.amazon.media.recommendations"     = "Recommandations sur l'accueil Amazon (inutile avec ton launcher)"
    "com.amazon.tv.ftvambient"             = "Mode Ambiant (écran de veille avec pubs/infos)"
    "com.amazon.ftv.screensaver"           = "Écran de veille Amazon (photos)"
    "com.amazon.tv.livetv"                 = "Onglet Live TV / chaînes"
    "com.amazon.tahoe"                     = "Amazon Kids / contrôle enfants"
    "com.amazon.cloud9"                    = "Navigateur Silk"
    "com.apple.atve.amazon.appletv"        = "App Apple TV"
    "com.amazon.tv.alexanotifications"     = "Notifications Alexa"
    "com.amazon.tv.alexaalerts"            = "Alertes/minuteurs Alexa"
    "com.amazon.tv.matter"                 = "Maison connectée Matter"
    "com.amazon.tv.mattercohost"           = "Maison connectée Matter (hôte)"
    "com.amazon.tv.forcedotaupdater.v2"    = "Mises à jour forcées (ATTENTION : tu n'auras plus les MAJ de sécurité automatiques)"
}

# Ne JAMAIS toucher (pour info) : com.amazon.tv.launcher, com.amazon.firehomestarter,
# com.amazon.tv.settings.*, com.amazon.tv.ime, com.amazon.venezia (Appstore),
# com.amazon.webview.chromium, com.amazon.device.software.ota, com.amazon.tcomm,
# com.amazon.dcp, com.amazon.identity.*, com.amazon.fireinputdevices,
# com.amazon.tv.keypolicymanager, com.amazon.firebat (Prime Video)

function Remove-Pkg($pkg) {
    $out = (adb -s $DEVICE shell pm uninstall -k --user 0 $pkg 2>&1) -join " "
    if ($out -match "Success") {
        Write-Host "  [RETIRE]    $pkg" -ForegroundColor Green
        Add-Content -Path "retires.txt" -Value "$pkg;uninstall"
        return
    }
    if ($out -match "not installed") {
        Write-Host "  [--]        $pkg (déjà retiré)" -ForegroundColor DarkGray
        return
    }
    # Suppression bloquée par Fire OS : on désactive à la place
    $out2 = (adb -s $DEVICE shell pm disable-user --user 0 $pkg 2>&1) -join " "
    if ($out2 -match "disabled-user") {
        Write-Host "  [DESACTIVE] $pkg" -ForegroundColor Green
        Add-Content -Path "retires.txt" -Value "$pkg;disable"
    } else {
        Write-Host "  [REFUS]     $pkg : $out2" -ForegroundColor Yellow
    }
}

Write-Host "Connexion à $DEVICE..."
adb connect $DEVICE | Out-Null
$state = (adb -s $DEVICE get-state 2>&1) -join " "
if ($state -notmatch "device") { Write-Host "Fire TV injoignable : $state" -ForegroundColor Red; exit 1 }

Write-Host "`n== Paquets sans risque ==" -ForegroundColor Cyan
foreach ($p in $SAFE) { Remove-Pkg $p }

Write-Host "`n== Paquets optionnels ==" -ForegroundColor Cyan
foreach ($p in $OPTIONAL.Keys) {
    $r = Read-Host "Retirer $p ? ($($OPTIONAL[$p])) [o/N]"
    if ($r -match "^[oOyY]") { Remove-Pkg $p }
}

Write-Host "`nTerminé. Redémarrage du Fire TV conseillé : adb -s $DEVICE reboot" -ForegroundColor Cyan
Write-Host "Pour tout remettre : powershell -ExecutionPolicy Bypass -File restore.ps1"
