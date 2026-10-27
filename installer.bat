@echo off
chcp 65001 >nul
cd /d "%~dp0"
rem IP du Fire TV : variable FIRETV, sinon firehome.firetv dans local.properties
if not defined FIRETV for /f "tokens=1,* delims==" %%a in ('findstr /b /l /c:"firehome.firetv=" local.properties 2^>nul') do set "FIRETV=%%b"
if not defined FIRETV (
    echo IP du Fire TV inconnue : ajoute firehome.firetv=IP:5555 dans local.properties
    pause
    exit /b 1
)

rem Java fourni avec Android Studio
if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"

echo.
echo [1/4] Compilation de FireHome (la premiere fois peut prendre quelques minutes)...
call gradlew.bat assembleRelease
if errorlevel 1 goto :err

echo.
echo [2/4] Connexion au Fire TV %FIRETV%...
adb connect %FIRETV%

echo.
echo [3/4] Installation...
adb -s %FIRETV% install -r app\build\outputs\apk\release\app-release.apk
if errorlevel 1 goto :err

echo.
echo [4/4] Activation de la redirection du bouton Accueil...
adb -s %FIRETV% shell settings put secure enabled_accessibility_services com.axel.homeboard/com.axel.homeboard.HomeRedirectService
adb -s %FIRETV% shell settings put secure accessibility_enabled 1
adb -s %FIRETV% shell am start -n com.axel.homeboard/.MainActivity

echo.
echo Termine ! Appuie sur le bouton Accueil de la telecommande pour tester.
pause
exit /b 0

:err
echo.
echo *** ERREUR : copie le message ci-dessus et envoie-le a Claude ***
pause
exit /b 1
