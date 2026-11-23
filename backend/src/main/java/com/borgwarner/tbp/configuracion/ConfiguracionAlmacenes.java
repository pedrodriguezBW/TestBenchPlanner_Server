package com.borgwarner.tbp.configuracion;

import com.borgwarner.tbp.aplicacion.puertos.AlmacenCalendario;
import com.borgwarner.tbp.aplicacion.EstadoConexion;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenCatalogos;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenEnsayos;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenHistoricoKpi;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenHorarios;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenRecursos;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenTiposEquipo;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenUsuarios;
import com.borgwarner.tbp.aplicacion.puertos.Colaboracion;
import com.borgwarner.tbp.aplicacion.puertos.ConsultaAprobaciones;
import com.borgwarner.tbp.aplicacion.puertos.Reloj;
import com.borgwarner.tbp.infraestructura.comun.AlmacenCalendarioArchivo;
import com.borgwarner.tbp.infraestructura.comun.RelojConfigurable;
import com.borgwarner.tbp.infraestructura.comun.CopiaSinConexion;
import com.borgwarner.tbp.infraestructura.local.AlmacenEnsayosLocal;
import com.borgwarner.tbp.infraestructura.local.AlmacenesAuxiliaresLocales;
import com.borgwarner.tbp.infraestructura.local.DatosLocales;
import com.borgwarner.tbp.infraestructura.sharepoint.AlmacenEnsayosSharePoint;
import com.borgwarner.tbp.infraestructura.sharepoint.AprobacionesSharePoint;
import com.borgwarner.tbp.infraestructura.sharepoint.ClienteSharePoint;
import com.borgwarner.tbp.infraestructura.sharepoint.ConexionEdge;
import com.borgwarner.tbp.infraestructura.sharepoint.EspejoSharePoint;
import com.borgwarner.tbp.infraestructura.sharepoint.ListasAuxiliaresSharePoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * Elige los adaptadores de persistencia según planner.almacen:
 *   - local: ficheros en disco (desarrollo, pruebas, laboratorio).
 *   - sharepoint: listas del sitio configurado, a través del Edge del usuario.
 *   - postgresql: base de datos PostgreSQL del servidor (hito S1).
 * El resto de la aplicación solo conoce los puertos: no cambia con el almacén.
 */
@Configuration
public class ConfiguracionAlmacenes {

    private static final Logger LOG = LoggerFactory.getLogger(ConfiguracionAlmacenes.class);

    @Bean
    public RelojConfigurable reloj(PropiedadesPlanner propiedades) {
        return new RelojConfigurable(propiedades.fechaFija());
    }

    /** Calendario de festivos de este PC (almacenes local y SharePoint, D6). */
    static AlmacenCalendario calendarioDeEstePc(PropiedadesPlanner propiedades) {
        return new AlmacenCalendarioArchivo(Path.of(propiedades.carpetaUsuario()));
    }

    /** Adaptadores del modo local. */
    @Configuration
    @ConditionalOnProperty(name = "planner.almacen", havingValue = "local", matchIfMissing = true)
    public static class Local {

        @Bean
        public AlmacenCalendario almacenCalendario(PropiedadesPlanner p) {
            return calendarioDeEstePc(p);
        }

        @Bean
        public DatosLocales datosLocales(PropiedadesPlanner p) {
            LOG.info("Almacén LOCAL en {} (escenario inicial {})", p.local().carpetaDatos(), p.local().escenarioInicial());
            return new DatosLocales(Path.of(p.local().carpetaDatos()), Path.of(p.local().escenarioInicial()), p.local().reiniciarAlArrancar());
        }

        @Bean
        public Colaboracion colaboracion(DatosLocales datos) {
            return new AlmacenesAuxiliaresLocales.ColaboracionLocal(datos);
        }

        @Bean
        public AlmacenEnsayos almacenEnsayos(DatosLocales datos, Colaboracion colaboracion) {
            return new AlmacenEnsayosLocal(datos, colaboracion);
        }

        @Bean
        public AlmacenCatalogos almacenCatalogos(DatosLocales datos, @Value("${planner.listas.permitir-vacias:false}") boolean permitirVacias) {
            return new AlmacenesAuxiliaresLocales.Catalogos(datos, permitirVacias);
        }

        @Bean
        public AlmacenHistoricoKpi almacenHistoricoKpi(DatosLocales datos) {
            return new AlmacenesAuxiliaresLocales.HistoricoKpi(datos);
        }

