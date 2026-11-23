
# Timing R&T

Timing R&T funciona en el perfil servidor, con la misma sesión de TestBenchPlanner y la migración Flyway V6. Su pestaña aparece junto a TestBenchPlanner cuando la cuenta tiene al menos un perfil del módulo.

## Preparar el piloto

1. Preparar una instancia de prueba con PostgreSQL y la interfaz React compilada. En **Configuration → Users/Rights Management**, dar acceso de **Solicitante**, **Técnico** o **Coordinador** según corresponda. El administrador que vaya a importar también necesita al menos un perfil. Un técnico puede tener rol **Read only** en TestBenchPlanner.
2. Revisar **Configuration → Projects → Project Number** para los proyectos activos. Es la cuenta usada en Timing R&T y conserva los ceros iniciales.
3. En **Timing R&T → Configuración → Importar tareas**, elegir el Excel de exportación, con la hoja `query`, y pulsar **Vista previa**. Revisar los recuentos, las correspondencias y los avisos. El importador busca responsables y técnicos por correo o nombre entre cuentas con el perfil correspondiente. Para proyectos usa el nombre y número, o un número inequívoco si el nombre no coincide. Las coincidencias ambiguas quedan pendientes.
4. Aplicar la importación en la instancia de prueba. En **Planificación**, comprobar las colas de **Análisis** y **Bancos**, completar los datos de tareas activas y asignar a los técnicos. Con una cuenta técnica, registrar un avance y cerrar una tarea. Comprobar sus horas, comentario e historial.
5. Revisar las horas heredadas con el laboratorio. `Horas_Tarea`, `Horas_pendientes` y `Horas_Totales` se pueden consultar en **Datos originales del Excel** junto con la prioridad y demás campos originales. Las horas registradas de la nueva aplicación salen de los avances. El `OrdenDIario` heredado queda como referencia y el nuevo orden se fija por técnico y día de trabajo.

## Corte

1. Una vez validado el piloto, preparar la versión del servicio con V6 y hacer una copia de PostgreSQL. Usar una base de producción separada de la base de pruebas.
2. Congelar la edición en Timing R&T antiguo y generar la exportación final. Simular y cotejar los recuentos. Resolver las correspondencias de las tareas activas antes de ponerlas disponibles.
3. Aplicar la importación y habilitar la operativa en la nueva aplicación. La recarga usa la columna `ID` de origen. Si una tarea ya se modificó en la aplicación, la importación mantiene esa versión y comunica el conflicto. Los códigos visibles duplicados se conservan. La carga no elimina tareas ausentes en el fichero.
