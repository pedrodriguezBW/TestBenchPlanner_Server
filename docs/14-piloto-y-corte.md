# 14 · Piloto y corte (hito S5)

> **Estado (17-nov-2026): en preparación.** Hecho: aprobaciones gestionadas desde la
> aplicación (apartado 1). Falta cerrar S4 en el PC servidor (`docs/13`, apartado 10)
> antes de empezar el piloto.
> Criterio de cierre del plan (§8): *sin incidencias bloqueantes; SharePoint en solo
> lectura como copia congelada*.

Decisiones del 17-nov-2026 (plan §9): puerto **5001**; como mucho **unas 6 personas
escribiendo a la vez** (el servidor abre hasta 10 conexiones a la base, de sobra);
aprobaciones como **tabla propia que gestiona un Admin desde la aplicación**; sin
nombre DNS (certificado autofirmado, `docs/13` apartado 4).

## 1. Aprobaciones gestionadas desde la aplicación (D54)

Hasta ahora la base de aprobaciones de servicios era una lista de SharePoint de otro
sitio (`processautomation`) que alguien actualizaba **a mano**; la aplicación solo la
leía para colorear los Approval ID y para el buscador. El servidor no puede leer
SharePoint, así que desde el corte esa lista deja de usarse y **la misma persona hace
lo mismo desde TestBenchPlanner**.

| | Cómo queda |
|---|---|
| Dónde | 🔧 Configuration › Tools › **📋 Approval IDs**, o la 🔍 de la columna Approval ID de un ensayo |
| Quién | **Solo Admin** da de alta, cambia y borra. Editores y Read only las ven y las usan como siempre |
| Alta | **＋ New approval**: Requester name (obligatorio), Service details, Global status (Approved, Pending, Rejected, NA). El ID se deja vacío para usar el **siguiente libre** (el más alto + 1, como hacía SharePoint) o se escribe uno concreto si no existe |
| Cambio | ✎ en la fila. Si otra persona la ha cambiado desde que se abrió la lista, no se guarda y avisa: se recarga y se repite |
| Baja | 🗑 en la fila (pide confirmación). Los ensayos que usaban ese ID lo muestran como «no creado» |
| Efecto | Inmediato para todos: los colores de los Approval ID se actualizan solos |
| Registro | Cada alta, cambio y baja queda en `auditoria` (entidad `aprobacion`) con quién, cuándo, antes y después |
| Con SharePoint o en local | Sigue siendo de solo lectura (no aparece ningún botón de edición) |

API (solo Admin; el resto recibe 403): `GET /api/v1/aprobaciones/gestion`
(`{editable}`), `POST /api/v1/aprobaciones` (alta), `PUT /api/v1/aprobaciones/{id}`
(cambio, con `anterior`), `POST /api/v1/aprobaciones/borrar` (`{id, anterior}`).
Datos no válidos o una aprobación cambiada por otra persona: 422 con el aviso.
Pruebas: `AprobacionesPostgresTest`.

**Áreas de bancos (D56)**, igual que las aprobaciones pero para un **Admin o un Lab
User** (D55): `GET /api/v1/areas/gestion` (`{editable, areas:[{code, name,
organization, order, benches}]}`), `POST /api/v1/areas` (`{name, organization}`;
`organization` null = *bench by bench*), `PUT /api/v1/areas/{code}` (`{name, order?,
anterior}`), `POST /api/v1/areas/borrar` (`{code, anterior}`; con bancos, 422).
Registro en `auditoria` (entidad `area`). **Durante el piloto**, una importación
deja los bancos con el área que tienen en SharePoint (los de un área nueva vuelven a
Thermal Benches), pero **no borra** las áreas creadas ni sus nombres: tras el corte
basta con volver a mover sus categorías. Pruebas: `GestionAreasPostgresTest`.

**Durante el piloto**, la verdad sigue siendo SharePoint: cada importación deja las
aprobaciones (y todo lo demás) **igual que la exportación**, así que lo que se edite
en el servidor antes del corte se pierde al reimportar. Las aprobaciones se empiezan a
gestionar en el servidor **después del corte**.

## 2. Piloto (2–3 personas)

Con S4 cerrado (servicio instalado, prueba de cierre en verde):

1. **Datos frescos**: exportación nueva de SharePoint e importación (`docs/10`,
   apartados 5.2 y 5.3).
