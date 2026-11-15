@echo off
rem Simule la remise des fichiers du poste du responsable aux autres machines, comme le feraient l'exploitant du
rem site central et les installateurs des box :
rem   - cle publique de l'organisation -> annuaire, relais, box-a, box-b (sous le nom organisation.pub.json) ;
rem   - cle de fraicheur et sa delegation, si elles existent -> annuaire seulement.
rem La cle privee (organisation.json) et les codes d'enrolement ne quittent jamais le poste du responsable.
rem   deposer.bat                tout deposer
rem   deposer.bat annuaire       une seule machine : annuaire, relais, box-a ou box-b
setlocal
chcp 65001 >nul
call "%~dp0config.bat"
set "CLE=%RESPONSABLE%\organisation.json.pub.json"
if not exist "%CLE%" goto :sans_cle
set "CIBLES=%~1"
if "%CIBLES%"=="" set "CIBLES=annuaire relais box-a box-b"
for %%M in (%CIBLES%) do call :deposer %%M
echo.
echo Empreinte a confirmer de vive voix et a recopier dans config.bat (ORGANISATION_CODE) :
"%JAVA%" %JAVA_ENCODAGE% -cp "%JAR_ANNUAIRE%" -Dloader.main=com.imagunity.directory.DirectoryAdmin ^
    org.springframework.boot.loader.launch.PropertiesLauncher empreinte --cle-publique "%CLE%"
echo Relancer ensuite les services concernes pour qu'ils relisent leurs fichiers.
exit /b 0

:deposer
set "M=%~1"
set "CONNUE="
for %%C in (annuaire relais box-a box-b) do if /i "%%C"=="%M%" set "CONNUE=1"
if not defined CONNUE (
    echo Machine inconnue : %M% ^(annuaire, relais, box-a ou box-b^)
    exit /b 1
)
set "DEST=%MACHINES%\%M%\config"
if not exist "%DEST%" mkdir "%DEST%"
copy /y "%CLE%" "%DEST%\organisation.pub.json" >nul
echo [%M%] cle publique -^> machines\%M%\config\organisation.pub.json
if /i "%M%"=="annuaire" call :fraicheur
exit /b 0

:fraicheur
if not exist "%RESPONSABLE%\fraicheur.json" (
    echo [annuaire] pas encore de cle de fraicheur deleguee ^(interface, etape 4^)
    exit /b 0
)
if not exist "%DEST%\fraicheur" mkdir "%DEST%\fraicheur"
copy /y "%RESPONSABLE%\fraicheur.json" "%DEST%\fraicheur\fraicheur.json" >nul
copy /y "%RESPONSABLE%\delegation.json" "%DEST%\fraicheur\delegation.json" >nul
echo [annuaire] cle de fraicheur -^> machines\annuaire\config\fraicheur\
exit /b 0

:sans_cle
echo Pas encore d'organisation sur le poste du responsable (%CLE%).
echo La creer avec interface.bat (onglet Demarrage), puis relancer deposer.bat.
exit /b 1