        @Bean
        public AlmacenesAuxiliaresLocales.Usuarios almacenUsuarios(DatosLocales datos, PropiedadesPlanner p,
                                               @Value("${planner.listas.permitir-vacias:false}") boolean permitirVacias) {
            return new AlmacenesAuxiliaresLocales.Usuarios(datos,
                    new AlmacenUsuarios.Identidad(p.local().usuarioEmail(), p.local().usuarioNombre()), permitirVacias);
        }

        @Bean
        public AlmacenRecursos almacenRecursos(DatosLocales datos, Colaboracion colaboracion,
                                               @Value("${planner.listas.permitir-vacias:false}") boolean permitirVacias) {
            return new AlmacenesAuxiliaresLocales.Recursos(datos, colaboracion, permitirVacias);
        }

        /** Tipos de equipo de facturación del modo local (tipos-equipo.json junto a los datos locales). */
        @Bean
        public AlmacenTiposEquipo almacenTiposEquipo(DatosLocales datos) {
            return new AlmacenesAuxiliaresLocales.TiposEquipo(datos);
        }

        @Bean
        public ConsultaAprobaciones consultaAprobaciones(DatosLocales datos) {
            return new AlmacenesAuxiliaresLocales.Aprobaciones(datos);
        }

        /** Sin horario de ensayos (Working Hours, D59): solo existe con PostgreSQL. */
        @Bean
        public AlmacenHorarios almacenHorarios() {
            return new AlmacenHorarios.SinHorarios();
        }
    }

    /** Adaptadores de SharePoint. */
    @Configuration
    @ConditionalOnProperty(name = "planner.almacen", havingValue = "sharepoint")
    public static class SharePoint {

        @Bean
        public AlmacenCalendario almacenCalendario(PropiedadesPlanner p) {
            return calendarioDeEstePc(p);
        }

        @Bean(destroyMethod = "close")
        public ConexionEdge conexionEdge(PropiedadesPlanner p) {
            return new ConexionEdge(p.sharepoint().puertoDepuracion(), Path.of(p.sharepoint().carpetaPerfilEdge()),
                    p.sharepoint().carrilesLecturaEfectivos());
        }

        @Bean
        public ClienteSharePoint clienteSharePoint(ConexionEdge conexion, PropiedadesPlanner p) {
            if (p.sharepoint().sitio() == null || p.sharepoint().sitio().isBlank()) {
                throw new IllegalStateException("Falta planner.sharepoint.sitio (URL del sitio de SharePoint)");
            }
            LOG.warn("Almacén SHAREPOINT {} · escritura {}", p.sharepoint().sitio(),
                    p.sharepoint().escrituraHabilitada() ? "HABILITADA" : "DESACTIVADA (solo lectura)");
            return new ClienteSharePoint(conexion, p.sharepoint().sitio(), p.sharepoint().escrituraHabilitada());
        }

        /**
         * Es también el bean de ConsultaAprobaciones: no se declara otro bean que
         * devuelva la misma instancia, porque habría dos candidatos del tipo
         * ConsultaAprobaciones (y de AprobacionesSharePoint) y la inyección fallaría.
         */
        @Bean
        public AprobacionesSharePoint aprobacionesSharePoint(ClienteSharePoint cliente, PropiedadesPlanner p) {
            if (p.sharepoint().sitioAprobaciones() == null || p.sharepoint().sitioAprobaciones().isBlank()) {
                throw new IllegalStateException("Falta planner.sharepoint.sitio-aprobaciones (sitio de la base de aprobaciones)");
            }
            return new AprobacionesSharePoint(cliente, p.sharepoint().sitioAprobaciones(), p.sharepoint().listaAprobaciones());
        }

        @Bean
        public EspejoSharePoint espejoSharePoint(ClienteSharePoint cliente, AprobacionesSharePoint aprobaciones, PropiedadesPlanner p) {
            return new EspejoSharePoint(cliente, aprobaciones, p.sharepoint().espejoHabilitado());
        }

        @Bean
        public Colaboracion colaboracion(ClienteSharePoint cliente, PropiedadesPlanner p) {
            return new ListasAuxiliaresSharePoint.ColaboracionSharePoint(cliente, p.sharepoint().publicarVersion());
        }

        /** Última copia buena en este PC para consultar sin conexión (H-04). */
        @Bean
        public CopiaSinConexion copiaSinConexion(PropiedadesPlanner p, EstadoConexion estado) {
            return new CopiaSinConexion(Path.of(p.carpetaUsuario()), estado);
        }

        @Bean
        public AlmacenEnsayos almacenEnsayos(ClienteSharePoint cliente, Colaboracion colaboracion, EspejoSharePoint espejo, CopiaSinConexion copia) {
            return copia.ensayos(new AlmacenEnsayosSharePoint(cliente, colaboracion, espejo));
        }

