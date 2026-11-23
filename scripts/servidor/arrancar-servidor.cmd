@echo off
rem ==========================================================================
rem  TestBenchPlanner - MODO SERVIDOR (hito S2), en el PC servidor.
rem  Arranca la aplicacion con PostgreSQL para que se entre desde el navegador
rem  de otros PCs:  http://NOMBRE-DEL-PC:5001  (o la IP del servidor).
rem
rem  Necesita:
rem   - backend\target\testbenchplanner.jar  (mvnw.cmd clean verify)
rem   - backend\config\application-postgresql.yml  (claves de la base)
rem   - opcional: backend\config\application-servidor.yml  (puerto, direccion)
rem  Se para cerrando esta ventana o con Ctrl+C. Es el modo de PRUEBAS: sin
rem  HTTPS (las contrasenas viajan sin cifrar) y sin arranque automatico.
rem  El servidor definitivo se instala como servicio de Windows con HTTPS:
rem  instalar-servicio.cmd (hito S4, docs\13-despliegue-servidor.md).
rem ==========================================================================
setlocal
title TestBenchPlanner - servidor
cd /d "%~dp0..\..\backend"
sc query TestBenchPlanner 2>nul | find "RUNNING" >nul
if not errorlevel 1 (
  echo.
  echo ERROR: el servicio TestBenchPlanner ya esta en marcha en este PC y usa el puerto.
  echo Para probar a mano, paralo antes: services.msc ^> TestBenchPlanner ^> Detener.
  goto :fallo
)
rem --- Busca Java 17+: JAVA_HOME, PATH, instalaciones habituales y el Java de VS Code
set "JAVA="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"
if not defined JAVA for /f "delims=" %%j in ('where java.exe 2^>nul') do if not defined JAVA set "JAVA=%%j"
if not defined JAVA for /d %%d in ("%ProgramFiles%\Java\jdk*" "%ProgramFiles%\Eclipse Adoptium\jdk*" "%ProgramFiles%\Microsoft\jdk*" "%ProgramFiles%\Amazon Corretto\jdk*" "%ProgramFiles%\Zulu\zulu*" "%USERPROFILE%\.jdks\*" "%LOCALAPPDATA%\Programs\Eclipse Adoptium\jdk*") do if exist "%%~d\bin\java.exe" set "JAVA=%%~d\bin\java.exe"
if not defined JAVA for /d %%e in ("%USERPROFILE%\.vscode\extensions\redhat.java-*") do for /d %%r in ("%%~e\jre\*") do if exist "%%~r\bin\java.exe" set "JAVA=%%~r\bin\java.exe"
if not defined JAVA (
  echo.
  echo ERROR: no se encuentra Java ^(17 o superior^).
  echo Define la variable de entorno JAVA_HOME con la carpeta del JDK
  echo o lanza este script desde la terminal de VS Code.
  goto :fallo
)
echo Java: %JAVA%
if not exist target\testbenchplanner.jar (
  echo.
  echo ERROR: falta backend\target\testbenchplanner.jar
  echo Ejecuta antes en backend:  .\mvnw.cmd clean verify   ^(tiene que acabar en BUILD SUCCESS^)
  goto :fallo
)
if not exist config\application-postgresql.yml (
  echo.
  echo ERROR: falta backend\config\application-postgresql.yml ^(claves de la base de datos^).
  echo Copialo de scripts\servidor\application-postgresql.yml.ejemplo y rellena las claves.
  goto :fallo
)
set "PUERTO=5001"
if exist config\application-servidor.yml (
  for /f "tokens=2 delims=: " %%p in ('findstr /r /c:"^ *port:" config\application-servidor.yml') do set "PUERTO=%%p"
)
echo.
echo  ==============================================================
echo   TestBenchPlanner en MODO SERVIDOR
echo   No se abre ningun navegador: entra desde cualquier PC con
echo      http://%COMPUTERNAME%:%PUERTO%
echo   o, en este mismo PC,  http://localhost:%PUERTO%
echo   Esta lista cuando aparezca "Started TestBenchPlannerApplication".
echo   Para pararlo: Ctrl+C o cerrar esta ventana.
echo  ==============================================================
echo.
"%JAVA%" -jar target\testbenchplanner.jar --spring.profiles.active=postgresql,servidor
if errorlevel 1 (
  echo.
  echo ERROR: TestBenchPlanner se ha parado con un error. Revisa los mensajes de arriba.
  goto :fallo
)
exit /b 0

:fallo
echo.
pause
exit /b 1
