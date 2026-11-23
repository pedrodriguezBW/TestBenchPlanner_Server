@echo off
rem ==========================================================================
rem  TestBenchPlanner - PASO 2 de la migración: importar en PostgreSQL.
rem  En el PC SERVIDOR, con backend\config\application-postgresql.yml.
rem
rem  A) FUSIÓN DE TRES VÍAS (cuando se ha trabajado a la vez en SharePoint y en
rem     el servidor): conserva lo hecho en PostgreSQL y añade lo hecho en
rem     SharePoint desde la exportación BASE (la que se usó para cargar la base).
rem       importar-en-postgresql.cmd CARPETA_BASE CARPETA_SHAREPOINT [simular^|aplicar] [conflictos.json]
rem     - Por defecto SIMULA: calcula, verifica y lo deshace todo.
rem     - Deja una carpeta de trabajo junto a CARPETA_SHAREPOINT
rem       (reconciliacion-AAAAMMDD-HHMMSS) con: postgresql-antes (copia JSON de
rem       la base antes de importar), fusion, conflictos.json e informe.json.
rem     - Si hay conflictos NO importa nada: rellena «elegir» en conflictos.json
rem       y vuelve a lanzar pasando ese fichero como cuarto parámetro.
rem     - «aplicar» exige el servicio TestBenchPlanner PARADO (nadie editando).
rem
rem  B) IMPORTACIÓN CLÁSICA (deja la base IGUAL que la exportación):
rem       importar-en-postgresql.cmd CARPETA_EXPORTACION [simular]
rem
rem  Antes de aplicar en una base con datos: copia de seguridad (pg_dump).
rem ==========================================================================
setlocal
if "%~1"=="" goto uso
cd /d "%~dp0..\..\backend"
set "JAVA=java"
if defined JAVA_HOME set "JAVA=%JAVA_HOME%\bin\java"
if not exist target\testbenchplanner.jar (
  echo Falta backend\target\testbenchplanner.jar: ejecuta antes mvnw.cmd clean verify
  exit /b 2
)
if not exist config\application-postgresql.yml (
  echo Falta backend\config\application-postgresql.yml (ver scripts\servidor\application-postgresql.yml.ejemplo^).
  exit /b 2
)
if not "%~2"=="" if exist "%~2\" goto tresvias

rem ---- B) importación clásica -------------------------------------------------
set "CARPETA=%~f1"
set "SIMULAR=false"
if /i "%~2"=="simular" set "SIMULAR=true"
"%JAVA%" -jar target\testbenchplanner.jar --spring.profiles.active=postgresql --planner.almacen=postgresql ^
  --server.port=0 "--planner.migracion.importar=%CARPETA%" --planner.migracion.simular=%SIMULAR%
if errorlevel 1 (
  echo.
  echo ERROR o RECHAZADA: no se ha guardado nada. Revisa el informe anterior.
  exit /b 1
)
exit /b 0

rem ---- A) fusión de tres vías -------------------------------------------------
:tresvias
set "BASE=%~f1"
set "SHAREPOINT=%~f2"
set "SIMULAR=true"
if /i "%~3"=="aplicar" set "SIMULAR=false"
if not "%~3"=="" if /i not "%~3"=="aplicar" if /i not "%~3"=="simular" (
  echo El tercer parametro debe ser simular o aplicar ^(es "%~3"^).
  exit /b 2
)
set "RESOLUCIONES="
if not "%~4"=="" (
  if not exist "%~4" (
    echo No existe el fichero de resoluciones: %~4
    exit /b 2
  )
  set "RESOLUCIONES=%~f4"
)
if "%SIMULAR%"=="false" (
  sc query TestBenchPlanner 2>nul | find "RUNNING" >nul
  if not errorlevel 1 (
    echo El servicio TestBenchPlanner esta en marcha: paralo antes de aplicar
    echo ^(sc stop TestBenchPlanner, como Administrador^) para que nadie edite durante la fusion.
    exit /b 2
  )
  echo.
  echo Se va a APLICAR la fusion en la base de datos. Hay copia de seguridad reciente?
  echo ^(scripts\servidor\servicio\copia-seguridad.cmd^)
  choice /c SN /m "Continuar"
  if errorlevel 2 exit /b 2
)
echo.
echo Base comun : %BASE%
echo SharePoint : %SHAREPOINT%
if defined RESOLUCIONES echo Resoluciones: %RESOLUCIONES%
if "%SIMULAR%"=="true" (echo Modo       : SIMULAR ^(no se guarda nada^)) else (echo Modo       : APLICAR)
echo.
"%JAVA%" -jar target\testbenchplanner.jar --spring.profiles.active=postgresql --planner.almacen=postgresql ^
  --server.port=0 "--planner.migracion.importar=%SHAREPOINT%" "--planner.migracion.base=%BASE%" ^
  "--planner.migracion.resoluciones=%RESOLUCIONES%" --planner.migracion.simular=%SIMULAR%
if errorlevel 3 (
  echo.
  echo HAY CONFLICTOS SIN RESOLVER: no se ha guardado nada.
  echo Abre conflictos.json de la carpeta de trabajo indicada arriba, rellena elegir
  echo y vuelve a lanzar:  %~nx0 "%~1" "%~2" simular RUTA\conflictos.json
  exit /b 3
)
if errorlevel 1 (
  echo.
  echo ERROR o RECHAZADA: no se ha guardado nada. Revisa el informe anterior.
  exit /b 1
)
if "%SIMULAR%"=="true" (
  echo.
  echo Simulacion correcta. Para guardar:  %~nx0 "%~1" "%~2" aplicar [conflictos.json]
)
exit /b 0

:uso
echo Uso:
echo   Fusion de tres vias:  %~nx0 CARPETA_BASE CARPETA_SHAREPOINT [simular^|aplicar] [conflictos.json]
echo   Importacion clasica:  %~nx0 CARPETA_EXPORTACION [simular]
exit /b 2
