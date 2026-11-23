# 15 · Horario de los ensayos (Working Hours) y facturación por horas

Petición de Pedro: «FacturacionHorasYLabUser.docx» (Fase 2, 18-nov-2026).
Discrepancias D58 (Lab User configura bancos), D59 (Working Hours) y D60
(facturación por horas). **Solo con PostgreSQL** (servidor): con SharePoint y en
local no hay horarios y la facturación es la de siempre.

## 1. Qué es

Pestaña **Configuration › 🕒 Test Calendar / Working Hours**. Todos la ven; solo un
**Admin** o un **Lab User** la cambia. Arriba se elige el banco (agrupados por
área); a la izquierda, sus ensayos (abiertos primero y, si se marca, los cerrados de
los últimos 3 meses) con fechas, opción Weekend, jornada y ⚠ si Working Hours
contradice la opción Weekend; a la derecha, el editor del ensayo elegido.

| Campo | Valores | Por defecto |
|---|---|---|
| Workday | 24 h (00:00–24:00), 8 h un turno de mañana (06:00–14:00) o de tarde (14:00–22:00), 16 h dos turnos (06:00–22:00) | 24 h |
| Start time | Hora del primer día, en medias horas dentro de la jornada | Inicio de la jornada (00:00 o 06:00) |
| End time | Hora del último día, en medias horas dentro de la jornada | Fin de la jornada (24:00, 14:00 o 22:00) |
| Weekends & holidays | As task / Work weekends & holidays / No weekends & holidays | As task |
| Setup hours | ≥ 0, en medias horas | El valor antiguo de la columna Setup (`setupHours`) |
| Stops | Por franjas (rejilla) o por horas | Ninguna |
| Note | Texto libre | — |

- **As task**: Weekend = Yes → se trabajan fines de semana y festivos (como en la
  planificación); Weekend = No → no se trabajan sábados, domingos ni los festivos del
  calendario común. Si se elige otra cosa que la opción Weekend del ensayo, la tabla
  del banco enseña ⚠ junto a Weekend con la explicación (la planificación y el Gantt
  siguen la opción Weekend; la facturación, Working Hours).
- **Planta abierta (06:00–22:00)**: aunque el banco funcione 24 h, de noche no hay
  nadie. El setup y las paradas que **afectan a los turnos** solo avanzan con la planta
  abierta (y en un día que se trabaja); la noche queda dentro de la parada.
- **Setup**: consume tiempo con la planta abierta desde el arranque; **no se factura**
  y el inicio facturable es cuando acaba (puede pasar al día o turno siguiente). Con
  jornada de 24 h y arranque a las 00:00, un setup de 8 h va de 06:00 a 14:00 y no se
  factura nada antes de las 14:00. Si coincide con una parada, el setup espera a que
  termine.
- **Paradas** (se guardan todas como paradas):
  - **Rejilla**: una fila por día y 48 medias horas; clic o arrastrar marca o quita
    medias horas paradas. Gris = no se trabaja (fuera de la jornada o día no
    laborable), azul = setup, verde = facturable, rojo = parada.
  - **Por horas**: inicio (fecha y media hora), duración (múltiplo de 0,5), motivo
    opcional y **Affects shifts**.
  - Cualquier parada de la lista se puede **modificar** (✏️: se carga en el formulario,
    *Update stop*) o borrar. Todo se guarda junto con **Save all changes**.
- El **Gantt, la ocupación y los conflictos no cambian**: el banco sigue ocupado.

### Ejemplo del documento (jornada de 16 h, 06:00–22:00)

Parada de 8 h que empieza a las 18:00:

| Affects shifts | Qué pasa | Se reanuda | No facturable |
|---|---|---|---|
| No | 8 h de reloj: 18:00 → 02:00 | 02:00 (se factura desde las 06:00, inicio del turno) | 18:00–22:00 (4 h de turno) |
| Sí | Solo cuenta el tiempo de turno: 4 h hasta las 22:00 (cierra la planta) y 4 h desde las 06:00 | 10:00 | Todo el tramo 18:00 → 10:00 (16 h; 8 h de turno) |

