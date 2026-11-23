# =============================================================================
#  TestBenchPlanner - RESTAURAR una copia de seguridad (hito S4).
#  Se lanza con herramientas\restaurar-copia.cmd (como Administrador).
#
#  1. Elige la copia (las de copias\ y las de DESTINO_EXTERNO, más recientes primero).
#  2. Hace ANTES una copia del estado actual (motivo «antes-de-restaurar»).
#  3. Para el servicio, aparta el esquema actual (lo renombra), carga la copia
#     en una sola transacción y, si todo va bien, borra el esquema apartado.
#     Si la carga falla, vuelve a dejar el esquema actual como estaba.
#  4. Arranca el servicio y espera a que responda (Flyway valida la estructura
#     y aplica las migraciones que falten si la copia es de una versión anterior).
#  Las sesiones abiertas se pierden: todos vuelven a entrar.
# =============================================================================
param(
    [string]$Fichero = '',
    [string]$Instalacion = ''
)
. "$PSScriptRoot\comun.ps1"

$inst = $Instalacion
if (-not $inst) { $inst = Carpeta-Instalacion $PSScriptRoot }
$servicioParado = $false
try {
    Exigir-Administrador
    Write-Host 'TestBenchPlanner - restaurar una copia de seguridad' -ForegroundColor White
    $conf = Leer-ConfCopias $inst
    $bin = Buscar-PgBin $conf.PG_BIN
    $cx = Leer-ConexionPropietario $inst
    $entorno = @{ PGPASSWORD = $cx.Clave; PGCONNECT_TIMEOUT = '15' }

    if (-not $Fichero) {
        $carpetas = @((Join-Path $inst 'copias'))
        if ($conf.DESTINO_EXTERNO -and (Test-Path $conf.DESTINO_EXTERNO)) { $carpetas += $conf.DESTINO_EXTERNO }
        $copias = @($carpetas | ForEach-Object { Get-ChildItem -Path $_ -Filter 'tbp_*.dump' -File -ErrorAction SilentlyContinue } |
            Sort-Object LastWriteTime -Descending | Select-Object -First 20)
        if ($copias.Count -eq 0) { throw "No hay copias (tbp_*.dump) en $($carpetas -join ' ni en ')." }
        Write-Host ''
        for ($i = 0; $i -lt $copias.Count; $i++) {
            Write-Host ('  {0,2}. {1:dd/MM/yyyy HH:mm}  {2,8:N2} MB  {3}' -f ($i + 1), $copias[$i].LastWriteTime, ($copias[$i].Length / 1MB), $copias[$i].FullName)
        }
        $n = Read-Host "`nNúmero de la copia a restaurar (Enter = cancelar)"
        if (-not $n -or $n -notmatch '^\d+$' -or [int]$n -lt 1 -or [int]$n -gt $copias.Count) { Write-Host 'Cancelado.'; exit 0 }
        $Fichero = $copias[[int]$n - 1].FullName
    }
    if (-not (Test-Path $Fichero)) { throw "No existe $Fichero" }

    Escribir-Paso 'Comprobando la copia'
    $lista = Ejecutar (Join-Path $bin 'pg_restore.exe') @('--list', $Fichero)
    $tablas = ([regex]::Matches($lista.Salida, 'TABLE DATA')).Count
    if ($lista.Codigo -ne 0 -or $tablas -lt 5) { throw "La copia no se puede leer o está incompleta: $($lista.Salida)" }
    if ($lista.Salida -notmatch ('(?m)\bSCHEMA - ' + [regex]::Escape($cx.Esquema) + '\b')) {
        throw "La copia no contiene el esquema «$($cx.Esquema)»: no es una copia de copia-seguridad."
    }
    Escribir-Ok "$Fichero ($tablas tablas)"

    Write-Host ''
    Write-Host "  Se SUSTITUIRÁN todos los datos actuales de TestBenchPlanner por los de esa copia." -ForegroundColor Yellow
    Write-Host '  Lo que se haya cambiado después de esa copia se pierde (queda en la copia previa que se hace ahora).' -ForegroundColor Yellow
    if ((Read-Host '  Escribe RESTAURAR para seguir') -cne 'RESTAURAR') { Write-Host 'Cancelado.'; exit 0 }

    Escribir-Paso 'Copia del estado actual'
    & (Join-Path $PSScriptRoot 'copia-seguridad.ps1') -Motivo 'antes-de-restaurar' -Instalacion $inst
    if ($LASTEXITCODE -eq 1) { throw 'No se ha podido copiar el estado actual: no se restaura nada.' }

    Escribir-Paso 'Parando TestBenchPlanner'
    Parar-Servicio
    $servicioParado = $true
    Escribir-Ok 'Parado'

    Escribir-Paso 'Restaurando'
    $psql = Join-Path $bin 'psql.exe'
    $apartado = '{0}_previo_{1:yyyyMMddHHmmss}' -f $cx.Esquema, (Get-Date)
    $base = @('-h', $cx.Servidor, '-p', $cx.Puerto, '-U', $cx.Usuario, '-d', $cx.Base, '--no-password', '-v', 'ON_ERROR_STOP=1', '-q')
    $r = Ejecutar $psql ($base + @('-c', "ALTER SCHEMA `"$($cx.Esquema)`" RENAME TO `"$apartado`"")) $entorno
    if ($r.Codigo -ne 0) { throw "No se ha podido apartar el esquema actual: $($r.Salida)" }
    $r = Ejecutar (Join-Path $bin 'pg_restore.exe') @('-h', $cx.Servidor, '-p', $cx.Puerto, '-U', $cx.Usuario, '-d', $cx.Base,
        '--no-password', '--single-transaction', '--exit-on-error', $Fichero) $entorno
    if ($r.Codigo -ne 0) {
        $deshacer = Ejecutar $psql ($base + @('-c', "DROP SCHEMA IF EXISTS `"$($cx.Esquema)`" CASCADE; ALTER SCHEMA `"$apartado`" RENAME TO `"$($cx.Esquema)`";")) $entorno
        if ($deshacer.Codigo -ne 0) {
            $servicioParado = $false   # no arrancar sobre una base a medias
            throw "La restauración ha fallado y NO se ha podido volver atrás. Los datos anteriores están en el esquema «$apartado». Avisa antes de arrancar. Detalle: $($r.Salida) / $($deshacer.Salida)"
        }
        throw "La restauración ha fallado; los datos siguen como estaban. Detalle: $($r.Salida)"
    }
    $r = Ejecutar $psql ($base + @('-c', "DROP SCHEMA `"$apartado`" CASCADE")) $entorno
    if ($r.Codigo -ne 0) { Escribir-Aviso "Restaurada, pero no se ha podido borrar el esquema apartado «$apartado»: $($r.Salida)" }
    Escribir-Ok "Datos de $(Split-Path -Leaf $Fichero) cargados"

    Escribir-Paso 'Arrancando TestBenchPlanner'
    Arrancar-Servicio
    $servicioParado = $false
    if (-not (Esperar-Salud $inst 240)) {
        throw "Restaurada, pero el servicio no responde. Mira $inst\logs\testbenchplanner.log"
    }
    Escribir-Ok 'En marcha'
    Anotar $inst "Restaurada la copia $Fichero"
    Write-Host ''
    Write-Host 'Restauración terminada. Todos tienen que volver a entrar.' -ForegroundColor Green
    exit 0
} catch {
    Escribir-Fallo $_.Exception.Message
    try { Anotar $inst "FALLO restauración ($Fichero): $($_.Exception.Message)" } catch { }
    if ($servicioParado) {
        Write-Host '    Arrancando de nuevo el servicio con los datos que haya...'
        try { Arrancar-Servicio } catch { Escribir-Fallo "Tampoco arranca: $($_.Exception.Message)" }
    }
    exit 1
}
