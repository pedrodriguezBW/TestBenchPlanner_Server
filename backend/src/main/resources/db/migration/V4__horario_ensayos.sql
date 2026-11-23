-- =============================================================================
-- TestBenchPlanner · V4 · Horario de los ensayos y facturación por horas (Fase 2)
--
-- Configuration › Test Calendar / Working Hours (D59): un registro por ensayo
-- con su jornada (24, 8 o 16 h), horas de inicio y fin, fines de semana y
-- festivos, setup, paradas y una nota. Sin registro, el ensayo trabaja con los
-- valores por defecto (24 h, fines de semana como su opción Weekend).
--
--   - Clave: el id del ensayo (el «uid» de «ensayo», p. ej. r1234567).
--   - Sin clave foránea a «ensayo» a propósito (como «credencial» en V3):
--     volver a importar los ensayos desde SharePoint no debe borrar los horarios.
--   - paradas: lista JSON [{inicio, fin?, horas?, afectaTurnos, metodo, motivo?}]
--     con fechas «aaaa-mm-ddTHH:MM» (ver docs/15-horario-de-ensayos.md).
--
-- Facturación por horas (D60): «FacturacionHorasDesde» en «configuracion_app»
-- es el primer mes de facturación (aaaa-mm) que se calcula por horas; los
-- anteriores (ya facturados) se calculan como siempre. Por defecto, el mes de
-- facturación en curso al aplicar esta migración (día 21 o más = el siguiente).
--
-- Permisos: llegan solos por los ALTER DEFAULT PRIVILEGES de la instalación
-- (tbp_app lee y escribe; tbp_consulta solo lee).
-- =============================================================================

CREATE TABLE horario_ensayo (
    ensayo_id       text        PRIMARY KEY,                                   -- id del ensayo (uid de «ensayo»)
    jornada         smallint    NOT NULL DEFAULT 24 CHECK (jornada IN (24, 8, 16)),
    hora_inicio     text,                                                      -- «HH:MM» el día de inicio; null = inicio de la jornada
    hora_fin        text,                                                      -- «HH:MM» el día de fin (hasta «24:00»); null = fin de la jornada
    fin_de_semana   text        CHECK (fin_de_semana IN ('si', 'no')),         -- null = como la opción Weekend del ensayo
    setup_horas     numeric,                                                   -- null = la columna antigua setupHours del ensayo
    paradas         jsonb       NOT NULL DEFAULT '[]'::jsonb,
    nota            text,
    modificado_por  text,                                                      -- email de quien lo cambió
    modificado_en   timestamptz NOT NULL DEFAULT now(),
    version         bigint      NOT NULL DEFAULT 1                             -- concurrencia optimista
);

COMMENT ON TABLE horario_ensayo IS 'Horario de trabajo de cada ensayo (Working Hours). Lo editan un Admin o un Lab User desde la aplicación.';

INSERT INTO configuracion_app (clave, valor)
VALUES ('FacturacionHorasDesde', to_char(date_trunc('month', (current_date - 20)::timestamp) + interval '1 month', 'YYYY-MM'))
ON CONFLICT (clave) DO NOTHING;
