
# Timing R&T

Timing R&T funciona en el perfil servidor, con la misma sesión de TestBenchPlanner y la migración Flyway V6. Su pestaña aparece junto a TestBenchPlanner cuando la cuenta tiene al menos un perfil del módulo.

## Preparar el piloto

1. Preparar una instancia de prueba con PostgreSQL y la interfaz React compilada. En **Configuration → Users/Rights Management**, dar acceso de **Solicitante**, **Técnico** o **Coordinador** según corresponda. El administrador que vaya a importar también necesita al menos un perfil. Un técnico puede tener rol **Read only** en TestBenchPlanner.
2. Revisar **Configuration → Projects → Project Number** para los proyectos activos. Es la cuenta usada en Timing R&T y conserva los ceros iniciales.
3. En **Timing R&T → Configuración → Importar tareas**, elegir el Excel de exportación, con la hoja `query`, y pulsar **Vista previa**. Revisar los recuentos, las correspondencias y los avisos. El importador busca responsables y técnicos por correo o nombre en el directorio de TestBenchPlanner. Para proyectos usa el nombre y número, o un número inequívoco si el nombre no coincide. Los datos por conciliar se notifican y se conserva el estado del Excel.
4. Aplicar la importación en la instancia de prueba. En **Planificación**, comprobar las colas de **Análisis** y **Bancos**, completar los datos de tareas activas y asignar a los técnicos. Con una cuenta técnica, registrar un avance y cerrar una tarea. Comprobar sus horas, comentario e historial.
5. Revisar las horas heredadas con el laboratorio. `Horas_Tarea`, `Horas_pendientes` y `Horas_Totales` se pueden consultar en **Datos originales del Excel** junto con la prioridad y demás campos originales. Las horas registradas de la nueva aplicación salen de los avances. El `OrdenDIario` del Excel se importa tal cual como orden diario de la tarea (ver «Orden diario»).

## Corte

1. Una vez validado el piloto, preparar la versión del servicio con V6 y hacer una copia de PostgreSQL. Usar una base de producción separada de la base de pruebas.
2. Congelar la edición en Timing R&T antiguo y generar la exportación final. Simular y cotejar los recuentos. Resolver las correspondencias de las tareas activas antes de ponerlas disponibles.
3. Aplicar la importación y habilitar la operativa en la nueva aplicación. La recarga usa la columna `ID` de origen. Si una tarea ya se modificó en la aplicación, la importación mantiene esa versión y comunica el conflicto. Los códigos visibles duplicados se conservan. La carga no elimina tareas ausentes en el fichero.

En instalaciones que ya importaron el Excel, Flyway V7 recupera automáticamente los estados «Sí» y «En proceso» de las filas importadas que seguían sin modificaciones y habían quedado no disponibles. Para revisar las filas editadas, usa el selector **Estado** y los datos originales de cada tarea.

## Vinculación con usuarios y proyectos

Al importar el Excel, cada tarea guarda el nombre original del responsable y del técnico y el proyecto y número originales. Si en ese momento no hay un usuario o un proyecto que corresponda, el campo queda vacío y el nombre original se conserva.

La vinculación se repite sola:

- al guardar **Configuration → Users/Rights Management** (alta de personas, cambio de nombre o de perfiles);
- al guardar **Configuration → Projects** o el **Project Number** de un proyecto;
- al arrancar el servidor;
- con **Timing R&T → Configuración → Datos sin vincular → Comprobar ahora**.

Reglas:

- Personas: el nombre del Excel se compara con el correo, el nombre y el nombre de responsable del usuario, sin distinguir acentos ni mayúsculas («Laura Ézara» = «Laura Ezara»). Si coincide con varios usuarios no se vincula.
- Proyectos: mismo nombre y número, o un número que solo tenga un proyecto del catálogo. La tarea pasa a mostrar el nombre y el número del catálogo; el número del Excel queda guardado. Los números distintos (por ejemplo 80834 frente a 80000834) se resuelven a mano.
- Solo se rellena lo que está vacío. Un responsable, técnico o proyecto elegido a mano en la aplicación no se cambia nunca.
- La vinculación queda en el historial de la tarea («Vinculación con TestBenchPlanner») y no cuenta como edición: una nueva importación del Excel sigue actualizando la tarea sin conflicto.

