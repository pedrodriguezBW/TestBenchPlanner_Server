package com.borgwarner.tbp.infraestructura.migracion;

import com.borgwarner.tbp.TestBenchPlannerApplication;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.infraestructura.postgresql.ImportadorDatos;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Importación con fusión de tres vías (servidor, perfil postgresql):
 *
 *  1. Exporta el estado ACTUAL de PostgreSQL con los mismos adaptadores que la
 *     exportación de SharePoint (carpeta «postgresql-antes»; sirve además de
 *     copia en JSON de lo que había antes de importar).
 *  2. Fusiona BASE (exportación con la que se cargó la base) + SHAREPOINT
 *     (exportación actual) + POSTGRESQL (paso 1) con {@link FusionTresVias}.
 *  3. Escribe la carpeta «fusion», «conflictos.json» e «informe.json».
 *  4. Si quedan conflictos sin resolver, PARA: no toca la base (resultado CONFLICTOS).
 *  5. Si no, importa «fusion» con el importador de siempre (una transacción,
 *     verificación de paridad, simular = deshacer al final). Como la carpeta
 *     fusionada ya contiene todo lo de PostgreSQL, la importación solo añade lo
 *     que llega de SharePoint.
 *
 * Para resolver conflictos: editar «elegir» en conflictos.json y volver a
 * lanzar con ese fichero como resoluciones.
 */
public class ReconciliacionTresVias {

    private static final Logger LOG = LoggerFactory.getLogger(ReconciliacionTresVias.class);

    public static final String CONFLICTOS = "CONFLICTOS";
    static final String CARPETA_ANTES = "postgresql-antes";
    static final String CARPETA_FUSION = "fusion";
    static final String FICHERO_CONFLICTOS = "conflictos.json";
    static final String FICHERO_INFORME = "informe.json";

    private final ImportadorDatos importador;
    private final ExportadorDatos exportador;

    public ReconciliacionTresVias(ImportadorDatos importador, ExportadorDatos exportador) {
        this.importador = importador;
        this.exportador = exportador;
    }

