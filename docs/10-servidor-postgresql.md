# 10 · Servidor local con PostgreSQL — hito S1 (base de datos)

> Plan de referencia: `TestBenchPlanner_plan_servidor_PostgreSQL.md` (§8, hito **S1**).
> Estado (17-nov-2026): **validado en el PC corporativo de Pedro.** `mvnw clean verify`
> en verde (299 pruebas, con el PostgreSQL temporal) y prueba completa con datos
> reales: exportación desde SharePoint, importación simulada y real, interfaz nueva,
> cambios que se conservan al reiniciar, conflicto entre dos pestañas y bloqueo de
> equipo. Falta repetirlo en el PC servidor. Lo aprendido en esa prueba está en 5.5.

## 1. Qué incluye S1

| Pieza | Dónde | Qué hace |
|---|---|---|
| Adaptador PostgreSQL | `infraestructura/postgresql/` | Tercer valor de `planner.almacen` (`postgresql`). Implementa los mismos puertos que `local` y `sharepoint`; dominio, API y React **no cambian**. |
| Estructura | `src/main/resources/db/migration/V1__esquema_inicial.sql` | Tablas, índices y secuencias. Solo por migraciones Flyway. |
| Instalación | `src/main/resources/db/instalacion/01-…sql`, `02-…sql` y `scripts/servidor/crear-base-de-datos.cmd` | Usuarios `tbp_owner`, `tbp_app`, `tbp_consulta`, base `tbp`, esquema `tbp` y permisos. |
| Flyway al arrancar | `MigracionesBaseDatos` | Con `tbp_owner` aplica lo pendiente y valida; si la base no cuadra, **la aplicación no arranca**. |
| Exportador | `infraestructura/migracion/ExportadorDatos` + `scripts/servidor/exportar-desde-sharepoint.cmd` | PC con permisos: lee SharePoint **en solo lectura** con los adaptadores de siempre y escribe una carpeta. |
| Importador | `infraestructura/postgresql/ImportadorDatos` + `scripts/servidor/importar-en-postgresql.cmd` | Servidor: carga la carpeta en PostgreSQL. Idempotente, en una transacción, con verificación de paridad y modo simulación. |
| Pruebas | `src/test/java/.../postgresql/` y `integracion/*PostgresTest` | Las mismas pruebas que el almacén local, ahora también contra PostgreSQL real. |

No incluye (hitos siguientes): perfil `servidor`, escuchar en la red y festivos
globales (S2); inicio de sesión y cuentas (S3); servicio de Windows, HTTPS,
copias y logs (S4); piloto y corte (S5).

## 2. Diseño de datos

**Modelo híbrido** en los ensayos: columnas para filtrar/ordenar/cruzar y el
`DataJson` íntegro en `data_json` (**texto**, no `jsonb`: `jsonb` reordena
claves y la aplicación compara el texto exacto para detectar conflictos). Las
columnas relacionales se recalculan del `DataJson` en cada guardado y nunca se
usan para planificar: manda el `DataJson`.

