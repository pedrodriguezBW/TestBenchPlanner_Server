-- Proyectos: el nombre ya no lleva el número (Configuración › Projects tiene la
-- columna Project Number). Se separan los que tienen exactamente la forma
-- «número - nombre» y cuyo número guardado es ese mismo (o ninguno). Hacia fuera
-- la etiqueta sigue siendo «número - nombre», idéntica al título anterior, así
-- que los ensayos y la facturación no cambian.
--
-- No se separan (y la pantalla los marca para corregirlos a mano):
--  - los de otra forma («80002613 VW …», «80001755 -Nombre»…);
--  - los que tienen otro número guardado;
--  - los que quedarían con el mismo nombre que otro proyecto.
CREATE TABLE proyecto_separado AS
WITH candidatos AS (
    SELECT p.id, p.titulo, m[1] AS numero, m[2] AS nombre
    FROM proyecto p, regexp_match(p.titulo, '^([0-9]+) - (\S(?:.*\S)?)$') AS m
    WHERE m IS NOT NULL AND (p.numero_proyecto IS NULL OR p.numero_proyecto = m[1])
      -- La etiqueta «número - nombre» tiene que volver a dar el mismo título:
      -- no se separa si el nombre resultante también empieza por un número de proyecto.
      AND m[2] !~ '^\s*[0-9]{5,}(\s|-)'
      AND NOT (position(m[1] IN m[2]) = 1 AND substring(m[2] FROM length(m[1]) + 1 FOR 1) !~ '[0-9]')
)
SELECT c.* FROM candidatos c
WHERE NOT EXISTS (SELECT 1 FROM proyecto p WHERE p.id <> c.id AND p.titulo = c.nombre)
  AND NOT EXISTS (SELECT 1 FROM candidatos o WHERE o.id <> c.id AND o.nombre = c.nombre);

-- Timing R&T: las tareas guardan el nombre del proyecto del catálogo y las
-- correspondencias manuales apuntan al nombre.
UPDATE timing_tarea t
SET datos = jsonb_set(t.datos, '{proyecto}', to_jsonb(s.nombre))
FROM proyecto_separado s
WHERE t.datos->>'proyectoId' = s.id::text AND t.datos->>'proyecto' = s.titulo;

UPDATE timing_vinculo v
SET destino = s.nombre
FROM proyecto_separado s
WHERE v.tipo = 'proyecto' AND v.destino = s.titulo;

INSERT INTO auditoria (usuario_email, usuario_nombre, entidad, clave, operacion, antes, despues)
SELECT NULL, 'Migración V9', 'proyecto', s.titulo, 'MODIFICACION',
       json_build_object('titulo', s.titulo, 'numero_proyecto', s.numero)::text,
       json_build_object('titulo', s.nombre, 'numero_proyecto', s.numero)::text
FROM proyecto_separado s;

UPDATE proyecto p
SET titulo = s.nombre, numero_proyecto = s.numero
FROM proyecto_separado s
WHERE p.id = s.id;

DROP TABLE proyecto_separado;

-- Correspondencias manuales de proyectos guardadas con el número corto del
-- Excel (80772): la clave pasa al formato largo (80000772), como la calcula
-- ahora la aplicación. Si dos quedan con la misma clave, vale la más reciente.
CREATE FUNCTION tbp_numero_largo(numero text) RETURNS text LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE
        WHEN btrim(numero) ~ '^8[0-9]{4,8}$' AND length(ltrim(substr(btrim(numero), 2), '0')) <= 7
            THEN '8' || lpad(ltrim(substr(btrim(numero), 2), '0'), 7, '0')
        ELSE btrim(numero)
    END
$$;

CREATE TABLE vinculo_largo AS
SELECT origen,
       left(origen, length(origen) - length(cuenta)) || tbp_numero_largo(cuenta) AS nuevo_origen,
       tbp_numero_largo(cuenta) AS nueva_cuenta,
       fecha
FROM timing_vinculo
WHERE tipo = 'proyecto' AND cuenta <> '' AND right(origen, length(cuenta) + 1) = '|' || cuenta;

DELETE FROM timing_vinculo v
USING vinculo_largo a, vinculo_largo b
WHERE v.tipo = 'proyecto' AND v.origen = a.origen
  AND b.origen <> a.origen AND b.nuevo_origen = a.nuevo_origen
  AND (b.fecha > a.fecha OR (b.fecha = a.fecha AND b.origen > a.origen));

UPDATE timing_vinculo v
SET origen = a.nuevo_origen, cuenta = a.nueva_cuenta
FROM vinculo_largo a
WHERE v.tipo = 'proyecto' AND v.origen = a.origen AND a.nuevo_origen <> a.origen;

DROP TABLE vinculo_largo;
DROP FUNCTION tbp_numero_largo(text);
