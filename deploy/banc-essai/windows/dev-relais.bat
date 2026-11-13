@echo off
rem Relais IU-T, sur http://localhost:%RELAIS_PORT% (base PostgreSQL « relais », morceaux sur le disque).
rem Il lit l'annuaire : lancer dev-annuaire.bat d'abord.
setlocal
chcp 65001 >nul
title ImagUnity - relais
call "%~dp0config.bat"
if not exist "%JAR_RELAIS%" goto :absent
if not exist "%CLE_ORGANISATION%" goto :cle
if not defined ORGANISATION_CODE goto :code
set "IMAGUNITY_RELAY_PORT=%RELAIS_PORT%"
set "IMAGUNITY_RELAY_DB_URL=jdbc:postgresql://%PG_HOTE%:%PG_PORT%/relais"
set "IMAGUNITY_RELAY_DB_USER=relais"
set "IMAGUNITY_RELAY_DB_PASSWORD=%RELAIS_DB_MOT_DE_PASSE%"
set "IMAGUNITY_RELAY_STORAGE_DIR=%DONNEES%\relais\morceaux"
set "IMAGUNITY_RELAY_DIRECTORY_URL=http://localhost:%ANNUAIRE_PORT%"
set "IMAGUNITY_RELAY_ORGANIZATION_KEY=%CLE_ORGANISATION%"
set "IMAGUNITY_RELAY_ORGANIZATION_CODE=%ORGANISATION_CODE%"
rem Essai : l'annuaire relu toutes les 30 s (5 min par defaut), pour voir vite une box nouvellement enrolee.
set "IMAGUNITY_RELAY_DIRECTORY_SYNC_INTERVAL=30s"
if not exist "%DONNEES%\relais" mkdir "%DONNEES%\relais"
cd /d "%DONNEES%\relais"
echo Relais sur http://localhost:%RELAIS_PORT%
"%JAVA%" %JAVA_ENCODAGE% -jar "%JAR_RELAIS%"
exit /b %errorlevel%
:absent
echo Relais pas encore construit : lancer construire.bat
exit /b 1
:cle
echo Cle du responsable absente (%CLE_ORGANISATION%) : voir README.md
exit /b 1
:code
echo ORGANISATION_CODE vide dans config.bat : y recopier l'empreinte affichee par « admin.bat organisation-creer »
exit /b 1