        @Bean
        public AlmacenCatalogos almacenCatalogos(ClienteSharePoint cliente, @Value("${planner.listas.permitir-vacias:false}") boolean permitirVacias,
                                                 CopiaSinConexion copia) {
            return copia.catalogos(new ListasAuxiliaresSharePoint.Catalogos(cliente, permitirVacias));
        }

        @Bean
        public AlmacenHistoricoKpi almacenHistoricoKpi(ClienteSharePoint cliente) {
            return new ListasAuxiliaresSharePoint.HistoricoKpi(cliente);
        }

        @Bean
        public AlmacenUsuarios almacenUsuarios(ClienteSharePoint cliente, @Value("${planner.listas.permitir-vacias:false}") boolean permitirVacias,
                                               CopiaSinConexion copia) {
            return copia.usuarios(new ListasAuxiliaresSharePoint.Usuarios(cliente, permitirVacias));
        }

        /** Tipos de equipo de facturación: lista TestBenchEquipmentTypes del sitio. */
        @Bean
        public AlmacenTiposEquipo almacenTiposEquipo(ClienteSharePoint cliente) {
            return new ListasAuxiliaresSharePoint.TiposEquipo(cliente);
        }

        @Bean
        public AlmacenRecursos almacenRecursos(ClienteSharePoint cliente, Colaboracion colaboracion,
                                               @Value("${planner.listas.permitir-vacias:false}") boolean permitirVacias) {
            return new ListasAuxiliaresSharePoint.Recursos(cliente, colaboracion, permitirVacias);
        }

        /** Sin horario de ensayos (Working Hours, D59): solo existe con PostgreSQL. */
        @Bean
        public AlmacenHorarios almacenHorarios() {
            return new AlmacenHorarios.SinHorarios();
        }
    }

    /**
     * Adaptadores de PostgreSQL. Al crear la conexión, ANTES de nada, Flyway
     * aplica o valida la estructura (MigracionesBaseDatos) con el usuario
     * propietario; la aplicación trabaja después con el usuario tbp_app, que no
     * puede cambiar la estructura.
     */
    @Configuration
    @ConditionalOnProperty(name = "planner.almacen", havingValue = "postgresql")
    public static class PostgreSql {

        @Bean(destroyMethod = "close")
        public com.zaxxer.hikari.HikariDataSource dataSource(PropiedadesPlanner p) {
            PropiedadesPlanner.PostgreSql c = p.postgresql();
            com.borgwarner.tbp.infraestructura.postgresql.MigracionesBaseDatos.preparar(c);
            com.zaxxer.hikari.HikariConfig hikari = new com.zaxxer.hikari.HikariConfig();
            hikari.setPoolName("tbp");
            hikari.setJdbcUrl(c.urlEfectiva());
            hikari.setUsername(c.usuarioAppEfectivo());
            hikari.setPassword(c.claveApp() == null ? "" : c.claveApp());
            hikari.setSchema(c.esquemaEfectivo());
            hikari.setMaximumPoolSize(c.conexionesMaximasEfectivas());
            hikari.setMinimumIdle(Math.min(2, c.conexionesMaximasEfectivas()));
            LOG.warn("Almacén POSTGRESQL {} · esquema {} · usuario {}", c.urlEfectiva(), c.esquemaEfectivo(), c.usuarioAppEfectivo());
            return new com.zaxxer.hikari.HikariDataSource(hikari);
        }

        @Bean
        public org.springframework.jdbc.datasource.DataSourceTransactionManager transactionManager(javax.sql.DataSource origen) {
            return new org.springframework.jdbc.datasource.DataSourceTransactionManager(origen);
        }

        @Bean
        public com.borgwarner.tbp.infraestructura.postgresql.BaseDatos baseDatos(javax.sql.DataSource origen,
                org.springframework.jdbc.datasource.DataSourceTransactionManager transacciones) {
            return new com.borgwarner.tbp.infraestructura.postgresql.BaseDatos(origen, transacciones);
        }

        @Bean
        public com.borgwarner.tbp.infraestructura.postgresql.UsuariosPostgres almacenUsuarios(
                com.borgwarner.tbp.infraestructura.postgresql.BaseDatos bd, PropiedadesPlanner p,
                @Value("${planner.listas.permitir-vacias:false}") boolean permitirVacias) {
            String email = p.postgresql().usuarioEmail() == null ? "" : p.postgresql().usuarioEmail().trim();
            String nombre = p.postgresql().usuarioNombre() == null ? "" : p.postgresql().usuarioNombre().trim();
            // Modo servidor (hito S3): cada petición es de quien ha iniciado sesión; la
            // identidad configurada solo vale fuera del servidor (una persona por instalación).
            var usuarios = new com.borgwarner.tbp.infraestructura.postgresql.UsuariosPostgres(bd,
                    new AlmacenUsuarios.Identidad(email, nombre), permitirVacias,
                    p.servidor().activo() ? com.borgwarner.tbp.api.acceso.IdentidadSesion::identidad : null);
            bd.identidad(usuarios::identidadActual);
            return usuarios;
        }

