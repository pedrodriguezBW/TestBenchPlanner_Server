package com.borgwarner.tbp.infraestructura.postgresql;

import com.borgwarner.tbp.configuracion.PropiedadesPlanner;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Estructura de la base de datos con Flyway (db/migration/V<n>__<descripcion>.sql).
 *
 *  - migrar-al-arrancar=true (por defecto): con el usuario PROPIETARIO
 *    (tbp_owner) se aplican las migraciones pendientes; antes, Flyway valida
 *    que las ya aplicadas no han cambiado (validateOnMigrate).
 *  - migrar-al-arrancar=false: con el usuario de la APLICACIÓN solo se valida;
 *    si falta alguna migración o alguna no cuadra, la aplicación no arranca.
 *
 * En los dos casos, una base que no cuadra con las migraciones de esta versión
 * impide arrancar: mejor un aviso claro que trabajar sobre una base inconsistente.
 * Flyway nunca borra nada (clean desactivado).
 */
public final class MigracionesBaseDatos {

    private static final Logger LOG = LoggerFactory.getLogger(MigracionesBaseDatos.class);
    public static final String UBICACION = "classpath:db/migration";

    private MigracionesBaseDatos() {
    }

    static Flyway flyway(String url, String usuario, String clave, String esquema) {
        return Flyway.configure()
                .dataSource(url, usuario, clave == null ? "" : clave)
                .schemas(esquema)
                .defaultSchema(esquema)
                .createSchemas(false)       // el esquema lo crea el script de instalación (con sus permisos)
                .locations(UBICACION)
                .cleanDisabled(true)
                .validateMigrationNaming(true)
                .load();
    }

    /** Aplica o valida la estructura según la configuración; lanza IllegalStateException si no cuadra. */
    public static void preparar(PropiedadesPlanner.PostgreSql c) {
        boolean migrar = c.migrarAlArrancar();
        String usuario = migrar ? c.usuarioPropietarioEfectivo() : c.usuarioAppEfectivo();
        String clave = migrar ? c.clavePropietario() : c.claveApp();
        Flyway flyway = flyway(c.urlEfectiva(), usuario, clave, c.esquemaEfectivo());
        try {
            if (migrar) {
                MigrateResult r = flyway.migrate();
                LOG.info("Base de datos {} · esquema {} · versión {} ({} migraciones aplicadas ahora)", c.urlEfectiva(),
                        c.esquemaEfectivo(), r.targetSchemaVersion == null ? flyway.info().current() : r.targetSchemaVersion,
                        r.migrationsExecuted);
            } else {
                flyway.validate();
                LOG.info("Base de datos {} · estructura validada (sin migrar)", c.urlEfectiva());
            }
        } catch (FlywayException | IllegalStateException e) {
            throw new IllegalStateException("No se ha podido preparar la base de datos " + c.urlEfectiva() + " con el usuario "
                    + usuario + " (conexión, clave o estructura). Si la estructura no cuadra con las migraciones de esta "
                    + "versión, la aplicación no arranca para no trabajar sobre una base inconsistente. Detalle: " + e.getMessage(), e);
        }
    }
}
