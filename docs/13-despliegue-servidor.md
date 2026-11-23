# 13 · Despliegue en el PC servidor (hito S4)

> **Estado (17-nov-2026): código terminado y `mvnw clean verify` en verde; sin probar en el servidor.** Hace falta
> un PC con permisos de administrador (el PC corporativo de pruebas no los tiene).
> Criterio de cierre del plan: *servidor reiniciado → app operativa sin
> intervención; restauración de copia probada* (apartado 10).

Con S1–S3 la aplicación funciona en modo servidor, pero hay que arrancarla a mano
(`arrancar-servidor.cmd`), va sin cifrar y no hay copias. S4 la deja **instalada
como servicio de Windows**:

| Tema | Cómo queda |
|---|---|
| Arranque | Servicio `TestBenchPlanner` (WinSW 2.12): automático con inicio retrasado, **también sin sesión iniciada**, después de PostgreSQL; si el proceso cae se reinicia (10 s, 30 s y después cada 2 min) |
| Cuenta | `NT AUTHORITY\LocalService` (sin privilegios ni acceso a la red con credenciales) |
| Java | Copia propia en `jre\`: el servicio no depende del JDK con el que se compiló |
| HTTPS | **Obligatorio.** Certificado autofirmado generado en el servidor (5 años, RSA 3072) con el nombre del PC, su nombre completo, `localhost` y sus IP. Solo TLS 1.2/1.3. La cookie de sesión solo viaja cifrada |
| Firewall | Regla `TestBenchPlanner`: solo el puerto de la app, solo `java.exe` de la instalación, solo perfiles dominio y privado |
| Energía | Sin suspensión ni hibernación con corriente |
| Copias | Tarea programada diaria (02:30, o al encender si estaba apagado) con `pg_dump`, comprobada, duplicada en otra ubicación si se indica, 30 días; copia extra antes de cada actualización y de cada restauración |
| Logs | `logs\testbenchplanner.log`, rotación diaria o cada 10 MB, 30 días, máximo 1 GB. Logs del servicio aparte (5 × 10 MB) |
| Salud | `GET /api/v1/salud` sin iniciar sesión: 200 `{"estado":"ok",...}` o 503 si no llega a la base. No da datos de la planificación ni de personas |
| Guía | Este documento |

## 1. Qué hay en la carpeta de instalación

Por defecto `C:\TestBenchPlanner` (se puede elegir otra al instalar):

```text
C:\TestBenchPlanner\
├── TestBenchPlanner.exe / .xml   servicio de Windows (WinSW) y su configuración (la genera el instalador)
├── app\testbenchplanner.jar      programa (y testbenchplanner.anterior.jar tras una actualización)
├── jre\                          Java propio
├── config\                       SOLO Administradores (y lectura del servicio)
│   ├── application-postgresql.yml   claves de la base (copiada de backend\config\)
│   ├── application-servidor.yml     puerto
│   ├── application-https.yml        clave del certificado (la escribe el instalador)
│   ├── tbp-certificado.p12          certificado + clave privada
│   └── copias.conf                  destino externo, días de retención
├── certificado\TestBenchPlanner.cer  parte pública del certificado: la que se reparte
├── copias\                       copias de la base (tbp_AAAAMMDD_HHMMSS_motivo.dump), SOLO Administradores
├── logs\                         testbenchplanner.log, logs del servicio, scripts.log
├── herramientas\                 copia-seguridad, restaurar-copia, comprobar-servidor, crear-admin
└── datos\  temp\                 de trabajo del servicio
```

Lo que hay en `config\` y `copias\` no se reparte nunca: contiene las claves de la
base, la clave privada del certificado y (en las copias) los hashes de las
contraseñas.

## 2. Antes de instalar (una vez, en el PC servidor)

Todo como **Administrador**. Es lo mismo que se hizo en el PC de pruebas para S1–S3
(`docs/10`, `docs/11`, `docs/12`), ahora en el servidor:

1. **PostgreSQL 16** instalado (instalador de EDB, con pgAdmin). Servicio
   `postgresql-x64-16` en automático.
2. **Que PostgreSQL escuche solo en este PC** (el plan lo exige: §2). El instalador
   de EDB lo deja escuchando en todas las direcciones. En una consola de Administrador:
   ```powershell
   & "C:\Program Files\PostgreSQL\16\bin\psql.exe" -h 127.0.0.1 -U postgres -c "ALTER SYSTEM SET listen_addresses = 'localhost'"
   Restart-Service postgresql-x64-16
   ```
   `comprobar-servidor` avisa si sigue escuchando en la red.
3. **JDK 17** (Eclipse Temurin u otro). Solo hace falta para compilar; el servicio
   usa su propia copia en `jre\`.
4. **Repositorio** clonado (p. ej. `C:\TestBenchPlannerServer`) y compilado **con la
   interfaz**:
   ```powershell
   cd C:\TestBenchPlannerServer\backend
   .\mvnw.cmd -Pinterfaz clean verify > resultado_mvn.txt 2>&1
   ```
   Tiene que acabar en `BUILD SUCCESS`. Sin acceso a nodejs.org, copia antes un
   `frontend\dist` ya compilado y usa `.\mvnw.cmd clean verify`. El instalador se
   niega a instalar un programa sin interfaz.
5. **Base creada y datos importados**: `scripts\servidor\crear-base-de-datos.cmd`,
   `backend\config\application-postgresql.yml` con las claves, importación de la
   última exportación de SharePoint (`docs/10`, apartado 5).
6. **Primer Admin**: `scripts\servidor\crear-admin.cmd TU_EMAIL` (o, ya instalado,
   `herramientas\crear-admin.cmd`).

## 3. Instalación

1. Doble clic en `scripts\servidor\instalar-servicio.cmd` (pide permisos de
   administrador).
2. Pregunta tres cosas (Enter = valor entre corchetes):
   - **Carpeta de instalación** `[C:\TestBenchPlanner]`.
   - **Puerto** `[5001]` (el definitivo, plan §9.3).
   - **Otra ubicación para las copias** (carpeta de otro disco o unidad de red;
     Enter = ninguna por ahora, se puede poner después en `config\copias.conf`).
3. Hace, por este orden, y dice OK/AVISO/FALLO en cada paso:
   comprueba el programa compilado → crea las carpetas → copia programa y Java →
   consigue WinSW → copia la configuración → genera el certificado → ajusta permisos →
   instala el servicio → regla de firewall → quita la suspensión → programa la copia
   diaria → **arranca y espera a que `/api/v1/salud` diga «ok»** → hace la primera copia.
4. Al final muestra la dirección: `https://NOMBRE-DEL-PC:5001`.

