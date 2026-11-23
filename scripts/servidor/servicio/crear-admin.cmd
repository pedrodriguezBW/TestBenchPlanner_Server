@echo off
rem ==========================================================================
rem  TestBenchPlanner - PRIMER ADMIN / RESCATE en el servidor INSTALADO (hito S4).
rem  Igual que scripts\servidor\crear-admin.cmd, pero con el Java, el programa y
rem  la configuracion de esta instalacion (carpeta de encima de herramientas\).
rem  Uso:  crear-admin.cmd [email] ["Nombre visible"]   (sin email, lo pregunta)
rem  Hay que ejecutarlo como Administrador: config\ solo la leen los Administradores.
rem  El servicio puede seguir en marcha mientras tanto.
rem ==========================================================================
setlocal
title TestBenchPlanner - crear Admin
net session >nul 2>&1
if errorlevel 1 (
  echo.
  echo Hace falta abrirlo como Administrador: clic derecho ^> Ejecutar como administrador
  echo ^(o desde una consola de Administrador: crear-admin.cmd TU_EMAIL^).
  goto :fallo
)
cd /d "%~dp0.."
if not exist jre\bin\java.exe (
  echo ERROR: no encuentro %CD%\jre\bin\java.exe. ^(comprueba que el servicio esta instalado^)
  goto :fallo
)
set "EMAIL=%~1"
set "NOMBRE=%~2"
if "%EMAIL%"=="" set /p "EMAIL=Email de la persona (nombre.apellido@borgwarner.com): "
if "%EMAIL%"=="" goto :fallo
"jre\bin\java.exe" -Djava.io.tmpdir="%CD%\temp" -jar app\testbenchplanner.jar --spring.profiles.active=postgresql --planner.almacen=postgresql ^
  --server.port=0 --server.address=127.0.0.1 --planner.carpeta-usuario=datos "--planner.cuentas.crear-admin=%EMAIL%" "--planner.cuentas.nombre=%NOMBRE%"
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
