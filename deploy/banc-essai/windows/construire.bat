@echo off
rem Construit le relais, l'annuaire et la box (console comprise si Node.js est installe), sans les tests.
setlocal
chcp 65001 >nul
call "%~dp0config.bat"
cd /d "%RACINE%\backend"
where node >nul 2>nul
if errorlevel 1 (
    echo Node.js absent : la box est construite sans sa console web.
    call mvnw.cmd -B -DskipTests -Djacoco.skip=true -Dconsole.skip=true -pl app,relay,directory-server -am package
) else (
    call mvnw.cmd -B -DskipTests -Djacoco.skip=true -pl app,relay,directory-server -am package
)
if errorlevel 1 goto :echec
echo.
echo Construit : %JAR_ANNUAIRE%
echo             %JAR_RELAIS%
echo             %JAR_BOX%
exit /b 0
:echec
echo Construction en echec.
exit /b 1