**Datos sin vincular** (coordinador o admin con perfil) lista los nombres y proyectos del Excel pendientes, con cuántas tareas tienen y cuántas están abiertas. Al vincular uno a mano se guarda una correspondencia que se aplica a todas sus tareas y que también usa la importación. Cambiar una correspondencia reasigna las tareas que se vincularon con ella; quitarla las devuelve a la correspondencia automática o las deja sin vincular, igual que las dejaría una nueva importación.

La migración V8 crea la tabla de correspondencias (`timing_vinculo`) y guarda el proyecto y número originales en las tareas ya importadas cuyo proyecto no se había cambiado a mano.

## Proyectos: número y nombre por separado (servidor)

- En la base de datos cada proyecto guarda el **nombre sin número** (`proyecto.titulo`) y el **número** (`numero_proyecto`). Hacia fuera la etiqueta sigue siendo «número - nombre» (`NombresProyecto.etiqueta`): es lo que guardan los ensayos, lo que sale en el desplegable Project y de donde la facturación saca el número.
- Migración V9: separa los títulos con la forma exacta «número - nombre» (si el número guardado coincide, no hay choque de nombres y la etiqueta vuelve a dar el mismo texto). El resto se marca con ⚠️ en Configuración › Projects para corregirlo a mano. También pasa al formato largo la clave de las correspondencias manuales de proyectos.
- Configuración › Projects: columnas # · Project Number · Name · Billing order. Admin y Lab User editan número y nombre en la fila (`PUT /api/v1/proyectos/editar`). El cambio conserva id, orden de facturación y posición y se aplica en la misma transacción a todos los ensayos (abiertos y cerrados, solo el campo «project»), a las tareas de Timing R&T del proyecto (historial «proyecto-renombrado») y a las correspondencias manuales. Queda en la auditoría.
- Números de la serie 8 en **formato largo** (8 cifras): 80834 → 80000834, 801786 → 80001786, 800001786 → 80001786. Se aplica al importar el Excel de Timing, al vincular, en las correspondencias manuales y al escribir un número en Projects. Otras series (10766, 00877…) no se tocan.
- Reimportar el Excel no deshace el vínculo de una tarea cuyo proyecto se ha renombrado después en TestBenchPlanner.
- Importación del export de SharePoint (`ImportadorDatos`): separa igual los títulos «número - nombre»; la etiqueta coincide con el título de SharePoint, así que ensayos, órdenes de facturación y verificación no cambian.

## Orden diario

- Como en el Timing R&T original: un único número por tarea y técnico, **sin día**. Menos de 9 = «Orden del día» (0, 1, 2… lo primero); 9 = en cola (valor por defecto al asignar un técnico); 99 o más = genéricas y ausencias.
- Se importa de la columna `OrdenDIario` del Excel. La migración V10 lo copia desde «legado» en las tareas ya importadas (sin contar como edición).
- **Mi cola** del técnico: orden del día, cola y genéricas; dentro de cada tramo, por orden diario y después por prioridad (menor = más urgente).
- **Orden diario** (pestaña solo para el perfil Coordinador): se elige un técnico y se ve su cola en tres bloques. Se reordena arrastrando o con ↑ ↓, y con «Al día» / «A la cola». Nada se guarda hasta «Guardar orden» (`POST /api/v1/timing/orden-diario`, todo o nada con control de versión): el día se numera 1, 2, 3…, la cola queda en 9 y las genéricas en 99. Cada cambio queda en el historial de la tarea («Orden diario cambiado»).
- Al cambiar de técnico una tarea en Planificación, su orden pasa a 9 salvo que se indique otro.