**WinSW** (el programa que convierte TestBenchPlanner en servicio) no está en el
repositorio. El instalador lo descarga de GitHub (versión 2.12.0). Si la red no lo
permite: descarga `WinSW-x64.exe` de
<https://github.com/winsw/winsw/releases/tag/v2.12.0> en otro PC, déjalo en
`scripts\servidor\winsw\WinSW-x64.exe` y repite.

**Se puede repetir** cuando quieras (cambiar el puerto, rehacer permisos, reinstalar
el servicio): conserva configuración, certificado y copias. Para cambiar de carpeta,
desinstala antes.

`arrancar-servidor.cmd` (S2) queda para pruebas sin servicio; se niega a arrancar si
el servicio está en marcha (usarían el mismo puerto).

## 4. HTTPS y el certificado

Con el certificado autofirmado la conexión **va cifrada**, pero los navegadores no lo
conocen y avisan («La conexión no es privada»). Para que no avisen, cada PC tiene
que confiar en él **una vez**:

1. Reparte `C:\TestBenchPlanner\certificado\TestBenchPlanner.cer` (correo, Teams,
   carpeta compartida). Es la parte pública: no es secreta.
2. En cada PC, **sin permisos de administrador**:
   - Doble clic en el fichero → *Instalar certificado…* → *Usuario actual* →
     *Colocar todos los certificados en el siguiente almacén* → *Entidades de
     certificación raíz de confianza* → Aceptar → *Sí* en el aviso de seguridad; o
   - en una consola: `certutil -user -addstore Root TestBenchPlanner.cer`
