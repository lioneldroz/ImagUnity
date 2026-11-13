@echo off
rem Cree, dans le PostgreSQL de la station, les comptes et les bases du relais et de l'annuaire (une seule fois).
rem Demande le mot de passe du superutilisateur « postgres ». Les erreurs « existe deja » sont sans consequence.
setlocal
chcp 65001 >nul
call "%~dp0config.bat"
set /p "PGPASSWORD=Mot de passe de l'utilisateur postgres : "
set "PSQL_ARGS=-h %PG_HOTE% -p %PG_PORT% -U postgres -v ON_ERROR_STOP=0"
"%PSQL%" %PSQL_ARGS% -c "CREATE ROLE relais LOGIN PASSWORD '%RELAIS_DB_MOT_DE_PASSE%'"
"%PSQL%" %PSQL_ARGS% -c "CREATE DATABASE relais OWNER relais"
"%PSQL%" %PSQL_ARGS% -c "CREATE ROLE annuaire LOGIN PASSWORD '%ANNUAIRE_DB_MOT_DE_PASSE%'"
"%PSQL%" %PSQL_ARGS% -c "CREATE DATABASE annuaire OWNER annuaire"
echo.
echo Bases « relais » et « annuaire » pretes. Les tables sont creees au premier demarrage de chaque service.
