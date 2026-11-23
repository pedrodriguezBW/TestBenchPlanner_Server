package com.borgwarner.tbp.infraestructura.migracion;

import com.borgwarner.tbp.TestBenchPlannerApplication;
import com.borgwarner.tbp.aplicacion.EstadoConexion;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenCatalogos;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenEnsayos;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenHistoricoKpi;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenRecursos;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenTiposEquipo;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenUsuarios;
import com.borgwarner.tbp.aplicacion.puertos.Colaboracion;
import com.borgwarner.tbp.aplicacion.puertos.ConsultaAprobaciones;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.facturacion.TipoEquipo;
import com.borgwarner.tbp.dominio.planificacion.FilaGuardada;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Paso 1 de la migración: lee TODO lo que la aplicación guarda en el almacén
 * configurado (SharePoint en el PC con permisos) a través de los mismos
 * adaptadores que usa la aplicación, y lo escribe en una carpeta con el
 * formato de CarpetaExportacion.
 *
 * Garantías:
 *  - Solo lectura: con SharePoint, si la escritura está habilitada se niega a
 *    exportar (así es imposible que la exportación toque las listas).
 *  - Nada a medias: si una lista no se ha podido leer, se ha servido desde la
 *    copia sin conexión o ha llegado vacía cuando no puede estarlo, se para y
 *    no se escribe el manifiesto (una carpeta sin manifiesto completo no se
 *    puede importar como exportación).
 *  - DataJson íntegro: se copia el texto tal cual, byte a byte.
 */
public class ExportadorDatos {

    private static final Logger LOG = LoggerFactory.getLogger(ExportadorDatos.class);

    private final AlmacenEnsayos ensayos;
    private final AlmacenCatalogos catalogos;
    private final AlmacenUsuarios usuarios;
    private final AlmacenRecursos recursos;
    private final AlmacenHistoricoKpi kpi;
    private final ConsultaAprobaciones aprobaciones;
    private final AlmacenTiposEquipo tipos;
    private final Colaboracion colaboracion;
    private final EstadoConexion conexion;

    public ExportadorDatos(AlmacenEnsayos ensayos, AlmacenCatalogos catalogos, AlmacenUsuarios usuarios, AlmacenRecursos recursos,
                           AlmacenHistoricoKpi kpi, ConsultaAprobaciones aprobaciones, AlmacenTiposEquipo tipos,
                           Colaboracion colaboracion, EstadoConexion conexion) {
        this.ensayos = ensayos;
        this.catalogos = catalogos;
        this.usuarios = usuarios;
        this.recursos = recursos;
        this.kpi = kpi;
        this.aprobaciones = aprobaciones;
        this.tipos = tipos;
        this.colaboracion = colaboracion;
        this.conexion = conexion;
    }

    /** La exportación no se puede completar; no se ha escrito el manifiesto. */
    public static class ExportacionFallida extends RuntimeException {
        public ExportacionFallida(String mensaje) {
            super(mensaje);
        }
    }

