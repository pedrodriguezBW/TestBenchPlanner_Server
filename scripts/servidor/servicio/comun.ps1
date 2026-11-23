# =============================================================================
#  TestBenchPlanner - funciones comunes de los scripts del servidor (hito S4).
#  Lo cargan los demás scripts con:  . "$PSScriptRoot\comun.ps1"
#  La instalación vive en una carpeta (por defecto C:\TestBenchPlanner):
#    TestBenchPlanner.exe/.xml  servicio de Windows (WinSW)
#    app\        testbenchplanner.jar (y testbenchplanner.anterior.jar)
#    jre\        Java propio de la instalación
#    config\     claves de la base, certificado HTTPS, copias.conf (solo Administradores)
#    certificado\TestBenchPlanner.cer  (parte pública, para repartir)
#    logs\  copias\  datos\  temp\  herramientas\ (estos scripts)
# =============================================================================
Set-StrictMode -Version 2
$ErrorActionPreference = 'Stop'
$script:NombreServicio = 'TestBenchPlanner'
$script:RutaSalud = '/api/v1/salud'

function Escribir-Paso([string]$texto) { Write-Host ''; Write-Host "==> $texto" -ForegroundColor Cyan }
function Escribir-Ok([string]$texto) { Write-Host "    OK     $texto" -ForegroundColor Green }
function Escribir-Aviso([string]$texto) { Write-Host "    AVISO  $texto" -ForegroundColor Yellow }
function Escribir-Fallo([string]$texto) { Write-Host "    FALLO  $texto" -ForegroundColor Red }

