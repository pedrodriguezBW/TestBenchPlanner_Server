# TestBenchPlanner · migración a React + Java

Migración de **TestBenchPlanner 4.9.4** —una interfaz HTML monolítica con backend
Python/Flask— a una aplicación local con **React** en el frontend y **Java /
Spring Boot** en el backend.

El objetivo es conservar el comportamiento funcional y la compatibilidad con las
listas actuales de SharePoint mientras la interfaz se sustituye por bloques. La
versión 4.9.4 se mantiene sin modificar como oráculo de paridad y como respaldo
durante la transición.

> **Estado actual (10-nov-2026): migración cerrada y validada en el PC.** H1, H2,
> H3 (bloques A–F), el cierre H3Z y la mejora de rendimiento con SharePoint H3R
> están terminados y validados. Una auditoría función a función de la 4.9.4
> (`docs/05-auditoria-cierre.md`) confirma que todo está migrado, es código muerto
> o tiene una decisión documentada. Solo queda H4 (entrega y retirada de la
> 4.9.4), aparcado mientras se añade la facturación. `/clasico` sigue disponible
> como respaldo hasta entonces.
>
> **Facturación (H5, 10-nov-2026): implementada como código** según el plan
> completo de facturación (fases 1–7 y base de RACS): sección «Billing» con
> periodos del 21 al 20, revisión, overview, configuración, aprobación,
> auditoría y exportaciones (Excel, Controlling y HTML). Pendiente de `mvnw clean
> verify` en el PC y de validación por Pedro. Detalle en `docs/07-facturacion.md`.
>
> **H6 (12-nov-2026) · Facturación rehecha** según «CambiosTestBenchFacturacion.docx»: se ha
> eliminado todo lo anterior (periodos, revisión, aprobación, ajustes, Controlling, auditoría,
> ficha «Billing Data», JSON) y queda la **hoja mensual** como la del Excel (Billing › Monthly
> sheet, exportable en Excel y HTML) con Equipment Types por año en la lista nueva
> **TestBenchEquipmentTypes**, Equipment Type del banco (columna `EquipmentType`), Billing Order
> del proyecto y columnas Setup (h), HV y Product Line en la tarea. Ver `docs/07-facturacion.md`.
> **H6b**: la hoja mensual es una tabla dinámica como la del Excel (Billing Order › Planta › Tipo ›
> Equipo › HV › Inicio › Fin › Coste/hora, campos elegibles, filas plegables; Excel con filas
> agrupadas) y el mes se elige con desplegables y flechas.
> **H6c**: el mes de facturación es el periodo del 21 del mes anterior al 20 del mes (horas, setup,
> Inicio/Fin y cabeceras referidos a ese periodo).
> Lo que sigue sobre H5–H5d es historia.
>
> **H5d (12-nov-2026)**: paridad con el Excel de facturación: tarifas por año como «Equipos y
> costes» (Equipo × HV), regla STC = 0, «Planta» = la del Excel (antes «línea de producto»), nombres
> Equipo/Tipo, UM «H» en Controlling y hoja «Detalle» con las columnas de INPUTS_2026. Ver
> docs/07-facturacion.md §7b.

> **H5c (11-nov-2026)**: guardar Test Benches/Projects en SharePoint solo escribe los campos
> que cambian (antes reescribía todas las filas y bloqueaba la aplicación varios minutos), y la
> pantalla cambia al momento: el guardado va en segundo plano («Saving…»; si falla, aviso y vuelta
> a lo guardado).
> Ver docs/06-rendimiento-sharepoint.md.

> **H5b (11-nov-2026)**: los maestros de facturación arrancan vacíos (se dan de
> alta a mano); la orden contable pasa a Configuration › Projects (columna
> `BillingOrder` de TestBenchProjects) y la categoría de cada banco a
> Configuration › Test Benches (columna `BillingCategory` de TestBenchBenches);
> requester y línea de producto son de cada ensayo; los periodos se pueden
> eliminar. Hay que crear las dos columnas en SharePoint.

> **S1 · Servidor con PostgreSQL: validado (17-nov-2026)** en el PC corporativo con
> la exportación real de SharePoint. Tercer almacén `planner.almacen=postgresql`
> (mismos puertos: dominio, API y React sin cambios), estructura por migraciones
> Flyway, usuarios `tbp_owner` / `tbp_app` / `tbp_consulta`, exportador desde
> SharePoint (solo lectura) e importador idempotente con verificación de paridad.
> Ver `docs/10-servidor-postgresql.md`.
>
> **S2 · Modo servidor (17-nov-2026): probado en el PC corporativo.** Perfil `servidor`:
> escucha en la red, solo con PostgreSQL, no se apaga al cerrar pestañas, sin `/clasico`
> ni API antigua, calendario de festivos común que solo cambia un Admin (migración V2)
> y Reset solo del navegador. El acceso desde otro PC se comprueba en el servidor.
> Ver `docs/11-modo-servidor.md`.
>
> **S3 · Inicio de sesión (17-nov-2026): probado en el PC corporativo.** Solo en modo servidor:
> email + contraseña de la aplicación (BCrypt, migración V3), roles de
> Users/Rights Management, contraseña temporal con cambio obligatorio, bloqueo tras
> 5 intentos, cierre tras 30 min sin actividad, primer Admin con `crear-admin.cmd` y
> auditoría con la persona real. Ver `docs/12-inicio-de-sesion.md`.
>
> **S4 · Despliegue (17-nov-2026): código terminado.** Servicio de Windows (WinSW) que
> arranca solo y se reinicia si cae, HTTPS con certificado autofirmado, firewall,
> copia diaria con `pg_dump`, restauración, actualización con copia previa, logs con
> rotación, `/api/v1/salud` y comprobación del servidor. Pendiente de probar en el PC
> servidor. Ver `docs/13-despliegue-servidor.md`.

## Índice

