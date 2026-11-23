# =============================================================================
#  TestBenchPlanner - INSTALACIÓN EN EL PC SERVIDOR (hito S4).
#  Se lanza con instalar-servicio.cmd (pide permisos de administrador).
#  Se puede repetir: conserva configuración, certificado y copias.
#
#  Deja TestBenchPlanner como servicio de Windows que arranca solo (también sin
#  sesión iniciada), se reinicia si cae y atiende por HTTPS; abre el puerto en
#  el firewall (solo perfiles dominio/privado), programa la copia diaria de la
#  base de datos y quita la suspensión del PC. Ver docs/13-despliegue-servidor.md.
# =============================================================================
. "$PSScriptRoot\servicio\comun.ps1"

$UrlWinSW = @(
    'https://github.com/winsw/winsw/releases/download/v2.12.0/WinSW-x64.exe',
    'https://github.com/winsw/winsw/releases/download/v2.12.0/WinSW.NET461.exe'
)
$TareaCopia = 'TestBenchPlanner - copia diaria'
$ReglaFirewall = 'TestBenchPlanner'
$SidAdministradores = '*S-1-5-32-544'
$SidSistema = '*S-1-5-18'
$SidServicioLocal = '*S-1-5-19'
$SidUsuarios = '*S-1-5-32-545'

function Preguntar([string]$texto, [string]$defecto) {
    $r = Read-Host "$texto [$defecto]"
    if ([string]::IsNullOrWhiteSpace($r)) { return $defecto }
    return $r.Trim()
}

function Icacls([string[]]$argumentos) {
    $r = Ejecutar "$env:SystemRoot\System32\icacls.exe" $argumentos
    if ($r.Codigo -ne 0) { throw "icacls $($argumentos -join ' '): $($r.Salida)" }
}

function Clave-Aleatoria([int]$largo = 32) {
    $letras = [char[]]'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'
    $bytes = New-Object byte[] $largo
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($bytes)
    return -join ($bytes | ForEach-Object { $letras[$_ % $letras.Length] })
}

function Jar-TieneInterfaz([string]$jar) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($jar)
    try { return [bool]($zip.Entries | Where-Object { $_.FullName -eq 'BOOT-INF/classes/static/index.html' }) }
    finally { $zip.Dispose() }
}

# Java 17+ con el que se compiló/arrancó hasta ahora: devuelve su carpeta (java.home).
function Buscar-Java {
    $candidatos = New-Object System.Collections.Generic.List[string]
    if ($env:JAVA_HOME) { $candidatos.Add((Join-Path $env:JAVA_HOME 'bin\java.exe')) }
    $enPath = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($enPath) { $candidatos.Add($enPath.Source) }
    $patrones = @("$env:ProgramFiles\Java\jdk*", "$env:ProgramFiles\Eclipse Adoptium\jdk*", "$env:ProgramFiles\Eclipse Adoptium\jre*",
        "$env:ProgramFiles\Microsoft\jdk*", "$env:ProgramFiles\Amazon Corretto\jdk*", "$env:ProgramFiles\Zulu\zulu*",
        "$env:USERPROFILE\.jdks\*", "$env:LOCALAPPDATA\Programs\Eclipse Adoptium\jdk*", "$env:USERPROFILE\.vscode\extensions\redhat.java-*\jre\*")
    foreach ($p in $patrones) {
        Get-ChildItem -Path $p -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending |
            ForEach-Object { $candidatos.Add((Join-Path $_.FullName 'bin\java.exe')) }
    }
    foreach ($java in $candidatos) {
        if (-not (Test-Path $java)) { continue }
        $r = Ejecutar $java @('-XshowSettings:properties', '-version')
        $home_ = [regex]::Match($r.Salida, '(?m)^\s*java\.home = (.+)$').Groups[1].Value.Trim()
        $version = [regex]::Match($r.Salida, '(?m)^\s*java\.specification\.version = (\d+)').Groups[1].Value
        if ($home_ -and $version -and [int]$version -ge 17 -and (Test-Path (Join-Path $home_ 'bin\java.exe'))) {
            return [pscustomobject]@{ Carpeta = $home_; Version = [int]$version }
        }
    }
    throw 'No encuentro Java 17 o superior. Instala un JDK 17 (p. ej. Eclipse Temurin) o define JAVA_HOME.'
}

