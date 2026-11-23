package com.borgwarner.tbp.postgresql;

import com.borgwarner.tbp.aplicacion.puertos.AlmacenUsuarios;
import com.borgwarner.tbp.configuracion.PropiedadesPlanner;
import com.borgwarner.tbp.infraestructura.postgresql.AprobacionesPostgres;
import com.borgwarner.tbp.infraestructura.postgresql.BaseDatos;
import com.borgwarner.tbp.infraestructura.postgresql.CatalogosPostgres;
import com.borgwarner.tbp.infraestructura.postgresql.ColaboracionPostgres;
import com.borgwarner.tbp.infraestructura.postgresql.EnsayosPostgres;
import com.borgwarner.tbp.infraestructura.postgresql.HistoricoKpiPostgres;
import com.borgwarner.tbp.infraestructura.postgresql.ImportadorDatos;
import com.borgwarner.tbp.infraestructura.postgresql.MigracionesBaseDatos;
import com.borgwarner.tbp.infraestructura.postgresql.RecursosPostgres;
import com.borgwarner.tbp.infraestructura.postgresql.TiposEquipoPostgres;
import com.borgwarner.tbp.infraestructura.postgresql.UsuariosPostgres;
import com.borgwarner.tbp.infraestructura.postgresql.VerificacionParidad;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * Los almacenes de PostgreSQL montados a mano (sin Spring) sobre una base de
 * prueba nueva, igual que los monta ConfiguracionAlmacenes.PostgreSql.
 */
final class BaseDePruebas implements AutoCloseable {

    final PostgresPruebas.Base base;
    final HikariDataSource origen;
    final BaseDatos bd;
    final UsuariosPostgres usuarios;
    final ColaboracionPostgres colaboracion;
    final ContratoAlmacenes.Almacenes almacenes;
    final ImportadorDatos importador;

    static PropiedadesPlanner.PostgreSql propiedades(PostgresPruebas.Base base, boolean migrar) {
        return new PropiedadesPlanner.PostgreSql(base.url(), "tbp", "tbp_app", PostgresPruebas.CLAVE, "tbp_owner",
                PostgresPruebas.CLAVE, migrar, 4, "usuario01@ejemplo.local", "Persona 01", true);
    }

    BaseDePruebas() {
        base = PostgresPruebas.nuevaBase();
        MigracionesBaseDatos.preparar(propiedades(base, true));
        HikariConfig c = new HikariConfig();
        c.setJdbcUrl(base.url());
        c.setUsername("tbp_app");
        c.setPassword(PostgresPruebas.CLAVE);
        c.setSchema("tbp");
        c.setMaximumPoolSize(6);
        origen = new HikariDataSource(c);
        bd = new BaseDatos(origen, new DataSourceTransactionManager(origen));
        usuarios = new UsuariosPostgres(bd, new AlmacenUsuarios.Identidad("usuario01@ejemplo.local", "Persona 01"), false);
        bd.identidad(usuarios::identidadActual);
        colaboracion = new ColaboracionPostgres(bd);
        almacenes = new ContratoAlmacenes.Almacenes(new EnsayosPostgres(bd, colaboracion), new CatalogosPostgres(bd, false), usuarios,
                new RecursosPostgres(bd, colaboracion, false), new HistoricoKpiPostgres(bd), new AprobacionesPostgres(bd),
                new TiposEquipoPostgres(bd), colaboracion);
        importador = new ImportadorDatos(bd, new VerificacionParidad(almacenes.ensayos(), almacenes.catalogos(), usuarios,
                almacenes.recursos(), almacenes.kpi(), almacenes.aprobaciones(), almacenes.tipos(), colaboracion));
    }

    @Override
    public void close() {
        origen.close();
    }
}