| Tabla | Lista de SharePoint | Clave | Notas |
|---|---|---|---|
| `ensayo` | TestBenchPlanner | `uid` | `data_json`, `version` (concurrencia), `orden` (como el Id de SharePoint), `banco`, `organizacion`, `tipo`, `responsable`, `proyecto`, `cerrado`, `approval_id`, `disponible`, `inicio_real`, `fin_real`, `fecha_cierre`, `creado_en`, `actualizado_en`, `actualizado_por` |
| `auditoria` | — (nueva) | `id` | Antes/después de **cada** cambio: quién, cuándo, entidad, clave, operación (ALTA, MODIFICACION, BAJA, IMPORTACION). Un ensayo borrado se recupera de aquí. |
| `registro_actividad` | TestBenchActivityLog | `id` | El «Activity log» visible. `huella` evita duplicar filas al repetir la importación. |
| `responsable`, `proyecto`, `banco` | TestBenchResponsibles / Projects / Benches | nombre / título | `seq` como Seq; `proyecto.orden_facturacion` (BillingOrder); `banco.tipo_equipo` (EquipmentType) y `banco.area` (Area, vacío = Thermal). |
| `nombre_area`, `configuracion_app` | TestBenchAppConfig | código / clave | `AreaName:<código>` y `CurrentVersion`. Con áreas gestionadas desde la aplicación (D56), además la clave `Areas` (lista JSON de áreas) y los nombres de las áreas nuevas. Desde V4, `FacturacionHorasDesde` (primer mes de facturación por horas, D60). |
| `usuario` | TestBenchUsers | `email` | Rol Admin / Editor / LabUser (D55) / ReadOnly (texto libre: no hace falta migración). En S3 se añadirán aquí las credenciales (migración V2). |
| `recurso`, `bloqueo_recurso` | TestBenchResources / ResourceBlocks | nombre / id | Los bloqueos conservan su Id de SharePoint. |
| `kpi_semana` | TestBenchKPIHistory | `id` | `anio`, `cw`, `org` + la fila completa en `fila_json`. |
| `aprobacion` | Services approvals database V2 (otro sitio) | Id | Pasa a tabla propia (pendiente de confirmar su gestión, §9.2 del plan). |
| `tipo_equipo` | TestBenchEquipmentTypes | título+año | Facturación. |
| `presencia` | TestBenchPresence | email | Latidos de los últimos 75 s. |
| `horario_ensayo` | — (nueva, V4) | `ensayo_id` | Working Hours de cada ensayo (D59): jornada, horas de inicio y fin, fines de semana, setup, paradas (`jsonb`), nota, `version` y quién/cuándo. Sin clave foránea a `ensayo`: reimportar no la borra. Ver `docs/15-horario-de-ensayos.md`. |

**Campos nuevos**: pueden empezar dentro del `DataJson` sin migración; cuando
haga falta filtrar por ellos, migración nueva con la columna y una línea en
`ColumnasEnsayo`.

### Concurrencia

Cada guardado de ensayos es **una transacción**: se bloquean las filas
afectadas (`SELECT … FOR UPDATE`, en orden de `uid`), se comprueba que su
`DataJson` sigue siendo el que vio el usuario y, si no, se devuelve el mismo
409 de siempre («Otra persona ha cambiado este ensayo») **sin escribir nada**.
Cada escritura suma 1 a `version`. Dos altas del mismo id a la vez también se
detectan. Las listas que se guardan por reconciliación (bancos, proyectos,
usuarios, recursos, KPI, tipos de equipo) se serializan con un cerrojo por
lista. Comportamiento visible: el mismo que con SharePoint.

## 3. Usuarios de base de datos

| Usuario | Uso | Permisos |
|---|---|---|
| `tbp_owner` | Flyway al arrancar | Dueño de la base y del esquema (DDL). |
| `tbp_app` | Aplicación | `SELECT, INSERT, UPDATE, DELETE` en tablas; secuencias. **Sin DDL.** |
| `tbp_consulta` | pgAdmin / DBeaver | Solo `SELECT`; además sus sesiones son de solo lectura. |

Los permisos de `tbp_app` y `tbp_consulta` se conceden **solos** a cada tabla
que cree Flyway (`ALTER DEFAULT PRIVILEGES`), así que las migraciones no llevan
`GRANT`. Escribir desde pgAdmin queda para un administrador y emergencias: se
salta validaciones, `version` y auditoría.

## 4. Cambios de estructura (Flyway)

1. Prototipar en un PostgreSQL de desarrollo (pgAdmin) y quedarse con el SQL.
2. Guardarlo como `backend/src/main/resources/db/migration/V<n>__<descripcion>.sql`
   (V2, V3…). **Nunca** editar una migración ya aplicada. Aplicadas: V1 estructura
   inicial, V2 calendario común, V3 cuentas, V4 horario de los ensayos y
   `FacturacionHorasDesde` (D59, D60).
3. Adaptar el código y probar (`mvnw clean verify` crea bases nuevas con todas las migraciones).
4. Desplegar: copia de seguridad → arrancar → Flyway aplica lo pendiente y lo
   anota en `tbp.flyway_schema_history`.
