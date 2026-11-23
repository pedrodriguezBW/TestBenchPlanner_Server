-- =============================================================================
-- TestBenchPlanner · V1 · Estructura inicial (hito S1)
--
-- Una tabla por lista de SharePoint, con MODELO HÍBRIDO en los ensayos:
-- columnas relacionales para lo que se filtra/ordena/cruza + el DataJson íntegro
-- en texto (no jsonb: jsonb reordena claves y la app compara el texto exacto).
--
-- Se ejecuta con tbp_owner (Flyway). Los permisos de tbp_app (datos) y de
-- tbp_consulta (solo lectura) llegan solos por los ALTER DEFAULT PRIVILEGES de
-- db/instalacion/02-esquema-y-permisos.sql. NUNCA cambiar este fichero una vez
-- aplicado: cualquier cambio de estructura va en una migración nueva V2, V3…
-- =============================================================================

-- ---- Ensayos (lista TestBenchPlanner) ---------------------------------------
CREATE SEQUENCE ensayo_orden_seq;

CREATE TABLE ensayo (
    uid             text        PRIMARY KEY,                -- Title de SharePoint = id del ensayo
    orden           bigint      NOT NULL DEFAULT nextval('ensayo_orden_seq'), -- orden de lectura (como el Id de SharePoint)
    data_json       text,                                   -- DataJson íntegro, byte a byte
    version         bigint      NOT NULL DEFAULT 1,         -- concurrencia optimista (sustituye al ETag)
    -- Copia relacional de campos del DataJson (se rellena al guardar; manda el DataJson)
    banco           text,                                   -- _bench
    organizacion    text,                                   -- _benchOrg
    tipo            text,                                   -- type
    responsable     text,                                   -- resp
    proyecto        text,                                   -- project
    cerrado         boolean,                                -- done
    approval_id     text,                                   -- approvalId
    disponible      date,                                   -- avail
    inicio_real     date,                                   -- actualStart
    fin_real        date,                                   -- trueEnd
    fecha_cierre    date,                                   -- doneDate
    creado_en       timestamptz NOT NULL DEFAULT now(),
    actualizado_en  timestamptz NOT NULL DEFAULT now(),
    actualizado_por text
);
ALTER SEQUENCE ensayo_orden_seq OWNED BY ensayo.orden;
CREATE INDEX ensayo_orden_idx       ON ensayo (orden);
CREATE INDEX ensayo_banco_idx       ON ensayo (banco);
CREATE INDEX ensayo_responsable_idx ON ensayo (responsable);
CREATE INDEX ensayo_proyecto_idx    ON ensayo (proyecto);

-- ---- Auditoría: antes/después de cada cambio (ensayos y listas) ---------------
CREATE TABLE auditoria (
    id            bigserial   PRIMARY KEY,
    cuando        timestamptz NOT NULL DEFAULT now(),
    usuario_email text,
    usuario_nombre text,
    entidad       text        NOT NULL,   -- ensayo, banco, proyecto, usuario, recurso, …
    clave         text        NOT NULL,   -- uid, nombre, email…
    operacion     text        NOT NULL,   -- ALTA, MODIFICACION, BAJA, IMPORTACION
    antes         text,                   -- valor anterior (DataJson o fila en JSON); null en altas
    despues       text                    -- valor nuevo; null en bajas
);
CREATE INDEX auditoria_entidad_idx ON auditoria (entidad, clave);
CREATE INDEX auditoria_cuando_idx  ON auditoria (cuando);

-- ---- Registro de actividad visible en la app (TestBenchActivityLog) -----------
CREATE TABLE registro_actividad (
    id        bigserial   PRIMARY KEY,
    cuando    text        NOT NULL,       -- «2026-11-05T10:00:00Z», como WhenTs
    email     text,
    nombre    text,
    accion    text,
    detalle   text,
    huella    text        UNIQUE,         -- solo filas importadas: evita duplicarlas al repetir la importación
    creado_en timestamptz NOT NULL DEFAULT now()
);

-- ---- Catálogos (TestBenchResponsibles, TestBenchProjects, TestBenchBenches) ---
CREATE TABLE responsable (
    id     bigserial PRIMARY KEY,
    nombre text      NOT NULL UNIQUE,
    seq    double precision
);

