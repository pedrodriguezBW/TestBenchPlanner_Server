@echo off
rem ==========================================================================
rem  TestBenchPlanner - RESTAURAR una copia de seguridad (sustituye TODOS los datos;
rem  antes guarda una copia del estado actual).
rem  Pide permisos de administrador si no los tiene (ventana de Windows).
rem ==========================================================================
setlocal
title TestBenchPlanner - restaurar copia
net session >nul 2>&1
if errorlevel 1 (
  echo Pidiendo permisos de administrador...
  powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0restaurar-copia.ps1"
echo.
pause
