@echo off
rem ==========================================================================
rem  TestBenchPlanner - PRIMER ADMIN (hito S3), en el PC servidor.
rem  Uso:  crear-admin.cmd nombre.apellido@borgwarner.com ["Nombre visible"]
rem
rem  Deja a esa persona como Admin con la contrasena que escribas (se pide dos
rem  veces y no se ve). Si no estaba en Users/Rights Management, la anade.
rem  Sirve tambien de RESCATE si ningun Admin puede entrar. No hay ninguna
rem  contrasena de serie: el control es tener acceso a este PC y a las claves
rem  de la base de datos (backend\config\application-postgresql.yml).
rem  El resto de cuentas las da un Admin desde la aplicacion.
rem ==========================================================================
setlocal
title TestBenchPlanner - crear Admin
if "%~1"=="" (
  echo Uso: %~nx0 EMAIL ["Nombre visible"]
  goto :fallo
)
cd /d "%~dp0..\..\backend"
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
  goto :fallo
)
"%JAVA%" -jar target\testbenchplanner.jar --spring.profiles.active=postgresql --planner.almacen=postgresql ^
  --server.port=0 --server.address=127.0.0.1 "--planner.cuentas.crear-admin=%~1" "--planner.cuentas.nombre=%~2"
if errorlevel 1 (
  echo.
  echo ERROR: no se ha creado la cuenta. Revisa el mensaje anterior.
  goto :fallo
)
echo.
pause
exit /b 0

:fallo
echo.
pause
exit /b 1
