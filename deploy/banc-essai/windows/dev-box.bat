@echo off
rem Box ImagUnity (logiciel lance directement, sans le lanceur des mises a jour signees).
rem   dev-box.bat        box « a » : console http://localhost:8081, entree DICOM 11112
rem   dev-box.bat b      box « b » : console http://localhost:8082, entree DICOM 11113
rem Chaque box a son dossier de donnees (identite, base, transferts) : donnees\box-a, donnees\box-b.
rem Destination DICOM : IU_TO_B (box a) ou IU_TO_A (box b), vers le partenaire box-b ou box-a ; PACS de restitution : voir PACS_* ci-dessous.
setlocal
chcp 65001 >nul
call "%~dp0config.bat"
set "BOX=%~1"
if "%BOX%"=="" set "BOX=a"
if /i "%BOX%"=="a" goto :a
if /i "%BOX%"=="b" goto :b
echo Box inconnue : %BOX% (a ou b)
exit /b 1
:a
set "BOX=a"
set "AUTRE=b"
set "DESTINATION_AET=IU_TO_B"
set "HTTP_PORT=8081"
set "DICOM_PORT=11112"
set "BOX_AET=IMAGUNITY_A"
goto :suite
:b
set "BOX=b"
set "AUTRE=a"
set "DESTINATION_AET=IU_TO_A"
set "HTTP_PORT=8082"
set "DICOM_PORT=11113"
set "BOX_AET=IMAGUNITY_B"
:suite
title ImagUnity - box %BOX%
if not exist "%JAR_BOX%" goto :absent
if not exist "%CLE_ORGANISATION%" goto :cle
if not defined ORGANISATION_CODE goto :code

rem --- PACS local de la box (a adapter : Orthanc d'essai, par exemple) : emetteurs admis et PACS de restitution.
if not defined PACS_AET_EMETTEUR set "PACS_AET_EMETTEUR=ORTHANC"
if not defined PACS_HOTE set "PACS_HOTE=127.0.0.1"
if not defined PACS_PORT set "PACS_PORT=4242"
if not defined PACS_AET set "PACS_AET=ORTHANC"

set "IMAGUNITY_BOX_NAME=Box d'essai %BOX%"
set "SERVER_ADDRESS=127.0.0.1"
set "IMAGUNITY_DICOM_BIND_ADDRESS=127.0.0.1"
set "IMAGUNITY_HTTP_PORT=%HTTP_PORT%"
set "IMAGUNITY_DICOM_PORT=%DICOM_PORT%"
set "IMAGUNITY_DATA_DIR=%DONNEES%\box-%BOX%"
set "IMAGUNITY_CONSOLE_USER=%CONSOLE_UTILISATEUR%"
set "IMAGUNITY_CONSOLE_PASSWORD=%CONSOLE_MOT_DE_PASSE%"
set "IMAGUNITY_DICOM_ALLOWED_CALLING_AETS=%PACS_AET_EMETTEUR%"
set "IMAGUNITY_DICOM_ROUTES_0_CALLED_AET=%DESTINATION_AET%"
set "IMAGUNITY_DICOM_ROUTES_0_DESTINATION=box-%AUTRE%"
set "IMAGUNITY_PACS_HOST=%PACS_HOTE%"
set "IMAGUNITY_PACS_PORT=%PACS_PORT%"
set "IMAGUNITY_PACS_AET=%PACS_AET%"
set "IMAGUNITY_BOX_AET=%BOX_AET%"
set "IMAGUNITY_DIRECTORY_ENABLED=true"
set "IMAGUNITY_DIRECTORY_URL=http://localhost:%ANNUAIRE_PORT%"
set "IMAGUNITY_DIRECTORY_ORGANIZATION_KEY=%CLE_ORGANISATION%"
set "IMAGUNITY_DIRECTORY_ORGANIZATION_CODE=%ORGANISATION_CODE%"
rem Essai : l'annuaire relu toutes les 30 s (5 min par defaut).
set "IMAGUNITY_DIRECTORY_SYNC_INTERVAL=30s"
set "IMAGUNITY_IU_RELAY=http://localhost:%RELAIS_PORT%"
if not exist "%IMAGUNITY_DATA_DIR%" mkdir "%IMAGUNITY_DATA_DIR%"
cd /d "%IMAGUNITY_DATA_DIR%"
echo Box %BOX% : console http://localhost:%HTTP_PORT%/  (compte %CONSOLE_UTILISATEUR%), entree DICOM sur le port %DICOM_PORT%, destination %DESTINATION_AET%
"%JAVA%" %JAVA_ENCODAGE% -jar "%JAR_BOX%"
exit /b %errorlevel%
:absent
echo Box pas encore construite : lancer construire.bat
exit /b 1
:cle
echo Cle du responsable absente (%CLE_ORGANISATION%) : voir README.md
exit /b 1
:code
echo ORGANISATION_CODE vide dans config.bat : y recopier l'empreinte affichee par « admin.bat organisation-creer »
exit /b 1
