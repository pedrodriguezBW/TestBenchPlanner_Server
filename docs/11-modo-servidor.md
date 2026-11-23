# 11 · Modo servidor — hito S2

> Plan de referencia: `TestBenchPlanner_plan_servidor_PostgreSQL.md` (§5.1 y §8, hito **S2**).
> Estado (17-nov-2026): **probado en el PC corporativo** (`clean verify` en verde,
> arranque en modo servidor, `0.0.0.0:5001` a la escucha). **Falta** el acceso desde
> otro PC: el portátil no deja crear la regla del cortafuegos sin permisos de
> administrador. Se comprueba en el PC servidor (con permisos) al repetir S1–S3.
> Requisito del plan: H3F cerrado (validado el 10-nov-2026).

**Desde el hito S3 cada persona entra con su cuenta** (email + contraseña de la
aplicación): ver `docs/12-inicio-de-sesion.md`. Arrancado a mano con
`arrancar-servidor.cmd` va **sin HTTPS: solo para pruebas**. El servidor definitivo
se instala como servicio con HTTPS (hito S4): `docs/13-despliegue-servidor.md`.

## 1. Qué cambia

| Tema | En el PC de cada usuario (como hasta ahora) | En modo servidor |
|---|---|---|
| Dónde escucha | `127.0.0.1:5001` (solo ese PC) | Todas las interfaces de red, puerto `5001` (configurables) |
| Almacén | `local`, `sharepoint` o `postgresql` | **Solo `postgresql`**; con otro valor no arranca y dice por qué |
| Cerrar la última pestaña | Apaga el backend | **No apaga nada**: la aplicación es de todos |
| `/clasico` | Disponible (respaldo) | **No disponible** (404): «el último que guarda gana» (D12) no es seguro con varios usuarios |
| API antigua `/api/*` | Disponible | **No disponible** (404), salvo `/api/heartbeat` y `/api/shutdown`, que la interfaz nueva sigue enviando (no apagan nada) |
| Botón «Versión clásica» | Visible | Oculto |
| 📤 Export read-only | Parte de `/clasico` | Parte de `/api/v1/exportaciones/plantilla-solo-lectura` (el mismo HTML; también fuera del servidor) |
| Calendario de festivos | De cada PC (D6), con `local`/`sharepoint` | **Común** en la base de datos; solo lo cambia un Admin (D46). También con `postgresql` fuera del modo servidor |
| Tools › Reset | «Reset (local)»: calendario de este PC + preferencias de pantalla | «Reset (this browser)»: solo las preferencias de pantalla de ese navegador; no toca nada compartido |
| Reinicio de pruebas `/api/v1/local/reinicio` | Solo `local` o pruebas | Imposible: con `reinicio-pruebas=true` no arranca |
| SharePoint (Edge/Playwright) | Con `sharepoint` | Nunca se crea |
| Sin `frontend/dist` | `/` abre la versión clásica | `/` muestra «Falta la interfaz» (503) |

Al arrancar, el log dice desde qué direcciones se entra (nombre del PC e IPs) y
avisa si ningún Admin tiene contraseña todavía (S3):

```text
WARN … MODO SERVIDOR · se entra desde otros PCs con: http://NOMBRE-PC:5001  http://10.x.x.x:5001
WARN … MODO SERVIDOR · ningún Admin tiene contraseña todavía: … crear-admin.cmd TU_EMAIL
```

Lecciones de la prueba en el PC corporativo (17-nov-2026):

- Con doble clic, `arrancar-servidor.cmd` no encontraba Java (VS Code lo añade solo
  a su terminal). El script lo busca ahora en `JAVA_HOME`, el `PATH`, las carpetas
  habituales y el Java de VS Code, dice cuál usa y no cierra la ventana si falla.
  En el servidor conviene definir `JAVA_HOME` en el sistema (S4 lo necesitará).
- En modo servidor no se abre ningún navegador: se entra con la dirección que dice
  la ventana (`http://NOMBRE-PC:5001` o `http://localhost:5001`).
- `abrir-puerto-firewall.cmd` necesita administrador; ahora pide permiso él mismo
  y no cierra la ventana. En un portátil corporativo sin permisos no se puede crear
  la regla (`Acceso denegado`).

## 2. Cómo se activa

Perfil `servidor` (`src/main/resources/application-servidor.yml`), junto al perfil
`postgresql` que ya tiene las claves de la base:

```text
--spring.profiles.active=postgresql,servidor
```

El perfil fija `server.address: 0.0.0.0`, `server.port: 5001`,
`planner.almacen: postgresql` y `planner.servidor.activo: true`. Para cambiar
puerto o dirección: `backend\config\application-servidor.yml` (no se versiona) a
partir de `scripts\servidor\application-servidor.yml.ejemplo`.

## 3. Puesta en marcha en el PC servidor

Sin nombre DNS (decisión de IT, 17-nov-2026): se entra por el **nombre del PC**
(en un dominio, el DNS interno normalmente ya lo conoce) o por su **IP**. Para
que la IP no cambie, conviene pedir a IT una IP fija o una reserva DHCP.

