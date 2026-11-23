# =============================================================================
#  TestBenchPlanner - DESINSTALAR EL SERVICIO (hito S4).
#  Quita el servicio de Windows, la regla de firewall y la tarea de copia diaria.
#  NO borra la carpeta de instalación (configuración, certificado, copias, logs)
#  ni la base de datos: reinstalar con instalar-servicio.cmd lo deja como estaba.
# =============================================================================
. "$PSScriptRoot\servicio\comun.ps1"
try {
    Exigir-Administrador
    $svc = Get-CimInstance Win32_Service -Filter "Name='$NombreServicio'" -ErrorAction SilentlyContinue
    if ((Read-Host 'Se va a quitar el servicio TestBenchPlanner (nadie podrá entrar). Escribe QUITAR para seguir') -cne 'QUITAR') {
        Write-Host 'Cancelado.'; exit 0
    }
    if ($svc) {
        Parar-Servicio
        $m = [regex]::Match([string]$svc.PathName, '^"?(.+\\TestBenchPlanner\.exe)', 'IgnoreCase')
        $r = $null
        if ($m.Success -and (Test-Path $m.Groups[1].Value)) { $r = Ejecutar $m.Groups[1].Value @('uninstall') }
        if (-not $r -or $r.Codigo -ne 0) {
            $r = Ejecutar "$env:SystemRoot\System32\sc.exe" @('delete', $NombreServicio)
            if ($r.Codigo -ne 0) { throw "No se ha podido quitar el servicio: $($r.Salida)" }
        }
        Escribir-Ok 'Servicio quitado'
    } else { Escribir-Ok 'El servicio no estaba instalado' }
    Get-NetFirewallRule -DisplayName 'TestBenchPlanner' -ErrorAction SilentlyContinue | Remove-NetFirewallRule
    Escribir-Ok 'Regla de firewall quitada'
    Unregister-ScheduledTask -TaskName 'TestBenchPlanner - copia diaria' -Confirm:$false -ErrorAction SilentlyContinue
    Escribir-Ok 'Copia diaria quitada'
    Write-Host 'La carpeta de instalación y la base de datos se conservan.'
    exit 0
} catch {
    Escribir-Fallo $_.Exception.Message
    exit 1
}
