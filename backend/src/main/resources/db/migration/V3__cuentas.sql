-- =============================================================================
-- TestBenchPlanner · V3 · Cuentas de la aplicación (hito S3)
--
-- Con la aplicación en el servidor cada persona entra con su email y una
-- contraseña propia de TestBenchPlanner (no la de Windows). El rol sigue en la
-- tabla «usuario» (Users/Rights Management); aquí solo está la contraseña y el
-- estado de la cuenta.
--
--   - La contraseña se guarda como hash BCrypt, NUNCA en claro.
--   - Sin fila aquí (o sin fila en «usuario») no se puede entrar.
--   - Sin clave foránea a «usuario» a propósito: repetir la importación desde
--     SharePoint durante el piloto no debe borrar las contraseñas. Quitar a
--     alguien de la lista sí borra su cuenta (lo hace la aplicación).
--
-- Permisos: tbp_app lee y escribe (ALTER DEFAULT PRIVILEGES de la instalación);
-- tbp_consulta (pgAdmin / DBeaver) NO la puede leer: ni los hashes salen de la
-- aplicación.
-- =============================================================================

CREATE TABLE credencial (
    email              text        PRIMARY KEY,               -- el de «usuario», en minúsculas
    hash               text        NOT NULL,                  -- BCrypt
    debe_cambiar       boolean     NOT NULL DEFAULT true,     -- contraseña temporal: cambio obligatorio al entrar
    intentos_fallidos  integer     NOT NULL DEFAULT 0,
    bloqueada_hasta    timestamptz,                           -- bloqueo temporal tras varios intentos fallidos
    sello              integer     NOT NULL DEFAULT 1,        -- cambia con cada contraseña nueva: cierra las demás sesiones
    creada_en          timestamptz NOT NULL DEFAULT now(),
    cambiada_en        timestamptz NOT NULL DEFAULT now(),
    ultimo_acceso      timestamptz
);

REVOKE ALL ON credencial FROM tbp_consulta;

COMMENT ON TABLE credencial IS 'Contraseñas (hash BCrypt) y estado de las cuentas de la aplicación. No legible por tbp_consulta.';