Con jornada de **24 h**, parada de 8 h a las 20:00:

| Affects shifts | Qué pasa | Se reanuda | No facturable |
|---|---|---|---|
| No | 8 h de reloj: 20:00 → 04:00 | 04:00 | 8 h |
| Sí | 2 h hasta las 22:00, la noche sigue parada y 6 h desde las 06:00 | 12:00 | Todo el tramo 20:00 → 12:00 (16 h) |

### Billing preview

Por periodo: **Hours** = horas de trabajo según el horario (jornada, fines de semana, inicio y fin), sin descontar nada; **Deducted** = setup y paradas (y la Parada (d) antigua si no hay paradas aquí); **Net hours** = lo que se factura (lo mismo que la hoja mensual).

## 2. Facturación por horas (D60)

Desde el mes **`FacturacionHorasDesde`** (Billing › Configuration › *Hour-based
billing*, solo Admin) la hoja mensual calcula así las **horas netas** de los Equipment
Types por horas:

> medias horas dentro de [inicio facturable (fin del setup), fin de la tarea) que
> caen en un día que se trabaja, dentro de la jornada y fuera de las paradas, en el
> periodo de facturación (día 21 del mes anterior a las 00:00 → día 21 a las 00:00);
> horas = medias horas / 2. Las paradas que se solapan cuentan una vez.

- El inicio y el fin de la tarea son los de siempre (Arranque Real / Fin Real o,
  si faltan, Test Start y hoy), con las horas de Working Hours.
- **Setup**: no se suma; la columna Setup de la hoja sale a 0.
- **Parada (d)** (ventana de cierre, columna «Parada (d)»): si el ensayo **no** tiene
  paradas en Working Hours se resta como días × horas de la jornada (24, 8 o 16),
  repartida entre periodos en proporción a sus horas facturables; si las tiene, se
  ignora y la línea avisa «Parada (d) ignorada: hay paradas en Working Hours».
- **Por piezas** (RACS): igual que siempre: las piezas de la columna **Pieces**
  (campo `setupHours`) en el periodo en que empieza la tarea.
- **Meses anteriores** a `FacturacionHorasDesde` (ya facturados): exactamente el
  cálculo de antes (días completos × 24 + setup − Parada × 24).
- La migración V4 pone como `FacturacionHorasDesde` el mes de facturación en curso
  el día que se aplica (día 21 o más → el mes siguiente). Vacío = todo por días.
- Con todo por defecto (24 h, Weekend = Yes, sin setup ni paradas) da lo mismo que
  antes. Con Weekend = No ya **no** se facturan fines de semana ni festivos, y el setup
  ya no se factura (avisado a Pedro).

En la hoja, el resumen dice si el mes va «por horas» y el detalle marca cada tarea
calculada por horas («· h», con el inicio facturable en el tooltip).

## 3. Tabla del banco

- La columna **Setup (h)** pasa a llamarse **Pieces**: solo se edita en los bancos
  cuyo Equipment Type factura por piezas (el del año en curso o, si no está, el del
  último año). En los demás muestra «—» y la ayuda «Setup hours are now in
  Configuration › Test Calendar / Working Hours». El campo y su validación no cambian.
- ⚠ junto a **Weekend** si Working Hours lo anula.
- La columna **Parada (d)** se mantiene.

## 4. Dónde se guarda

| Dato | Dónde |
|---|---|
| Horario de cada ensayo | Tabla `horario_ensayo` (V4): `ensayo_id` (sin clave foránea), `jornada`, `turno` (V5: `manana`/null o `tarde`, solo con 8 h), `hora_inicio`/`hora_fin` («HH:MM», null = por defecto), `fin_de_semana` (`si`/`no`/null), `setup_horas` (null = el valor antiguo), `paradas` (jsonb), `nota`, `modificado_por`, `modificado_en`, `version` |
| Primer mes por horas | `configuracion_app`, clave `FacturacionHorasDesde` («aaaa-mm») |
| Cambios | `auditoria`, entidad `horario` (ALTA / MODIFICACION / BAJA) y `configuracion_app` |