5. Al arrancar se valida siempre: si la estructura no cuadra (migración
   retocada, falta alguna con `migrar-al-arrancar=false`…), la aplicación **no
   arranca** y lo dice en el log.

## 5. Instalación y migración de datos

### 5.1 PostgreSQL (una vez, en el PC servidor)

1. Instalar PostgreSQL 16 (instalador oficial para Windows; incluye pgAdmin).
   En `postgresql.conf` dejar `listen_addresses = 'localhost'` (valor por defecto).
2. `scripts\servidor\crear-base-de-datos.cmd`: pide la clave del superusuario
   `postgres` y las tres claves nuevas, y crea usuarios, base y esquema.
3. Crear `backend\config\application-postgresql.yml` (no se versiona) a partir de
   `scripts\servidor\application-postgresql.yml.ejemplo` con las claves de
   `tbp_app` y `tbp_owner`.
4. Arrancar una vez con el perfil `postgresql`: Flyway crea las tablas.
   ```powershell
   cd backend
   .\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=postgresql"
   ```
   En **PowerShell** hacen falta `.\` y las comillas (sin ellas corta el parámetro por
   el punto: «Unknown lifecycle phase ".run.profiles=postgresql"»). En `cmd` vale
   `mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=postgresql`.

### 5.2 Exportar (PC con permisos de SharePoint)

Antes, en ese PC, tiene que existir `backend\config\application-sharepoint.yml`
(el de siempre, con el sitio). En un clon nuevo del repositorio no viene: esa
carpeta no se versiona. Se copia del TestBenchPlanner de siempre o se crea como en
`04-ejecucion-y-pruebas.md`. Desde `backend`, en PowerShell:
`..\scripts\servidor\exportar-desde-sharepoint.cmd C:\tbp-exportacion-AAAA-MM-DD`.

```bat
scripts\servidor\exportar-desde-sharepoint.cmd D:\tbp-exportacion-2026-11-16
```

- Usa `backend\config\application-sharepoint.yml` (el de siempre) y arranca en
  un puerto libre sin abrir la interfaz. Mejor con TestBenchPlanner cerrado:
  usa el mismo Edge (puerto 9222) para leer SharePoint.
- **Solo lectura**: si `escritura-habilitada` está a `true`, se niega a exportar.
- Si alguna lista no se puede leer, llega vacía cuando no puede estarlo o se ha
  servido desde la copia sin conexión, **se para** y no escribe el manifiesto.
- Resultado: `planner.json`, `configuracion.json`, `usuarios.json`,
  `recursos.json`, `kpis.json`, `aprobaciones.json`, `tipos-equipo.json`,
  `version.json`, `actividad.json` (últimas 5000 filas) y `exportacion.json`
  (fecha, origen, recuentos y huella SHA-256 de cada fichero).
- La carpeta tiene **datos reales sin anonimizar**: no subirla al repositorio
  (`datos/privado/` está excluida de Git).
- Es el formato de un escenario del modo local: se puede abrir con
  `--planner.local.escenario-inicial=<carpeta>` para ver exactamente lo exportado.

### 5.3 Importar (servidor)

```bat
scripts\servidor\importar-en-postgresql.cmd D:\tbp-exportacion-2026-11-16 simular
scripts\servidor\importar-en-postgresql.cmd D:\tbp-exportacion-2026-11-16
```

- Comprueba las huellas del manifiesto (un fichero retocado o copiado a medias no se importa).
- Deja la base **exactamente** como la exportación (altas, cambios y bajas) en
  una sola transacción; el registro de actividad solo se añade. Repetir la misma
  importación no cambia nada; una exportación más reciente aplica solo las
  diferencias. Cada cambio queda en `auditoria` con operación IMPORTACION.
- **Verificación de paridad** antes de confirmar (ver 6). Si algo no cuadra se
  deshace todo y el informe dice qué (resultado RECHAZADO, código de salida 1).
- `simular`: hace todo, verifica y deshace (resultado SIMULADO).
- Durante el piloto (S5) se puede repetir exportar/importar las veces que haga
  falta. Ojo: importar **sustituye** lo que se haya cambiado en la base desde la
  importación anterior (la verdad sigue siendo SharePoint hasta el corte). Si se
  ha trabajado en los dos lados, usar la fusión de tres vías (5.4).

### 5.4 Importar con fusión de tres vías (se ha trabajado en los dos lados)

Si después de cargar la base se ha seguido editando en SharePoint **y** en el
servidor, la importación de 5.3 perdería lo hecho en el servidor. La fusión de
tres vías compara, campo a campo:

- **BASE**: la exportación con la que se cargó la base (estado común; p. ej. `D:\tbp-exportacion-2026-11-16`).
- **SHAREPOINT**: una exportación nueva hecha con 5.2.
- **POSTGRESQL**: el estado actual de la base (se exporta solo, al empezar).

```bat
rem 1) Simular (por defecto): no guarda nada
scripts\servidor\importar-en-postgresql.cmd D:\tbp-exportacion-2026-11-16 D:\tbp-exportacion-2026-11-23
rem 2) Si hay conflictos: rellenar «elegir» en conflictos.json y volver a simular con él
scripts\servidor\importar-en-postgresql.cmd D:\tbp-exportacion-2026-11-16 D:\tbp-exportacion-2026-11-23 simular D:\reconciliacion-…\conflictos.json
rem 3) Aplicar (servicio parado y copia de seguridad hecha)
scripts\servidor\importar-en-postgresql.cmd D:\tbp-exportacion-2026-11-16 D:\tbp-exportacion-2026-11-23 aplicar D:\reconciliacion-…\conflictos.json
```

| Caso | Resultado |
|---|---|
| Campo cambiado solo en un lado | Se toma ese cambio. |
| Mismo ensayo, campos distintos cambiados en cada lado | Se combinan (el DataJson se fusiona por campos). |
| Mismo campo cambiado distinto en los dos lados | **Conflicto**: no se importa nada hasta resolverlo (`local` o `sharepoint`). |
| Alta en un lado | Se añade. |
| Borrado en el servidor, sin cambios en SharePoint | Sigue borrado. |
| Borrado en SharePoint | **Se conserva** en la base salvo `"elegir": "borrar"`. |
| Borrado en un lado y modificado en el otro | Se conserva la versión modificada salvo `"borrar"`. |
| Registro de actividad | Solo se añaden filas. |
| Fichero que no está en la base (p. ej. `tipos-equipo.json`) | Manda PostgreSQL y se añade lo nuevo de SharePoint. |

Listas con identidad: ensayos por `uid`, usuarios por `email`, bancos y equipos
por nombre, responsables y proyectos por texto, KPI por `Year|CW|Org`,
aprobaciones y bloqueos por `id`, tipos de equipo por título + año.

Carpeta de trabajo (junto a la de SharePoint, `reconciliacion-AAAAMMDD-HHMMSS`):
`postgresql-antes` (copia JSON de la base antes de importar), `fusion` (lo que
se importa, con manifiesto), `conflictos.json` (todas las decisiones; en los
ensayos incluye banco · tipo · proyecto) e `informe.json`. `"porDefecto":
"local"` o `"sharepoint"` resuelve de golpe los conflictos que queden vacíos.

La carpeta `fusion` se importa con el importador de 5.3 (una transacción,
verificación de paridad, auditoría IMPORTACION). Códigos de salida: 0 bien,
1 error o rechazada, 3 conflictos sin resolver.

### 5.5 Lo aprendido en la primera prueba real (17-nov-2026)

| Situación | Qué pasa | Qué hacer |
|---|---|---|
| `mvnw.cmd …` en PowerShell | «no se encontró el comando» o «Unknown lifecycle phase» | `.\mvnw.cmd` y el `-D…` entre comillas (ver 5.1). |
| Clon nuevo del repositorio | La raíz abre la versión clásica | Falta `frontend\dist` (no se versiona): copiarla del TestBenchPlanner de siempre o `npm run build` en `frontend`, y volver a compilar/arrancar. |
| Exportar en un clon nuevo | «Falta backend\config\application-sharepoint.yml» | Ver 5.2. |
| Exportar: «no se han podido leer … sus bloqueos» | La lista `TestBenchResourceBlocks` no se leía | Corregido (D47): `Author` es la columna de persona de SharePoint (Creado por) y ahora se pide con `$expand`. |
| `resultado_mvn.txt` desde PowerShell | Sale en UTF-16 | Normal; se lee igual. |

Resultado de la exportación real: 294 ensayos, 10 responsables, 182 proyectos,
27 bancos, 18 usuarios, 7 equipos, 0 bloqueos, 48 semanas de KPI, 3356
aprobaciones, 14 tipos de equipo y 4142 filas de actividad, sin avisos.

## 6. Paridad SharePoint ↔ base de datos

Tres niveles:

1. **En cada importación** (`VerificacionParidad`): lo que devuelven los
   adaptadores de PostgreSQL tiene que ser igual a lo que devuelve el modo local
   leyendo la carpeta exportada (= lo que había en SharePoint). Se comparan
   ensayos (uid, orden y texto exacto), catálogos, usuarios, recursos y bloqueos,
   KPI, aprobaciones, tipos de equipo, versión y registro de actividad. Todo lo
   que hay por encima de los almacenes es el mismo código en los tres modos.
2. **`ParidadApiPostgresTest`**: arranca la aplicación dos veces (local y
   PostgreSQL) sobre el escenario anonimizado con la forma de una exportación y
   compara 23 respuestas de la API (planificación, catálogos, KPI, ocupación,
   facturación, copia JSON, API clásica…), antes y después de la misma serie de
   escrituras (edición con versión y conflicto, alta, catálogos, bloqueo, foto
   semanal, usuarios).
3. **Mismas pruebas de integración que el almacén local**: ver 7.

Formas canónicas de la comparación (lo que **no** cuenta como diferencia):
números 1 = 1.0; filas con clave repetida (la base no las admite: el importador
avisa y se queda con la primera); área vacía o «Thermal» = Thermal Benches;
título de usuario vacío = su email; Seq ausente de un equipo = su posición.

## 7. Pruebas

`mvnw.cmd clean verify` arranca un **PostgreSQL temporal** (binarios
`io.zonky.test`, ~10 MB, se descargan de Maven Central la primera vez; no hace
falta instalar nada ni Docker). Para usar un PostgreSQL instalado de desarrollo:

```bat
mvnw.cmd clean verify -Dtbp.pruebas.postgresql.url=jdbc:postgresql://127.0.0.1:5432/postgres -Dtbp.pruebas.postgresql.clave=<clave de postgres>
```

| Prueba | Qué comprueba |
|---|---|
| `ContratoAlmacenesLocalTest` / `ContratoAlmacenesPostgresTest` | **Las mismas 15 pruebas** contra los dos almacenes: orden y texto exacto, conflicto, nada a medias, altas repetidas, último gana, catálogos (columnas de facturación y área, `/clasico` no las borra), D11, usuarios, recursos y bloqueos, foto KPI, tipos de equipo, aprobaciones, actividad, versión, presencia. |
| `ApiPostgresTest`, `AreasPostgresTest`, `ConfiguracionBancosPostgresTest`, `CopiaSeguridadPostgresTest`, `OperacionesInmediatasPostgresTest`, `SoloLecturaPostgresTest`, `ReinicioPostgresTest` | Las pruebas de integración del almacén local (heredan sus casos) con `planner.almacen=postgresql`. |
| `ListasProtegidasPostgresTest` | D11 de punta a punta mirando las tablas (equivale a `ListasProtegidasTest`, que mira ficheros). |
| `ImportacionPostgresTest` | Exportar → importar: manifiesto con huellas, verificación, idempotencia (2.ª importación = 0 cambios y mismas versiones), deshacer cambios hechos en la base, simulación, fichero retocado, ficheros obligatorios, carpeta no vacía, columnas relacionales. |
| `FusionTresViasTest` | La fusión sin base de datos: campos combinados, conflicto, texto DataJson conservado byte a byte, altas, los tres tipos de borrado, catálogos, KPI, actividad, fichero sin base, resoluciones y `porDefecto`. |
| `ReconciliacionTresViasPostgresTest` | De punta a punta en PostgreSQL: cambios en los dos lados se conservan, conflicto = no se toca la base, resolución aplicada, simulación. |
| `EstructuraYPermisosPostgresTest` | Flyway registra V1 y V2 (calendario común, S2); `tbp_app` no puede crear/alterar/borrar tablas; `tbp_consulta` solo lee; validar con `tbp_app`; migración retocada o esquema ausente = no arranca. |
| `ConcurrenciaPostgresTest` | 5 personas guardan a la vez el mismo ensayo: gana 1, 4 conflictos, `version` +1. 4 altas simultáneas del mismo id: 1 fila. |
| `ParidadApiPostgresTest` | Paridad de punta a punta (ver 6). |

## 8. Configuración

| Propiedad | Por defecto | Uso |
|---|---|---|
| `planner.almacen` | `local` | `postgresql` para la base de datos. |
| `planner.postgresql.url` | `jdbc:postgresql://127.0.0.1:5432/tbp` | Servidor y base. |
| `planner.postgresql.esquema` | `tbp` | Esquema. |
| `planner.postgresql.usuario-app` / `clave-app` | `tbp_app` / vacío | Conexión del día a día. |
| `planner.postgresql.usuario-propietario` / `clave-propietario` | `tbp_owner` / vacío | Solo Flyway. |
| `planner.postgresql.migrar-al-arrancar` | `true` | `false`: solo valida (con `tbp_app`) y no arranca si falta algo. |
| `planner.postgresql.conexiones-maximas` | `10` | Tamaño del grupo de conexiones. |
| `planner.postgresql.usuario-email` / `usuario-nombre` | `planner.local.usuario-email` | **Provisional hasta S3**: identidad de quien usa la aplicación. |
| `planner.postgresql.reinicio-pruebas` | `false` | Solo pruebas: `/api/v1/local/reinicio` vacía la base y carga un escenario. |
| `planner.migracion.exportar` / `importar` | vacío | Carpeta de la exportación (los scripts lo rellenan). |
| `planner.migracion.simular` | `false` | Importar sin guardar. |
| `planner.migracion.max-actividad` | `5000` | Filas del registro de actividad que se exportan. |
| `planner.migracion.base` | vacío | Fusión de tres vías: exportación con la que se cargó la base (ver 5.4). |
| `planner.migracion.resoluciones` | vacío | Fusión de tres vías: `conflictos.json` editado. |
| `planner.migracion.salida` | vacío | Fusión de tres vías: carpeta de trabajo (vacío = junto a la de SharePoint). |

