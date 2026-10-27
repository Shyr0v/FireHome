# Publie le projet sur GitHub (à lancer une fois depuis la racine du projet)
# Lancement : powershell -ExecutionPolicy Bypass -File publier.ps1
#
# Prérequis : git installé et un compte GitHub.
# - Si GitHub CLI (gh) est installé, le dépôt est créé automatiquement.
# - Sinon, crée un dépôt VIDE nommé "FireHome" sur https://github.com/new
#   (sans README ni .gitignore) puis relance ce script.

Set-Location $PSScriptRoot
$ErrorActionPreference = "Stop"

if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    Write-Host "git n'est pas installé : winget install Git.Git" -ForegroundColor Red; exit 1
}

if (-not (Test-Path ".git")) {
    git init -b main | Out-Null
    Write-Host "Dépôt git créé."
}

git add -A
$pending = git status --porcelain
if ($pending) {
    git commit -m "FireHome : launcher personnalisé pour Fire TV Stick" | Out-Null
    Write-Host "Commit créé."
}

$remote = git remote 2>$null
if (-not $remote) {
    if (Get-Command gh -ErrorAction SilentlyContinue) {
        gh repo create FireHome --public --source . --remote origin --description "Launcher Android TV personnalisé pour Fire TV Stick (Kotlin, Jetpack Compose) : météo, programme TV, profils, écran de veille, sans root" 
    } else {
        $user = Read-Host "Ton nom d'utilisateur GitHub"
        git remote add origin "https://github.com/$user/FireHome.git"
    }
}

git push -u origin main
if ($LASTEXITCODE -ne 0) {
    Write-Host "`nEchec de l'envoi. Verifie que le depot VIDE 'FireHome' existe sur https://github.com/new puis relance ce script." -ForegroundColor Red
    exit 1
}
Write-Host "`nPublié ! " -ForegroundColor Green -NoNewline
git remote get-url origin
