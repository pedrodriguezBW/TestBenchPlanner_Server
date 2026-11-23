@echo off
rem ==========================================================================
rem  TestBenchPlanner - abre el puerto de la aplicacion en el firewall de
rem  Windows del PC servidor (hito S2). EJECUTAR COMO ADMINISTRADOR.
rem  Solo redes de dominio y privadas (nunca publicas) y solo ese puerto.
rem  Uso:  abrir-puerto-firewall.cmd [PUERTO]     (por defecto 5001)
rem  Para quitarla:  netsh advfirewall firewall delete rule name="TestBenchPlanner"
rem ==========================================================================
setlocal
set "PUERTO=%~1"
if "%PUERTO%"=="" set "PUERTO=5001"
net session >nul 2>&1
if errorlevel 1 (
  echo Hay que ejecutarlo como administrador ^(boton derecho - Ejecutar como administrador^).
  exit /b 2
)
netsh advfirewall firewall delete rule name="TestBenchPlanner" >nul 2>&1
netsh advfirewall firewall add rule name="TestBenchPlanner" dir=in action=allow protocol=TCP localport=%PUERTO% profile=domain,private
if errorlevel 1 (
  echo ERROR: no se ha podido crear la regla.
  exit /b 1
)
echo Regla "TestBenchPlanner" creada: entrada TCP %PUERTO%, redes de dominio y privadas.
exit /b 0
