-- =============================================================================
-- TestBenchPlanner · Esquema «tbp» y permisos (se ejecuta conectado a la base
-- «tbp» con el superusuario; lo incluye 01-crear-roles-y-base.sql).
--
-- Los ALTER DEFAULT PRIVILEGES hacen que TODA tabla o secuencia que cree
-- tbp_owner (Flyway) en el esquema tbp quede automáticamente:
--   · lectura y escritura de datos para tbp_app (nunca DDL),
--   · solo lectura para tbp_consulta.
-- Por eso las migraciones no necesitan GRANT.
-- =============================================================================
CREATE SCHEMA tbp AUTHORIZATION tbp_owner;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

GRANT USAGE ON SCHEMA tbp TO tbp_app, tbp_consulta;

ALTER DEFAULT PRIVILEGES FOR ROLE tbp_owner IN SCHEMA tbp
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO tbp_app;
ALTER DEFAULT PRIVILEGES FOR ROLE tbp_owner IN SCHEMA tbp
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO tbp_app;
ALTER DEFAULT PRIVILEGES FOR ROLE tbp_owner IN SCHEMA tbp
    GRANT SELECT ON TABLES TO tbp_consulta;
ALTER DEFAULT PRIVILEGES FOR ROLE tbp_owner IN SCHEMA tbp
    GRANT SELECT ON SEQUENCES TO tbp_consulta;

-- Comodidad en pgAdmin/DBeaver y en la aplicación: el esquema tbp por defecto.
ALTER ROLE tbp_owner SET search_path = tbp;
ALTER ROLE tbp_app SET search_path = tbp;
ALTER ROLE tbp_consulta SET search_path = tbp;
-- Red de seguridad: las sesiones de consulta son de solo lectura.
ALTER ROLE tbp_consulta SET default_transaction_read_only = on;