- [Estado de la migración](#estado-de-la-migración)
- [Arquitectura](#arquitectura)
- [Estructura del repositorio](#estructura-del-repositorio)
- [Tecnologías](#tecnologías)
- [Puesta en marcha](#puesta-en-marcha)
- [Pruebas y paridad](#pruebas-y-paridad)
- [Configuración](#configuración)
- [APIs e interfaces](#apis-e-interfaces)
- [Reglas de seguridad y trabajo](#reglas-de-seguridad-y-trabajo)
- [Documentación](#documentación)

## Estado de la migración

| Hito | Resultado incorporado al repositorio | Estado |
|---|---|---|
| **H1 · Caracterización** | Inventario funcional de la 4.9.4, escenario anonimizado, laboratorio de paridad, 67 casos de referencia y discrepancias documentadas. | **Terminado** |
| **H2 · Backend Java** | Monolito modular Spring Boot, dominio y casos de uso, persistencia local y SharePoint, API nueva y API compatible con la 4.9.4. | **Terminado**; pruebas verdes y SharePoint validado en solo lectura |
| **H2b · Protección de datos** | Protección frente a lecturas ausentes, fallidas o vacías; reconciliación bloqueada para evitar sobrescrituras destructivas de listas compartidas. | **Integrado en H2** |
| **H3A · React, bloque A** | Vista de banco: tabla de 30 columnas, Gantt, edición, selección, operaciones de cola, aprobaciones, cierre y lista de ensayos cerrados. | **Terminado** |
| **H3B · React, bloque B** | Dashboard (bloques KPI, plan anual por banco, gráfica de ocupación por CW, tabla de ocupación por tipo de banco, 📊 Update Yearly Status) y «By Responsible» (Manager View, carga del equipo, aviso de IDs sin aprobar). | **Terminado** |
| **H3C · React, bloque C** | KPI History: estadísticas de ensayos cerrados (periodo, responsable, por mes, trimestre y motivo, detalle) y foto semanal (medias, tabla por semana, motivos acumulados) por organización. Incluye la corrección del ciclo de vida (minimizar ya no cierra el backend). | **Terminado** |
| **H3D · React, bloque D** | Equipos auxiliares: columna «Resources» y selector de equipos en los bancos, «List of Equipments» (Gantt por equipo, bloqueos manuales) y «Equipments List» (catálogo, solo Admin). Incluye la separación entre controles de consulta y de escritura en solo lectura. | **Terminado** |
| **H3E · React, bloque E** | Configuración: Test Benches (categorías y bancos; renombrar conserva ensayos, borrar los borra), Projects, Users/Rights Management, Tools (calendario de festivos, IDs live, Reset local), About y Activity log. | **Terminado** |
| **H3F · React, bloque F** | Exportaciones e importaciones: 📊 Excel de los Gantt (banco, Dashboard, «By Responsible», equipos; con foco), ⬇️ Exportar CSV de estadísticas, copia de seguridad JSON (exportar e importar) y 📤 Export read-only. El Excel lo genera el backend Java con el mismo aspecto que la 4.9.4. | **Terminado**; validado en el PC |
| **H3Z · Cierre de la migración** | Auditoría de las 510 piezas de la 4.9.4 (funciones, rutas, semillas, temporizadores) y cierre de los huecos: presencia, aviso de versión antigua, consulta sin conexión con la última copia, bienvenida, relectura de catálogos, calendario con Escape, tiradores táctiles, sugerencias completas al entrar y orden de Admin en Users (D29). Inventario y discrepancias en su estado final. | **Terminado**; validado en el PC |
| **H3R · Rendimiento con SharePoint** | Lecturas compartidas y sin repeticiones, conexiones de solo lectura en paralelo a la de escritura, estados de aprobación por lotes, consultas de una pestaña recargada descartadas sin avisos, altas y copias visibles al momento y copia sin conexión sin choques entre escrituras. Detalle en `docs/06-rendimiento-sharepoint.md`. | **Terminado**; validado en el PC (10-nov-2026) |
| **H5 · Facturación** | Módulo «Billing» separado de la planificación: datos de facturación del ensayo (hora real, paradas con fecha y hora, setup, HV, orden, requester, línea de producto, facturable), motor de periodos y tramos, maestros (categorías, tarifas versionadas, reglas, Controlling; orden del proyecto en Projects y categoría del banco en Test Benches), revisión con ajustes trazables y tarifas excepcionales, aprobación con foto inmutable, reapertura, auditoría y exportaciones. Paridad con las 522 filas de 2026 y la hoja Controlling del Excel. | **Código terminado**; pendiente de `mvnw clean verify` y validación |
| **H7 · Áreas de bancos** | Nivel nuevo por encima de las categorías (Thermal Benches, HVCH - CTAG, HVCH - STC): menú lateral por áreas con selector y secciones plegables, Test Benches por áreas, filtro de dos niveles (área y, en Thermal, Org) en Dashboard, «By Responsible», tarjetas y KPI History, foto semanal por área y filtro Org en List of Equipments. Columna nueva `Area` en TestBenchBenches. Ver `docs/08-areas-de-bancos.md`. | **Código terminado**; pendiente de `mvnw clean verify` y validación |
| **H8 · Gantt general rediseñado** | Dashboard y «By Responsible»: color por proyecto y estado por forma, carriles para ensayos simultáneos con aviso «N en paralelo», ocupación por banco, categorías plegables, pasado atenuado, tarjeta de detalle al pasar el ratón (clic abre el ensayo, F enfoca el proyecto) y foco con ficha resumen, bancos resaltados y flechas de trazabilidad. Ver `docs/09-gantt-rediseno.md`. | **Código terminado**; pendiente de validación |
| **H9 · Gantt a altura libre** | Dashboard y «By Responsible»: sin tirador en la tarjeta del Gantt, que ocupa todo el alto que necesita (sin scroll vertical propio), con cabecera y barra horizontal que acompañan al scroll de la página. Banco: el divisor fija el alto de la tabla y el Gantt se desplaza hacia abajo en vez de comprimirse. Ver `docs/09-gantt-rediseno.md`. | **Código terminado**; pendiente de validación |
| **S1 · Servidor: base de datos** | Almacén PostgreSQL (`planner.almacen=postgresql`) con modelo híbrido (columnas + `DataJson` íntegro), `version` y auditoría antes/después; Flyway al arrancar (no arranca si la base no cuadra); usuarios de base de datos con permisos separados; exportación desde SharePoint en solo lectura e importación idempotente en una transacción con verificación de paridad. Las pruebas del almacén local se ejecutan también contra PostgreSQL. Ver `docs/10-servidor-postgresql.md`. | **Validado** (17-nov-2026, PC corporativo con datos reales); falta repetirlo en el servidor |
| **S2 · Servidor: modo servidor** | Perfil `servidor` (dirección y puerto configurables, solo PostgreSQL, comprobado al arrancar); sin apagado por pestañas; sin `/clasico` ni API antigua; Export read-only desde `/api/v1/exportaciones/plantilla-solo-lectura`; calendario común en la base (V2) que solo cambia un Admin; Tools › Reset solo del navegador; scripts para arrancar y abrir el puerto. Ver `docs/11-modo-servidor.md`. | **Probado** en el PC corporativo (17-nov-2026); acceso desde otro PC pendiente (en el servidor) |
| **S3 · Servidor: inicio de sesión** | Solo en modo servidor: formulario y sesión (Spring Security), contraseñas BCrypt en `credencial` (V3, no legible por `tbp_consulta`), roles de Users/Rights Management leídos en cada petición, contraseña temporal generada por un Admin con cambio obligatorio, bloqueo temporal tras intentos fallidos, cierre por inactividad real (los sondeos no cuentan), protección de origen, primer Admin con `crear-admin.cmd`, auditoría y registro de actividad con la persona real. Ver `docs/12-inicio-de-sesion.md`. | **Probado** en el PC corporativo (17-nov-2026) |
| **S4 · Servidor: despliegue** | Instalador del servicio de Windows (WinSW, LocalService, inicio automático retrasado tras PostgreSQL, reinicio si cae), Java propio en la instalación, HTTPS obligatorio con certificado autofirmado del servidor, regla de firewall solo dominio/privado, sin suspensión, copia diaria `pg_dump` comprobada y duplicada fuera con 30 días de retención, restauración con vuelta atrás, actualización con copia previa y vuelta al programa anterior, logs con rotación, `/api/v1/salud` y `comprobar-servidor`. Ver `docs/13-despliegue-servidor.md`. | **Código terminado**; pendiente de `mvnw clean verify` y prueba de cierre en el servidor |
| **H4 · Entrega** | Validación integral en oficina, escritura controlada en SharePoint, runtime de Java incluido, lanzador y retirada controlada de la 4.9.4. | Pendiente; aparcado hasta terminar la facturación |

### Funcionalidad disponible

- Planificación y edición diaria por banco.
- Cálculo de fechas, estados, sugerencias, cierres, KPI, ocupación y calendario
en el backend; React no duplica reglas de negocio.
- Operaciones sobre la cola: comprometer y deshacer, confirmar todas, arrastrar,
duplicar, mover, borrar y añadir ensayos.
- Cierre, corrección, restauración y reutilización de ensayos.
- Concurrencia optimista en la API React: ante un cambio ajeno se puede ver la
versión remota o forzar la versión local.
- Relectura periódica, cola de guardado y recuperación ante pérdida de conexión.
- Ciclo de vida por pestaña: el backend se cierra solo cuando se cierra la última
pestaña; minimizar el navegador o dejar una pestaña oculta no lo cierra.
- Dashboard: navegación por semanas, filtro Org, bloques «Esta semana» / «Semana
que viene», Gantt de todos los bancos (año o semana, zoom y foco de proyecto),
gráfica de ocupación por CW (% u horas, rango de CW, series ocultables), tabla de
ocupación por tipo de banco (semana o acumulado) y foto semanal del histórico.
- «By Responsible»: planificación de una persona o del equipo (Manager View),
carga por responsable y aviso de ensayos sin Approval ID aprobado.
- KPI History: estadísticas de ensayos cerrados con periodo (año, año hasta hoy,
trimestre, mes o fechas), responsable y Org, y foto semanal del histórico con
medias, tabla por semana y motivos acumulados, y «⬇️ Exportar CSV» de los
ensayos cerrados del periodo (mismo formato que la 4.9.4).
- Equipos auxiliares: columna «Resources» en cada banco con aviso «!» si un
equipo asignado está ocupado en esas fechas, selector «Disponibles / En uso»
con búsqueda, Gantt de ocupación por equipo con bloqueos y solapes, bloqueos
manuales (Admin) y catálogo «Equipments List» (Admin; renombrar un equipo lo
cambia en todos los ensayos).
- Configuración: Test Benches (solo Admin; cada cambio se guarda en el acto y
renombrar o borrar un banco actualiza sus ensayos en el mismo paso), Projects
(Admin y Editor; alta, pegar, importar CSV/TXT, borrar), Users/Rights
Management, Tools (calendario de festivos de este PC, IDs live, Reset local),
About y Activity log.
- Exportaciones: «📊 Excel» en los tres Gantt (banco, Dashboard / «By
Responsible» y equipos), con el foco de proyecto aplicado; «⬇️ Export (JSON
backup)» e «⬆️ Import (JSON)» en Tools (importar: solo Admin, sin confirmación
como hoy, con la protección de listas del backend); «📤 Export read-only» (Admin)
genera la foto HTML de solo lectura para el proveedor.
- Colaboración: burbujas de quién está conectado, aviso de versión antigua
(si la versión publicada es mayor) y bienvenida al entrar, como la 4.9.4.
- Sin conexión: si SharePoint no responde se muestra la última copia buena de
este PC en solo consulta, con el aviso «Modo offline · solo consulta» y el
candado; no se guarda nada hasta volver a abrir con conexión.
- Solo lectura: se bloquean únicamente las acciones que modifican datos; filtros,
búsquedas, navegación y consultas siguen disponibles en todas las pantallas.
- Rendimiento con SharePoint: lecturas compartidas sin repeticiones, consultas
en paralelo a los guardados, estados de aprobación por lotes, recarga (F5) sin
avisos por peticiones abandonadas y altas o copias de ensayos visibles al momento.
- Facturación (sección «Billing», H6): Monthly sheet y Configuration (Admin); columnas
Pieces (antes Setup (h); D60), HV y Product Line en cada banco; horario por ensayo y facturación por horas
con PostgreSQL en `docs/15-horario-de-ensayos.md`; «Equipment Type» en Test Benches y «Billing order»
en Projects. Ver `docs/07-facturacion.md`. (Texto anterior, H5:) columna «Billing» y ficha «Billing Data» en cada banco y en
los cerrados; sección opcional en la ventana de cierre (el cierre no depende de
los datos económicos); eliminar periodos; columnas «Billing order» en Projects y
«Billing category» en Test Benches. Ver `docs/07-facturacion.md`.
- Áreas de bancos (H7): Thermal Benches, HVCH - CTAG y HVCH - STC por encima de
las categorías; menú lateral agrupado y filtrable por área; filtro Area + Org (Org
solo en Thermal Benches) en todas las pantallas de indicadores; foto semanal por
área; filtro Org en List of Equipments. Ver `docs/08-areas-de-bancos.md`.
- Gantt general rediseñado (H8): color por proyecto y estado por forma, carriles para
ensayos simultáneos, ocupación por banco, categorías plegables, tarjeta al pasar el
ratón y foco con ficha y trazabilidad entre bancos. Ver `docs/09-gantt-rediseno.md`.
- Gantt a altura libre (H9): los Gantt ocupan todo el alto que necesitan, con
cabecera y barra horizontal flotantes; en cada banco, ampliar la tabla desplaza
el Gantt en vez de comprimirlo.
- La aplicación se abre en el Dashboard, como la 4.9.4.
- Interfaz clásica servida en `/clasico` como respaldo hasta la retirada de la
4.9.4 (H4).

## Arquitectura

La aplicación se ejecuta como un **monolito modular local**. Spring Boot escucha
en `127.0.0.1:5001`, sirve la API y publica la compilación de React. El frontend
de desarrollo usa Vite en el puerto `5173` y reenvía `/api` y `/clasico` al
backend.

```text
Navegador
├── /                    React (frontend/dist)
└── /clasico             HTML 4.9.4 sin modificar
          │
          ▼
Spring Boot · 127.0.0.1:5001
├── /api/v1/*            API de la interfaz React
├── /api/*               API compatible con la 4.9.4
├── dominio              Reglas puras de negocio
├── aplicacion           Casos de uso y puertos
└── infraestructura
    ├── local            Escenarios en disco para desarrollo y pruebas
    ├── sharepoint       Listas de SharePoint mediante Edge + Playwright/CDP
    ├── postgresql       Base de datos PostgreSQL (servidor, hito S1)
    └── migracion        Exportar (SharePoint) → importar (PostgreSQL)
```

### Principios de diseño

- **Una única implementación de las reglas:** fechas, estados, validaciones,
sugerencias, cierres e indicadores viven en el backend.
- **Arquitectura por puertos y adaptadores:** el dominio no depende de Spring,
SharePoint ni del sistema de ficheros.
- **Compatibilidad durante la migración:** `/api/*` y `/clasico` permiten ejecutar
la 4.9.4 sobre el backend Java.
- **Persistencia intercambiable:** `planner.almacen` selecciona el adaptador
`local`, `sharepoint` o `postgresql`. La estructura de la base de datos solo
cambia con migraciones Flyway versionadas (`db/migration/V<n>__…sql`).
- **Concurrencia optimista:** `/api/v1` usa la huella de `DataJson`; SharePoint
añade comprobación de ETag y PostgreSQL bloqueo de filas en una transacción y
columna `version`.
- **Conservación de datos:** `DataJson` mantiene campos desconocidos y el orden de
las claves para no reescribir filas sin cambios.

## Estructura del repositorio

```text
TestBenchPlanner/
├── backend/                              Backend Java / Spring Boot
│   ├── .mvn/                             Configuración de Maven Wrapper
│   ├── mvnw · mvnw.cmd                   Maven Wrapper para Linux/macOS y Windows
│   ├── pom.xml                           Dependencias, build y perfil «interfaz»
│   └── src/
│       ├── main/
│       │   ├── java/com/borgwarner/tbp/
│       │   │   ├── api/
│       │   │   │   ├── compat/           API /api/* y servicio de /clasico
│       │   │   │   ├── local/            Reinicio del escenario local para pruebas
│       │   │   │   ├── v1/               Planificación, configuración e indicadores
│       │   │   │   └── comun/            Errores HTTP y ciclo de vida
│       │   │   ├── aplicacion/            Casos de uso, sesión, listas e indicadores
│       │   │   │   └── puertos/           Contratos de persistencia e integración
│       │   │   ├── configuracion/         Selección y propiedades de adaptadores
│       │   │   ├── dominio/
│       │   │   │   ├── calendario/        Festivos, semanas ISO y días laborables
│       │   │   │   ├── comun/             Semántica y serialización JSON compatible
│       │   │   │   ├── ensayo/            Ensayos, duración, estados y motivos
│       │   │   │   ├── facturacion/       Reglas, Equipment Types y hoja mensual de facturación
│       │   │   │   ├── kpi/               KPI, ocupación, fotos e histórico
│       │   │   │   ├── listas/            Protección de listas compartidas
│       │   │   │   ├── operaciones/       Edición, cola y cierre
│       │   │   │   ├── planificacion/     Motor de fechas y sugerencias
│       │   │   │   └── usuarios/          Usuario y roles
│       │   │   └── infraestructura/
│       │   │       ├── comun/             Reloj, calendario, versiones y KPI
│       │   │       ├── local/             Almacenes basados en ficheros
│       │   │       ├── sharepoint/         Edge/CDP, listas, aprobaciones y espejo
│       │   │       ├── postgresql/         Almacenes en PostgreSQL, Flyway, importador y verificación de paridad
│       │   │       └── migracion/          Formato de la exportación, exportador y tareas de línea de órdenes
│       │   └── resources/
│       │       ├── application.yml         Configuración por defecto
│       │       └── db/
│       │           ├── migration/          Migraciones Flyway (V1__esquema_inicial.sql…)
│       │           └── instalacion/        Usuarios, base y permisos (una vez, con el superusuario)
│       └── test/java/com/borgwarner/tbp/
│           ├── dominio/                   Pruebas unitarias del dominio
│           ├── integracion/               API, almacenes y protecciones (y sus variantes *PostgresTest)
│           ├── postgresql/                Contrato de almacenes, importación, permisos, concurrencia y paridad con PostgreSQL
│           └── paridad/                   Comparación Java ↔ referencias 4.9.4
│
├── frontend/                             Interfaz React de H3
│   ├── public/                            Recursos estáticos
│   ├── dist/                              Build generado; el JAR lo sirve en / si existe
│   ├── package.json                       Scripts y dependencias
│   ├── vite.config.ts                     Puerto 5173 y proxy al backend
│   └── src/
│       ├── api/                           Cliente y tipos de /api/v1
│       ├── bloques/banco/                 Bloque A: tabla, Gantt, cierre y operaciones
│       ├── bloques/panel/                 Bloque B: Dashboard y «By Responsible»
│       ├── bloques/kpi/                   Bloque C: KPI History y estadísticas
│       ├── bloques/recursos/              Bloque D: equipos auxiliares
│       ├── bloques/configuracion/         Bloque E: Test Benches, Projects, Users, Tools, About y Activity log
│       ├── bloques/facturacion/           Facturación: Monthly sheet y Configuration
│       ├── componentes/                   Diálogos, avisos y selector de fecha
│       ├── estado/                        Carga, guardado, conflictos, relectura, Dashboard, equipos y ciclo de vida
│       ├── estilos/                       CSS original y estilos de la nueva interfaz
│       └── utilidades/                    Fechas, textos y preferencias locales
│
├── legacy/                               Versión 4.9.4 original: no modificar
│   ├── BW_TestBench_Planner_31_Local.html
│   └── backend_testbench_v4_9_4.py
│
├── datos/
│   ├── escenarios/sharepoint-2026-11-05/ Escenario anonimizado versionado
│   └── privado/                          Lecturas reales; excluidas de Git
│
├── herramientas/
│   ├── importar-sharepoint/              Generación de escenarios anonimizados
│   └── laboratorio/
│       ├── casos/                         Entradas del laboratorio
│       ├── casos-interfaz/                Casos de pantalla (Dashboard, «By Responsible», KPI History y equipos)
│       ├── referencias/                   Resultados esperados de la 4.9.4
│       ├── comparar.cjs                   Comparador backend/paridad
│       ├── comparar-interfaz.cjs          Paridad funcional de React (bloque A)
│       ├── comparar-panel.cjs             Paridad visual de pantallas (bloques B, C, D y E)
│       ├── pruebas-ciclo-de-vida.cjs      Minimizar u ocultar no da de baja la pestaña
│       ├── pruebas-interfaz.cjs           Conflicto, conexión y solo lectura
│       └── backend-simulado-4.9.4.cjs     Backend de apoyo para pruebas React
│
├── scripts/servidor/                     Crear la base, exportar desde SharePoint, importar en PostgreSQL, primer Admin, arrancar el servidor y abrir el puerto
│   ├── instalar-servicio · actualizar-servidor · desinstalar-servicio   Servicio de Windows (S4)
│   └── servicio/                         Herramientas que se copian a la instalación: copias, restauración, comprobación, primer Admin
│
├── docs/
│   ├── 00-plan-aprobado.md               Alcance e hitos
│   ├── 01-inventario-funcional.md        Inventario de comportamiento 4.9.4
│   ├── 02-discrepancias.md                Diferencias conocidas y decisiones
│   ├── 03-arquitectura.md                 Arquitectura Java y React
│   ├── 04-ejecucion-y-pruebas.md          Guía operativa y de validación
│   ├── 10-servidor-postgresql.md          Servidor con PostgreSQL: datos, Flyway, migración y pruebas (S1)
│   ├── 11-modo-servidor.md                Modo servidor: red, sin /clasico, calendario común (S2)
│   ├── 12-inicio-de-sesion.md             Inicio de sesión: cuentas, roles, bloqueo, inactividad (S3)
│   └── 13-despliegue-servidor.md          Instalación como servicio, HTTPS, copias, actualización (S4)
│
├── .gitignore
└── README.md
```

## Tecnologías

| Área | Tecnología |
|---|---|
| Backend | Java 17, Spring Boot 3.3.5, Maven Wrapper |
| Frontend | React 19.2.5, TypeScript 5.8.3, Vite 7.3.5 |
| Integración SharePoint | Playwright Java mediante Edge y CDP |
| Laboratorio web | Node.js y Playwright |
| Persistencia de desarrollo | Ficheros JSON bajo `~/.testbenchplanner` |
| Base de datos del servidor | PostgreSQL 16, JDBC (Spring JDBC, HikariCP), Flyway; pruebas con PostgreSQL temporal (`io.zonky.test:embedded-postgres`) |

## Puesta en marcha

### Requisitos de desarrollo

- **JDK 17 o superior.** Puede usarse un JDK portable; no es obligatorio
instalarlo en el sistema.
- Conexión a Internet en la primera ejecución de Maven para descargar el wrapper
y las dependencias.
- **Node.js solo es necesario si se modifica el frontend** o se ejecutan las
pruebas web. Las entregas de H3 incluyen `frontend/dist` compilado; si falta en un
clon limpio, se puede generar con npm o con el perfil Maven `interfaz`.

En Windows, si se usa un JDK portable:

```bat
set JAVA_HOME=C:\ruta\jdk-17
set PATH=%JAVA_HOME%\bin;%PATH%
```

### Compilar y ejecutar las pruebas

```bat
cd backend
mvnw.cmd clean verify
```

En Linux o macOS:

```bash
cd backend
./mvnw clean verify
```

### Ejecutar en modo local

El modo local arranca con el escenario anonimizado y escribe únicamente en la
carpeta del usuario.

```bat
cd backend
mvnw.cmd spring-boot:run -Dspring-boot.run.arguments="--planner.fecha-fija=2026-11-05 --planner.local.reiniciar-al-arrancar=true"
```

Abrir:

- Nueva interfaz: <http://127.0.0.1:5001/>
- Interfaz clásica: <http://127.0.0.1:5001/clasico>

Los datos locales se guardan en `%USERPROFILE%\.testbenchplanner\datos-locales`.

### Desarrollar el frontend

Con el backend Java arrancado en `5001`:

```bash
cd frontend
npm install
npm run dev
```

Vite abre <http://127.0.0.1:5173> y reenvía las rutas del backend.

Para generar `frontend/dist`:

```bash
npm run build
```

También se puede recompilar React desde Maven sin instalar Node globalmente:

```bat
cd backend
mvnw.cmd -Pinterfaz clean verify
```

Este perfil descarga un Node portable en `backend/target`.

### Ejecutar contra SharePoint en solo lectura

1. Cerrar TestBenchPlanner 4.9.4 si está abierto; utiliza los mismos puertos
`5001` y `9222`.
2. Crear `backend/config/application-sharepoint.yml` —la carpeta está excluida
de Git—:

```yaml
planner:
  almacen: sharepoint
  sharepoint:
    sitio: https://<tenant>.sharepoint.com/sites/<sitio>
    sitio-aprobaciones: https://<tenant>.sharepoint.com/sites/<sitio>/processautomation
    escritura-habilitada: false
```

3. Arrancar el perfil:

```bat
cd backend
mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=sharepoint
```

Con `escritura-habilitada: false` no se escribe en SharePoint: también quedan
desactivados presencia, registro de actividad y publicación de versión.

### Ejecutar con PostgreSQL

Instalación de la base (una vez), exportación desde SharePoint e importación:
`docs/10-servidor-postgresql.md`, apartado 5. Con `backend/config/application-postgresql.yml`:

```bat
cd backend
mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=postgresql
```

En PowerShell: `.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=postgresql"`
(con `.\` y las comillas).

Al arrancar, Flyway crea o actualiza las tablas con `tbp_owner`; si la base no
cuadra con las migraciones, la aplicación no arranca. Fuera del modo servidor la
identidad es la de `planner.postgresql.usuario-email`.

### Ejecutar en modo servidor (S2)

Con la base de S1 y `frontend\dist` presente al compilar:

```bat
scripts\servidor\abrir-puerto-firewall.cmd   (una vez, como administrador)
scripts\servidor\crear-admin.cmd TU_EMAIL    (una vez: primer Admin, pide la contraseña)
scripts\servidor\arrancar-servidor.cmd
```

Equivale a `--spring.profiles.active=postgresql,servidor`. Se entra desde otro PC
con `http://NOMBRE-DEL-SERVIDOR:5001` (el log muestra las direcciones) y cada
persona con su email y su contraseña (S3); las cuentas se dan en Users/Rights
Management. Ver `docs/11-modo-servidor.md` y `docs/12-inicio-de-sesion.md`.

### Instalar en el PC servidor (S4)

Compilado con la interfaz (`mvnw.cmd -Pinterfaz clean verify`) y con
`backend\config\application-postgresql.yml`:

```bat
scripts\servidor\instalar-servicio.cmd      (como administrador; se puede repetir)
scripts\servidor\actualizar-servidor.cmd    (cada versión nueva: copia previa y cambio)
```

Se entra con `https://NOMBRE-DEL-SERVIDOR:5001`. En la carpeta de instalación,
`herramientas\` tiene copia-seguridad, restaurar-copia, comprobar-servidor y
crear-admin. Ver `docs/13-despliegue-servidor.md`.

## Pruebas y paridad

### Backend

`mvnw.cmd verify` ejecuta, entre otras, estas suites:

| Suite | Cobertura principal |
|---|---|
| `ParidadLaboratorioTest` | 80 casos de planificación, `DataJson`, orden, KPI, ocupación, histórico, cierre, Dashboard (tabla de ocupación, gráfica por CW y KPI por responsable), KPI History (fotos por organización y estadísticas con filtros) y equipos auxiliares (asignar, quitar, renombrar) frente a la 4.9.4. |
| `ValoresYJsonTest` | Valores, JSON, festivos y semanas ISO compatibles con JavaScript/Python. |
| `ApiLocalTest` | API compatible y API v1, conflicto 409, forzado, reglas 422, `/clasico` y salud (`/api/v1/salud`, S4). |
| `RelojTest` | Cambio de día en la zona `Europe/Madrid` y fecha fija. |
| `ProteccionListasTest` | Protección ante lecturas vacías y listas vaciadas. |
| `ListasProtegidasTest` | Protección de punta a punta sin alterar los ficheros locales. |
| `ReinicioLocalTest` | Reinicio controlado del escenario local usado por las pruebas web. |
| `CicloDeVidaTest` | Apagado solo al cerrar la última pestaña: minimizar u ocultar no la da de baja; recargar no apaga. |
| `SoloLecturaIntegracionTest` | Como usuario de solo lectura: las consultas de todas las pantallas responden y los filtros cambian el resultado; las escrituras devuelven 403 sin tocar datos. |
| `ExcelGanttTest` | El `.xlsx` generado en Java coincide, XML a XML, con las referencias sacadas del generador Python de la 4.9.4 (banco, Dashboard, responsable, equipos y foco); nombre del fichero y casos límite. |
| `CopiaSeguridadTest` | Copia JSON: exportar devuelve el estado con el formato de la 4.9.4; importar exige Admin, rechaza ficheros sin `benches`, respeta la protección de listas y una ida y vuelta no cambia nada. |
| `CopiaSinConexionTest` | Consulta sin conexión: se guarda la última lectura buena, se muestra si SharePoint falla, una lista vacía no pisa la copia y con la copia no se habilita ningún guardado (D11). |
| `ConfiguracionBancosTest` | Test Benches: renombrar conserva ensayos, borrar los borra, mover u Org no tocan ensayos, solo Admin, no se renombra a un banco existente ni se vacía la lista. |
| `LecturaCompartidaTest` | Varias peticiones a la vez leen SharePoint una sola vez; cada una recibe su copia; tras escribir no se reutiliza lo leído antes; un fallo o una lectura parcial no se guardan. |
| `ConexionEdgeTest` | Una consulta no espera detrás de un guardado largo ni de otra consulta; las escrituras van siempre por la conexión principal; la consulta de una pestaña cerrada se descarta y una escritura nunca. |
| `ClienteSharePointTest` | La sesión se comprueba como mucho una vez por minuto, columnas y FormDigest se reutilizan, una sesión caducada se recupera y desde una consulta no se puede escribir. |
| `AprobacionesSharePointTest` · `CatalogosSharePointTest` | Estados de aprobación por lotes con el mismo resultado por ID; catálogos y usuarios leídos una sola vez al arrancar. |
| `CopiaSinConexionConcurrenciaTest` | 32 lecturas simultáneas no chocan al guardar la copia sin conexión (sin `.tmp` sueltos) y solo se reescribe si cambia el contenido. |
| `ManejadorErroresTest` · `FiltroPestanaTest` | Una petición abandonada al recargar no deja avisos; solo las consultas se asocian a la pestaña. |
| `AreasTest` · `AreasIntegracionTest` | Áreas de bancos: columna `Area` (vacía = Thermal), Org fija en HVCH, filtros por área y Org, histórico de KPI con semanas antiguas, foto semanal con seis filas y conservación del área al guardar desde `/clasico`. |
| `HojaMensualTest` | Facturación: regresión con enero de 2026 del Excel (25 «Coste total» y las 34 filas de la hoja ENERO) y reglas de la hoja mensual (meses, parada, setup, piezas, STC, proyecto 0, incompletas). |
| `ApiFacturacionTest` | Facturación en local: Equipment Types (guardar, validar), hoja mensual y exportación Excel/HTML. |
| `ContratoAlmacenesLocalTest` · `ContratoAlmacenesPostgresTest` | Las mismas 15 pruebas de almacén contra el modo local y contra PostgreSQL (orden y texto exacto, conflicto, nada a medias, catálogos, D11, usuarios, recursos, KPI, tipos de equipo, aprobaciones, actividad, versión, presencia). |
| `*PostgresTest` de `integracion/` | `ApiLocalTest`, `AreasIntegracionTest`, `ConfiguracionBancosTest`, `CopiaSeguridadTest`, `OperacionesInmediatasTest`, `SoloLecturaIntegracionTest` y `ReinicioLocalTest` repetidas con `planner.almacen=postgresql`; `ListasProtegidasPostgresTest` comprueba D11 sobre las tablas. |
| `ImportacionPostgresTest` · `ParidadApiPostgresTest` | Exportar → importar (huellas, verificación, idempotencia, simulación) y paridad de 23 respuestas de la API entre local y PostgreSQL antes y después de las mismas escrituras. |
| `ModoServidorPostgresTest` · `ComprobacionModoServidorTest` · `FiltroModoServidorTest` | Modo servidor (S2): sin `/clasico` ni API antigua, no arranca sin PostgreSQL o con el reinicio de pruebas, calendario común solo Admin con auditoría, plantilla de Export read-only, salud sin sesión y sin datos (S4). `CicloDeVidaTest` comprueba además que en el servidor cerrar pestañas no apaga. |
| `InicioSesionPostgresTest` · `InactividadPostgresTest` · `CuentasPostgresTest` · `FiltroOrigenTest` | Inicio de sesión (S3): sin sesión no se ve nada, rol por persona, auditoría con quien hace el cambio, bloqueo y desbloqueo, contraseña temporal con cambio obligatorio, sesiones cerradas al cambiar la contraseña o salir de la lista, inactividad (los sondeos no cuentan), otro origen rechazado, primer Admin. |
| `EstructuraYPermisosPostgresTest` · `ConcurrenciaPostgresTest` | Flyway y permisos (`tbp_app` sin DDL, `tbp_consulta` solo lectura, migración retocada = no arranca); guardados simultáneos: gana uno, el resto recibe conflicto. |
| `GeneradorIdsTest` · `OperacionesInmediatasTest` · `ServicioPlanificacionLatenciaTest` | Altas y copias guardadas con el id que la interfaz ya muestra, sin relectura extra y con las reglas de siempre. |

Las salidas de paridad quedan en `backend/target/paridad` y pueden volver a
compararse con:

```bash
node herramientas/laboratorio/comparar.cjs backend/target/paridad
```

### Interfaz React

Las pruebas web requieren Node.js y Playwright:

```bash
node herramientas/laboratorio/backend-simulado-4.9.4.cjs 5001
TZ=Europe/Madrid node herramientas/laboratorio/comparar-interfaz.cjs
TZ=Europe/Madrid node herramientas/laboratorio/pruebas-interfaz.cjs
TZ=Europe/Madrid node herramientas/laboratorio/comparar-panel.cjs
TZ=Europe/Madrid node herramientas/laboratorio/pruebas-ciclo-de-vida.cjs
TZ=Europe/Madrid node herramientas/laboratorio/pruebas-cierre.cjs
TZ=Europe/Madrid node herramientas/laboratorio/pruebas-rendimiento.cjs
TZ=Europe/Madrid node herramientas/laboratorio/pruebas-facturacion.cjs
TZ=Europe/Madrid node herramientas/laboratorio/pruebas-areas.cjs
TZ=Europe/Madrid node herramientas/laboratorio/pruebas-gantt.cjs
cd frontend && npm test        # pruebas unitarias (altas y copias inmediatas, cola de configuración, Gantt general)
```

El comparador de interfaz recorre 65 casos de edición, fechas, estados, cola,
cierre y roles. `pruebas-interfaz.cjs` cubre además conflicto, desconexión, modo
solo lectura, filtros de consulta en todas las pantallas migradas y el flujo de reinicio local. `comparar-panel.cjs` ejecuta 94 casos
de Dashboard, «By Responsible», KPI History, equipos auxiliares, configuración y
exportaciones en la 4.9.4 y en React con los mismos gestos y compara lo que se
ve: KPI, barras del Gantt (posición, color, texto), puntos de la gráfica, tablas,
selector y ventanas, avisos y mensajes. En los casos de exportación compara
además lo descargado: la matriz enviada para el Excel, el CSV byte a byte, la
copia JSON y lo incrustado en la foto de solo lectura. El contenido del Excel se
comprueba aparte con `herramientas/laboratorio/excel_espejo.py` y
`comparar_excel.py` (ver `referencias-excel/LEEME.md`). `pruebas-ciclo-de-vida.cjs` comprueba que ocultar o
minimizar no da de baja la pestaña y que cerrarla sí. `pruebas-cierre.cjs` cubre
lo añadido en H3Z: presencia, aviso de versión, bienvenida, modo sin conexión,
calendario, sugerencias, tirador del Gantt y orden de Users.
`pruebas-rendimiento.cjs` simula un guardado lento (2,5 s) y comprueba que
añadir y duplicar se ven al momento, que lo escrito en la fila nueva se guarda,
que un fallo la retira y que cada pestaña envía su código.
`pruebas-areas.cjs` cubre las áreas de bancos: Test Benches por áreas, mover una
categoría, menú lateral, filtro de dos niveles, renombrar un área y filtro Org de los equipos.
`pruebas-gantt.cjs` cubre el Gantt general rediseñado (H8): carriles sin solapes,
color por proyecto y «Color: Estado», formas de estado, categorías plegables,
tarjeta y tecla F, foco con ficha y trazabilidad, y clic que abre el ensayo;
y la altura libre (H9): sin scroll vertical propio, cabecera y barra horizontal
flotantes, y divisor del banco que desplaza el Gantt sin comprimirlo.

## Configuración

Propiedades principales de Spring Boot:

| Propiedad | Valor por defecto | Uso |
|---|---|---|
| `planner.almacen` | `local` | Adaptador `local`, `sharepoint` o `postgresql`. |
| `planner.fecha-fija` | vacío | Simula la fecha actual con formato `aaaa-mm-dd`. |
| `planner.carpeta-usuario` | `~/.testbenchplanner` | Datos locales, calendario y perfil de Edge. |
| `planner.local.escenario-inicial` | `../datos/escenarios/sharepoint-2026-11-05` | Escenario inicial del modo local. |
| `planner.local.reiniciar-al-arrancar` | `false` | Restaura el escenario al iniciar. |
| `planner.local.usuario-email` | `laboratorio@ejemplo.local` | Identidad simulada del modo local. |
| `planner.sharepoint.sitio` | vacío | Sitio principal; obligatorio con SharePoint. |
| `planner.sharepoint.sitio-aprobaciones` | vacío | Sitio de la base de aprobaciones. |
| `planner.sharepoint.escritura-habilitada` | `false` | Habilita las escrituras en las listas. |
| `planner.sharepoint.publicar-version` | `false` | Publica la versión en `TestBenchAppConfig` al arrancar. Mantener en `false` hasta distribuir la versión nueva: con `true`, los PCs con la 4.9.4 verán el aviso de versión antigua. |
| `planner.sharepoint.espejo-habilitado` | `true` | Actualiza columnas espejo cuando se permite escribir. |
| `planner.sharepoint.puerto-depuracion` | `9222` | Puerto CDP de Edge. |
| `planner.sharepoint.carriles-lectura` | `2` | Conexiones de solo lectura con Edge en paralelo a la principal (que hace todas las escrituras). `0` vuelve al comportamiento anterior (todo por una sola conexión). |
| `planner.servidor.activo` | `false` | Modo servidor (lo activa el perfil `servidor`, que además fija `server.address: 0.0.0.0` y `planner.almacen: postgresql`). Ver `docs/11-modo-servidor.md`. |
| `server.address` / `server.port` | `127.0.0.1` / `5001` | Dónde escucha (en el servidor, `config/application-servidor.yml`). |
| `planner.postgresql.*` | ver `docs/10-servidor-postgresql.md` | URL, esquema, usuarios y claves (`tbp_app`, `tbp_owner`), `migrar-al-arrancar`, identidad fuera del modo servidor. |
| `planner.autenticacion.*` | `5` / `15m` / `30m` / `10` | Modo servidor (S3): `intentos-maximos`, `bloqueo`, `inactividad`, `longitud-minima-clave`. Ver `docs/12-inicio-de-sesion.md`. |
| `planner.migracion.exportar` / `importar` / `simular` | vacío / vacío / `false` | Migración en dos pasos (los scripts de `scripts/servidor/` las rellenan). |
| `planner.listas.permitir-vacias` | `false` | Permite guardar sobre listas compartidas vacías solo en una instalación nueva. |

## APIs e interfaces

- **`/`**: frontend React compilado. Si `frontend/dist` no está disponible, se
sirve la interfaz clásica (en modo servidor, un aviso «Falta la interfaz»).
- **`/clasico`**: HTML 4.9.4 original sobre el backend Java. **No disponible en
modo servidor** (D48), igual que la API antigua `/api/*` salvo `/api/heartbeat` y `/api/shutdown`.
- **`GET /api/v1/exportaciones/plantilla-solo-lectura`**: el mismo HTML, base de 📤 Export read-only.
- **`/api/v1/calendario`**: con PostgreSQL es el calendario común (`compartido: true`);
cambiarlo exige Admin (D46).
- **`/api/v1/*`**: API usada por React para sesión, planificación, campos,
operaciones, cierre, configuración, aprobaciones, recursos e indicadores
(`/api/v1/indicadores/bloques`, `tabla-ocupacion`, `tendencia`, `foto-semanal`,
`historico`, `estadisticas`), equipos (`/api/v1/recursos`, `recursos/bloqueos`,
`recursos/renombrar`), presencia y versión (`/api/v1/presencia`,
`/api/v1/version`) y exportaciones (`POST /api/v1/exportaciones/gantt`,
`GET /api/v1/exportaciones/copia`, `POST /api/v1/importaciones/copia`).
`/api/export_gantt` (contrato 4.9.4) usa el mismo generador de Excel.
- **`/api/v1/facturacion/*`** (H6): `GET/PUT /tipos-equipo`, `GET /hoja?mes=aaaa-mm[&niveles=…]`,
`GET /hoja/exportar?mes=…&formato=excel|html`. (Antes, H5:) facturación (maestros, periodos, acciones de línea,
envío, aprobación, reapertura, eliminar periodo, exportaciones, auditoría y ficha
«Billing Data»). `GET/PUT /api/v1/catalogos` lleva también `areaNames` y `benches[].area` (H7), `projectOrders`
(columna BillingOrder; cambiarla exige Admin) y `benches[].equipmentType` (columna EquipmentType).
- **`/api/*`**: contrato compatible con el backend Python para mantener operativa
la interfaz 4.9.4.
- **`/api/v1/local/reinicio`**: reinicio del escenario para el laboratorio;
solo existe con `planner.almacen=local` o, para las pruebas, con
`planner.almacen=postgresql` y `planner.postgresql.reinicio-pruebas=true`
(vacía la base y carga el escenario).

> `/clasico` conserva el modelo de concurrencia de la 4.9.4: el último guardado
gana. No debe utilizarse al mismo tiempo que la interfaz React sobre los mismos
ensayos. El aviso de conflicto y la comprobación de versión pertenecen a
`/api/v1`.

## Reglas de seguridad y trabajo

- En desarrollo y pruebas, SharePoint se usa **solo para lectura**.
- La escritura está desactivada por defecto y toda operación pasa por la
protección `planner.sharepoint.escritura-habilitada`.
- No se crean listas ni columnas desde Java.
- `datos/privado/`, `backend/config/` y los perfiles de Edge no se versionan.
Tampoco las carpetas de exportación (datos reales) ni ninguna clave de base de datos.
- La estructura de la base de datos solo cambia con una migración Flyway nueva;
nunca a mano en el servidor ni editando una migración ya aplicada.
- Los escenarios versionados deben estar anonimizados.
- `legacy/` no se modifica: es la referencia funcional.
- Una funcionalidad solo se considera migrada cuando no presenta diferencias
sin clasificar frente al laboratorio.
- Las diferencias aprobadas se registran en
`herramientas/laboratorio/cambios-aprobados.json` (datos) y
`herramientas/laboratorio/cambios-aprobados-interfaz.json` (pantallas).
- **Solo lectura en la interfaz:** `soloLectura` se aplica solo a las acciones
que modifican datos (editar, ordenar, añadir, borrar, cerrar, asignar, guardar
catálogos…). Nunca se deshabilitan contenedores completos ni controles de
consulta (filtros, búsquedas, semanas, rangos, zoom, foco, recargar). Toda
escritura se comprueba también en el backend (403).
- La facturación solo escribe en la planificación el objeto `DataJson.billing`
del ensayo; sus periodos, maestros y auditoría van en su propio almacén. La orden
de cada proyecto y la categoría de cada banco van en las columnas `BillingOrder`
(TestBenchProjects) y `BillingCategory` (TestBenchBenches), que crea a mano un
Admin del sitio (la aplicación no cambia el esquema de las listas).
- Áreas de bancos: el área de cada categoría va en la columna `Area` de
TestBenchBenches (texto de una línea, vacía = Thermal Benches), que también crea
a mano un Admin del sitio antes de pasar la primera categoría a un área HVCH. Los
nombres de área renombrados van en `TestBenchAppConfig` (`AreaName:<código>`).

## Documentación

- [`docs/00-plan-aprobado.md`](docs/00-plan-aprobado.md): alcance, decisiones e
hitos aprobados.
- [`docs/01-inventario-funcional.md`](docs/01-inventario-funcional.md): inventario
de la versión 4.9.4.
- [`docs/02-discrepancias.md`](docs/02-discrepancias.md): discrepancias conocidas
y resolución adoptada.
- [`docs/03-arquitectura.md`](docs/03-arquitectura.md): detalle de módulos,
correspondencia con la 4.9.4 y diseño de React.
- [`docs/04-ejecucion-y-pruebas.md`](docs/04-ejecucion-y-pruebas.md): comandos de
ejecución, configuración y validación manual.
- [`docs/05-auditoria-cierre.md`](docs/05-auditoria-cierre.md): auditoría función
a función de la 4.9.4 y resolución de los huecos (H3Z).
- [`docs/05-defectos-postmigracion.md`](docs/05-defectos-postmigracion.md):
defectos heredados y mejoras no bloqueantes, con el seguimiento por hito.
- [`docs/06-rendimiento-sharepoint.md`](docs/06-rendimiento-sharepoint.md):
causas y correcciones de los retrasos con SharePoint (H3R).
- [`docs/07-facturacion.md`](docs/07-facturacion.md): análisis, diseño, reglas,
pantallas, pruebas y pendientes de la facturación (H5).
- [`docs/08-areas-de-bancos.md`](docs/08-areas-de-bancos.md): áreas de bancos
(Thermal Benches, HVCH - CTAG, HVCH - STC): datos, filtros, pantallas, KPI y pruebas (H7).
- [`docs/09-gantt-rediseno.md`](docs/09-gantt-rediseno.md): Gantt general
rediseñado (propuesta A + trazabilidad + tarjetas): qué se ve, cómo se deduce la
trazabilidad, paridad y pruebas (H8).
- [`docs/10-servidor-postgresql.md`](docs/10-servidor-postgresql.md): servidor
con PostgreSQL (S1): diseño de datos, usuarios de base de datos, Flyway,
exportación/importación, paridad, pruebas y lo aprendido en la prueba real.
- [`docs/11-modo-servidor.md`](docs/11-modo-servidor.md): modo servidor (S2):
qué cambia, cómo se activa, puesta en marcha, calendario común y pruebas.
- [`docs/12-inicio-de-sesion.md`](docs/12-inicio-de-sesion.md): inicio de sesión
(S3): cuentas, primer Admin, bloqueo, inactividad, API, prueba de cierre y límites.
- [`docs/13-despliegue-servidor.md`](docs/13-despliegue-servidor.md): despliegue
(S4): instalación como servicio, HTTPS y certificado, copias, restauración,
actualización, logs, salud, prueba de cierre y problemas frecuentes.
- [`docs/16-timing-rt.md`](docs/16-timing-rt.md): perfiles, importación de tareas,
  piloto y corte de Timing R&T.
- [`herramientas/laboratorio/README.md`](herramientas/laboratorio/README.md): uso
del laboratorio de paridad.
