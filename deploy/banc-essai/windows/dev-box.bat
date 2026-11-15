@echo off
rem Box ImagUnity (logiciel lance directement, sans le lanceur des mises a jour signees).
rem   dev-box.bat        box « a » : console http://localhost:8081, entree DICOM 11112
rem   dev-box.bat b      box « b » : console http://localhost:8082, entree DICOM 11113
rem Chaque box est une machine simulee : machines\box-a, machines\box-b, avec sa configuration (config\ : cle
rem publique de l'organisation deposee par deposer.bat) et ses donnees (donnees\ : identite, base, transferts).
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
set "MACHINE=%MACHINE_BOX%-%BOX%"
set "CONFIG=%MACHINE%\config"
if not exist "%CONFIG%\organisation.pub.json" goto :cle
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
set "IMAGUNITY_DATA_DIR=%MACHINE%\donnees"
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
set "IMAGUNITY_DIRECTORY_ORGANIZATION_KEY=%CONFIG%\organisation.pub.json"
set "IMAGUNITY_DIRECTORY_ORGANIZATION_CODE=%ORGANISATION_CODE%"
rem Essai : l'annuaire relu toutes les 30 s (5 min par defaut).
set "IMAGUNITY_DIRECTORY_SYNC_INTERVAL=30s"
set "IMAGUNITY_IU_RELAY=http://localhost:%RELAIS_PORT%"
rem Code d'enrolement tire par le responsable (interface, onglet Codes) : la box envoie seule sa demande.
rem   set CODE_ENROLEMENT=XXXX-XXXX-XXXX-XXXX-XXXX   puis   dev-box.bat
if defined CODE_ENROLEMENT set "IMAGUNITY_DIRECTORY_ENROLLMENT_CODE=%CODE_ENROLEMENT%"
if not exist "%IMAGUNITY_DATA_DIR%" mkdir "%IMAGUNITY_DATA_DIR%"
cd /d "%IMAGUNITY_DATA_DIR%"
echo Box %BOX% : console http://localhost:%HTTP_PORT%/  (compte %CONSOLE_UTILISATEUR%), entree DICOM sur le port %DICOM_PORT%, destination %DESTINATION_AET%
"%JAVA%" %JAVA_ENCODAGE% -jar "%JAR_BOX%"
exit /b %errorlevel%
:absent
echo Box pas encore construite : lancer construire.bat
exit /b 1
:cle
echo Cle publique de l'organisation absente de la machine box-%BOX% (%CONFIG%\organisation.pub.json) : deposer.bat
exit /b 1
:code
echo ORGANISATION_CODE vide dans config.bat : y recopier l'empreinte affichee par l'interface du responsable
exit /b 1