        /** Cuentas (contraseñas) del inicio de sesión del modo servidor (hito S3). */
        @Bean
        public com.borgwarner.tbp.infraestructura.postgresql.CuentasPostgres cuentasPostgres(
                com.borgwarner.tbp.infraestructura.postgresql.BaseDatos bd, PropiedadesAutenticacion autenticacion) {
            return new com.borgwarner.tbp.infraestructura.postgresql.CuentasPostgres(bd, autenticacion);
        }

        /** Con PostgreSQL la aplicación es compartida: un calendario común que solo cambia un Admin (D46). */
        @Bean
        public AlmacenCalendario almacenCalendario(com.borgwarner.tbp.infraestructura.postgresql.BaseDatos bd) {
            return new com.borgwarner.tbp.infraestructura.postgresql.CalendarioPostgres(bd);
        }

        @Bean
        public Colaboracion colaboracion(com.borgwarner.tbp.infraestructura.postgresql.BaseDatos bd) {
            return new com.borgwarner.tbp.infraestructura.postgresql.ColaboracionPostgres(bd);
        }

        @Bean
        public AlmacenEnsayos almacenEnsayos(com.borgwarner.tbp.infraestructura.postgresql.BaseDatos bd, Colaboracion colaboracion) {
            return new com.borgwarner.tbp.infraestructura.postgresql.EnsayosPostgres(bd, colaboracion);
        }

        @Bean
        public AlmacenCatalogos almacenCatalogos(com.borgwarner.tbp.infraestructura.postgresql.BaseDatos bd,
                                                 @Value("${planner.listas.permitir-vacias:false}") boolean permitirVacias) {
            return new com.borgwarner.tbp.infraestructura.postgresql.CatalogosPostgres(bd, permitirVacias);
        }

        @Bean
        public AlmacenHistoricoKpi almacenHistoricoKpi(com.borgwarner.tbp.infraestructura.postgresql.BaseDatos bd) {
            return new com.borgwarner.tbp.infraestructura.postgresql.HistoricoKpiPostgres(bd);
        }

        @Bean
        public AlmacenRecursos almacenRecursos(com.borgwarner.tbp.infraestructura.postgresql.BaseDatos bd, Colaboracion colaboracion,
                                               @Value("${planner.listas.permitir-vacias:false}") boolean permitirVacias) {
            return new com.borgwarner.tbp.infraestructura.postgresql.RecursosPostgres(bd, colaboracion, permitirVacias);
        }

        @Bean
        public AlmacenTiposEquipo almacenTiposEquipo(com.borgwarner.tbp.infraestructura.postgresql.BaseDatos bd) {
            return new com.borgwarner.tbp.infraestructura.postgresql.TiposEquipoPostgres(bd);
        }

        @Bean
        public ConsultaAprobaciones consultaAprobaciones(com.borgwarner.tbp.infraestructura.postgresql.BaseDatos bd) {
            return new com.borgwarner.tbp.infraestructura.postgresql.AprobacionesPostgres(bd);
        }

        /** Horario de los ensayos (Working Hours, D59) y primer mes de facturación por horas (D60). */
        @Bean
        public AlmacenHorarios almacenHorarios(com.borgwarner.tbp.infraestructura.postgresql.BaseDatos bd) {
            return new com.borgwarner.tbp.infraestructura.postgresql.HorariosPostgres(bd);
        }

        @Bean
        public com.borgwarner.tbp.infraestructura.postgresql.ImportadorDatos importadorDatos(
                com.borgwarner.tbp.infraestructura.postgresql.BaseDatos bd, AlmacenEnsayos ensayos, AlmacenCatalogos catalogos,
                AlmacenUsuarios usuarios, AlmacenRecursos recursos, AlmacenHistoricoKpi kpi, ConsultaAprobaciones aprobaciones,
                AlmacenTiposEquipo tipos, Colaboracion colaboracion) {
            return new com.borgwarner.tbp.infraestructura.postgresql.ImportadorDatos(bd,
                    new com.borgwarner.tbp.infraestructura.postgresql.VerificacionParidad(ensayos, catalogos, usuarios, recursos,
                            kpi, aprobaciones, tipos, colaboracion));
        }
    }
}
