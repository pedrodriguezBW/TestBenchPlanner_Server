@echo off
rem ==========================================================================
rem  TestBenchPlanner - QUITAR el servicio de Windows, la regla de firewall y la
rem  copia diaria. NO borra la carpeta de instalacion (datos, copias, logs) ni la
rem  base de datos.
rem  Pide permisos de administrador si no los tiene (ventana de Windows).
rem ==========================================================================
setlocal
title TestBenchPlanner - desinstalar servicio
net session >nul 2>&1
if errorlevel 1 (
  echo Pidiendo permisos de administrador...
  powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0desinstalar-servicio.ps1"
echo.
pause
