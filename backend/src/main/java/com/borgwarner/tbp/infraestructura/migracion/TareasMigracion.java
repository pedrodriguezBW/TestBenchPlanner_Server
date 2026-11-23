package com.borgwarner.tbp.infraestructura.migracion;

import com.borgwarner.tbp.aplicacion.EstadoConexion;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenCatalogos;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenEnsayos;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenHistoricoKpi;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenRecursos;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenTiposEquipo;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenUsuarios;
import com.borgwarner.tbp.aplicacion.puertos.Colaboracion;
import com.borgwarner.tbp.aplicacion.puertos.ConsultaAprobaciones;
import com.borgwarner.tbp.configuracion.PropiedadesPlanner;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.infraestructura.postgresql.ImportadorDatos;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Exportar / importar desde la línea de órdenes (scripts/servidor/*.cmd):
 *
 *   --planner.almacen=sharepoint --planner.migracion.exportar=CARPETA   (PC con permisos)
 *   --planner.almacen=postgresql --planner.migracion.importar=CARPETA   (servidor)
 *       [--planner.migracion.simular=true]
 *
 *   Fusión de tres vías (conserva lo hecho en PostgreSQL y añade lo de SharePoint):
 *   --planner.almacen=postgresql --planner.migracion.importar=CARPETA_SHAREPOINT
 *       --planner.migracion.base=CARPETA_BASE [--planner.migracion.resoluciones=conflictos.json]
 *       [--planner.migracion.salida=CARPETA_TRABAJO] [--planner.migracion.simular=true]
 *
 * Con salir-al-terminar (por defecto) la aplicación se cierra al acabar con
 * código 0 si todo ha ido bien, 1 si no y 3 si la fusión tiene conflictos sin resolver.
 */
@Component
public class TareasMigracion implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(TareasMigracion.class);
    /** Código de salida: la fusión de tres vías tiene conflictos sin resolver (no se ha importado nada). */
    static final int CODIGO_CONFLICTOS = 3;

    private final PropiedadesPlanner propiedades;
    private final ApplicationContext contexto;
    private final ObjectProvider<ImportadorDatos> importador;
    private final ExportadorDatos exportador;

    public TareasMigracion(PropiedadesPlanner propiedades, ApplicationContext contexto, ObjectProvider<ImportadorDatos> importador,
                           AlmacenEnsayos ensayos, AlmacenCatalogos catalogos, AlmacenUsuarios usuarios, AlmacenRecursos recursos,
                           AlmacenHistoricoKpi kpi, ConsultaAprobaciones aprobaciones, AlmacenTiposEquipo tipos,
                           Colaboracion colaboracion, EstadoConexion conexion) {
        this.propiedades = propiedades;
        this.contexto = contexto;
        this.importador = importador;
        this.exportador = new ExportadorDatos(ensayos, catalogos, usuarios, recursos, kpi, aprobaciones, tipos, colaboracion, conexion);
    }

    @Override
    public void run(ApplicationArguments args) {
        PropiedadesPlanner.Migracion m = propiedades.migracion();
        String exportar = m.exportar() == null ? "" : m.exportar().trim();
        String importar = m.importar() == null ? "" : m.importar().trim();
        if (exportar.isEmpty() && importar.isEmpty()) {
            return;
        }
        int codigo = 0;
        try {
            if (!exportar.isEmpty()) {
                exportar(Path.of(exportar));
            }
            if (!importar.isEmpty()) {
                String base = m.base() == null ? "" : m.base().trim();
                codigo = base.isEmpty() ? (importar(Path.of(importar), m.simular()) ? 0 : 1)
                        : reconciliar(Path.of(base), Path.of(importar), m);
            }
        } catch (RuntimeException e) {
            LOG.error("La migración de datos no se ha completado: {}", e.getMessage(), e);
            codigo = 1;
        }
        if (m.salirAlTerminar()) {
            int fin = codigo;
            System.exit(SpringApplication.exit(contexto, () -> fin));
        }
    }

    private void exportar(Path destino) {
        String almacen = propiedades.almacen() == null ? "local" : propiedades.almacen();
        if ("sharepoint".equals(almacen) && propiedades.sharepoint() != null && propiedades.sharepoint().escrituraHabilitada()) {
            throw new IllegalStateException("La exportación se hace SIEMPRE en solo lectura: arranca sin "
                    + "--planner.sharepoint.escritura-habilitada=true");
        }
        String origen = "sharepoint".equals(almacen) && propiedades.sharepoint() != null ? propiedades.sharepoint().sitio() : almacen;
        ObjectNode manifiesto = exportador.exportar(destino, almacen, origen, propiedades.migracion().maxActividadEfectiva());
        LOG.info("EXPORTACIÓN COMPLETA en {}\n{}", destino.toAbsolutePath(), bonito(manifiesto));
    }

    private boolean importar(Path carpeta, boolean simular) {
        ImportadorDatos imp = importador.getIfAvailable();
        if (imp == null) {
            throw new IllegalStateException("La importación solo se hace con --planner.almacen=postgresql");
        }
        ObjectNode informe = imp.importar(carpeta, simular);
        String resultado = informe.path("resultado").asText();
        if ("RECHAZADO".equals(resultado)) {
            LOG.error("IMPORTACIÓN RECHAZADA: la base no queda igual que la exportación; no se ha guardado nada.\n{}", bonito(informe));
            return false;
        }
        LOG.info("IMPORTACIÓN {}\n{}", "SIMULADO".equals(resultado) ? "SIMULADA (no se ha guardado nada)" : "COMPLETA", bonito(informe));
        return true;
    }

    /** Importación con fusión de tres vías (ReconciliacionTresVias). */
    private int reconciliar(Path base, Path sharepoint, PropiedadesPlanner.Migracion m) {
        ImportadorDatos imp = importador.getIfAvailable();
        if (imp == null) {
            throw new IllegalStateException("La importación solo se hace con --planner.almacen=postgresql");
        }
        String resoluciones = m.resoluciones() == null ? "" : m.resoluciones().trim();
        String salida = m.salida() == null ? "" : m.salida().trim();
        ObjectNode informe = new ReconciliacionTresVias(imp, exportador).ejecutar(base, sharepoint,
                resoluciones.isEmpty() ? null : Path.of(resoluciones), salida.isEmpty() ? null : Path.of(salida), m.simular(),
                m.maxActividadEfectiva());
        String resultado = informe.path("resultado").asText();
        String trabajo = informe.path("carpetaTrabajo").asText();
        if (ReconciliacionTresVias.CONFLICTOS.equals(resultado)) {
            LOG.error("FUSIÓN CON CONFLICTOS: {} sin resolver; no se ha guardado nada.\n{}\n"
                            + "Resuélvelos en {}\\conflictos.json y vuelve a lanzar con ese fichero.",
                    informe.path("conflictosPendientes").asInt(), bonito(informe), trabajo);
            return CODIGO_CONFLICTOS;
        }
        if ("RECHAZADO".equals(resultado)) {
            LOG.error("FUSIÓN RECHAZADA: la base no queda igual que la carpeta fusionada; no se ha guardado nada.\n{}",
                    bonito(informe));
            return 1;
        }
        LOG.info("FUSIÓN DE TRES VÍAS {} (carpeta de trabajo: {})\n{}",
                "SIMULADO".equals(resultado) ? "SIMULADA (no se ha guardado nada)" : "COMPLETA", trabajo, bonito(informe));
        return 0;
    }

    private static String bonito(ObjectNode n) {
        try {
            return JsonJs.mapper().writer().with(SerializationFeature.INDENT_OUTPUT).writeValueAsString(n);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return n.toString();
        }
    }
}