function Obtener-WinSW([string]$destinoExe) {
    $local = @("$PSScriptRoot\winsw\WinSW-x64.exe", "$PSScriptRoot\winsw\WinSW.NET461.exe")
    foreach ($f in $local) {
        if (Test-Path $f) { Copy-Item $f $destinoExe -Force; return "copiado de $f" }
    }
    if (Test-Path $destinoExe) { return 'el que ya estaba instalado' }
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    New-Item -ItemType Directory -Force -Path "$PSScriptRoot\winsw" | Out-Null
    foreach ($url in $UrlWinSW) {
        $guardado = Join-Path "$PSScriptRoot\winsw" (Split-Path -Leaf $url)
        try {
            Invoke-WebRequest -Uri $url -OutFile $guardado -UseBasicParsing -TimeoutSec 120
            $cab = [IO.File]::ReadAllBytes($guardado)[0..1]
            if ((Get-Item $guardado).Length -gt 100KB -and $cab[0] -eq 0x4D -and $cab[1] -eq 0x5A) {
                Copy-Item $guardado $destinoExe -Force
                return "descargado de $url"
            }
            Remove-Item $guardado -Force
        } catch {
            Escribir-Aviso "No se ha podido descargar $url ($($_.Exception.Message))"
        }
    }
    throw ("No tengo WinSW (el programa que convierte TestBenchPlanner en servicio de Windows).`n" +
        "Descárgalo a mano desde https://github.com/winsw/winsw/releases/tag/v2.12.0 (fichero WinSW-x64.exe)`n" +
        "y déjalo en $PSScriptRoot\winsw\WinSW-x64.exe. Luego vuelve a lanzar la instalación.")
}

function Nombres-Del-Servidor {
    $dns = New-Object System.Collections.Generic.List[string]
    $dns.Add($env:COMPUTERNAME)
    try {
        $fqdn = [Net.Dns]::GetHostEntry($env:COMPUTERNAME).HostName
        if ($fqdn -and -not $dns.Contains($fqdn)) { $dns.Add($fqdn) }
    } catch { }
    $dns.Add('localhost')
    $ips = New-Object System.Collections.Generic.List[string]
    $ips.Add('127.0.0.1')
    Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object { $_.IPAddress -ne '127.0.0.1' -and $_.IPAddress -notlike '169.254.*' } |
        ForEach-Object { if (-not $ips.Contains($_.IPAddress)) { $ips.Add($_.IPAddress) } }
    return [pscustomobject]@{ Dns = $dns; Ips = $ips }
}

