# Plan de migración aprobado (5-nov-2026)

Decisiones del usuario:
- Se empieza de cero; no se reutiliza trabajo previo.
- Java irá **empaquetado** con su propio runtime (no se puede instalar en los PCs).
- Festivos, importar JSON y presencia (D6, D10, D9): igual que la 4.9.4.
- D1 se corrige; D2 se conserva y documenta; D8 y D12 a criterio técnico.
- Conflictos de edición: **aviso al usuario** («Otra persona ha cambiado este ensayo», con «Ver su versión» / «Guardar la mía encima»).
- SharePoint: el desarrollo solo lee; la versión entregada escribirá en las listas
  igual que la 4.9.4. La escritura es una opción de configuración: apagada en
  desarrollo y pruebas, encendida en la entrega.

## H1 · Caracterización y protección del comportamiento
Objetivo: un oráculo ejecutable de la 4.9.4. Entregables: inventario con IDs,
laboratorio, escenario real anonimizado, casos y referencias, discrepancias.

## H2 · Backend Java (monolito modular Spring Boot)
Dominio sin dependencias (planificación, estados, sugerencias, cierre, KPI,
ocupación, calendario) · casos de uso · puertos de persistencia · adaptador
local (escenario en disco) · adaptador SharePoint (mismas listas, mismo DataJson,
espejo y KPI byte a byte; escritura desactivable) · API para React y API
compatible `/api/*` para servir la 4.9.4 como respaldo · concurrencia optimista
con aviso. Aceptación: 0 diferencias sin clasificar con las referencias.

## H3 · Interfaz React por bloques
A operativa diaria · B dashboard · C KPI/estadísticas · D recursos · E
configuración · F exportaciones. Sin reglas de negocio en los componentes.
Aceptación por bloque: casos del laboratorio iguales a la 4.9.4 y pruebas
manuales del bloque.

## H4 · Validación integral y retirada controlada
Validación en la oficina (SharePoint primero en solo lectura y después
escritura controlada), paquete con Java incluido y lanzador, documentación de
instalación, trazabilidad final. La 4.9.4 sigue disponible como respaldo hasta
que se valide la nueva.

## Estado (10-nov-2026)
- H1, H2 (con H2b), H3 bloques A–F, cierre H3Z y rendimiento con SharePoint
  H3R: terminados y validados en el PC.
- H4: pendiente. Se aparca para añadir antes la facturación.

## Servidor local con PostgreSQL (16-nov-2026)
Plan acordado en `TestBenchPlanner_plan_servidor_PostgreSQL.md`: una sola
instancia en un PC servidor, PostgreSQL local, usuarios propios de la
aplicación, Flyway y migración de datos en dos pasos. Hitos S1 (base de datos),
S2 (modo servidor), S3 (autenticación), S4 (despliegue) y S5 (piloto y corte).
- **S1 · Base de datos**: **validado** el 17-nov-2026 en el PC corporativo de
  Pedro (`mvnw clean verify` en verde y prueba completa con la exportación real de
  SharePoint). Falta repetirlo en el PC servidor. Ver `docs/10-servidor-postgresql.md`.
- **S2 · Modo servidor**: código terminado el 17-nov-2026 (perfil `servidor`,
  solo PostgreSQL, sin apagado por pestañas, sin `/clasico` ni API antigua,
  calendario común solo Admin con la migración V2, Reset solo del navegador).
  Probado en el PC corporativo el 17-nov-2026 (`clean verify` en verde, arranque
  en modo servidor); el acceso desde otro PC se comprueba en el PC servidor (el
  portátil no permite abrir el puerto). Pedro pidió darlo por bueno y seguir con
  S3. Ver `docs/11-modo-servidor.md`.
- **S3 · Inicio de sesión**: código terminado el 17-nov-2026 (formulario y sesión
  con Spring Security solo en modo servidor, contraseñas BCrypt en la tabla
  `credencial` (V3), roles de Users/Rights Management, contraseña temporal con
  cambio obligatorio, bloqueo temporal, cierre por inactividad, primer Admin con
  `crear-admin.cmd`, auditoría con la persona real). Probado en el PC corporativo
  el 17-nov-2026 (`clean verify`, roles, bloqueo e inactividad). Ver
  `docs/12-inicio-de-sesion.md`.
- **S4 · Despliegue**: código terminado el 17-nov-2026 (instalador del servicio de
  Windows con WinSW, Java propio, HTTPS autofirmado, firewall, energía, copia diaria
  `pg_dump` con retención y destino externo, restauración, actualización con copia
  previa, logs con rotación, `/api/v1/salud`, `comprobar-servidor`).
  `mvnw clean verify` en verde el 17-nov-2026. **Instalado** el 17-nov-2026 en el PC
  servidor (VGEDTOFF0001, `C:\TestBenchPlanner`, puerto 5001); acceso desde otros PCs
  comprobado. Como los permisos de administrador de IT duran un día, se añade la
  **actualización delegada** (`docs/13`, apartado 7.1): cuentas concretas actualizan
  dejando el `.jar` en `entrada\`, sin administrador. Pendiente de la prueba de cierre
  (reinicio sin intervención y restauración). Ver `docs/13-despliegue-servidor.md`.
- **S5 · Piloto y corte**: en preparación. Hecho el 17-nov-2026: aprobaciones
  gestionadas desde la aplicación, solo Admin (D54). Guía del piloto y del corte en
  `docs/14-piloto-y-corte.md`. El piloto empieza cuando S4 esté cerrado.
  Fase 1 de las peticiones del 18-nov-2026: rol **Lab User** (D55), **áreas
  editables** con PostgreSQL por un Admin o un Lab User (D56) y **filtro de área**
  en la hoja mensual de facturación (D57).
  Fase 2 (18-nov-2026): el Lab User también configura los **bancos** (D58);
  pestaña **Test Calendar / Working Hours** por ensayo con PostgreSQL (D59, migración
  V4) y **facturación por horas** desde el mes `FacturacionHorasDesde`, sin facturar
  el setup y con la columna «Pieces» en la tabla del banco (D60). Ver
  `docs/15-horario-de-ensayos.md`.
- Decisiones del 17-nov-2026 (plan §9): **sin nombre DNS** por ahora (se entra
  por el nombre del PC o la IP; HTTPS de S4 con certificado autofirmado), Pedro
  tiene permisos de administrador en el PC servidor, puerto definitivo **5001**,
  como mucho **unas 6 personas escribiendo a la vez** y aprobaciones como **tabla
  propia que gestiona un Admin desde la aplicación** (sustituye a la edición manual
  de la lista de SharePoint).

