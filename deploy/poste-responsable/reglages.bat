@echo off
rem Reglages du poste du responsable. A modifier une fois, puis lancer interface-responsable.bat.

rem --- Adresse de l'annuaire de l'organisation (site central), donnee par l'exploitant.
rem     Station d'essai : http://localhost:8090
if not defined ANNUAIRE set "ANNUAIRE=https://annuaire.exemple.org"

rem --- Dossier des fichiers de l'organisation (cle privee, cle publique, codes d'enrolement, cle de fraicheur).
rem     Par defaut a cote de ce fichier, dans "donnees" : le dossier entier peut ainsi vivre sur une cle USB
rem     dediee, branchee seulement le temps des signatures. A SAUVEGARDER sur un second support.
if not defined DOSSIER set "DOSSIER=%~dp0donnees"

rem --- Java 25 : "java" du PATH, ou chemin complet, par exemple C:\Program Files\Java\jdk-25\bin\java.exe
if not defined JAVA set "JAVA=java"

rem --- Duree de validite des entrees signees, en jours
if not defined VALIDITE_JOURS set "VALIDITE_JOURS=90"
