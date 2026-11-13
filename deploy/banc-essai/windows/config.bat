@echo off
rem Reglages communs des scripts de developpement sous Windows (station d'essai, donnees synthetiques uniquement).
rem Modifier ce fichier, puis lancer dev-annuaire.bat, dev-relais.bat et dev-box.bat, chacun dans sa fenetre.

rem --- Java 25 : "java" du PATH, ou chemin complet, par exemple C:\Program Files\Java\jdk-25\bin\java.exe
if not defined JAVA set "JAVA=java"

rem --- PostgreSQL de la station (relais et annuaire) : psql du PATH, ou chemin complet
if not defined PSQL set "PSQL=psql"
set "PG_HOTE=localhost"
set "PG_PORT=5432"
rem Mots de passe d'essai des deux bases, crees par creer-bases.bat : a changer si la station est partagee.
set "RELAIS_DB_MOT_DE_PASSE=relais-essai"
set "ANNUAIRE_DB_MOT_DE_PASSE=annuaire-essai"

rem --- Ports
set "ANNUAIRE_PORT=8090"
set "RELAIS_PORT=8443"

rem --- Cle du responsable de l'organisation (admin.bat organisation-creer, voir README.md)
rem Empreinte courte affichee a la creation de la cle, verifiee puis recopiee ici : sans elle, le relais et les box
rem refusent de demarrer.
set "ORGANISATION_CODE="

rem --- Compte de depot du responsable sur l'annuaire (essai)
set "ANNUAIRE_COMPTE_UTILISATEUR=responsable"
set "ANNUAIRE_COMPTE_MOT_DE_PASSE=depot-essai"

rem --- Compte de la console des box (essai)
set "CONSOLE_UTILISATEUR=operateur"
set "CONSOLE_MOT_DE_PASSE=essai-synthetique"

rem --- Chemins (ne pas modifier)
set "ICI=%~dp0"
for %%I in ("%~dp0..\..\..") do set "RACINE=%%~fI"
set "DONNEES=%ICI%donnees"
set "CLE_ORGANISATION=%DONNEES%\organisation.json.pub.json"
set "JAR_ANNUAIRE=%RACINE%\backend\directory-server\target\imagunity-directory.jar"
set "JAR_RELAIS=%RACINE%\backend\relay\target\imagunity-relay-exec.jar"
set "JAR_BOX=%RACINE%\backend\app\target\imagunity-box.jar"
rem Messages accentues lisibles dans la console Windows.
set "JAVA_ENCODAGE=-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"
