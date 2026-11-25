@echo off
rem ==========================================================================
rem  TestBenchPlanner - PASO 2 de la migración: importar en PostgreSQL.
rem  En el PC SERVIDOR, con backend\config\application-postgresql.yml.
rem
rem  FUSIÓN DE TRES VÍAS: conserva lo hecho en el servidor y añade lo hecho en
rem  SharePoint desde la exportación BASE (la que se usó para cargar la base).
rem    importar-en-postgresql.cmd CARPETA_BASE CARPETA_SHAREPOINT [simular^|aplicar] [conflictos.json] [forzar-base]
rem
rem  - Por defecto SIMULA: calcula, verifica y lo deshace todo.
rem  - Comprueba primero que CARPETA_BASE es de verdad la exportación con la que
rem    se cargó la base (D65). Si no lo es o no se puede saber, solo deja simular;
rem    para aplicar igualmente hay que añadir «forzar-base» al final, a propósito.
rem    Tras aplicar, la base recuerda la exportación de SharePoint importada: la
rem    siguiente vez esa carpeta es la BASE obligatoria.
rem  - Nunca borra usuarios con perfil de Timing R&T o cuenta, ni proyectos que use
rem    Timing; y si la importación fuera a cambiar Timing, cuentas, horarios o
rem    calendario, lo deshace todo.
rem  - Deja una carpeta de trabajo junto a CARPETA_SHAREPOINT
rem    (reconciliacion-AAAAMMDD-HHMMSS) con: postgresql-antes (copia JSON de
rem    la base antes de importar), fusion, conflictos.json e informe.json.
rem  - Si hay conflictos NO importa nada: rellena «elegir» en conflictos.json
rem    y vuelve a lanzar pasando ese fichero como cuarto parámetro.
rem  - «aplicar» exige el servicio TestBenchPlanner PARADO (nadie editando), una
rem    ventana de Administrador y hace antes una COPIA DE SEGURIDAD COMPLETA de la
rem    base (servicio\copia-seguridad.ps1); si la copia falla, no importa nada.
rem    Instalación en otra carpeta que C:\TestBenchPlanner: set TBP_INSTALACION=...
rem
rem  La importación clásica (sin carpeta base) se ha retirado (D65): dejaba la
rem  base igual que SharePoint y borraba lo hecho en el servidor.
rem ==========================================================================
setlocal
if "%~1"=="" goto uso
if "%~2"=="" goto uso
if not exist "%~1\" (
  echo No existe la carpeta base: %~1
  goto uso
)
if not exist "%~2\" (
  echo No existe la carpeta de SharePoint: %~2
  goto uso
)
set "BASE=%~f1"
set "SHAREPOINT=%~f2"
set "SIMULAR=true"
if /i "%~3"=="aplicar" set "SIMULAR=false"
if not "%~3"=="" if /i not "%~3"=="aplicar" if /i not "%~3"=="simular" (
  echo El tercer parametro debe ser simular o aplicar ^(es "%~3"^).
  exit /b 2
)
set "RESOLUCIONES="
set "FORZAR=false"
if /i "%~4"=="forzar-base" (
  set "FORZAR=true"
) else if not "%~4"=="" (
  if not exist "%~4" (
    echo No existe el fichero de resoluciones: %~4
    exit /b 2
  )
  set "RESOLUCIONES=%~f4"
)
if /i "%~5"=="forzar-base" set "FORZAR=true"
if not "%~5"=="" if /i not "%~5"=="forzar-base" (
  echo El quinto parametro solo puede ser forzar-base ^(es "%~5"^).
  exit /b 2
)
cd /d "%~dp0..\..\backend"
set "JAVA=java"
if defined JAVA_HOME set "JAVA=%JAVA_HOME%\bin\java"
if not exist target\testbenchplanner.jar (
  echo Falta backend\target\testbenchplanner.jar: ejecuta antes mvnw.cmd clean verify
  exit /b 2
)
if not exist config\application-postgresql.yml (
  echo Falta backend\config\application-postgresql.yml ^(ver scripts\servidor\application-postgresql.yml.ejemplo^).
  exit /b 2
)
if "%SIMULAR%"=="false" (
  sc query TestBenchPlanner 2>nul | find "RUNNING" >nul
  if not errorlevel 1 (
    echo El servicio TestBenchPlanner esta en marcha: paralo antes de aplicar
    echo ^(sc stop TestBenchPlanner, como Administrador^) para que nadie edite durante la fusion.
    exit /b 2
  )
  net session >nul 2>&1
  if errorlevel 1 (
    echo Para aplicar hay que abrir la consola como Administrador: antes se hace
    echo una copia de seguridad completa y su configuracion solo la leen los Administradores.
    exit /b 2
  )
)
echo.
echo Base comun : %BASE%
echo SharePoint : %SHAREPOINT%
if defined RESOLUCIONES echo Resoluciones: %RESOLUCIONES%
if "%FORZAR%"=="true" echo Base       : FORZADA ^(no se exige que sea la exportacion con la que se cargo la base^)
if "%SIMULAR%"=="true" (echo Modo       : SIMULAR ^(no se guarda nada^)) else (echo Modo       : APLICAR)
echo.
if "%SIMULAR%"=="true" goto importar
echo Copia de seguridad completa de la base antes de aplicar...
if defined TBP_INSTALACION (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0servicio\copia-seguridad.ps1" -Motivo antes-de-importar -Instalacion "%TBP_INSTALACION%"
) else (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0servicio\copia-seguridad.ps1" -Motivo antes-de-importar
)
if errorlevel 2 (
  echo AVISO: la copia local esta bien pero no se ha podido duplicar en el destino externo.
  goto importar
)
if errorlevel 1 (
  echo.
  echo ERROR: no se ha podido hacer la copia de seguridad: NO se importa nada.
  exit /b 1
)

:importar
"%JAVA%" -jar target\testbenchplanner.jar --spring.profiles.active=postgresql --planner.almacen=postgresql ^
  --server.port=0 "--planner.migracion.importar=%SHAREPOINT%" "--planner.migracion.base=%BASE%" ^
  "--planner.migracion.resoluciones=%RESOLUCIONES%" --planner.migracion.simular=%SIMULAR% --planner.migracion.forzar-base=%FORZAR%
if errorlevel 4 (
  echo.
  echo LA CARPETA BASE NO ES LA EXPORTACION CON LA QUE SE CARGO LA BASE ^(o no se puede comprobar^):
  echo no se ha guardado nada. Mira "comprobacionBase" en informe.json de la carpeta de trabajo.
  echo Usa la carpeta correcta o, si estas seguro, simula, revisa el informe y anade forzar-base.
  exit /b 4
)
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
  echo Simulacion terminada. Revisa en informe.json: comprobacionBase, avisos y referenciasRotas.
  echo Para guardar:  %~nx0 "%~1" "%~2" aplicar [conflictos.json]
)
exit /b 0

:uso
echo Uso:  %~nx0 CARPETA_BASE CARPETA_SHAREPOINT [simular^|aplicar] [conflictos.json] [forzar-base]
echo   CARPETA_BASE: la exportacion de SharePoint con la que se cargo la base
echo   ^(o la de la ultima fusion aplicada^).
exit /b 2
