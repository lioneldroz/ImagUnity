@echo off
rem Outil du responsable (cle de l'organisation, enrolement, publication) : memes commandes que dans le guide.
rem   admin.bat organisation-creer --organisation essai --cles donnees\organisation.json
rem   admin.bat enroler --cles donnees\organisation.json --demande demande-a.json --sortie entree-a.json
rem   admin.bat publier --annuaire http://localhost:8090 --entree entree-a.json
rem La phrase de passe de la cle se saisit au clavier (ou IMAGUNITY_PHRASE_ORGANISATION).
setlocal
chcp 65001 >nul
call "%~dp0config.bat"
if not exist "%JAR_ANNUAIRE%" goto :absent
if not defined IMAGUNITY_ANNUAIRE_COMPTE set "IMAGUNITY_ANNUAIRE_COMPTE=%ANNUAIRE_COMPTE_UTILISATEUR%:%ANNUAIRE_COMPTE_MOT_DE_PASSE%"
if not exist "%DONNEES%" mkdir "%DONNEES%"
cd /d "%ICI%"
"%JAVA%" %JAVA_ENCODAGE% -cp "%JAR_ANNUAIRE%" -Dloader.main=com.imagunity.directory.DirectoryAdmin ^
    org.springframework.boot.loader.launch.PropertiesLauncher %*
exit /b %errorlevel%
:absent
echo Annuaire pas encore construit : lancer construire.bat
exit /b 1
