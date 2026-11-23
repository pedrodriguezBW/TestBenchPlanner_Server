@echo off
rem ==========================================================================
rem  TestBenchPlanner - COMPROBAR el servidor: servicio, salud, PostgreSQL, firewall,
rem  certificado, copias, disco y errores del log. Solo mira, no cambia nada.
rem  Pide permisos de administrador si no los tiene (ventana de Windows).
rem ==========================================================================
setlocal
title TestBenchPlanner - comprobar servidor
net session >nul 2>&1
if errorlevel 1 (
  echo Pidiendo permisos de administrador...
  powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0comprobar-servidor.ps1"
echo.
pause
