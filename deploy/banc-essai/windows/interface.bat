@echo off
rem Interface du responsable sur la station d'essai : s'ouvre dans le navigateur.
rem Au premier lancement, elle propose de creer l'organisation (equivalent de
rem   admin.bat organisation-creer --organisation essai --cles donnees\organisation.json)
rem puis guide pas a pas (onglet "Demarrage"). Le compte de l'annuaire est celui de config.bat.
setlocal
call "%~dp0config.bat"
call "%~dp0admin.bat" interface --cles "%DONNEES%\organisation.json" --codes "%DONNEES%\codes.json" ^
    --annuaire http://localhost:%ANNUAIRE_PORT% --ouvrir
exit /b %errorlevel%
