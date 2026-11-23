# 12 · Inicio de sesión — hito S3

> Plan de referencia: `TestBenchPlanner_plan_servidor_PostgreSQL.md` (§5.2 y §8, hito **S3**).
> Estado (17-nov-2026): **código terminado; pendiente de `mvnw clean verify` en el PC**
> y de la prueba del apartado 6.
> S2 se da por bueno en el PC corporativo salvo el acceso desde otro PC, que no se
> pudo probar (el portátil no permite abrir el puerto sin permisos de administrador).
> Esa comprobación se hace en el PC servidor al repetir S1–S3.

En el **modo servidor** cada persona entra con su **email** y una **contraseña de
TestBenchPlanner** (no la de Windows). Fuera del modo servidor (cada PC con su
backend: local, SharePoint o PostgreSQL de pruebas) **no cambia nada**: no hay
formulario y la identidad es la de siempre.

## 1. Qué hay

| Tema | Cómo funciona |
|---|---|
| Entrar | Formulario en `/login` (email + contraseña). Sin sesión, cualquier página lleva ahí y la API responde 401. |
| Quién puede entrar | Solo las personas de **Users/Rights Management** que tengan contraseña. Quien no esté en la lista no entra (antes entraba como Read only). |
| Rol | El de siempre (Admin / Editor / Read only) o **Lab User** (un Editor que además gestiona las áreas, D55, configura los bancos, D58, y el horario de los ensayos, D59; solo con PostgreSQL), leído de la lista **en cada petición**: un cambio de rol se aplica en el acto, sin volver a entrar. |
| Contraseña | Hash **BCrypt** (coste 12) en la tabla `credencial` (migración V3). Nunca se guarda ni se escribe en el log. Mínimo 10 caracteres, distinta del email, al menos 4 caracteres distintos, máximo 72. |
| Alta y «la he olvidado» | Un Admin pulsa 🔑 en Users/Rights Management: se genera una **contraseña temporal** (`Abcd-efgh-2345`), que se enseña una sola vez. |
| Primer acceso | Con la temporal solo se puede ir a **cambiar la contraseña**; la API responde 403 `cambiar-clave` hasta entonces. |
| Cambiar la propia | Botón **🔑 Password** de la barra superior (pide la actual). Cierra las demás sesiones de esa persona. |
| Bloqueo | **5 intentos fallidos seguidos** → cuenta bloqueada **15 minutos**, aunque luego se acierte. Un Admin puede desbloquear antes (🔓). Los intentos fallidos al cambiar la contraseña también cuentan. Un email sin cuenta recibe exactamente las mismas respuestas (también «bloqueada»), para que el formulario no delate qué emails tienen cuenta. |
| Inactividad | **30 minutos** sin clics, teclas, rueda ni toques → la sesión se cierra y la interfaz vuelve al formulario con el motivo. Los sondeos automáticos (relectura cada 20 s, presencia, aprobaciones…) **no** cuentan (D50). |
| Salir | Botón **Log out** de la barra superior. |
| Quitar a alguien | Quitarlo de Users/Rights Management borra su cuenta y cierra sus sesiones. |
| Contraseña nueva | Cualquier contraseña nueva (temporal o propia) cierra las sesiones abiertas con la anterior. |
| Auditoría | `auditoria` y el registro de actividad guardan a la **persona que ha iniciado sesión**. Entradas y salidas en el registro de actividad (`login` / `logout`); bloqueos, desbloqueos, temporales y cambios de contraseña en `auditoria` (entidad `cuenta`, sin contraseñas). |
| Peticiones desde otra web (CSRF) | Cookie de sesión `SameSite=Lax` + se rechaza (403 `origen`) cualquier POST/PUT/PATCH/DELETE cuyo `Origin` no sea el propio servidor. |
| Fijación de sesión | Al entrar, la sesión cambia de identificador. |
| Primer Admin | `scripts\servidor\crear-admin.cmd EMAIL` en el servidor (apartado 3). No hay ninguna contraseña de serie en el código. |

Los valores se cambian en `backend\config\application-servidor.yml`:

```yaml
planner:
  autenticacion:
    intentos-maximos: 5
    bloqueo: 15m
    inactividad: 30m
    longitud-minima-clave: 10
```

## 2. Tabla `credencial` (V3)

| Columna | Qué guarda |
|---|---|
| `email` | Clave; el de `usuario`, en minúsculas |
| `hash` | BCrypt |
| `debe_cambiar` | Contraseña temporal: cambio obligatorio al entrar |
| `intentos_fallidos`, `bloqueada_hasta` | Bloqueo temporal |
| `sello` | Cambia con cada contraseña nueva; la sesión guarda el suyo y, si no coincide, termina |
| `creada_en`, `cambiada_en`, `ultimo_acceso` | Fechas |

