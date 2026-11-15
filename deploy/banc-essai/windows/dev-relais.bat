@echo off
rem Relais IU-T, sur http://localhost:%RELAIS_PORT% (base PostgreSQL « relais », morceaux sur le disque).
rem Il lit l'annuaire : lancer dev-annuaire.bat d'abord.
setlocal
chcp 65001 >nul
title ImagUnity - relais
call "%~dp0config.bat"
if not exist "%JAR_RELAIS%" goto :absent
set "CONFIG=%MACHINE_RELAIS%\config"
if not exist "%CONFIG%\organisation.pub.json" goto :cle
if not defined ORGANISATION_CODE goto :code
set "IMAGUNITY_RELAY_PORT=%RELAIS_PORT%"
set "IMAGUNITY_RELAY_DB_URL=jdbc:postgresql://%PG_HOTE%:%PG_PORT%/relais"
set "IMAGUNITY_RELAY_DB_USER=relais"
set "IMAGUNITY_RELAY_DB_PASSWORD=%RELAIS_DB_MOT_DE_PASSE%"
set "IMAGUNITY_RELAY_STORAGE_DIR=%MACHINE_RELAIS%\morceaux"
set "IMAGUNITY_RELAY_DIRECTORY_URL=http://localhost:%ANNUAIRE_PORT%"
set "IMAGUNITY_RELAY_ORGANIZATION_KEY=%CONFIG%\organisation.pub.json"
set "IMAGUNITY_RELAY_ORGANIZATION_CODE=%ORGANISATION_CODE%"
rem Essai : l'annuaire relu toutes les 30 s (5 min par defaut), pour voir vite une box nouvellement enrolee.
set "IMAGUNITY_RELAY_DIRECTORY_SYNC_INTERVAL=30s"
if not exist "%MACHINE_RELAIS%\travail" mkdir "%MACHINE_RELAIS%\travail"
cd /d "%MACHINE_RELAIS%\travail"
echo Relais sur http://localhost:%RELAIS_PORT%
"%JAVA%" %JAVA_ENCODAGE% -jar "%JAR_RELAIS%"
exit /b %errorlevel%
:absent
echo Relais pas encore construit : lancer construire.bat
exit /b 1
:cle
echo Cle publique de l'organisation absente de la machine relais (%CONFIG%\organisation.pub.json) : deposer.bat
exit /b 1
:code
echo ORGANISATION_CODE vide dans config.bat : y recopier l'empreinte affichee par l'interface du responsable
exit /b 1
