-- Timing R&T: vinculación de los datos heredados del Excel con usuarios y
-- proyectos de TestBenchPlanner dados de alta después de la importación.

-- Correspondencias manuales («Datos sin vincular»). origen = nombre del Excel
-- normalizado (sin acentos ni mayúsculas); en proyectos, nombre|número.
-- destino = correo del usuario o título del proyecto (claves estables al reimportar).
CREATE TABLE timing_vinculo (
    tipo text NOT NULL CHECK (tipo IN ('persona', 'proyecto')),
    origen text NOT NULL,
    nombre text NOT NULL,
    cuenta text NOT NULL DEFAULT '',
    destino text NOT NULL,
    autor text NOT NULL,
    fecha timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tipo, origen)
);
REVOKE ALL ON timing_vinculo FROM tbp_consulta;

-- Proyecto y número originales en las tareas importadas cuyo proyecto no se ha
-- cambiado a mano (el importador guardaba el nombre del Excel en «proyecto»).
UPDATE timing_tarea
SET datos = datos || jsonb_build_object('proyectoOrigen', coalesce(datos->>'proyecto', ''),
                                        'cuentaOrigen', coalesce(datos->>'cuenta', ''))
WHERE origen_id IS NOT NULL
  AND NOT (datos ? 'proyectoOrigen')
  AND datos ? 'proyecto'
  AND coalesce(datos->>'proyecto', '') = btrim(coalesce(datos->'legado'->>'Proyecto', ''));

-- Las ya vinculadas muestran el proyecto y el número del catálogo, igual que
-- las que se vinculen a partir de ahora (el número del Excel queda en cuentaOrigen).
UPDATE timing_tarea t
SET datos = t.datos || jsonb_build_object('proyecto', p.titulo,
                                          'cuenta', coalesce(nullif(btrim(p.numero_proyecto), ''), t.datos->>'cuentaOrigen', ''))
FROM proyecto p
WHERE t.datos ? 'proyectoOrigen'
  AND jsonb_typeof(t.datos->'proyectoId') = 'number'
  AND p.id = (t.datos->>'proyectoId')::bigint;
