@echo off
rem ==========================================================================
rem  TestBenchPlanner - COPIA DE SEGURIDAD de la base de datos, ahora (a mano).
rem  La diaria la hace sola la tarea programada. Las copias quedan en copias\
rem  Pide permisos de administrador si no los tiene (ventana de Windows).
rem ==========================================================================
setlocal
title TestBenchPlanner - copia de seguridad
net session >nul 2>&1
if errorlevel 1 (
  echo Pidiendo permisos de administrador...
  powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0copia-seguridad.ps1"
echo.
pause