function Crear-Certificado([string]$inst) {
    $p12 = Join-Path $inst 'config\tbp-certificado.p12'
    $cer = Join-Path $inst 'certificado\TestBenchPlanner.cer'
    $clave = Clave-Aleatoria 32
    $n = Nombres-Del-Servidor
    $keytool = Join-Path $inst 'jre\bin\keytool.exe'
    foreach ($f in @($p12, $cer)) { if (Test-Path $f) { Remove-Item $f -Force } }
    if (Test-Path $keytool) {
        $san = (($n.Dns | ForEach-Object { "dns:$_" }) + ($n.Ips | ForEach-Object { "ip:$_" })) -join ','
        $entorno = @{ TBP_CLAVE_CERT = $clave }
        $r = Ejecutar $keytool @('-genkeypair', '-alias', 'tbp', '-keyalg', 'RSA', '-keysize', '3072', '-sigalg', 'SHA256withRSA',
            '-validity', '1825', '-storetype', 'PKCS12', '-keystore', $p12, '-storepass:env', 'TBP_CLAVE_CERT',
            '-dname', "CN=$($n.Dns[0]), OU=HVCH Testing, O=BorgWarner, L=Vigo, C=ES",
            '-ext', "SAN=$san", '-ext', 'EKU=serverAuth', '-ext', 'KU=digitalSignature,keyEncipherment') $entorno
        if ($r.Codigo -ne 0) { throw "keytool no ha creado el certificado: $($r.Salida)" }
        $r = Ejecutar $keytool @('-exportcert', '-rfc', '-alias', 'tbp', '-storetype', 'PKCS12', '-keystore', $p12,
            '-storepass:env', 'TBP_CLAVE_CERT', '-file', $cer) $entorno
        if ($r.Codigo -ne 0) { throw "keytool no ha exportado el certificado: $($r.Salida)" }
    } else {
        $san = (($n.Dns | ForEach-Object { "DNS=$_" }) + ($n.Ips | ForEach-Object { "IPAddress=$_" })) -join '&'
        $c = New-SelfSignedCertificate -Subject "CN=$($n.Dns[0]), OU=HVCH Testing, O=BorgWarner, L=Vigo, C=ES" `
            -CertStoreLocation Cert:\LocalMachine\My -KeyAlgorithm RSA -KeyLength 3072 -HashAlgorithm SHA256 `
            -KeyExportPolicy Exportable -NotAfter (Get-Date).AddYears(5) `
            -TextExtension @('2.5.29.37={text}1.3.6.1.5.5.7.3.1', "2.5.29.17={text}$san")
        try {
            Export-PfxCertificate -Cert $c -FilePath $p12 -Password (ConvertTo-SecureString $clave -AsPlainText -Force) | Out-Null
            Export-Certificate -Cert $c -FilePath $cer -Type CERT | Out-Null
        } finally {
            Remove-Item -Path "Cert:\LocalMachine\My\$($c.Thumbprint)" -DeleteKey -ErrorAction SilentlyContinue
        }
    }
    $yml = "# Generado por instalar-servicio el $(Get-Date -Format 'yyyy-MM-dd HH:mm'). NO se versiona ni se reparte.`r`n" +
        "# Clave del almacén config\tbp-certificado.p12 (perfil https, docs/13-despliegue-servidor.md).`r`n" +
        "server:`r`n  ssl:`r`n    key-store-password: `"$clave`"`r`n"
    [IO.File]::WriteAllText((Join-Path $inst 'config\application-https.yml'), $yml, (New-Object Text.UTF8Encoding($false)))
    $x = New-Object Security.Cryptography.X509Certificates.X509Certificate2($cer)
    Escribir-Ok "Certificado autofirmado para $($n.Dns -join ', ') / $($n.Ips -join ', '); caduca el $($x.NotAfter.ToString('dd/MM/yyyy'))"
}

function Ajustar-Puerto([string]$inst, [string]$puerto) {
    $f = Join-Path $inst 'config\application-servidor.yml'
    if (-not (Test-Path $f)) {
        $t = "# Ajustes locales del modo servidor (no se versiona). Ver docs/13-despliegue-servidor.md.`r`nserver:`r`n  port: $puerto`r`n"
        [IO.File]::WriteAllText($f, $t, (New-Object Text.UTF8Encoding($false)))
        return
    }
    $t = Get-Content -Raw -Encoding UTF8 $f
    if ($t -match '(?m)^[ \t]+port[ \t]*:') {
        $t = [regex]::Replace($t, '(?m)^([ \t]+port[ \t]*:[ \t]*)[^\r\n#]*', "`${1}$puerto")
    } elseif ($t -notmatch '(?m)^server[ \t]*:') {
        $t = $t.TrimEnd() + "`r`nserver:`r`n  port: $puerto`r`n"
    } else {
        throw "En $f hay una sección server: sin port. Añade «  port: $puerto» debajo de server: y repite."
    }
    [IO.File]::WriteAllText($f, $t, (New-Object Text.UTF8Encoding($false)))
}

function Escribir-XmlServicio([string]$inst, [string]$puerto, [string]$servicioPg) {
    $dependencia = ''
    if ($servicioPg) { $dependencia = "  <depend>$servicioPg</depend>`r`n" }
    $xml = @"
<?xml version="1.0" encoding="UTF-8"?>
<!-- Servicio de Windows de TestBenchPlanner (WinSW 2.x). Lo genera instalar-servicio: no editar a mano. -->
<service>
  <id>TestBenchPlanner</id>
  <name>TestBenchPlanner</name>
  <description>TestBenchPlanner (HVCH Testing Vigo). Se entra con https://$($env:COMPUTERNAME):$puerto</description>
  <executable>%BASE%\jre\bin\java.exe</executable>
  <arguments>-Xms256m -Xmx1g -Dfile.encoding=UTF-8 -Djava.io.tmpdir="%BASE%\temp" -jar "%BASE%\app\testbenchplanner.jar" --spring.profiles.active=postgresql,servidor,https --planner.carpeta-usuario=datos --logging.pattern.console=</arguments>
  <workingdirectory>%BASE%</workingdirectory>
  <startmode>Automatic</startmode>
  <delayedAutoStart>true</delayedAutoStart>
$dependencia  <onfailure action="restart" delay="10 sec"/>
  <onfailure action="restart" delay="30 sec"/>
  <onfailure action="restart" delay="2 min"/>
  <resetfailure>1 hour</resetfailure>
  <stoptimeout>40 sec</stoptimeout>
  <serviceaccount>
    <domain>NT AUTHORITY</domain>
    <user>LocalService</user>
  </serviceaccount>
  <logpath>%BASE%\logs</logpath>
  <log mode="roll-by-size">
    <sizeThreshold>10240</sizeThreshold>
    <keepFiles>5</keepFiles>
  </log>
</service>
"@
    [IO.File]::WriteAllText((Join-Path $inst 'TestBenchPlanner.xml'), $xml, (New-Object Text.UTF8Encoding($false)))
}

function Quitar-Servicio([string]$exe) {
    $svc = Get-Service -Name $NombreServicio -ErrorAction SilentlyContinue
    if (-not $svc) { return }
    Parar-Servicio
    $r = Ejecutar $exe @('uninstall')
    if ($r.Codigo -ne 0) {
        $r2 = Ejecutar "$env:SystemRoot\System32\sc.exe" @('delete', $NombreServicio)
        if ($r2.Codigo -ne 0) { throw "No se ha podido quitar el servicio anterior: $($r.Salida) $($r2.Salida)" }
    }
    for ($i = 0; $i -lt 30 -and (Get-Service -Name $NombreServicio -ErrorAction SilentlyContinue); $i++) { Start-Sleep -Seconds 1 }
}

# ------------------------------------------------------------------ inicio
try {
    Exigir-Administrador
    $repo = (Resolve-Path "$PSScriptRoot\..\..").Path
    $jar = Join-Path $repo 'backend\target\testbenchplanner.jar'
    Write-Host 'TestBenchPlanner - instalación como servicio de Windows (hito S4)' -ForegroundColor White

    Escribir-Paso 'Comprobando lo compilado'
    if (-not (Test-Path $jar)) { throw "Falta $jar. Compila antes en backend:  .\mvnw.cmd clean verify  (o -Pinterfaz)." }
    if (-not (Jar-TieneInterfaz $jar)) {
        throw 'El programa compilado no lleva la interfaz (frontend\dist). Compila con  .\mvnw.cmd -Pinterfaz clean verify  o copia frontend\dist y recompila.'
    }
    Escribir-Ok "$jar ($([math]::Round((Get-Item $jar).Length / 1MB, 1)) MB, con interfaz)"

    $existente = Get-CimInstance Win32_Service -Filter "Name='$NombreServicio'" -ErrorAction SilentlyContinue
    $defecto = 'C:\TestBenchPlanner'
    if ($existente) {
        $m = [regex]::Match([string]$existente.PathName, '^"?(.+)\\TestBenchPlanner\.exe', 'IgnoreCase')
        if ($m.Success) { $defecto = $m.Groups[1].Value }
    }
    $inst = Preguntar 'Carpeta de instalación' $defecto
    if ($existente -and $inst -ne $defecto) { throw "El servicio ya está instalado en $defecto. Desinstálalo antes (desinstalar-servicio.cmd) o usa esa carpeta." }
    $puertoDefecto = (Direccion-Servicio $inst).Puerto
    if (-not (Test-Path (Join-Path $inst 'config\application-servidor.yml'))) {
        $f = Join-Path $repo 'backend\config\application-servidor.yml'
        if (Test-Path $f) { $p = Leer-ValorYaml (Get-Content -Raw -Encoding UTF8 $f) 'port'; if ($p) { $puertoDefecto = $p } }
    }
    $puerto = Preguntar 'Puerto de acceso' $puertoDefecto
    if ($puerto -notmatch '^\d+$' -or [int]$puerto -lt 1 -or [int]$puerto -gt 65535) { throw "Puerto no válido: $puerto" }

    Escribir-Paso "Preparando $inst"
    foreach ($d in @('app', 'jre', 'config', 'certificado', 'logs', 'copias', 'datos', 'temp', 'herramientas')) {
        New-Item -ItemType Directory -Force -Path (Join-Path $inst $d) | Out-Null
    }
    $exe = Join-Path $inst 'TestBenchPlanner.exe'
    if (Test-Path $exe) { Quitar-Servicio $exe }
    Escribir-Ok 'Carpetas listas'

    Escribir-Paso 'Programa y Java propios de la instalación'
    $jarDestino = Join-Path $inst 'app\testbenchplanner.jar'
    if ((Test-Path $jarDestino) -and ((Get-FileHash $jarDestino).Hash -ne (Get-FileHash $jar).Hash)) {
        Copy-Item $jarDestino (Join-Path $inst 'app\testbenchplanner.anterior.jar') -Force
    }
    Copy-Item $jar $jarDestino -Force
    Escribir-Ok "app\testbenchplanner.jar"
    $java = Buscar-Java
    $jre = Join-Path $inst 'jre'
    if ((Resolve-Path $java.Carpeta).Path.TrimEnd('\') -ine $jre.TrimEnd('\')) {
        $r = Ejecutar "$env:SystemRoot\System32\robocopy.exe" @($java.Carpeta, $jre, '/MIR', '/NFL', '/NDL', '/NJH', '/NJS', '/NP', '/R:1', '/W:1')
        if ($r.Codigo -ge 8) { throw "No se ha podido copiar Java: $($r.Salida)" }
    }
    Escribir-Ok "Java $($java.Version) copiado de $($java.Carpeta) a jre\ (el servicio no depende de esa instalación)"

    Escribir-Paso 'Servicio de Windows (WinSW)'
    Escribir-Ok ("WinSW: " + (Obtener-WinSW $exe))

    Escribir-Paso 'Configuración'
    $cfg = Join-Path $inst 'config\application-postgresql.yml'
    if (-not (Test-Path $cfg)) {
        $origen = Join-Path $repo 'backend\config\application-postgresql.yml'
        if (-not (Test-Path $origen)) {
            throw "Falta la configuración de la base de datos. Créala a partir de scripts\servidor\application-postgresql.yml.ejemplo en $cfg (o en backend\config\) y repite."
        }
        Copy-Item $origen $cfg
        Escribir-Ok "Copiada $origen"
    } else {
        Escribir-Ok 'config\application-postgresql.yml ya estaba (se conserva)'
    }
    $cx = Leer-ConexionPropietario $inst
    Ajustar-Puerto $inst $puerto
    Escribir-Ok "Puerto $puerto (config\application-servidor.yml)"
    $copiasConf = Join-Path $inst 'config\copias.conf'
    if (-not (Test-Path $copiasConf)) {
        Write-Host '    Copia diaria: además de en la carpeta copias\ de la instalación, ¿en otra ubicación?'
        Write-Host '    (carpeta de otro disco o unidad de red \\servidor\carpeta; Enter = ninguna por ahora)'
        $externo = (Read-Host '    Otra ubicación').Trim()
        $t = "# Copias de seguridad de TestBenchPlanner (copia-seguridad.ps1). Una clave por línea, sin comillas.`r`n" +
            "# DESTINO_EXTERNO: carpeta adicional (otro disco o unidad de red). Vacío = solo copias\ de la instalación.`r`n" +
            "#   La copia diaria la hace la cuenta SYSTEM: en una unidad de red, el EQUIPO ($($env:COMPUTERNAME)$) necesita permiso de escritura.`r`n" +
            "DESTINO_EXTERNO=$externo`r`n# Días que se guardan (siempre quedan al menos las 7 más recientes).`r`nRETENCION_DIAS=30`r`n" +
            "# Carpeta bin de PostgreSQL si no se encuentra sola.`r`nPG_BIN=`r`n"
        [IO.File]::WriteAllText($copiasConf, $t, (New-Object Text.UTF8Encoding($false)))
    }
    Escribir-Ok 'config\copias.conf'
    Copy-Item "$PSScriptRoot\servicio\*" (Join-Path $inst 'herramientas') -Force
    Escribir-Ok 'Herramientas copiadas a herramientas\'

    Escribir-Paso 'HTTPS'
    if (-not (Test-Path (Join-Path $inst 'config\tbp-certificado.p12')) -or -not (Test-Path (Join-Path $inst 'config\application-https.yml'))) {
        Crear-Certificado $inst
    } else {
        $x = New-Object Security.Cryptography.X509Certificates.X509Certificate2((Join-Path $inst 'certificado\TestBenchPlanner.cer'))
        Escribir-Ok "Se conserva el certificado actual (caduca el $($x.NotAfter.ToString('dd/MM/yyyy'))). Para uno nuevo, borra config\tbp-certificado.p12 y repite."
    }

    Escribir-Paso 'Permisos de las carpetas'
    Icacls @($inst, '/inheritance:r', '/grant:r', "${SidAdministradores}:(OI)(CI)F", "${SidSistema}:(OI)(CI)F",
        "${SidServicioLocal}:(OI)(CI)RX", "${SidUsuarios}:(OI)(CI)RX", '/C', '/Q')
    foreach ($d in @('logs', 'datos', 'temp')) {
        Icacls @((Join-Path $inst $d), '/grant', "${SidServicioLocal}:(OI)(CI)M", '/C', '/Q')
    }
    Icacls @((Join-Path $inst 'config'), '/inheritance:r', '/grant:r', "${SidAdministradores}:(OI)(CI)F", "${SidSistema}:(OI)(CI)F",
        "${SidServicioLocal}:(OI)(CI)RX", '/C', '/Q')
    Icacls @((Join-Path $inst 'copias'), '/inheritance:r', '/grant:r', "${SidAdministradores}:(OI)(CI)F", "${SidSistema}:(OI)(CI)F", '/C', '/Q')
    Escribir-Ok 'config\ y copias\ solo para Administradores (claves de la base y del certificado); el servicio corre como LocalService'

    Escribir-Paso 'Instalando el servicio'
    $pg = Servicio-PostgreSql
    if ($pg) { Escribir-Ok "Arrancará después de PostgreSQL ($pg)" } else { Escribir-Aviso 'No encuentro el servicio de PostgreSQL: el servicio no esperará a que arranque.' }
    Escribir-XmlServicio $inst $puerto $pg
    $r = Ejecutar $exe @('install')
    if ($r.Codigo -ne 0) { throw "WinSW no ha instalado el servicio: $($r.Salida)" }
    Escribir-Ok 'Servicio TestBenchPlanner: automático (inicio retrasado), se reinicia si cae'

    Escribir-Paso 'Firewall'
    Get-NetFirewallRule -DisplayName $ReglaFirewall -ErrorAction SilentlyContinue | Remove-NetFirewallRule
    New-NetFirewallRule -DisplayName $ReglaFirewall -Description 'TestBenchPlanner (HTTPS). Creada por instalar-servicio.' `
        -Direction Inbound -Protocol TCP -LocalPort $puerto -Action Allow -Profile Domain, Private `
        -Program (Join-Path $inst 'jre\bin\java.exe') | Out-Null
    Escribir-Ok "Entrada TCP $puerto permitida solo en perfiles dominio y privado"

    Escribir-Paso 'Energía'
    foreach ($a in @(@('/change', 'standby-timeout-ac', '0'), @('/change', 'hibernate-timeout-ac', '0'))) {
        $r = Ejecutar "$env:SystemRoot\System32\powercfg.exe" $a
        if ($r.Codigo -ne 0) { Escribir-Aviso "powercfg $($a -join ' '): $($r.Salida)" }
    }
    Escribir-Ok 'Sin suspensión ni hibernación con corriente'

    Escribir-Paso 'Copia diaria de la base de datos'
    $accion = New-ScheduledTaskAction -Execute 'powershell.exe' `
        -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$inst\herramientas\copia-seguridad.ps1`" -Motivo diaria"
    $cuando = New-ScheduledTaskTrigger -Daily -At '02:30'
    $quien = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
    $ajustes = New-ScheduledTaskSettingsSet -StartWhenAvailable -ExecutionTimeLimit (New-TimeSpan -Hours 2)
    Register-ScheduledTask -TaskName $TareaCopia -Action $accion -Trigger $cuando -Principal $quien -Settings $ajustes `
        -Description 'pg_dump diario de TestBenchPlanner (docs/13-despliegue-servidor.md)' -Force | Out-Null
    Escribir-Ok "Tarea «$TareaCopia» a las 02:30 (si el PC estaba apagado, en cuanto se encienda)"

    Escribir-Paso 'Arrancando TestBenchPlanner'
    Arrancar-Servicio
    if (Esperar-Salud $inst 240) {
        $s = Consultar-Salud $inst
        Escribir-Ok "En marcha: versión $($s.Datos.version), base de datos $($s.Datos.baseDatos)"
    } else {
        throw "El servicio no responde. Mira $inst\logs\testbenchplanner.log y $inst\logs\TestBenchPlanner.err.log"
    }

    Escribir-Paso 'Primera copia de seguridad'
    & "$inst\herramientas\copia-seguridad.ps1" -Motivo instalacion -Instalacion $inst
    if ($LASTEXITCODE -eq 1) { Escribir-Aviso 'La copia ha fallado: revísalo con herramientas\copia-seguridad.cmd' }

    Anotar $inst "Instalación/reinstalación del servicio (puerto $puerto)"
    Write-Host ''
    Write-Host '==============================================================' -ForegroundColor Green
    Write-Host "  TestBenchPlanner instalado en $inst" -ForegroundColor Green
    Write-Host "  Se entra con:  https://$($env:COMPUTERNAME):$puerto" -ForegroundColor Green
    Write-Host "  Certificado para los PCs de los usuarios:  $inst\certificado\TestBenchPlanner.cer"
    Write-Host '  Siguiente: docs\13-despliegue-servidor.md (repartir el certificado,'
    Write-Host '  comprobar-servidor, prueba de reinicio y de restauración).'
    Write-Host '==============================================================' -ForegroundColor Green
    exit 0
} catch {
    Write-Host ''
    Escribir-Fallo $_.Exception.Message
    exit 1
}
