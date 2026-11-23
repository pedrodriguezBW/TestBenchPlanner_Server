-- =============================================================================
-- TestBenchPlanner · Instalación de PostgreSQL (UNA SOLA VEZ, en el PC servidor)
--
-- Crea los tres usuarios de base de datos y la base «tbp». Lo ejecuta un
-- administrador con el superusuario «postgres» desde psql (ver
-- scripts/servidor/crear-base-de-datos.cmd, que pide las tres claves):
--
--   psql -U postgres -d postgres -v clave_owner=… -v clave_app=… -v clave_consulta=…
--        -f 01-crear-roles-y-base.sql
--
-- La estructura (tablas) NO se crea aquí: la crea Flyway al arrancar la
-- aplicación con tbp_owner (db/migration/V1__…, V2__…).
-- =============================================================================
\set ON_ERROR_STOP on

-- tbp_owner: dueño de la base y del esquema; solo lo usa Flyway (DDL).
CREATE ROLE tbp_owner LOGIN PASSWORD :'clave_owner';
-- tbp_app: la aplicación en el día a día (lee y escribe datos, sin DDL).
CREATE ROLE tbp_app LOGIN PASSWORD :'clave_app';
-- tbp_consulta: pgAdmin / DBeaver en solo lectura.
CREATE ROLE tbp_consulta LOGIN PASSWORD :'clave_consulta';

CREATE DATABASE tbp OWNER tbp_owner ENCODING 'UTF8' TEMPLATE template0;
REVOKE ALL ON DATABASE tbp FROM PUBLIC;
GRANT CONNECT ON DATABASE tbp TO tbp_app, tbp_consulta;

\connect tbp
\ir 02-esquema-y-permisos.sql
