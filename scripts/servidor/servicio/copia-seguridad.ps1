# =============================================================================
#  TestBenchPlanner - COPIA DE SEGURIDAD de la base de datos (hito S4).
#  - La lanza cada día la tarea programada «TestBenchPlanner - copia diaria»
#    (cuenta SYSTEM) y, antes de tocar nada, actualizar-servidor y restaurar-copia.
#  - A mano: herramientas\copia-seguridad.cmd (como Administrador).
#  Copia con pg_dump (formato personalizado, comprimido) SOLO el esquema de la
#  aplicación, la comprueba con pg_restore --list, la duplica en DESTINO_EXTERNO
#  (config\copias.conf) y borra las de más de RETENCION_DIAS (siempre quedan
#  las 7 más recientes). Todo queda anotado en logs\scripts.log.
#  Código de salida: 0 bien · 1 fallo · 2 copia local bien pero no la externa.
# =============================================================================
param(
    [string]$Motivo = 'manual',
    [string]$Instalacion = ''
)
. "$PSScriptRoot\comun.ps1"

function Limpiar-Antiguas([string]$carpeta, [int]$dias) {
    $copias = @(Get-ChildItem -Path $carpeta -Filter 'tbp_*.dump' -File -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending)
    $limite = (Get-Date).AddDays(-$dias)
    $borradas = 0
    for ($i = 7; $i -lt $copias.Count; $i++) {
        if ($copias[$i].LastWriteTime -lt $limite) { Remove-Item $copias[$i].FullName -Force; $borradas++ }
    }
    return $borradas
}

$inst = $Instalacion
if (-not $inst) { $inst = Carpeta-Instalacion $PSScriptRoot }
$Motivo = ($Motivo -replace '[^A-Za-z0-9-]', '')
if (-not $Motivo) { $Motivo = 'manual' }
try {
    $conf = Leer-ConfCopias $inst
    $bin = Buscar-PgBin $conf.PG_BIN
    $cx = Leer-ConexionPropietario $inst
    $carpeta = Join-Path $inst 'copias'
    New-Item -ItemType Directory -Force -Path $carpeta | Out-Null
    $fichero = Join-Path $carpeta ('tbp_{0:yyyyMMdd_HHmmss}_{1}.dump' -f (Get-Date), $Motivo)
    $entorno = @{ PGPASSWORD = $cx.Clave; PGCONNECT_TIMEOUT = '15' }
    Write-Host "Copia de la base $($cx.Base) (esquema $($cx.Esquema)) en $fichero"
    $r = Ejecutar (Join-Path $bin 'pg_dump.exe') @('-h', $cx.Servidor, '-p', $cx.Puerto, '-U', $cx.Usuario, '-d', $cx.Base,
        '-n', $cx.Esquema, '-Fc', '--no-password', '-f', $fichero) $entorno
    if ($r.Codigo -ne 0) {
        if (Test-Path $fichero) { Remove-Item $fichero -Force }
        throw "pg_dump ha fallado: $($r.Salida)"
    }
    $lista = Ejecutar (Join-Path $bin 'pg_restore.exe') @('--list', $fichero)
    $tablas = ([regex]::Matches($lista.Salida, 'TABLE DATA')).Count
    if ($lista.Codigo -ne 0 -or $tablas -lt 5) {
        throw "La copia $fichero no se puede leer o está incompleta ($tablas tablas): $($lista.Salida)"
    }
    $mb = [math]::Round((Get-Item $fichero).Length / 1MB, 2)
    Escribir-Ok "Copia comprobada: $tablas tablas, $mb MB"
    $resultado = 0
    $nota = "Copia $Motivo correcta: $(Split-Path -Leaf $fichero) ($mb MB, $tablas tablas)"
    if ($conf.DESTINO_EXTERNO) {
        try {
            if (-not (Test-Path $conf.DESTINO_EXTERNO)) { New-Item -ItemType Directory -Force -Path $conf.DESTINO_EXTERNO | Out-Null }
            Copy-Item $fichero $conf.DESTINO_EXTERNO -Force
            $copiada = Join-Path $conf.DESTINO_EXTERNO (Split-Path -Leaf $fichero)
            if ((Get-Item $copiada).Length -ne (Get-Item $fichero).Length) { throw 'el tamaño no coincide' }
            Escribir-Ok "Duplicada en $($conf.DESTINO_EXTERNO)"
            $nota += "; duplicada en $($conf.DESTINO_EXTERNO)"
            $b = Limpiar-Antiguas $conf.DESTINO_EXTERNO ([int]$conf.RETENCION_DIAS)
            if ($b -gt 0) { $nota += "; $b antiguas borradas allí" }
        } catch {
            $resultado = 2
            Escribir-Aviso "No se ha podido duplicar en $($conf.DESTINO_EXTERNO): $($_.Exception.Message)"
            $nota += "; FALLO al duplicar en $($conf.DESTINO_EXTERNO): $($_.Exception.Message)"
        }
    }
    $b = Limpiar-Antiguas $carpeta ([int]$conf.RETENCION_DIAS)
    if ($b -gt 0) { $nota += "; $b antiguas borradas" }
    Anotar $inst $nota
    exit $resultado
} catch {
    Escribir-Fallo $_.Exception.Message
    try { Anotar $inst "FALLO copia $($Motivo): $($_.Exception.Message)" } catch { }
    exit 1
}