3. Cierra y abre el navegador. Edge y Chrome usan el almacén de Windows; Firefox
   tiene el suyo (Ajustes › Certificados › Importar).

Si IT puede distribuirlo por directiva de grupo a todos los PCs, mejor: nadie tiene
que hacer nada.

Notas:

- Hay que escribir **`https://`**. Con `http://NOMBRE:5001` el navegador no
  muestra la aplicación (el puerto solo habla HTTPS).
- El certificado lleva el nombre del PC, su nombre completo en el dominio,
  `localhost` y las IP que tenía al instalarlo. Si la IP cambia, quien entre por IP
  verá el aviso (por nombre, no). `comprobar-servidor` lo detecta.
- **Renovar** (caduca a los 5 años, o si cambia el nombre o la IP): borra
  `config\tbp-certificado.p12`, repite la instalación y reparte el `.cer` nuevo.
- **Certificado de la CA interna** (si IT lo facilita, plan §9.1): pon su `.p12`/`.pfx`
  como `config\tbp-certificado.p12`, su clave en `config\application-https.yml`
  (`key-store-password`) y reinicia el servicio. No hay que repartir nada.

## 5. Copias de seguridad

- **Qué**: `pg_dump` del esquema de la aplicación (`tbp`: datos, cuentas, auditoría,
  historial de Flyway y permisos), formato comprimido. Se comprueba con
  `pg_restore --list` (si no se puede leer o le faltan tablas, cuenta como fallo).
- **Cuándo**: cada día a las 02:30 (tarea «TestBenchPlanner - copia diaria»; si el PC
  estaba apagado, al encenderlo), antes de cada actualización, antes de cada
  restauración y cuando quieras con `herramientas\copia-seguridad.cmd`.
- **Dónde**: `copias\` y, si se indica, `DESTINO_EXTERNO` en `config\copias.conf`.
  La copia diaria corre como SYSTEM: en una **unidad de red** quien escribe es el
  **equipo** (`DOMINIO\NOMBRE-DEL-PC$`), que necesita permiso de escritura en esa
  carpeta (pedirlo a IT). Una carpeta de **OneDrive** de una persona no sirve para la
  copia automática (SYSTEM no ve su OneDrive); sí otro disco del servidor.
- **Retención**: `RETENCION_DIAS=30`; nunca se borran las 7 más recientes.
- **Registro**: cada copia (bien o mal) en `logs\scripts.log`. `comprobar-servidor`
  falla si la última tiene más de 26 h.

## 6. Restaurar una copia

`herramientas\restaurar-copia.cmd`:

1. Lista las 20 copias más recientes (de `copias\` y del destino externo) y pide el número.
2. Comprueba que se puede leer y que es de TestBenchPlanner.
3. Pide escribir `RESTAURAR`.
4. Hace una copia del estado actual (`antes-de-restaurar`). Si falla, no sigue.
5. Para el servicio, **aparta** el esquema actual (lo renombra), carga la copia en
   **una sola transacción** y, si va bien, borra el apartado. Si la carga falla,
   deja el esquema actual como estaba.
6. Arranca el servicio y espera a que responda. Si la copia es de una versión
   anterior, Flyway aplica las migraciones que falten; las cuentas y contraseñas
   vuelven a ser las de la copia.

Todas las sesiones abiertas se pierden: todo el mundo vuelve a entrar.

## 7. Actualizar a una versión nueva

1. En el repositorio del servidor: traer los cambios y compilar
   (`.\mvnw.cmd -Pinterfaz clean verify`, `BUILD SUCCESS`).
2. `scripts\servidor\actualizar-servidor.cmd`:
   - si el programa es idéntico al instalado, no hace nada;
   - **copia de seguridad `antes-de-actualizar`** (si falla, no sigue);
   - para el servicio, guarda el programa actual como `app\testbenchplanner.anterior.jar`,
     pone el nuevo y refresca `herramientas\`;
   - arranca (Flyway aplica las migraciones nuevas: plan §4, paso 4) y espera a que responda.
3. Si el nuevo no responde, ofrece **volver al anterior**. Si una migración nueva ya
   cambió la base, el anterior no arranca (Flyway no reconoce la estructura): entonces
   restaura la copia `antes-de-actualizar` (apartado 6) con el anterior ya puesto.

Avisa antes a los usuarios: durante la actualización (1–2 minutos) no se puede entrar
y luego hay que volver a iniciar sesión.

## 8. Logs y salud

| Fichero | Qué |
|---|---|
| `logs\testbenchplanner.log` | Log de la aplicación. Rota cada día o a los 10 MB (comprimido `.gz`), 30 días, máximo 1 GB |
| `logs\TestBenchPlanner.err.log` / `.out.log` | Lo que el programa escribe antes de tener log propio (p. ej. un error al arrancar). 5 × 10 MB |
| `logs\TestBenchPlanner.wrapper.log` | Arranques y paradas del servicio |
| `logs\scripts.log` | Copias, restauraciones, actualizaciones e instalaciones |

`https://NOMBRE-DEL-PC:5001/api/v1/salud` responde sin iniciar sesión:

