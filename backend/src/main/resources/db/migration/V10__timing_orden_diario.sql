-- Timing R&T: el orden diario del Excel (columna OrdenDIario) no se importaba;
-- solo quedaba en «legado». Se copia a las tareas que no tienen orden diario,
-- tal cual (0, 1, 2… primero; 9 por defecto; 99 genéricas y ausencias), igual
-- que lo hace ahora la importación. No cuenta como edición: una nueva
-- importación del mismo Excel sigue sin conflictos.
UPDATE timing_tarea
SET datos = jsonb_set(datos, '{ordenDiario}',
        to_jsonb(replace(btrim(coalesce(datos->'legado'->>'OrdenDIario', datos->'legado'->>'OrdenDiario')), ',', '.')::numeric))
WHERE (datos->'ordenDiario' IS NULL OR jsonb_typeof(datos->'ordenDiario') = 'null')
  AND btrim(coalesce(datos->'legado'->>'OrdenDIario', datos->'legado'->>'OrdenDiario', ''))
      ~ '^-?[0-9]+([.,][0-9]+)?([eE][-+]?[0-9]+)?$';
