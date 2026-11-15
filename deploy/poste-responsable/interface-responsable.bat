@echo off
rem Interface du responsable de l'organisation ImagUnity : double-cliquer sur ce fichier.
rem Elle s'ouvre dans le navigateur de ce poste. Au premier lancement, elle propose de creer l'organisation
rem (onglet "Demarrage"), puis guide pas a pas : site central, compte de l'annuaire, cle de fraicheur, box.
rem Reglages (adresse de l'annuaire, dossier des fichiers) : reglages.bat.
setlocal
chcp 65001 >nul
title ImagUnity - interface du responsable
call "%~dp0reglages.bat"

set "JAR=%~dp0imagunity-directory.jar"
if not exist "%JAR%" set "JAR=%~dp0..\..\backend\directory-server\target\imagunity-directory.jar"
if not exist "%JAR%" goto :sans_jar

"%JAVA%" -version >nul 2>&1
if errorlevel 1 goto :sans_java

if not exist "%DOSSIER%" mkdir "%DOSSIER%"
echo.
echo  Interface du responsable ImagUnity
echo  Annuaire : %ANNUAIRE%
echo  Fichiers de l'organisation : %DOSSIER%
echo.
echo  La page s'ouvre dans le navigateur. Laisser cette fenetre ouverte pendant l'utilisation ;
echo  pour arreter : bouton "Quitter" de la page, ou fermer cette fenetre.
echo.
"%JAVA%" -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp "%JAR%" ^
    -Dloader.main=com.imagunity.directory.DirectoryAdmin org.springframework.boot.loader.launch.PropertiesLauncher ^
    interface --cles "%DOSSIER%\organisation.json" --codes "%DOSSIER%\codes.json" --annuaire "%ANNUAIRE%" ^
    --validite-jours %VALIDITE_JOURS% --ouvrir
if errorlevel 1 pause
exit /b %errorlevel%

:sans_jar
echo Fichier imagunity-directory.jar introuvable a cote de ce lanceur.
echo Il est fourni dans la livraison (dossier poste-responsable), ou construit par construire.bat.
pause
exit /b 1

:sans_java
echo Java 25 introuvable ("%JAVA%"). L'installer (par exemple Eclipse Temurin 25),
echo ou indiquer son chemin complet dans reglages.bat (JAVA=...\bin\java.exe).
pause
exit /b 1