- **Sin clave foránea a `usuario` a propósito**: repetir la importación desde
  SharePoint durante el piloto no borra las contraseñas. Quien desaparece de la
  lista no puede entrar (se comprueba en cada petición) y, si lo quita un Admin
  desde la aplicación, su cuenta se borra.
- **`tbp_consulta` (pgAdmin / DBeaver) no puede leerla** (`REVOKE ALL`): ni los
  hashes salen de la aplicación. `tbp_app` lee y escribe.
- El reinicio de pruebas la vacía; la importación real no la toca.

## 3. Puesta en marcha en el PC servidor

1. Compilar y migrar como siempre (`mvnw.cmd clean verify`; la base pasa a **v3** sola
   al arrancar con `migrar-al-arrancar: true`).
2. **Primer Admin** (una vez), en una ventana de PowerShell o cmd del servidor:

   ```powershell
   cd C:\…\TestBenchPlannerServer
   .\scripts\servidor\crear-admin.cmd pedrodriguez@borgwarner.com
   ```

   Pide la contraseña dos veces (no se ve al escribirla). Si el email no estaba en
   Users/Rights Management, lo añade como Admin; si estaba con otro rol, lo pasa a
   Admin. Sirve también de **rescate** si ningún Admin puede entrar.
3. `arrancar-servidor.cmd`. Si ningún Admin tiene contraseña, el log lo avisa:
   `MODO SERVIDOR · ningún Admin tiene contraseña todavía…`.
4. Entrar con ese email. En Configuration › Users/Rights Management, para cada
   persona: comprobar su rol y pulsar 🔑. Pasarle la contraseña temporal por un
   canal privado (Teams en un chat 1:1, en persona…).

Para probar en un solo PC (sin red): `.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=postgresql,servidor"`
y abrir `http://localhost:5001`. El primer Admin se crea igual con `crear-admin.cmd`.

## 4. Qué ve cada persona

- **Barra superior** (solo en el servidor): nombre, **🔑 Password** y **Log out**.
- **Users/Rights Management** (Admin): columna **Account** con `no password`,
  `temporary`, `active` (al pasar el ratón, último acceso) o `locked`; botones 🔑
  (contraseña temporal) y 🔓 (desbloquear). El texto de ayuda explica el alta.
- Sesión cerrada (inactividad, contraseña cambiada, quitado de la lista): la
  siguiente petición lleva al formulario con el motivo.
- Las páginas de entrada y de cambio de contraseña están en español, sin depender
  de la interfaz compilada.

## 5. API

| Ruta | Quién | Qué hace |
|---|---|---|
| `GET /login` · `POST /login` | Todos | Formulario (`usuario`, `clave`). Éxito → `/` (o `/cambiar-clave`); fallo → `/login?error` o `/login?bloqueada`. |
| `GET/POST /cambiar-clave` | Con sesión | Cambio de contraseña (`actual`, `nueva`, `repetida`). |
| `POST /logout` | Con sesión | Cierra la sesión → `/login?salida`. |
| `GET /api/v1/cuentas` | Admin | Estado de la cuenta de cada persona de la lista (sin hashes). |
| `POST /api/v1/cuentas/clave-temporal` `{email}` | Admin | `{ok, email, clave}`: la contraseña temporal, una sola vez. |
| `POST /api/v1/cuentas/desbloquear` `{email}` | Admin | Quita el bloqueo. |
| Cualquier `/api/*` sin sesión | — | 401 `{"error":"sin-sesion","motivo":"inactividad"\|"caducada"}`. |
| Cualquier `/api/*` con temporal | — | 403 `{"error":"cambiar-clave"}`. |

Sin sesión siguen abiertos `/api/heartbeat` y `/api/shutdown` (la interfaz los envía
al cerrar la pestaña; en el servidor no apagan nada), `/login` y `/logo.png`.

## 6. Prueba de cierre del hito

Criterio del plan: «Acceso por rol verificado (Admin / Editor / solo lectura);
bloqueo e inactividad probados».

1. `mvnw.cmd clean verify` en verde (incluye las pruebas del apartado 7).
2. `crear-admin.cmd TU_EMAIL` y arrancar en modo servidor.
3. Sin sesión, `http://…:5001` lleva al formulario. Entrar como Admin.
4. Users/Rights Management: dar contraseña temporal a un Editor y a una persona
   Read only (añadir una de prueba si no hay). Entrar con cada una en otro navegador
   o una ventana InPrivate: obliga a cambiar la contraseña; luego,
   - Editor: edita ensayos, no puede cambiar el calendario ni Users;
   - Read only: no guarda nada (candado de solo lectura).