function Test-Administrador {
    $yo = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
    return $yo.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Exigir-Administrador {
    if (-not (Test-Administrador)) {
        throw 'Hace falta ejecutar esto como Administrador (clic derecho > Ejecutar como administrador).'
    }
}

# Carpeta de la instalación: la de encima de herramientas\ si se lanza desde allí.
function Carpeta-Instalacion([string]$desde) {
    $padre = Split-Path -Parent $desde
    if ((Split-Path -Leaf $desde) -ieq 'herramientas' -and (Test-Path (Join-Path $padre 'config'))) { return $padre }
    return 'C:\TestBenchPlanner'
}

# Lee un valor de un YAML sencillo (clave: valor, con o sin comillas). $null si no está.
function Leer-ValorYaml([string]$texto, [string]$clave) {
    $m = [regex]::Match($texto, "(?m)^[ \t]*" + [regex]::Escape($clave) + "[ \t]*:[ \t]*(?:""([^""]*)""|'([^']*)'|([^#\r\n]*))")
    if (-not $m.Success) { return $null }
    $v = ($m.Groups[1].Value + $m.Groups[2].Value + $m.Groups[3].Value).Trim()
    if ($v -eq '') { return $null }
    return $v
}

# Conexión del propietario (tbp_owner) a partir de config\application-postgresql.yml.
function Leer-ConexionPropietario([string]$instalacion) {
    $fichero = Join-Path $instalacion 'config\application-postgresql.yml'
    if (-not (Test-Path $fichero)) { throw "Falta $fichero (claves de la base de datos)." }
    $texto = Get-Content -Raw -Encoding UTF8 $fichero
    $url = Leer-ValorYaml $texto 'url'
    if (-not $url) { $url = 'jdbc:postgresql://127.0.0.1:5432/tbp' }
    $m = [regex]::Match($url, '^jdbc:postgresql://([^:/?]+)(?::(\d+))?/([^?]+)')
    if (-not $m.Success) { throw "No entiendo la url de la base de datos: $url" }
    $usuario = Leer-ValorYaml $texto 'usuario-propietario'
    if (-not $usuario) { $usuario = 'tbp_owner' }
    $clave = Leer-ValorYaml $texto 'clave-propietario'
    if (-not $clave) { throw "Falta clave-propietario en $fichero." }
    $esquema = Leer-ValorYaml $texto 'esquema'
    if (-not $esquema) { $esquema = 'tbp' }
    return [pscustomobject]@{
        Servidor = $m.Groups[1].Value
        Puerto   = $(if ($m.Groups[2].Value) { $m.Groups[2].Value } else { '5432' })
        Base     = $m.Groups[3].Value
        Usuario  = $usuario
        Clave    = $clave
        Esquema  = $esquema
    }
}

# config\copias.conf: líneas CLAVE=valor (sin comillas). Las barras \ se respetan tal cual.
function Leer-ConfCopias([string]$instalacion) {
    $conf = @{ DESTINO_EXTERNO = ''; RETENCION_DIAS = '30'; PG_BIN = '' }
    $fichero = Join-Path $instalacion 'config\copias.conf'
    if (Test-Path $fichero) {
        foreach ($linea in Get-Content -Encoding UTF8 $fichero) {
            $l = $linea.Trim()
            if ($l -eq '' -or $l.StartsWith('#')) { continue }
            $i = $l.IndexOf('=')
            if ($i -gt 0) { $conf[$l.Substring(0, $i).Trim().ToUpper()] = $l.Substring($i + 1).Trim() }
        }
    }
    return $conf
}

# Carpeta bin de PostgreSQL (pg_dump, pg_restore, psql).
function Buscar-PgBin([string]$preferida) {
    foreach ($c in @($preferida, $env:PG_BIN)) {
        if ($c -and (Test-Path (Join-Path $c 'pg_dump.exe'))) { return $c }
    }
    $servicios = @(Get-CimInstance Win32_Service | Where-Object { $_.Name -like 'postgresql*' } | Sort-Object Name -Descending)
    foreach ($s in $servicios) {
        $m = [regex]::Match([string]$s.PathName, '^"?(.+?)\\pg_ctl\.exe', 'IgnoreCase')
        if ($m.Success -and (Test-Path (Join-Path $m.Groups[1].Value 'pg_dump.exe'))) { return $m.Groups[1].Value }
    }
    $raiz = Join-Path $env:ProgramFiles 'PostgreSQL'
    if (Test-Path $raiz) {
        $versiones = Get-ChildItem $raiz -Directory | Sort-Object { [int](($_.Name -replace '\D', '') + '0') } -Descending
        foreach ($d in $versiones) {
            $bin = Join-Path $d.FullName 'bin'
            if (Test-Path (Join-Path $bin 'pg_dump.exe')) { return $bin }
        }
    }
    throw 'No encuentro pg_dump.exe. Indica la carpeta en config\copias.conf (PG_BIN=C:\Program Files\PostgreSQL\16\bin).'
}

# Nombre del servicio de Windows de PostgreSQL (postgresql-x64-16…), o $null.
function Servicio-PostgreSql {
    $s = @(Get-Service -Name 'postgresql*' -ErrorAction SilentlyContinue | Sort-Object Name -Descending)
    if ($s.Count -gt 0) { return $s[0].Name }
    return $null
}

# Ejecuta un programa sin que PowerShell convierta su salida de error en excepción.
# Devuelve @{ Codigo; Salida } (salida y errores juntos, como texto).
function Ejecutar([string]$programa, [string[]]$argumentos, [hashtable]$entorno = @{}) {
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = $programa
    $info.Arguments = ($argumentos | ForEach-Object { Comillas $_ }) -join ' '
    $info.UseShellExecute = $false
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.CreateNoWindow = $true
    foreach ($k in $entorno.Keys) { $info.EnvironmentVariables[$k] = [string]$entorno[$k] }
    $p = [System.Diagnostics.Process]::Start($info)
    $salidaTarea = $p.StandardOutput.ReadToEndAsync()
    $errorTarea = $p.StandardError.ReadToEndAsync()
    $p.WaitForExit()
    return [pscustomobject]@{ Codigo = $p.ExitCode; Salida = ($salidaTarea.Result + $errorTarea.Result).Trim() }
}

function Comillas([string]$a) {
    if ($a -eq '') { return '""' }
    if ($a -notmatch '[\s"]') { return $a }
    return '"' + ($a -replace '(\\*)"', '$1$1\"' -replace '(\\+)$', '$1$1') + '"'
}

# Puerto y protocolo con los que responde el servicio instalado.
function Direccion-Servicio([string]$instalacion) {
    $puerto = '5001'
    $f = Join-Path $instalacion 'config\application-servidor.yml'
    if (Test-Path $f) {
        $p = Leer-ValorYaml (Get-Content -Raw -Encoding UTF8 $f) 'port'
        if ($p) { $puerto = $p }
    }
    $https = Test-Path (Join-Path $instalacion 'config\tbp-certificado.p12')
    return [pscustomobject]@{ Puerto = $puerto; Https = $https; Esquema = $(if ($https) { 'https' } else { 'http' }) }
}

# Consulta /api/v1/salud en este PC. Solo se acepta el certificado de ESTA instalación.
function Consultar-Salud([string]$instalacion, [int]$esperaS = 10) {
    $d = Direccion-Servicio $instalacion
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $cer = Join-Path $instalacion 'certificado\TestBenchPlanner.cer'
    if ($d.Https -and (Test-Path $cer)) {
        # Validación en código compilado: en PowerShell 5.1 Invoke-WebRequest comprueba el
        # certificado en otro hilo, donde un scriptblock no puede ejecutarse.
        if (-not ('TbpCertificadoPropio' -as [type])) {
            Add-Type -TypeDefinition @'
using System.Net.Security;
using System.Security.Cryptography.X509Certificates;
public static class TbpCertificadoPropio {
    public static string Huella;
    public static bool Validar(object origen, X509Certificate certificado, X509Chain cadena, SslPolicyErrors errores) {
        return certificado != null && Huella != null && string.Equals(certificado.GetCertHashString(), Huella, System.StringComparison.OrdinalIgnoreCase);
    }
}
'@
        }
        [TbpCertificadoPropio]::Huella = (New-Object System.Security.Cryptography.X509Certificates.X509Certificate2($cer)).Thumbprint
        [Net.ServicePointManager]::ServerCertificateValidationCallback = [Delegate]::CreateDelegate(
            [Net.Security.RemoteCertificateValidationCallback], [TbpCertificadoPropio].GetMethod('Validar'))
    }
    $url = "$($d.Esquema)://localhost:$($d.Puerto)$script:RutaSalud"
    try {
        $r = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec $esperaS
        return [pscustomobject]@{ Url = $url; Codigo = [int]$r.StatusCode; Datos = ($r.Content | ConvertFrom-Json); Error = $null }
    } catch {
        $codigo = 0
        $datos = $null
        $resp = $null
        if ($_.Exception.PSObject.Properties['Response']) { $resp = $_.Exception.Response }
        if ($null -ne $resp) {
            $codigo = [int]$resp.StatusCode
            try { $datos = (New-Object IO.StreamReader($resp.GetResponseStream())).ReadToEnd() | ConvertFrom-Json } catch { }
        }
        return [pscustomobject]@{ Url = $url; Codigo = $codigo; Datos = $datos; Error = $_.Exception.Message }
    }
}

# Espera a que la aplicación conteste «ok». Devuelve $true / $false.
function Esperar-Salud([string]$instalacion, [int]$maximoS = 180) {
    $limite = (Get-Date).AddSeconds($maximoS)
    while ((Get-Date) -lt $limite) {
        $s = Consultar-Salud $instalacion 5
        if ($s.Codigo -eq 200 -and $s.Datos -and $s.Datos.estado -eq 'ok') { return $true }
        Start-Sleep -Seconds 3
        Write-Host '.' -NoNewline
    }
    Write-Host ''
    return $false
}

function Parar-Servicio {
    $svc = Get-Service -Name $script:NombreServicio -ErrorAction SilentlyContinue
    if ($svc -and $svc.Status -ne 'Stopped') {
        Stop-Service -Name $script:NombreServicio -Force
        $svc.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(90))
    }
}

function Arrancar-Servicio {
    Start-Service -Name $script:NombreServicio
}

# Registro de los scripts (copias, restauraciones, actualizaciones) en logs\scripts.log
function Anotar([string]$instalacion, [string]$texto) {
    $carpeta = Join-Path $instalacion 'logs'
    if (-not (Test-Path $carpeta)) { New-Item -ItemType Directory -Path $carpeta | Out-Null }
    $linea = '{0:yyyy-MM-dd HH:mm:ss} [{1}] {2}' -f (Get-Date), $env:USERNAME, $texto
    Add-Content -Path (Join-Path $carpeta 'scripts.log') -Value $linea -Encoding UTF8
}