1. Base de datos y datos: `10-servidor-postgresql.md`, apartado 5 (instalar
   PostgreSQL 16, `crear-base-de-datos.cmd`, `application-postgresql.yml`,
   importar una exportación).
2. Compilar con la interfaz: que exista `frontend\dist` (copiada o con
   `npm run build`) **antes** de `mvnw.cmd clean verify`, para que vaya dentro del `.jar`.
3. Abrir el puerto, **como administrador** (solo redes de dominio y privadas):
   ```bat
   scripts\servidor\abrir-puerto-firewall.cmd
   ```
4. Arrancar:
   ```bat
   scripts\servidor\arrancar-servidor.cmd
   ```
   Hasta S4 (servicio de Windows) la aplicación vive mientras esa ventana esté
   abierta.
5. Desde otro PC: `http://NOMBRE-DEL-SERVIDOR:5001`.

Todavía por HTTP: HTTPS (certificado autofirmado, sin DNS) llega en S4.

## 4. Prueba de cierre del hito

1. `mvnw.cmd clean verify` en verde.
2. En el servidor, `arrancar-servidor.cmd`: el log muestra las direcciones.
3. Desde **otro PC**: abrir la dirección, ver la planificación, editar un ensayo.
4. Cerrar todas las pestañas en los dos PCs: la aplicación sigue en marcha
   (vuelve a abrir la dirección y responde).
5. `http://…:5001/clasico` → «La versión clásica no está disponible en el servidor».
6. Configuration › Tools › Calendar: título «shared · Vigo»; añadir un festivo
   como Admin y comprobar que lo ve otro PC. (Con un Editor, los controles salen
   desactivados y la API responde 403.)
7. 📤 Export read-only sigue generando el HTML.

## 5. Calendario común (D46)

- Migración **V2** (`V2__calendario_global.sql`): tabla `calendario` (una fila:
  año y «saltar festivos», con quién y cuándo se cambió) y tabla `festivo`.
  Primera demostración del flujo del plan (§4): la base existente pasa de v1 a v2
  sola al arrancar con `migrar-al-arrancar: true`.
- Si la tabla está vacía, la primera lectura guarda el calendario de un PC nuevo
  (festivos de Vigo del año en curso). **Los festivos que alguien hubiese añadido a
  mano en su PC no se migran**: un Admin los vuelve a añadir una vez.
- Cada cambio queda en `auditoria` (entidad `calendario`, antes y después).
- `PUT /api/v1/calendario` y `POST /api/v1/calendario/anio` exigen Admin con el
  calendario común (403 si no); `GET` lleva `compartido: true`.
- Importar una copia JSON (Tools › Import, solo Admin) sustituye también el calendario común.

## 6. Pruebas

| Prueba | Qué comprueba |
|---|---|
| `ModoServidorPostgresTest` | Sesión con `modoServidor`; `/clasico` y API antigua 404; latido y cierre de pestaña siguen respondiendo; plantilla de Export read-only; sin reinicio de pruebas; calendario común (Admin lo cambia, Editor lo ve y recibe 403, auditoría) y el plan usa ese calendario. |
| `ComprobacionModoServidorTest` | No arranca con `sharepoint`/`local` ni con `reinicio-pruebas`, y lo dice. |
| `FiltroModoServidorTest` | Qué rutas se cierran y cuáles no. |
| `CicloDeVidaTest` | + en modo servidor cerrar todas las pestañas no apaga. |
| `EstructuraYPermisosPostgresTest` | + V2 aplicada, `tbp_app` escribe en `festivo`, una sola fila en `calendario`, `tbp_consulta` solo lee. |

## 7. Ficheros

- Backend: `configuracion/ComprobacionModoServidor` (+ `META-INF/spring.factories`),
  `configuracion/AvisoModoServidor`, `api/comun/FiltroModoServidor`,
  `infraestructura/postgresql/CalendarioPostgres`, `application-servidor.yml`,
  `db/migration/V2__calendario_global.sql`; cambios en `PropiedadesPlanner`,
  `ConfiguracionAlmacenes`, `CicloDeVida`, `ConfiguracionController`,
  `PlanificacionController`, `InterfazClasicaController`, `AlmacenCalendario`,
  `ImportadorDatos` (el reinicio de pruebas vacía también el calendario).
- Interfaz: `api/tipos.ts`, `App.tsx`, `bloques/configuracion/ConfigHerramientas.tsx`,
  `bloques/exportaciones/descargas.ts`.
- Scripts: `scripts/servidor/arrancar-servidor.cmd`, `abrir-puerto-firewall.cmd`,
  `application-servidor.yml.ejemplo`.

## 8. Pendiente

- Acceso desde otro PC (pasos 3-4 del apartado 4): en el PC servidor.
- S3 · Inicio de sesión: probado en el PC corporativo (`docs/12-inicio-de-sesion.md`).
- S4 · Servicio de Windows, HTTPS autofirmado, copias, logs y salud: código
  terminado (`docs/13-despliegue-servidor.md`). Con el servicio instalado, la regla
  de firewall la crea `instalar-servicio.cmd` (no hace falta `abrir-puerto-firewall.cmd`).