CREATE TABLE proyecto (
    id                 bigserial PRIMARY KEY,
    titulo             text      NOT NULL UNIQUE,
    seq                double precision,
    orden_facturacion  text                    -- BillingOrder
);

CREATE TABLE banco (
    id          bigserial PRIMARY KEY,
    nombre      text      NOT NULL UNIQUE,     -- Title
    group_key   text,
    group_name  text,
    dot         text,
    org         text,
    seq         double precision,
    tipo_equipo text,                          -- EquipmentType (facturación)
    area        text                           -- Area: vacío = Thermal Benches
);

-- Nombres de las áreas (TestBenchAppConfig «AreaName:<código>»)
CREATE TABLE nombre_area (
    codigo text PRIMARY KEY,
    nombre text NOT NULL
);

-- Valores sueltos de la aplicación (TestBenchAppConfig: CurrentVersion…)
CREATE TABLE configuracion_app (
    clave text PRIMARY KEY,
    valor text
);

-- ---- Usuarios y roles (TestBenchUsers) ----------------------------------------
CREATE TABLE usuario (
    id                 bigserial PRIMARY KEY,
    email              text      NOT NULL UNIQUE,  -- siempre en minúsculas
    titulo             text,
    rol                text      NOT NULL,         -- Admin | Editor | ReadOnly
    nombre_responsable text
);

-- ---- Equipos auxiliares y bloqueos (TestBenchResources / ResourceBlocks) -------
CREATE TABLE recurso (
    id         bigserial PRIMARY KEY,
    nombre     text      NOT NULL UNIQUE,
    group_key  text,
    group_name text,
    dot        text,
    codigo     text,                               -- EquipmentCode
    org        text,
    seq        double precision
);

CREATE SEQUENCE bloqueo_recurso_id_seq;
CREATE TABLE bloqueo_recurso (
    id      bigint PRIMARY KEY DEFAULT nextval('bloqueo_recurso_id_seq'),  -- se conserva el Id de SharePoint
    titulo  text,
    recurso text,
    inicio  date,
    fin     date,
    motivo  text,
    nota    text,
    autor   text
);
ALTER SEQUENCE bloqueo_recurso_id_seq OWNED BY bloqueo_recurso.id;

-- ---- Histórico semanal de KPI (TestBenchKPIHistory) ---------------------------
CREATE TABLE kpi_semana (
    id        bigserial PRIMARY KEY,
    anio      integer,                 -- Year
    cw        integer,                 -- CW
    org       text,                    -- ALL, BW, CTAG, THERMAL…
    fila_json text      NOT NULL       -- la fila completa con los nombres de columna de la lista
);
CREATE INDEX kpi_semana_clave_idx ON kpi_semana (anio, cw, org);

-- ---- Aprobaciones (base de aprobaciones de servicios) -------------------------
-- Hasta ahora solo lectura desde otro sitio de SharePoint; en el servidor pasa a
-- ser tabla propia (pendiente de confirmar su gestión desde la app).
CREATE TABLE aprobacion (
    id          integer PRIMARY KEY,   -- Id de la base de aprobaciones = Approval ID
    estado      text,                  -- approved, pending, rejected, na, not_created, other:…
    color       text,
    raw         text,                  -- valor original del estado
    solicitante text,
    servicio    text
);

-- ---- Facturación: Equipment Types por año (TestBenchEquipmentTypes) -----------
CREATE TABLE tipo_equipo (
    id      bigserial PRIMARY KEY,
    clave   text      NOT NULL UNIQUE,   -- título en minúsculas + «|» + año
    titulo  text      NOT NULL,
    grupo   text,
    anio    integer   NOT NULL,
    sin_hv  double precision,
    con_hv  double precision,
    unidad  text,                        -- HORAS | PIEZAS
    regla   text                         -- LINEAR, ASINH_100, …
);

-- ---- Presencia (TestBenchPresence) -------------------------------------------
CREATE TABLE presencia (
    email         text        PRIMARY KEY,
    nombre        text,
    iniciales     text,
    color         text,
    ultimo_latido timestamptz NOT NULL
);

-- ---- Protección del historial de Flyway ----------------------------------------
-- Los privilegios por defecto darían a tbp_app escritura en el historial de
-- migraciones (lo crea tbp_owner en este esquema): solo puede leerlo.
REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON flyway_schema_history FROM tbp_app;