Formato de `paradas`: `[{"inicio": "2026-11-16T18:00", "horas": 8, "afectaTurnos": true,
"metodo": "horas", "motivo": "Avería"}, {"inicio": "2026-11-17T06:00", "fin":
"2026-11-17T07:30", "afectaTurnos": false, "metodo": "franjas"}]`.

- **Reimportar** desde SharePoint no toca `horario_ensayo` (como `credencial`). El
  reinicio de pruebas (`/api/v1/local/reinicio`) sí la vacía, y también
  `configuracion_app` (entonces no hay facturación por horas hasta que un Admin elige
  el mes).
- **Copia JSON** (Tools › Export): si hay horarios, añade `workingHours` {id: horario};
  al importar una copia que lo trae se sustituyen todos los horarios. Una copia sin
  `workingHours` los conserva.

## 5. API (`/api/v1`)

| Método y ruta | Quién | Qué |
|---|---|---|
| `GET /horarios/gestion` | Todos | `{editable, puedeEditar, facturacionHorasDesde, jornadas}` |
| `GET /horarios?banco=X` | Todos | Ensayos del banco con su horario (o el de por defecto con el setup antiguo), fechas, Weekend, `version` |
| `GET /horarios/avisos` | Todos | `{bancosPorPiezas, ensayos: {id: {jornada, anulaFinDeSemana, trabajaFinesDeSemana}}}` (tabla del banco) |
| `POST /horarios/{id}/vista-previa` | Todos | Con `{horario}` (sin guardar) o `{}` (el guardado): inicio facturable, horas por periodo, setup, paradas, rejilla |
| `PUT /horarios/{id}` | Admin, Lab User | `{horario, anterior: {version}}`; 422 si otra persona lo ha cambiado o no cumple las reglas |
| `POST /horarios/{id}/restablecer` | Admin, Lab User | `{anterior: {version}}`: vuelve a los valores por defecto |
| `GET /facturacion/horas-desde` | Todos | `{editable, facturacionHorasDesde}` |
| `PUT /facturacion/horas-desde` | Admin | `{mes: "aaaa-mm"}` o `{mes: null}` |

Reglas (422): jornada 24/8/16; horas en medias horas dentro de la jornada (el mismo
día, inicio < fin); setup ≥ 0 en medias horas; paradas que empiezan en una media hora
dentro de las fechas del ensayo, franjas con fin > inicio y dentro del ensayo,
duraciones > 0 en medias horas; motivo ≤ 200 y nota ≤ 2000 caracteres.

## 6. Probar en local

1. `cd backend; .\mvnw.cmd clean verify` (incluye `HorasTrabajoTest`, `HojaMensualTest`,
   `HorariosPostgresTest` y `EstructuraYPermisosPostgresTest` con un PostgreSQL temporal).
2. `..\scripts\servidor\arrancar-servidor.cmd` (PostgreSQL 16 del portátil): Flyway
   aplica V4 al arrancar. Entrar en `http://localhost:5001`.
3. Con un **Lab User**: Configuration › Test Calendar / Working Hours → banco → ensayo
   en curso → 16 h, setup 2 h, parada de 8 h a las 18:00 con *Affects shifts* →
   la vista previa debe mostrar la parada de 18:00 a 10:00. Save.
4. En el banco: la columna Weekend muestra ⚠ si se cambió «Weekends & holidays»; la
   columna Pieces muestra «—» salvo en bancos por piezas.
5. Con un **Editor** (ventana privada): la pestaña se ve pero no se puede guardar.
6. Billing › Configuration (Admin): *First month billed in hours*. Billing › Monthly
   sheet de ese mes: «Hours from Working Hours»; el mes anterior, «Whole-day calculation»
   con los mismos importes que antes.
