-- La primera importación de Timing R&T degradaba Sí y En proceso a No
-- disponible cuando faltaba conciliar algún dato. Recuperar el estado del
-- Excel solo para tareas que nadie haya modificado desde la importación.
UPDATE timing_tarea
SET datos = datos || jsonb_build_object(
    'disponible', true,
    'estado', CASE WHEN lower(btrim(datos->'legado'->>'Disp.')) = 'en proceso'
                   THEN 'en-proceso' ELSE 'pendiente' END),
    version = version + 1,
    version_importada = version + 1
WHERE origen_id IS NOT NULL
  AND version = version_importada
  AND datos->>'estado' = 'pendiente'
  AND datos->>'disponible' = 'false'
  AND lower(btrim(datos->'legado'->>'Disp.')) IN ('sí', 'si', 'en proceso');
