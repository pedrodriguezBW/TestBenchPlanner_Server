@echo off
rem ==========================================================================
rem  TestBenchPlanner - INSTALAR (o reinstalar) en el PC servidor como servicio de
rem  Windows con HTTPS, firewall, copia diaria y sin suspension (hito S4).
rem  Antes: backend\target\testbenchplanner.jar con interfaz (mvnw.cmd -Pinterfaz clean verify)
rem  y backend\config\application-postgresql.yml. Guia: docs\13-despliegue-servidor.md
rem  Pide permisos de administrador si no los tiene (ventana de Windows).
rem ==========================================================================
setlocal
title TestBenchPlanner - instalar servicio
net session >nul 2>&1
if errorlevel 1 (
  echo Pidiendo permisos de administrador...
  powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0instalar-servicio.ps1"
echo.
pause