Las claves pueden ir también en variables de entorno: `PLANNER_POSTGRESQL_CLAVEAPP`,
`PLANNER_POSTGRESQL_CLAVEPROPIETARIO`.

## 9. Diferencias con SharePoint (registradas en 02-discrepancias)

- **D41** Usuarios por email (SharePoint reconciliaba por `Title`).
- **D42** Presencia sin el desfase de una hora en verano (D9 ya no aplica con la base).
- **D43** Guardados atómicos: una operación de varias filas se guarda entera o no se guarda.
- **D44** Borrar un ensayo lo elimina de `ensayo` y deja la copia en `auditoria` (SharePoint lo mandaba a la papelera).
- **D45** Bloqueo de equipo con un id que no existe: no se crea otro (como SharePoint; el modo local sí lo creaba).
- **D46** (S2) Calendario de festivos común en la base de datos (tablas `calendario` y `festivo`, migración V2); solo lo cambia un Admin.
- **D47** Lectura de bloqueos de equipos en SharePoint: la columna `Author` es la de persona de SharePoint; ahora se lee bien (la 4.9.4 fallaba sin avisar).

## 10. Pendiente

- Repetir en el PC servidor (Pedro tiene permisos de administrador): instalación
  (5.1), copiar allí una exportación (5.2), `importar … simular` y la importación (5.3).
- Puntos abiertos del plan (§9): **sin nombre DNS por ahora** (se entra por el
  nombre del PC o su IP; el certificado de S4 será autofirmado), gestión de
  aprobaciones desde la app, puerto definitivo y número de usuarios que escriben a la vez.
- Siguiente hito: **S2 · Modo servidor**, en curso: `11-modo-servidor.md`.