5. Tres o más intentos fallidos hasta ver «Cuenta bloqueada»; comprobar `locked`
   en Users; 🔓 la desbloquea.
6. Para la inactividad sin esperar 30 min: `inactividad: 2m` en
   `config\application-servidor.yml`, reiniciar, dejar la pestaña quieta 2-3 min:
   vuelve al formulario con «La sesión se ha cerrado tras 2 minutos sin actividad».
   Quitarlo después.
7. Configuration › Activity log: `login`/`logout` con el nombre de cada persona;
   un cambio de un Editor queda a su nombre (en `auditoria`, con pgAdmin como
   `tbp_owner`).

## 7. Pruebas automáticas

| Prueba | Qué comprueba |
|---|---|
| `InicioSesionPostgresTest` (12) | Sin sesión: 401 en la API y `/` → `/login`. Entrar y salir (registro `login`). Rol por persona (Admin/Editor/Read only: calendario, Users, cuentas, guardar). Auditoría con quien hace el cambio. Bloqueo tras 3 intentos (aunque luego acierte), auditoría BLOQUEO y desbloqueo por Admin. Sin cuenta o fuera de la lista no se entra, y el bloqueo no delata qué emails tienen cuenta. Temporal → cambio obligatorio (403 `cambiar-clave`, reglas, la temporal deja de valer). Cambiar la contraseña cierra la otra sesión. Quitar de la lista borra la cuenta y la sesión. Petición desde otro origen → 403. |
| `InactividadPostgresTest` (3) | Con 3 s de inactividad: los sondeos (`X-TBP-Inactivo` alto) no alargan la sesión; usarla sí; el bloqueo temporal caduca. |
| `CuentasPostgresTest` (9) | Reglas de contraseña, temporal y cambio, fallo al cambiar cuenta para el bloqueo, temporal nueva desbloquea y cierra sesiones, email sin cuenta bloqueado igual, primer Admin (alta, ascenso, ya Admin), estado sin hashes, reimportar no borra las cuentas. |
| `FiltroOrigenTest` (3) | Mismo origen, cabecera de inactividad, páginas escapadas. |
| `EstructuraYPermisosPostgresTest` | + V3 aplicada; `tbp_app` escribe en `credencial`; `tbp_consulta` no la puede leer. |
| `ModoServidorPostgresTest` | Las de S2, ahora entrando con cuenta. |

Todas las demás pruebas (modo local, SharePoint, PostgreSQL fuera del servidor)
siguen igual: sin modo servidor la seguridad no se aplica a ninguna petición.

## 8. Ficheros

- Backend nuevo: `api/acceso/` (`AccesoController`, `CuentasController`,
  `CuentaAutenticada`, `IdentidadSesion`, `ProveedorAutenticacion`,
  `ManejadoresAcceso`, `FiltroSesion`, `FiltroOrigen`, `SesionServidor`,
  `PaginasAcceso`), `configuracion/ConfiguracionSeguridad`,
  `configuracion/PropiedadesAutenticacion`, `configuracion/TareaCuentaAdmin`,
  `infraestructura/postgresql/CuentasPostgres`, `db/migration/V3__cuentas.sql`.
- Backend cambiado: `pom.xml` (spring-boot-starter-security), `application.yml`,
  `application-servidor.yml`, `TestBenchPlannerApplication`, `ServicioSesion`,
  `AlmacenUsuarios`, `UsuariosPostgres`, `ConfiguracionAlmacenes`,
  `ImportadorDatos`, `AvisoModoServidor`.
- Interfaz: `estado/actividad.ts` (nuevo), `api/cliente.ts`, `api/tipos.ts`,
  `App.tsx`, `bloques/configuracion/ConfigUsuarios.tsx`.
- Scripts: `scripts/servidor/crear-admin.cmd` (nuevo),
  `application-servidor.yml.ejemplo`, `application-postgresql.yml.ejemplo`.

## 9. Límites conocidos

- **Por HTTP solo en pruebas**: arrancado con `arrancar-servidor.cmd` la contraseña
  viaja sin cifrar. Instalado como servicio (S4) va siempre por HTTPS
  (`docs/13-despliegue-servidor.md`); allí el primer Admin o el rescate se hacen con
  `C:\TestBenchPlanner\herramientas\crear-admin.cmd` (como Administrador).
- Sesiones en memoria: al reiniciar la aplicación todos vuelven a entrar.
- No hay «he olvidado mi contraseña» por correo: lo resuelve un Admin con 🔑.
- Quien conozca el email de alguien puede bloquear su cuenta 15 min fallando a
  propósito (es lo que pide el bloqueo temporal); un Admin la desbloquea con 🔓.
- La primera vez que alguien entra en el servidor, las preferencias de pantalla
  del navegador (columnas, zoom…) son las de ese navegador, como hasta ahora.
