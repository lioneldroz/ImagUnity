@echo off
rem Service d'annuaire de l'organisation, sur http://localhost:%ANNUAIRE_PORT% (base PostgreSQL « annuaire »).
rem Machine simulee : machines\annuaire. Elle ne lit que ses propres fichiers, deposes par deposer.bat :
rem   config\organisation.pub.json   cle publique de l'organisation
rem   config\fraicheur\              cle de fraicheur deleguee et sa delegation (facultatif)
setlocal
chcp 65001 >nul
title ImagUnity - annuaire
call "%~dp0config.bat"
if not exist "%JAR_ANNUAIRE%" goto :absent
set "CONFIG=%MACHINE_ANNUAIRE%\config"
if not exist "%CONFIG%\organisation.pub.json" goto :cle
if not defined ORGANISATION_CODE goto :code
set "IMAGUNITY_DIRECTORY_ORGANIZATION_KEY=%CONFIG%\organisation.pub.json"
set "IMAGUNITY_DIRECTORY_ORGANIZATION_CODE=%ORGANISATION_CODE%"
set "IMAGUNITY_DIRECTORY_PORT=%ANNUAIRE_PORT%"
set "IMAGUNITY_DIRECTORY_DB_URL=jdbc:postgresql://%PG_HOTE%:%PG_PORT%/annuaire"
set "IMAGUNITY_DIRECTORY_DB_USER=annuaire"
set "IMAGUNITY_DIRECTORY_DB_PASSWORD=%ANNUAIRE_DB_MOT_DE_PASSE%"
set "IMAGUNITY_DIRECTORY_PUBLISHER_USER=%ANNUAIRE_COMPTE_UTILISATEUR%"
set "IMAGUNITY_DIRECTORY_PUBLISHER_PASSWORD=%ANNUAIRE_COMPTE_MOT_DE_PASSE%"
rem Cle de fraicheur deleguee par le responsable (interface, etape 4), deposee sur cette machine par deposer.bat.
if exist "%CONFIG%\fraicheur\fraicheur.json" if exist "%CONFIG%\fraicheur\delegation.json" (
    set "IMAGUNITY_DIRECTORY_FRESHNESS_KEY=%CONFIG%\fraicheur\fraicheur.json"
    set "IMAGUNITY_DIRECTORY_FRESHNESS_DELEGATION=%CONFIG%\fraicheur\delegation.json"
)
if not exist "%MACHINE_ANNUAIRE%\travail" mkdir "%MACHINE_ANNUAIRE%\travail"
cd /d "%MACHINE_ANNUAIRE%\travail"
echo Annuaire sur http://localhost:%ANNUAIRE_PORT%  (interface d'administration : /admin/)
"%JAVA%" %JAVA_ENCODAGE% -jar "%JAR_ANNUAIRE%"
exit /b %errorlevel%
:absent
echo Annuaire pas encore construit : lancer construire.bat
exit /b 1
:cle
echo Cle publique de l'organisation absente de la machine annuaire (%CONFIG%\organisation.pub.json).
echo Creer l'organisation sur le poste du responsable (interface.bat), puis la deposer : deposer.bat
exit /b 1
:code
echo ORGANISATION_CODE vide dans config.bat : y recopier l'empreinte affichee par l'interface (interface.bat,
echo onglet Demarrage, etape 2) ou par deposer.bat, apres l'avoir verifiee.
exit /b 1