    /**
     * @param destino   carpeta nueva o vacía
     * @param almacen   almacén de origen (para el manifiesto)
     * @param origen    sitio o descripción del origen (para el manifiesto)
     * @param maxActividad filas del registro de actividad a exportar (las más recientes)
     * @return el manifiesto escrito
     */
    public ObjectNode exportar(Path destino, String almacen, String origen, int maxActividad) {
        prepararCarpeta(destino);
        CarpetaExportacion carpeta = new CarpetaExportacion(destino);
        ArrayNode avisos = JsonJs.mapper().createArrayNode();
        ObjectNode recuentos = JsonJs.objetoVacio();

        // 1) Leer todo antes de escribir nada.
        List<FilaGuardada> filas = ensayos.leer();
        ArrayNode planner = JsonJs.mapper().createArrayNode();
        for (FilaGuardada f : filas) {
            ObjectNode o = planner.addObject();
            o.put("uid", f.uid());
            o.put("data", f.texto());
        }
        exigir(!filas.isEmpty(), "la lista de ensayos ha llegado vacía");
        ObjectNode cfg = catalogos.leer().deepCopy();
        for (String clave : List.of("responsibles_error", "projects_error", "benches_error")) {
            exigir(!cfg.has(clave), "no se ha podido leer una lista de catálogos (" + clave + ": " + cfg.path(clave).asText() + ")");
        }
        exigir(cfg.path("benches").size() > 0, "la lista de bancos ha llegado vacía");
        exigir(cfg.path("projects").size() > 0, "la lista de proyectos ha llegado vacía");
        cfg.remove("ok");
        ArrayNode listaUsuarios = usuarios.leer();
        exigir(listaUsuarios.size() > 0, "la lista de usuarios ha llegado vacía (¿se ha podido leer TestBenchUsers?)");
        ObjectNode rec = recursos.leer().deepCopy();
        exigir(!rec.has("resources_error") && !rec.has("blocks_error"), "no se han podido leer los equipos auxiliares o sus bloqueos ("
                + rec.path("resources_error").asText(rec.path("blocks_error").asText()) + ")");
        ArrayNode filasKpi = JsonJs.mapper().createArrayNode();
        kpi.leer().forEach(filasKpi::add);
        ArrayNode listaAprobaciones = aprobaciones.todas();
        ObjectNode tiposEquipo = JsonJs.objetoVacio();
        ArrayNode listaTipos = tiposEquipo.putArray("tipos");
        try {
            for (TipoEquipo t : tipos.leer()) {
                listaTipos.add(t.json());
            }
        } catch (RuntimeException e) {
            avisos.add("Equipment Types: " + e.getMessage() + " (se exporta vacía)");
        }
        String version = colaboracion.versionPublicada();
        ArrayNode actividad = JsonJs.mapper().createArrayNode();
        if (maxActividad > 0) {
            ObjectNode leida = colaboracion.leerActividad(maxActividad);
            if (leida.has("error")) {
                avisos.add("Registro de actividad: " + leida.path("error").asText() + " (se exporta vacío)");
            } else {
                JsonNode rows = leida.path("rows");
                for (int i = rows.size() - 1; i >= 0; i--) {
                    actividad.add(rows.get(i)); // del más antiguo al más reciente, como el modo local
                }
            }
        }
        exigir(!conexion.sinConexion(), "SharePoint no ha respondido y se ha usado la copia sin conexión de este PC");

        // 2) Escribir los ficheros y, al final, el manifiesto con sus huellas.
        carpeta.escribir("planner.json", planner);
        carpeta.escribir("configuracion.json", cfg);
        carpeta.escribir("usuarios.json", listaUsuarios);
        carpeta.escribir("recursos.json", rec);
        carpeta.escribir("kpis.json", filasKpi);
        carpeta.escribir("aprobaciones.json", listaAprobaciones);
        carpeta.escribir("tipos-equipo.json", tiposEquipo);
        ObjectNode v = JsonJs.objetoVacio();
        if (version == null) {
            v.putNull("published");
        } else {
            v.put("published", version);
        }
        carpeta.escribir("version.json", v);
        carpeta.escribir(CarpetaExportacion.ACTIVIDAD, actividad);

        recuentos.put("ensayos", planner.size());
        recuentos.put("responsables", cfg.path("responsibles").size());
        recuentos.put("proyectos", cfg.path("projects").size());
        recuentos.put("bancos", cfg.path("benches").size());
        recuentos.put("usuarios", listaUsuarios.size());
        recuentos.put("recursos", rec.path("resources").size());
        recuentos.put("bloqueos", rec.path("blocks").size());
        recuentos.put("kpi", filasKpi.size());
        recuentos.put("aprobaciones", listaAprobaciones.size());
        recuentos.put("tiposEquipo", listaTipos.size());
        recuentos.put("actividad", actividad.size());

        ObjectNode manifiesto = JsonJs.objetoVacio();
        manifiesto.put("formato", 1);
        manifiesto.put("aplicacion", TestBenchPlannerApplication.VERSION);
        manifiesto.put("almacen", almacen);
        manifiesto.put("origen", origen == null ? "" : origen);
        manifiesto.put("fecha", Instant.now().toString());
        manifiesto.set("recuentos", recuentos);
        ObjectNode huellas = manifiesto.putObject("ficheros");
        for (String f : CarpetaExportacion.FICHEROS) {
            huellas.put(f, carpeta.huella(f));
        }
        manifiesto.set("avisos", avisos);
        carpeta.escribir(CarpetaExportacion.MANIFIESTO, manifiesto);
        LOG.info("Exportación terminada en {}: {}", destino.toAbsolutePath(), JsonJs.escribir(recuentos));
        return manifiesto;
    }

    private static void exigir(boolean condicion, String motivo) {
        if (!condicion) {
            throw new ExportacionFallida("Exportación cancelada: " + motivo + ". No se ha escrito el manifiesto; "
                    + "vuelve a intentarlo cuando SharePoint responda con normalidad.");
        }
    }

    private static void prepararCarpeta(Path destino) {
        try {
            if (Files.exists(destino)) {
                if (!Files.isDirectory(destino)) {
                    throw new IllegalArgumentException("El destino de la exportación no es una carpeta: " + destino);
                }
                try (Stream<Path> dentro = Files.list(destino)) {
                    Iterator<Path> it = dentro.iterator();
                    if (it.hasNext()) {
                        throw new IllegalArgumentException("La carpeta de la exportación debe ser nueva o estar vacía: " + destino);
                    }
                }
            }
            Files.createDirectories(destino);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
