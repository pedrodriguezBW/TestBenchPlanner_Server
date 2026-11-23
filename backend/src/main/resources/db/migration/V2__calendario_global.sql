-- =============================================================================
-- TestBenchPlanner · V2 · Calendario de festivos común (hito S2)
--
-- Hasta ahora cada PC tenía su propio calendario (calendario.json, D6). Con la
-- aplicación en un servidor el calendario es UNO para todos y solo lo cambia
-- un Admin (D46). Una sola fila en «calendario» (año y regla de festivos) y los
-- festivos en «festivo». Si la tabla está vacía, la aplicación la rellena la
-- primera vez con los festivos de Vigo del año en curso, como un PC nuevo.
--
-- Permisos: llegan solos por los ALTER DEFAULT PRIVILEGES de la instalación.
-- =============================================================================

CREATE TABLE calendario (
    id               smallint    PRIMARY KEY DEFAULT 1 CHECK (id = 1),  -- una sola fila
    anio             integer     NOT NULL,                              -- año de trabajo (Configuration → Calendar)
    saltar_festivos  boolean     NOT NULL DEFAULT true,                 -- «Skip holidays when scheduling»
    actualizado_en   timestamptz NOT NULL DEFAULT now(),
    actualizado_por  text                                               -- email de quien lo cambió
);

CREATE TABLE festivo (
    fecha   date PRIMARY KEY,
    nombre  text NOT NULL DEFAULT ''
);

COMMENT ON TABLE calendario IS 'Calendario de festivos común (una fila). Lo edita solo un Admin desde la aplicación.';
COMMENT ON TABLE festivo IS 'Festivos del calendario común.';
