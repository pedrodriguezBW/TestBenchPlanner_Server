-- =============================================================================
-- TestBenchPlanner · V5 · Turno de mañana o de tarde (Working Hours, D59)
--
-- Con jornada de 8 h (un turno) se puede elegir el turno: mañana 06:00–14:00
-- (por defecto, null) o tarde 14:00–22:00. Con otras jornadas queda en null.
-- Los horarios ya guardados siguen igual (turno de mañana).
-- =============================================================================

ALTER TABLE horario_ensayo
    ADD COLUMN turno text CHECK (turno IN ('manana', 'tarde'));

COMMENT ON COLUMN horario_ensayo.turno IS 'Con jornada de 8 h: manana (06:00-14:00, null) o tarde (14:00-22:00).';