```json
{"estado":"ok","version":"5.0.0","almacen":"postgresql","modoServidor":true,
 "baseDatos":"ok","arrancadaDesde":"2026-11-18T07:02:11Z","segundosEnMarcha":3600}
```

503 con `"estado":"error"` si la aplicación responde pero no llega a PostgreSQL.

`herramientas\comprobar-servidor.cmd` lo repasa todo y no cambia nada: servicio
(en marcha, automático), salud, PostgreSQL (en marcha y escuchando solo en este PC),
puerto y firewall, redes marcadas como públicas, certificado (caducidad, nombre,
IP), última copia y tarea programada, espacio en disco y errores del log. Termina
con «BIEN» o con el número de fallos.

## 9. Energía y Windows Update

- El instalador quita la suspensión y la hibernación con corriente (`powercfg`).
  La pantalla puede apagarse; eso no afecta.
- Los reinicios de Windows Update: fija **horas activas** (Configuración › Windows
  Update › Opciones avanzadas) que cubran el horario de trabajo, o pide a IT una
  ventana de reinicio fuera de horario. Tras el reinicio el servicio arranca solo
  (con inicio retrasado: 1–2 minutos después de Windows).

## 10. Prueba de cierre del hito

En el PC servidor, después de instalar:

| # | Prueba | Resultado esperado |
|---|---|---|
| 1 | `herramientas\comprobar-servidor.cmd` | «Resultado: BIEN» (los avisos de destino externo o red pública se pueden aceptar de momento) |
| 2 | Desde **otro PC** de la red: `https://NOMBRE-DEL-PC:5001`, sin y con el certificado importado | Sin importar: aviso del navegador. Importado: candado sin aviso. Login y uso normal |
| 3 | `http://NOMBRE-DEL-PC:5001` | No muestra la aplicación |
| 4 | **Reiniciar el servidor y NO iniciar sesión** en él. Esperar 3 minutos y entrar desde otro PC | Funciona sin tocar nada |
| 5 | Administrador de tareas del servidor → terminar `java.exe` de TestBenchPlanner | En unos 10 s vuelve a responder solo |
| 6 | `Restart-Service TestBenchPlanner` (consola de Administrador) | Vuelve a responder en menos de 1 minuto; hay que volver a entrar |
| 7 | **Restauración**: copia manual → cambia algo visible (comentario de un ensayo) → `restaurar-copia` eligiendo esa copia | El cambio ha desaparecido; todos vuelven a entrar; existe la copia `antes-de-restaurar` |
| 8 | Al día siguiente | Hay copia `diaria` en `copias\` (y en el destino externo) y `scripts.log` la anota |
| 9 | Recompilar y `actualizar-servidor.cmd` | Copia `antes-de-actualizar`, servicio de vuelta en marcha (con el mismo programa dice «nada que actualizar») |

Con 1–7 en verde se puede dar S4 por cerrado; la 8 se mira al día siguiente.

## 11. Desinstalar

`scripts\servidor\desinstalar-servicio.cmd` (pide escribir `QUITAR`): quita el
servicio, la regla de firewall y la copia diaria. **No** borra la carpeta de
instalación ni la base: reinstalar lo deja todo como estaba.

## 12. Problemas frecuentes

| Síntoma | Causa y solución |
|---|---|
| El instalador dice que el programa no lleva la interfaz | Compila con `-Pinterfaz` o copia `frontend\dist` antes de compilar |
| No se descarga WinSW | Descárgalo a mano (apartado 3) |
| El servicio no arranca, `TestBenchPlanner.err.log` habla de `keystore` o `password` | Falta o no cuadra `config\application-https.yml`: borra `config\tbp-certificado.p12` y repite la instalación |
| Arranca y se para con un error de Flyway | La base no cuadra con el programa (p. ej. tras volver a una versión anterior): restaura la copia `antes-de-actualizar` |
| Desde otro PC no se llega | `comprobar-servidor`: regla de firewall, red marcada como **Pública** (la regla es solo dominio/privado), puerto. Prueba `Test-NetConnection NOMBRE-DEL-PC -Port 5001` desde el otro PC |
| El navegador avisa aunque se importó el certificado | Se entra por un nombre o IP que no está en el certificado (apartado 4), o se importó en el almacén equivocado (tiene que ser *Entidades de certificación raíz de confianza*) |
| La copia diaria falla en la unidad de red | El equipo no tiene permiso de escritura allí (apartado 5). La copia local sí se hace (código 2) |
| `Acceso denegado` al abrir `config\` o `copias\` | Es a propósito: solo Administradores (abre el Explorador como administrador) |

## 13. Decisiones y límites

- **D51 · LocalService**: el servicio no corre como SYSTEM sino como `LocalService`,
  con lectura de la instalación y escritura solo en `logs\`, `datos\` y `temp\`. Si
  algún día hiciera falta acceso a la red con credenciales (no es el caso: la base
  está en el mismo PC), habría que usar una cuenta de servicio.
- **D52 · Salud sin sesión**: `/api/v1/salud` es la única ruta de la API que no pide
  sesión (además de los avisos de pestaña). Solo dice versión, almacén y si llega a
  la base.
- **D53 · Solo HTTPS en el puerto**: no hay redirección de `http://` a `https://`;
  haría falta abrir un segundo puerto (el plan pide uno solo).
- La copia es **del esquema `tbp`**, no de todo PostgreSQL: los usuarios de base de
  datos (`tbp_owner`, `tbp_app`, `tbp_consulta`) los crea `crear-base-de-datos.cmd` y
  tienen que existir para restaurar en un PostgreSQL nuevo.
- Una restauración en **otro** PC (p. ej. si se rompe el servidor): instalar
  PostgreSQL, `crear-base-de-datos.cmd` con las mismas claves, instalar el servicio
  con la misma configuración y `restaurar-copia` con la copia del destino externo.
- Las sesiones siguen en memoria: reiniciar el servicio obliga a volver a entrar.
- El servicio **depende** de PostgreSQL: para reiniciar PostgreSQL a mano hay que
  parar también TestBenchPlanner (`Restart-Service postgresql-x64-16 -Force` y
  después `Start-Service TestBenchPlanner`). Si PostgreSQL se cae solo, la
  aplicación sigue en marcha, la salud da 503 y vuelve a funcionar cuando PostgreSQL vuelve.
- Sin probar aquí: no hay Windows ni PostgreSQL en el entorno donde se escribió. Los
  scripts están pensados para PowerShell 5.1 (el de Windows 10/11 y Server).
