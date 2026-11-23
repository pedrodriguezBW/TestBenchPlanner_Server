# =============================================================================
#  TestBenchPlanner - COMPROBAR EL SERVIDOR (hito S4).
#  herramientas\comprobar-servidor.cmd (como Administrador). Solo mira, no cambia nada.
#  Repasa servicio, salud, PostgreSQL, puerto, firewall, certificado, copias,
#  espacio en disco y errores recientes del log. Útil tras un reinicio.
# =============================================================================
param([string]$Instalacion = '')
. "$PSScriptRoot\comun.ps1"

$inst = $Instalacion
if (-not $inst) { $inst = Carpeta-Instalacion $PSScriptRoot }
$fallos = 0
$avisos = 0
function Fallo([string]$t) { Escribir-Fallo $t; $script:fallos++ }
function Aviso([string]$t) { Escribir-Aviso $t; $script:avisos++ }

Write-Host "TestBenchPlanner - comprobación del servidor ($inst, $(Get-Date -Format 'dd/MM/yyyy HH:mm'))" -ForegroundColor White
$d = Direccion-Servicio $inst

Escribir-Paso 'Servicio de Windows'
$svc = Get-CimInstance Win32_Service -Filter "Name='$NombreServicio'" -ErrorAction SilentlyContinue
if (-not $svc) { Fallo 'El servicio TestBenchPlanner no está instalado (scripts\servidor\instalar-servicio.cmd).' }
else {
    if ($svc.State -eq 'Running') { Escribir-Ok "En marcha (cuenta $($svc.StartName))" } else { Fallo "Estado: $($svc.State)" }
    if ($svc.StartMode -eq 'Auto') { Escribir-Ok 'Arranque automático' } else { Fallo "Arranque: $($svc.StartMode) (debería ser automático)" }
}
$arranque = (Get-CimInstance Win32_OperatingSystem).LastBootUpTime
Escribir-Ok "Windows encendido desde $($arranque.ToString('dd/MM/yyyy HH:mm'))"

Escribir-Paso 'Salud de la aplicación'
$s = Consultar-Salud $inst
if ($s.Codigo -eq 200 -and $s.Datos.estado -eq 'ok') {
    $h = [math]::Round($s.Datos.segundosEnMarcha / 3600, 1)
    Escribir-Ok "$($s.Url) -> versión $($s.Datos.version), base de datos $($s.Datos.baseDatos), arrancada hace $h h"
    if (-not $s.Datos.modoServidor) { Fallo 'No está en modo servidor' }
} elseif ($s.Codigo -eq 503) { Fallo "Responde pero no llega a la base de datos ($($s.Url))" }
else { Fallo "No responde en $($s.Url): $($s.Error)" }
if (-not $d.Https) { Fallo 'Sin HTTPS (falta config\tbp-certificado.p12)' }

Escribir-Paso 'PostgreSQL'
$pg = Servicio-PostgreSql
if (-not $pg) { Fallo 'No encuentro el servicio de PostgreSQL' }
else {
    $ps = Get-Service $pg
    if ($ps.Status -eq 'Running') { Escribir-Ok "$pg en marcha" } else { Fallo "${pg}: $($ps.Status)" }
}
try {
    $cx = Leer-ConexionPropietario $inst
    $escucha = @(Get-NetTCPConnection -LocalPort ([int]$cx.Puerto) -State Listen -ErrorAction SilentlyContinue | Select-Object -ExpandProperty LocalAddress -Unique)
    $abiertas = @($escucha | Where-Object { $_ -notin @('127.0.0.1', '::1') })
    if ($escucha.Count -eq 0) { Fallo "Nadie escucha en el puerto $($cx.Puerto)" }
    elseif ($abiertas.Count -gt 0) { Aviso "PostgreSQL escucha también en $($abiertas -join ', '): debería ser solo 127.0.0.1 (listen_addresses = 'localhost', docs/13)" }
    else { Escribir-Ok "Solo escucha en este PC ($($escucha -join ', '):$($cx.Puerto))" }
} catch { Fallo $_.Exception.Message }

Escribir-Paso 'Red'
$app = @(Get-NetTCPConnection -LocalPort ([int]$d.Puerto) -State Listen -ErrorAction SilentlyContinue | Select-Object -ExpandProperty LocalAddress -Unique)
if ($app.Count -eq 0) { Fallo "Nadie escucha en el puerto $($d.Puerto)" } else { Escribir-Ok "Puerto $($d.Puerto) a la escucha ($($app -join ', '))" }
$regla = @(Get-NetFirewallRule -DisplayName 'TestBenchPlanner' -ErrorAction SilentlyContinue)
if ($regla.Count -eq 0) { Fallo 'Falta la regla de firewall TestBenchPlanner' }
else {
    $puertos = @($regla | Get-NetFirewallPortFilter | Select-Object -ExpandProperty LocalPort)
    if ($regla[0].Enabled -ne 'True') { Fallo 'La regla de firewall está desactivada' }
    elseif ($puertos -notcontains $d.Puerto) { Fallo "La regla de firewall abre $($puertos -join ',') y la aplicación usa $($d.Puerto)" }
    else { Escribir-Ok "Firewall: puerto $($d.Puerto) abierto en perfiles $($regla[0].Profile)" }
}
$perfiles = @(Get-NetConnectionProfile -ErrorAction SilentlyContinue | ForEach-Object { "$($_.InterfaceAlias)=$($_.NetworkCategory)" })
if ($perfiles) { Escribir-Ok "Redes: $($perfiles -join '; ')" }
if (@(Get-NetConnectionProfile -ErrorAction SilentlyContinue | Where-Object { $_.NetworkCategory -eq 'Public' }).Count -gt 0) {
    Aviso 'Hay una red marcada como Pública: por ella no se entra (la regla es solo dominio/privado).'
}

