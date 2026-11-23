@echo off
rem ==========================================================================
rem  TestBenchPlanner - PASO 1 de la migración: exportar desde SharePoint.
rem  En un PC CON permisos de SharePoint (el de siempre). Siempre en SOLO
rem  LECTURA: este script fuerza escritura-habilitada=false.
rem  Uso:  exportar-desde-sharepoint.cmd D:\tbp-exportacion-AAAA-MM-DD
rem  La carpeta debe ser nueva o estar vacía. Contiene datos REALES: no la
rem  subas al repositorio. Mejor con TestBenchPlanner cerrado.
rem ==========================================================================
setlocal
if "%~1"=="" (
  echo Uso: %~nx0 CARPETA_DESTINO
  exit /b 2
)
set "DESTINO=%~f1"
cd /d "%~dp0..\..\backend"
set "JAVA=java"
if defined JAVA_HOME set "JAVA=%JAVA_HOME%\bin\java"
if not exist target\testbenchplanner.jar (
  echo Falta backend\target\testbenchplanner.jar: ejecuta antes mvnw.cmd clean verify
  exit /b 2
)
if not exist config\application-sharepoint.yml (
  echo Falta backend\config\application-sharepoint.yml (sitio de SharePoint^).
  exit /b 2
)
"%JAVA%" -jar target\testbenchplanner.jar --spring.profiles.active=sharepoint --planner.almacen=sharepoint ^
  --planner.sharepoint.escritura-habilitada=false --server.port=0 "--planner.migracion.exportar=%DESTINO%"
if errorlevel 1 (
  echo.
  echo ERROR: la exportacion no se ha completado. Revisa el mensaje anterior.
  exit /b 1
)
echo.
echo Exportacion completa en %DESTINO%
exit /b 0