2. **Cuentas**: en Configuration › Users/Rights Management, comprobar que las personas
   del piloto están con su rol y generar su **contraseña temporal** (`docs/12`).
   Dársela en persona o por Teams; al entrar la cambian.
3. **Certificado**: cada una instala `TestBenchPlanner.cer` (`docs/13`, apartado 4).
4. **Uso real en paralelo** durante unos días, **a la vez que SharePoint**. Lo que se
   cambie en el servidor es de prueba (se pierde al reimportar).
5. Comprobar, como mínimo:

| # | Prueba | Esperado |
|---|---|---|
| P1 | Las 2–3 personas entran a la vez desde sus PCs | Cada una con su nombre y su rol |
| P2 | Dos personas cambian **el mismo ensayo** a la vez | La segunda recibe el aviso de conflicto; no se pierde nada |
| P3 | Varias personas trabajan en bancos distintos a la vez | Sin esperas ni errores; los cambios de los demás aparecen solos en unos 20 s |
| P4 | Un Admin da de alta, cambia y borra una aprobación de prueba | Los demás ven el color del Approval ID cambiar |
| P5 | Facturación, KPI, exportación a Excel y Gantt | Iguales que con SharePoint |
| P6 | Dejar la aplicación 30 min sin tocar | Pide entrar otra vez (D50) |
| P7 | Copia diaria de esos días | Existe en `copias\` |
| P8 | `herramientas\comprobar-servidor.cmd` al final del piloto | «Resultado: BIEN» |
| P9 | Un Lab User cambia el horario de un ensayo en curso (Test Calendar / Working Hours: 16 h, setup, una parada que afecta a los turnos) y otra persona lo cambia a la vez | La vista previa cuadra con `docs/15` (ejemplo 18:00 → 10:00); la segunda recibe «Otra persona ha cambiado…»; un Editor no puede guardar |
| P10 | Billing › Monthly sheet del primer mes por horas y del anterior | El anterior, igual que con SharePoint; el primero, «Hours from Working Hours» con el setup sin facturar. Revisar 2–3 tareas a mano |
| P11 | Weekend ⚠ y columna Pieces en la tabla del banco | ⚠ solo en los ensayos cuyo Working Hours anula Weekend; Pieces editable solo en bancos por piezas |

6. **Incidencias**: anotarlas (qué, quién, cuándo, captura) y mandármelas. Bloqueante
   = impide trabajar o pierde/corrompe datos. No se pasa al corte con ninguna abierta.

## 3. Corte

Elegir un momento sin actividad (p. ej. viernes por la tarde) y avisar antes a todos.

1. **Avisar** de que desde esa hora no se edita en SharePoint ni en la aplicación de
   siempre.
2. **Congelar SharePoint**: el propietario del sitio pone las listas de
   TestBenchPlanner (y la de aprobaciones de `processautomation`) en **solo
   lectura** para todos (permisos de la lista: Lectura). La aplicación no lo hace:
   nunca escribe en SharePoint.
3. **Parar el servicio** para que nadie entre a medias (consola de Administrador):
   `Stop-Service TestBenchPlanner`
4. **Exportación final** desde un PC con permisos de SharePoint
   (`exportar-desde-sharepoint.cmd`).
5. **Importación final** en el servidor: primero `simular`, después de verdad. El
   informe tiene que decir que la paridad cuadra.
6. **Copia de seguridad** inmediata: `herramientas\copia-seguridad.cmd`, y guardar
   también la carpeta de la exportación final en un sitio seguro (datos reales).
7. **Arrancar**: `Start-Service TestBenchPlanner` y `comprobar-servidor.cmd`.
8. **Abrir a todos**: dirección `https://NOMBRE-DEL-PC:5001`, certificado, contraseña
   temporal de cada persona. Retirar los accesos directos a la aplicación de siempre.
9. Desde ese momento, las aprobaciones se gestionan en la aplicación (apartado 1).
   Comprobar en Billing › Configuration el **primer mes por horas** (D60): la
   importación final no lo toca, pero conviene que sea el primer mes que se va a
   facturar desde el servidor.

**Vuelta atrás**: SharePoint queda intacto y congelado. Si en los primeros días
apareciera algo bloqueante, se puede volver a abrir SharePoint, pero lo cambiado en el
servidor desde el corte habría que pasarlo a mano (no hay sincronización de vuelta;
plan §10).

## 4. Cierre de S5

- Piloto sin incidencias bloqueantes abiertas.
- Corte hecho y SharePoint en solo lectura.
- Una semana de uso normal con copias diarias correctas.