Escribir-Paso 'Certificado HTTPS'
$cer = Join-Path $inst 'certificado\TestBenchPlanner.cer'
if (Test-Path $cer) {
    $x = New-Object Security.Cryptography.X509Certificates.X509Certificate2($cer)
    $dias = [int]($x.NotAfter - (Get-Date)).TotalDays
    $san = ($x.Extensions | Where-Object { $_.Oid.Value -eq '2.5.29.17' } | ForEach-Object { $_.Format($false) }) -join ''
    if ($dias -lt 0) { Fallo "Caducado el $($x.NotAfter.ToString('dd/MM/yyyy'))" }
    elseif ($dias -lt 60) { Aviso "Caduca en $dias días ($($x.NotAfter.ToString('dd/MM/yyyy'))): renuévalo (docs/13)" }
    else { Escribir-Ok "Válido hasta el $($x.NotAfter.ToString('dd/MM/yyyy')) ($dias días)" }
    if ($san -notmatch [regex]::Escape($env:COMPUTERNAME)) { Aviso "El certificado no incluye el nombre $($env:COMPUTERNAME)" }
    $ips = @(Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue | Where-Object { $_.IPAddress -ne '127.0.0.1' -and $_.IPAddress -notlike '169.254.*' } | Select-Object -ExpandProperty IPAddress)
    foreach ($ip in $ips) { if ($san -notmatch [regex]::Escape($ip)) { Aviso "La IP $ip no está en el certificado (¿ha cambiado?): quien entre por IP verá un aviso" } }
} else { Fallo "Falta $cer" }

Escribir-Paso 'Copias de seguridad'
$conf = Leer-ConfCopias $inst
$ultima = Get-ChildItem (Join-Path $inst 'copias') -Filter 'tbp_*.dump' -File -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $ultima) { Fallo 'No hay ninguna copia en copias\' }
else {
    $horas = [math]::Round(((Get-Date) - $ultima.LastWriteTime).TotalHours, 1)
    if ($horas -gt 26) { Fallo "La última copia es de hace $horas h ($($ultima.Name))" } else { Escribir-Ok "Última copia hace $horas h: $($ultima.Name) ($([math]::Round($ultima.Length / 1MB, 2)) MB)" }
}
if ($conf.DESTINO_EXTERNO) {
    if (Test-Path $conf.DESTINO_EXTERNO) {
        $ext = Get-ChildItem $conf.DESTINO_EXTERNO -Filter 'tbp_*.dump' -File -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($ext -and ((Get-Date) - $ext.LastWriteTime).TotalHours -le 26) { Escribir-Ok "También en $($conf.DESTINO_EXTERNO) ($($ext.Name))" }
        else { Aviso "En $($conf.DESTINO_EXTERNO) no hay copia de las últimas 26 h" }
    } else { Aviso "No se llega a $($conf.DESTINO_EXTERNO) desde esta sesión" }
} else { Aviso 'Las copias solo están en este PC (DESTINO_EXTERNO vacío en config\copias.conf)' }
$tarea = Get-ScheduledTask -TaskName 'TestBenchPlanner - copia diaria' -ErrorAction SilentlyContinue
if (-not $tarea) { Fallo 'Falta la tarea programada «TestBenchPlanner - copia diaria»' }
else {
    $info = $tarea | Get-ScheduledTaskInfo
    $resultado = if ($info.LastRunTime -and $info.LastRunTime.Year -gt 2000) { "última: $($info.LastRunTime.ToString('dd/MM HH:mm')), resultado $($info.LastTaskResult)" } else { 'todavía no se ha ejecutado' }
    if ($info.LastTaskResult -notin @(0, 267011) -and $info.LastRunTime.Year -gt 2000) { Aviso "Tarea programada: $resultado (0 = bien, 2 = sin copia externa)" }
    else { Escribir-Ok "Tarea programada: $resultado; próxima $($info.NextRunTime.ToString('dd/MM HH:mm'))" }
}

Escribir-Paso 'Disco y log'
$unidad = (Get-Item $inst).PSDrive
$libreGb = [math]::Round($unidad.Free / 1GB, 1)
if ($libreGb -lt 5) { Fallo "Quedan $libreGb GB libres en $($unidad.Name):" } else { Escribir-Ok "$libreGb GB libres en $($unidad.Name):" }
$log = Join-Path $inst 'logs\testbenchplanner.log'
if (Test-Path $log) {
    $errores = @(Select-String -Path $log -Pattern '^\S+(\s+\S+)?\s+ERROR\s' -ErrorAction SilentlyContinue)
    if ($errores.Count -gt 0) {
        Aviso "$($errores.Count) líneas ERROR en el log actual; la última:"
        Write-Host ('           ' + $errores[-1].Line.Substring(0, [math]::Min(200, $errores[-1].Line.Length)))
    } else { Escribir-Ok 'Sin errores en el log actual' }
} else { Aviso "Todavía no hay $log" }

Write-Host ''
if ($fallos -eq 0) { Write-Host "Resultado: BIEN ($avisos avisos)" -ForegroundColor Green }
else { Write-Host "Resultado: $fallos FALLOS, $avisos avisos" -ForegroundColor Red }
exit $fallos
