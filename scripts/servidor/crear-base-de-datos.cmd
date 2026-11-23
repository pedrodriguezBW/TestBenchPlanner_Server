@echo off
rem ==========================================================================
rem  TestBenchPlanner - Crea los usuarios y la base de datos en PostgreSQL.
rem  UNA SOLA VEZ, en el PC servidor, con PostgreSQL ya instalado.
rem  Pide la clave del superusuario «postgres» y las tres claves nuevas
rem  (tbp_owner, tbp_app, tbp_consulta). Guarda esas claves en un lugar seguro:
rem  tbp_owner y tbp_app van luego en backend\config\application-postgresql.yml.
rem  Claves: letras, números y - _ . (sin espacios ni & | ^ %% !).
rem  Carpeta de PostgreSQL: variable PG_BIN o la de la versión 16 por defecto.
rem ==========================================================================
setlocal
if not defined PG_BIN set "PG_BIN=C:\Program Files\PostgreSQL\16\bin"
if not exist "%PG_BIN%\psql.exe" (
  echo No encuentro psql.exe en "%PG_BIN%". Indica la carpeta: set PG_BIN=C:\...\PostgreSQL\NN\bin
  exit /b 2
)
set "SQL=%~dp0..\..\backend\src\main\resources\db\instalacion\01-crear-roles-y-base.sql"
if not exist "%SQL%" (
  echo No encuentro %SQL%
  exit /b 2
)
call :clave "Clave NUEVA de tbp_owner - estructura, solo Flyway" CLAVE_OWNER || exit /b 2
call :clave "Clave NUEVA de tbp_app - la aplicacion" CLAVE_APP || exit /b 2
call :clave "Clave NUEVA de tbp_consulta - pgAdmin y DBeaver, solo lectura" CLAVE_CONSULTA || exit /b 2
echo.
echo Ahora psql pedira la clave del superusuario postgres.
"%PG_BIN%\psql.exe" -h 127.0.0.1 -U postgres -d postgres -W -v clave_owner=%CLAVE_OWNER% -v clave_app=%CLAVE_APP% -v clave_consulta=%CLAVE_CONSULTA% -f "%SQL%"
if errorlevel 1 (
  echo.
  echo ERROR: no se ha creado la base de datos. Revisa el mensaje de psql.
  exit /b 1
)
echo.
echo Base «tbp» creada con los usuarios tbp_owner, tbp_app y tbp_consulta.
echo Siguiente paso: backend\config\application-postgresql.yml (ver scripts\servidor\application-postgresql.yml.ejemplo).
exit /b 0

:clave
for /f "usebackq delims=" %%i in (`powershell -NoProfile -Command "$s = Read-Host '%~1' -AsSecureString; [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($s))"`) do set "%2=%%i"
if not defined %2 (
  echo Clave vacia: se cancela.
  exit /b 1
)
exit /b 0
