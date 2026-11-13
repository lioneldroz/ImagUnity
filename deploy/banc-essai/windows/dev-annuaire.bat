@echo off
rem Service d'annuaire de l'organisation, sur http://localhost:%ANNUAIRE_PORT% (base PostgreSQL « annuaire »).
setlocal
chcp 65001 >nul
title ImagUnity - annuaire
call "%~dp0config.bat"
if not exist "%JAR_ANNUAIRE%" goto :absent
if not exist "%CLE_ORGANISATION%" goto :cle
set "IMAGUNITY_DIRECTORY_ORGANIZATION_KEY=%CLE_ORGANISATION%"
set "IMAGUNITY_DIRECTORY_PORT=%ANNUAIRE_PORT%"
set "IMAGUNITY_DIRECTORY_DB_URL=jdbc:postgresql://%PG_HOTE%:%PG_PORT%/annuaire"
set "IMAGUNITY_DIRECTORY_DB_USER=annuaire"
set "IMAGUNITY_DIRECTORY_DB_PASSWORD=%ANNUAIRE_DB_MOT_DE_PASSE%"
set "IMAGUNITY_DIRECTORY_PUBLISHER_USER=%ANNUAIRE_COMPTE_UTILISATEUR%"
set "IMAGUNITY_DIRECTORY_PUBLISHER_PASSWORD=%ANNUAIRE_COMPTE_MOT_DE_PASSE%"
if not exist "%DONNEES%\annuaire" mkdir "%DONNEES%\annuaire"
cd /d "%DONNEES%\annuaire"
echo Annuaire sur http://localhost:%ANNUAIRE_PORT%  (interface d'administration : /admin/)
"%JAVA%" %JAVA_ENCODAGE% -jar "%JAR_ANNUAIRE%"
exit /b %errorlevel%
:absent
echo Annuaire pas encore construit : lancer construire.bat
exit /b 1
:cle
echo Cle du responsable absente (%CLE_ORGANISATION%) : la creer avec
echo   admin.bat organisation-creer --organisation essai --cles donnees\organisation.json
exit /b 1
