@echo off
rem ==========================================================================
rem  TestBenchPlanner - ACTUALIZAR el servicio instalado con lo recien compilado
rem  (backend\target\testbenchplanner.jar): copia de seguridad previa, cambio del
rem  programa, arranque y comprobacion. Guia: docs\13-despliegue-servidor.md
rem  Pide permisos de administrador si no los tiene (ventana de Windows).
rem ==========================================================================
setlocal
title TestBenchPlanner - actualizar servidor
net session >nul 2>&1
if errorlevel 1 (
  echo Pidiendo permisos de administrador...
  powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0actualizar-servidor.ps1"
echo.
pause
