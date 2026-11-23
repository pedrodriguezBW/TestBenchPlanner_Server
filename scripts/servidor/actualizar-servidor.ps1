# =============================================================================
#  TestBenchPlanner - ACTUALIZAR EL SERVIDOR INSTALADO (hito S4).
#  Se lanza con actualizar-servidor.cmd (pide permisos de administrador) desde
#  el repositorio, después de compilar (backend\target\testbenchplanner.jar).
#
#  1. Copia de seguridad «antes-de-actualizar» (si falla, no se toca nada).
#  2. Para el servicio, guarda el programa actual como app\testbenchplanner.anterior.jar
#     y pone el nuevo. Actualiza también herramientas\.
#  3. Arranca (Flyway aplica las migraciones nuevas) y espera a que responda.
#  4. Si no responde, ofrece volver al programa anterior. Si una migración nueva
#     ya cambió la base, volver exige además restaurar la copia del paso 1.
# =============================================================================
. "$PSScriptRoot\servicio\comun.ps1"

function Jar-TieneInterfaz([string]$jar) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($jar)
    try { return [bool]($zip.Entries | Where-Object { $_.FullName -eq 'BOOT-INF/classes/static/index.html' }) }
    finally { $zip.Dispose() }
}

try {
    Exigir-Administrador
    Write-Host 'TestBenchPlanner - actualizar el servidor' -ForegroundColor White
    $repo = (Resolve-Path "$PSScriptRoot\..\..").Path
    $jar = Join-Path $repo 'backend\target\testbenchplanner.jar'
    $svc = Get-CimInstance Win32_Service -Filter "Name='$NombreServicio'" -ErrorAction SilentlyContinue
    if (-not $svc) { throw 'El servicio no está instalado: usa instalar-servicio.cmd.' }
    $m = [regex]::Match([string]$svc.PathName, '^"?(.+)\\TestBenchPlanner\.exe', 'IgnoreCase')
    if (-not $m.Success) { throw "No entiendo dónde está instalado: $($svc.PathName)" }
    $inst = $m.Groups[1].Value
    $actual = Join-Path $inst 'app\testbenchplanner.jar'
    $anterior = Join-Path $inst 'app\testbenchplanner.anterior.jar'

    Escribir-Paso 'Comprobando lo compilado'
    if (-not (Test-Path $jar)) { throw "Falta $jar. Compila antes:  .\mvnw.cmd clean verify" }
    if (-not (Jar-TieneInterfaz $jar)) { throw 'El programa compilado no lleva la interfaz: compila con  .\mvnw.cmd -Pinterfaz clean verify' }
    if ((Test-Path $actual) -and ((Get-FileHash $actual).Hash -eq (Get-FileHash $jar).Hash)) {
        Copy-Item "$PSScriptRoot\servicio\*" (Join-Path $inst 'herramientas') -Force
        Escribir-Ok 'El servidor ya tiene exactamente este programa: no hay nada que actualizar (herramientas\ sí se ha refrescado).'
        exit 0
    }
    Escribir-Ok "$jar ($((Get-Item $jar).LastWriteTime.ToString('dd/MM/yyyy HH:mm')))"
    $antes = Consultar-Salud $inst
    if ($antes.Datos) { Escribir-Ok "Versión instalada ahora: $($antes.Datos.version)" }

    Escribir-Paso 'Copia de seguridad previa'
    & (Join-Path $inst 'herramientas\copia-seguridad.ps1') -Motivo 'antes-de-actualizar' -Instalacion $inst
    if ($LASTEXITCODE -eq 1) { throw 'La copia previa ha fallado: no se actualiza nada.' }

    Escribir-Paso 'Cambiando el programa'
    Parar-Servicio
    Copy-Item $actual $anterior -Force
    Copy-Item $jar $actual -Force
    Copy-Item "$PSScriptRoot\servicio\*" (Join-Path $inst 'herramientas') -Force
    Escribir-Ok 'Programa nuevo en app\ (el anterior queda como testbenchplanner.anterior.jar)'

    Escribir-Paso 'Arrancando'
    Arrancar-Servicio
    if (Esperar-Salud $inst 240) {
        $s = Consultar-Salud $inst
        Escribir-Ok "En marcha: versión $($s.Datos.version)"
        Anotar $inst "Actualizado a la versión $($s.Datos.version) desde $jar"
        Write-Host ''
        Write-Host 'Actualización terminada. Conviene pasar herramientas\comprobar-servidor.cmd.' -ForegroundColor Green
        exit 0
    }
    Escribir-Fallo "El programa nuevo no responde. Mira $inst\logs\testbenchplanner.log"
    Anotar $inst 'FALLO al actualizar: el programa nuevo no responde'
    if ((Read-Host '¿Volver al programa anterior? (S/N)') -match '^[sS]') {
        Parar-Servicio
        Copy-Item $anterior $actual -Force
        Arrancar-Servicio
        if (Esperar-Salud $inst 240) {
            Escribir-Ok 'De vuelta en el programa anterior.'
            Anotar $inst 'Vuelta al programa anterior tras una actualización fallida'
        } else {
            Escribir-Fallo ('Tampoco responde el anterior: probablemente una migración nueva ya cambió la base. ' +
                'Restaura la copia «antes-de-actualizar» con herramientas\restaurar-copia.cmd (el programa anterior ya está puesto).')
        }
    }
    exit 1
} catch {
    Escribir-Fallo $_.Exception.Message
    exit 1
}