    /**
     * @param base         exportación de SharePoint con la que se cargó PostgreSQL (estado común)
     * @param sharepoint   exportación actual de SharePoint (con manifiesto)
     * @param resoluciones conflictos.json editado, o null
     * @param salida       carpeta de trabajo nueva o vacía, o null (junto a la de SharePoint, con fecha)
     * @param simular      true = todo, incluida la verificación, y se deshace al final
     * @param maxActividad filas del registro de actividad de PostgreSQL que se exportan
     * @return informe; «resultado»: IMPORTADO | SIMULADO | RECHAZADO | CONFLICTOS
     */
    public ObjectNode ejecutar(Path base, Path sharepoint, Path resoluciones, Path salida, boolean simular, int maxActividad) {
        CarpetaExportacion carpetaBase = new CarpetaExportacion(base);
        carpetaBase.comprobar();
        CarpetaExportacion carpetaSharePoint = new CarpetaExportacion(sharepoint);
        ObjectNode manifiestoSharePoint = carpetaSharePoint.comprobar();
        if (manifiestoSharePoint == null) {
            throw new IllegalArgumentException("La carpeta de SharePoint no tiene manifiesto (" + CarpetaExportacion.MANIFIESTO
                    + "): tiene que ser una exportación completa hecha con exportar-desde-sharepoint.cmd");
        }
        if (base.toAbsolutePath().normalize().equals(sharepoint.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("La carpeta base y la de SharePoint son la misma");
        }
        FusionTresVias.Resoluciones res = FusionTresVias.Resoluciones.NINGUNA;
        if (resoluciones != null) {
            res = FusionTresVias.Resoluciones.de(leerArbol(resoluciones));
        }
        Path trabajo = salida != null ? salida : sharepoint.toAbsolutePath().getParent()
                .resolve("reconciliacion-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));

        ObjectNode informe = JsonJs.objetoVacio();
        informe.put("fecha", Instant.now().toString());
        informe.put("modo", simular ? "simular" : "aplicar");
        informe.put("base", base.toAbsolutePath().toString());
        informe.put("sharepoint", sharepoint.toAbsolutePath().toString());
        informe.put("resoluciones", resoluciones == null ? "" : resoluciones.toAbsolutePath().toString());
        informe.put("carpetaTrabajo", trabajo.toAbsolutePath().toString());
        informe.set("exportacionSharePoint", manifiestoSharePoint.deepCopy());

        // 1) Estado actual de PostgreSQL.
        Path antes = trabajo.resolve(CARPETA_ANTES);
        exportador.exportar(antes, "postgresql", "base de datos del servidor (antes de la reconciliación)", maxActividad);

        // 2) Fusión.
        FusionTresVias.Resultado r = new FusionTresVias(ReconciliacionTresVias::leerTexto, ReconciliacionTresVias::escribirTexto, res)
                .fusionar(leerCarpeta(carpetaBase), leerCarpeta(carpetaSharePoint), leerCarpeta(new CarpetaExportacion(antes)));

        // 3) Ficheros de trabajo.
        Path fusion = trabajo.resolve(CARPETA_FUSION);
        escribirFusion(fusion, r);
        Map<String, String> descripciones = descripciones(r, carpetaSharePoint, new CarpetaExportacion(antes));
        escribirConflictos(trabajo.resolve(FICHERO_CONFLICTOS), r, res, descripciones);
        informe.set("recuentos", JsonJs.mapper().valueToTree(r.recuentos()));
        ArrayNode avisos = informe.putArray("avisos");
        r.avisos().forEach(avisos::add);
        long borrados = r.decisiones().stream().filter(d -> !FusionTresVias.CAMPO.equals(d.tipo())).count();
        informe.put("conflictosPendientes", r.pendientes().size());
        informe.put("conflictosResueltos", r.decisiones().stream().filter(d -> FusionTresVias.CAMPO.equals(d.tipo()) && !d.pendiente()).count());
        informe.put("borradosRevisables", borrados);

        // 4) Conflictos pendientes: no se toca la base.
        if (!r.pendientes().isEmpty()) {
            informe.put("resultado", CONFLICTOS);
            ArrayNode lista = informe.putArray("pendientes");
            r.pendientes().stream().limit(50).forEach(d -> lista.add(d.id()));
            escribir(trabajo.resolve(FICHERO_INFORME), informe);
            LOG.warn("Reconciliación con {} conflictos sin resolver: no se ha importado nada. Revisa {}", r.pendientes().size(),
                    trabajo.resolve(FICHERO_CONFLICTOS));
            return informe;
        }

        // 5) Importación de la carpeta fusionada.
        ObjectNode importacion = importador.importar(fusion, simular);
        informe.put("resultado", importacion.path("resultado").asText());
        informe.set("importacion", importacion);
        escribir(trabajo.resolve(FICHERO_INFORME), informe);
        return informe;
    }

    // ---- lectura y escritura ------------------------------------------------------------

    private static Map<String, Object> leerCarpeta(CarpetaExportacion carpeta) {
        Map<String, Object> ficheros = new LinkedHashMap<>();
        for (String f : CarpetaExportacion.FICHEROS) {
            if (carpeta.existe(f)) {
                ficheros.put(f, leerArbol(carpeta.carpeta().resolve(f)));
            }
        }
        return ficheros;
    }

    private static Object leerArbol(Path fichero) {
        try {
            return JsonJs.mapper().readValue(fichero.toFile(), Object.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("No se puede leer " + fichero + ": " + e.getMessage(), e);
        }
    }

    /** DataJson → árbol (Map/List…). */
    private static Object leerTexto(String texto) {
        try {
            return JsonJs.mapper().readValue(texto, Object.class);
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** Árbol → DataJson con el formato de JSON.stringify (como lo escribe la aplicación). */
    private static String escribirTexto(Object arbol) {
        return JsonJs.escribir(JsonJs.mapper().valueToTree(arbol));
    }

    /** La carpeta fusionada con su manifiesto (huellas), como una exportación más. */
    private static void escribirFusion(Path destino, FusionTresVias.Resultado r) {
        try {
            if (Files.exists(destino)) {
                try (var dentro = Files.list(destino)) {
                    if (dentro.findAny().isPresent()) {
                        throw new IllegalArgumentException("La carpeta de la fusión debe ser nueva o estar vacía: " + destino);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        CarpetaExportacion carpeta = new CarpetaExportacion(destino);
        ObjectNode recuentos = JsonJs.objetoVacio();
        for (Map.Entry<String, Object> f : r.ficheros().entrySet()) {
            JsonNode contenido = JsonJs.mapper().valueToTree(f.getValue());
            carpeta.escribir(f.getKey(), contenido);
            if (contenido.isArray()) {
                recuentos.put(f.getKey(), contenido.size());
            }
        }
        ObjectNode manifiesto = JsonJs.objetoVacio();
        manifiesto.put("formato", 1);
        manifiesto.put("aplicacion", TestBenchPlannerApplication.VERSION);
        manifiesto.put("almacen", "fusion-tres-vias");
        manifiesto.put("origen", "base + SharePoint + PostgreSQL");
        manifiesto.put("fecha", Instant.now().toString());
        manifiesto.set("recuentos", recuentos);
        ObjectNode huellas = manifiesto.putObject("ficheros");
        for (String f : r.ficheros().keySet()) {
            huellas.put(f, carpeta.huella(f));
        }
        carpeta.escribir(CarpetaExportacion.MANIFIESTO, manifiesto);
    }

    /**
     * conflictos.json: todas las decisiones. En los CAMPO pendientes hay que
     * rellenar «elegir» con "local" (PostgreSQL) o "sharepoint"; en los
     * borrados se puede cambiar entre "conservar" y "borrar".
     */
    private static void escribirConflictos(Path fichero, FusionTresVias.Resultado r, FusionTresVias.Resoluciones res,
                                           Map<String, String> descripciones) {
        ObjectNode raiz = JsonJs.objetoVacio();
        raiz.put("instrucciones", "CAMPO: rellena «elegir» con \"local\" (PostgreSQL) o \"sharepoint\". "
                + "BORRADO / BORRADO_MODIFICADO: «elegir» \"conservar\" o \"borrar\". "
                + "«porDefecto» (\"local\" o \"sharepoint\") se aplica a los CAMPO que queden vacíos. "
                + "Después vuelve a lanzar la importación pasando este fichero.");
        raiz.put("porDefecto", res.porDefecto());
        raiz.put("pendientes", r.pendientes().size());
        ArrayNode lista = raiz.putArray("decisiones");
        for (FusionTresVias.Decision d : r.decisiones()) {
            ObjectNode o = lista.addObject();
            o.put("id", d.id());
            o.put("tipo", d.tipo());
            o.put("fichero", d.fichero());
            o.put("elemento", d.elemento());
            String descripcion = descripciones.get(d.id());
            if (descripcion != null) {
                o.put("descripcion", descripcion);
            }
            if (FusionTresVias.CAMPO.equals(d.tipo())) {
                o.put("campo", d.campo());
            } else {
                o.put("borradoEn", d.ladoBorrado());
            }
            o.set("base", nodo(d.base()));
            o.set("sharepoint", nodo(d.sharepoint()));
            o.set("local", nodo(d.local()));
            // Lo que eligió el usuario se mantiene; lo aplicado por defecto también se ve.
            o.put("elegir", d.pendiente() ? "" : d.elegido());
        }
        escribir(fichero, raiz);
    }

    /** Para los ensayos: «banco · tipo · proyecto», para encontrarlos sin buscar el uid. */
    private static Map<String, String> descripciones(FusionTresVias.Resultado r, CarpetaExportacion sharepoint, CarpetaExportacion local) {
        Map<String, String> porUid = new HashMap<>();
        for (CarpetaExportacion c : List.of(sharepoint, local)) {
            for (JsonNode fila : c.leer(FusionTresVias.PLANNER, JsonJs.mapper().createArrayNode())) {
                ObjectNode data = fila.path("data").isTextual() ? JsonJs.leerObjeto(fila.path("data").asText()) : null;
                if (data != null) {
                    porUid.put(fila.path("uid").asText(""), String.join(" · ", data.path("_bench").asText(""),
                            data.path("type").asText(""), data.path("project").asText("")));
                }
            }
        }
        Map<String, String> salida = new HashMap<>();
        for (FusionTresVias.Decision d : r.decisiones()) {
            if (FusionTresVias.PLANNER.equals(d.fichero()) && d.elemento().startsWith("uid=")) {
                String uid = d.elemento().substring(4);
                if (porUid.containsKey(uid)) {
                    salida.put(d.id(), porUid.get(uid));
                }
            }
        }
        return salida;
    }

    private static JsonNode nodo(Object valor) {
        return valor == FusionTresVias.AUSENTE ? JsonJs.mapper().getNodeFactory().textNode(FusionTresVias.TEXTO_AUSENTE)
                : JsonJs.mapper().valueToTree(valor);
    }

    private static void escribir(Path fichero, JsonNode contenido) {
        new CarpetaExportacion(fichero.getParent()).escribir(fichero.getFileName().toString(), contenido);
    }
}
