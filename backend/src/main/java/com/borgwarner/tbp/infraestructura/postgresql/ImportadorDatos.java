package com.borgwarner.tbp.infraestructura.postgresql;

import com.borgwarner.tbp.aplicacion.puertos.AlmacenUsuarios;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.facturacion.TipoEquipo;
import com.borgwarner.tbp.dominio.planificacion.Areas;
import com.borgwarner.tbp.infraestructura.migracion.CarpetaExportacion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Importa una exportación (carpeta con el formato de CarpetaExportacion) en
 * PostgreSQL. Es IDEMPOTENTE: deja la base exactamente con los datos de la
 * exportación, así que repetirla con los mismos ficheros no cambia nada, y
 * con una exportación más reciente solo aplica las diferencias.
 *
 *  - Todo en UNA transacción: si algo falla, la base queda como estaba.
 *  - Antes de confirmar se VERIFICA la paridad: lo que devuelve la base con
 *    los adaptadores de PostgreSQL debe ser igual a lo que devuelve el modo
 *    local leyendo la misma carpeta (que es lo que había en SharePoint). Si hay
 *    diferencias, se deshace todo y se informa.
 *  - simular=true hace todo (incluida la verificación) y lo deshace al final:
 *    sirve para ver qué cambiaría.
 *  - Cada cambio queda en la auditoría con operación IMPORTACION. El registro de
 *    actividad solo se AÑADE (nunca se borra lo anotado en la base).
 *  - D65: las tablas que solo existen en el servidor (Timing R&T, cuentas,
 *    horarios, calendario) no están en la exportación. Se bloquean durante la
 *    importación y, si al terminar han cambiado en algo (p. ej. al borrar un
 *    usuario se irían sus perfiles de Timing), se deshace todo (RECHAZADO).
 */
public class ImportadorDatos {

    private static final Logger LOG = LoggerFactory.getLogger(ImportadorDatos.class);
    static final AlmacenUsuarios.Identidad IMPORTACION = new AlmacenUsuarios.Identidad("importacion", "Importación");
    /**
     * Tablas en orden de vaciado (reinicio de pruebas). La importación normal no
     * toca «horario_ensayo» (D59) ni «credencial».
     */
    static final List<String> TABLAS = List.of("credencial", "horario_ensayo", "ensayo", "auditoria", "registro_actividad", "responsable", "proyecto", "banco",
            "nombre_area", "configuracion_app", "usuario", "recurso", "bloqueo_recurso", "kpi_semana", "aprobacion",
            "tipo_equipo", "presencia", "festivo", "calendario");

    /**
     * D65: tablas solo del servidor que la importación NUNCA puede cambiar, con
     * las columnas que se comparan (en credencial, solo email y contraseña: el
     * último acceso puede cambiar mientras se simula con el servicio en marcha).
     */
    static final Map<String, String> TABLAS_DEL_SERVIDOR = tablasDelServidor();

    private static Map<String, String> tablasDelServidor() {
        Map<String, String> t = new LinkedHashMap<>();
        t.put("credencial", "t.email, t.hash");
        for (String tabla : List.of("timing_perfil", "timing_equipo", "timing_tarea", "timing_avance", "timing_historial",
                "timing_vinculo", "horario_ensayo", "festivo", "calendario")) {
            t.put(tabla, "t.*");
        }
        return java.util.Collections.unmodifiableMap(t);
    }

    /** Clave de configuracion_app con la exportación de SharePoint importada la última vez (D65). */
    static final String CLAVE_ULTIMA_EXPORTACION = "migracion.ultima-exportacion";

    private final BaseDatos bd;
    private final VerificacionParidad verificacion;

    public ImportadorDatos(BaseDatos bd, VerificacionParidad verificacion) {
        this.bd = bd;
        this.verificacion = verificacion;
    }

    /** Se deshace la transacción para simular o porque la verificación no cuadra. */
    private static final class Deshacer extends RuntimeException {
        Deshacer() {
            super(null, null, false, false);
        }
    }

    /**
     * Importa la carpeta. Devuelve el informe: {"resultado": IMPORTADO | SIMULADO | RECHAZADO,
     * "tablas": {…recuentos…}, "avisos": […], "verificacion": {"ok":…, "diferencias":[…]}}.
     */
    public ObjectNode importar(Path carpeta, boolean simular) {
        CarpetaExportacion datos = new CarpetaExportacion(carpeta);
        ObjectNode manifiesto = datos.comprobar();
        ObjectNode informe = JsonJs.objetoVacio();
        informe.put("carpeta", carpeta.toAbsolutePath().toString());
        informe.set("exportacion", manifiesto == null ? JsonJs.objetoVacio().put("aviso", "sin manifiesto") : manifiesto.deepCopy());
        ObjectNode tablas = informe.putObject("tablas");
        ArrayNode avisos = informe.putArray("avisos");
        boolean[] verificada = {false};
        try {
            bd.enTransaccion(() -> {
                bloquearTodo();
                bd.jdbc().execute("LOCK TABLE " + String.join(", ", TABLAS_DEL_SERVIDOR.keySet()) + " IN SHARE MODE");
                Map<String, String> antes = huellasDelServidor();
                cargar(datos, tablas, avisos);
                List<String> diferencias = new ArrayList<>(verificacion.comparar(carpeta));
                Map<String, String> despues = huellasDelServidor();
                for (String tabla : TABLAS_DEL_SERVIDOR.keySet()) {
                    if (!Objects.equals(antes.get(tabla), despues.get(tabla))) {
                        diferencias.add("la importación cambiaría la tabla del servidor «" + tabla
                                + "», que no viene de SharePoint (p. ej. al borrar un usuario se borran sus perfiles de Timing)");
                    }
                }
                ObjectNode v = informe.putObject("verificacion").put("ok", diferencias.isEmpty());
                ArrayNode lista = v.putArray("diferencias");
                diferencias.forEach(lista::add);
                verificada[0] = diferencias.isEmpty();
                if (!diferencias.isEmpty() || simular) {
                    throw new Deshacer();
                }
                return null;
            });
            informe.put("resultado", "IMPORTADO");
        } catch (Deshacer d) {
            informe.put("resultado", verificada[0] ? "SIMULADO" : "RECHAZADO");
        }
        LOG.info("Importación {}: {}", informe.path("resultado").asText(), JsonJs.escribir(tablas));
        return informe;
    }

    /**
     * Solo pruebas y laboratorio: vacía TODAS las tablas (también auditoría y
     * actividad) y carga la carpeta, sin verificar. Equivale a copiar un
     * escenario en el modo local.
     */
    public void reiniciar(Path carpeta) {
        CarpetaExportacion datos = new CarpetaExportacion(carpeta);
        datos.comprobar();
        bd.enTransaccion(() -> {
            bloquearTodo();
            for (String tabla : TABLAS) {
                bd.jdbc().update("DELETE FROM " + tabla);
            }
            cargar(datos, JsonJs.objetoVacio(), JsonJs.mapper().createArrayNode());
            return null;
        });
    }

    /** Huella MD5 del contenido de cada tabla del servidor (D65). */
    private Map<String, String> huellasDelServidor() {
        Map<String, String> h = new LinkedHashMap<>();
        for (Map.Entry<String, String> t : TABLAS_DEL_SERVIDOR.entrySet()) {
            h.put(t.getKey(), bd.jdbc().queryForObject("SELECT md5(coalesce(string_agg(x.fila, '|' ORDER BY x.fila), '')) FROM "
                    + "(SELECT ROW(" + t.getValue() + ")::text AS fila FROM " + t.getKey() + " t) x", String.class));
        }
        return h;
    }

    // ---- D65: protección de lo creado en el servidor y comprobación de la base común --------

    /**
     * Elementos que la fusión de tres vías nunca borra aunque falten en SharePoint
     * (o la resolución diga «borrar»): usuarios con perfil de Timing R&T o con
     * cuenta, y proyectos que usan las tareas o las correspondencias de Timing.
     * Clave: «fichero#ruta» de la lista (como en FusionTresVias.claveDeLista).
     */
    public Map<String, Set<String>> protegidos() {
        Set<String> usuarios = new LinkedHashSet<>();
        bd.jdbc().queryForList("SELECT lower(email) FROM timing_perfil UNION SELECT lower(email) FROM credencial", String.class)
                .forEach(usuarios::add);
        Set<String> proyectos = new LinkedHashSet<>();
        bd.jdbc().query("SELECT p.titulo, p.numero_proyecto FROM proyecto p WHERE p.id::text IN "
                        + "(SELECT datos->>'proyectoId' FROM timing_tarea WHERE datos->>'proyectoId' IS NOT NULL) "
                        + "OR p.titulo IN (SELECT destino FROM timing_vinculo WHERE tipo = 'proyecto')",
                (java.sql.ResultSet rs) -> {
                    while (rs.next()) {
                        proyectos.add(NombresProyecto.etiqueta(rs.getString(1), rs.getString(2)));
                    }
                    return null;
                });
        Map<String, Set<String>> salida = new LinkedHashMap<>();
        salida.put("usuarios.json#", usuarios);
        salida.put("configuracion.json#projects", proyectos);
        return salida;
    }

    /**
     * ¿Es «base» la exportación con la que se cargó la base de datos? (D65)
     *
     *  - Si la base ya guardó la exportación de su última fusión (registrarExportacion),
     *    la carpeta tiene que ser esa misma (huellas SHA-256 iguales).
     *  - Si no (base cargada antes de D65), se compara cada ensayo de la carpeta con
     *    lo que dejó la última importación según la auditoría.
     *
     * Devuelve {"verificada": bool, "metodo": ..., "detalle": ..., ...recuentos}.
     */
    public ObjectNode comprobarBase(Path base) {
        CarpetaExportacion carpeta = new CarpetaExportacion(base);
        ObjectNode r = JsonJs.objetoVacio();
        List<String> guardada = bd.jdbc().queryForList("SELECT valor FROM configuracion_app WHERE clave = ?", String.class,
                CLAVE_ULTIMA_EXPORTACION);
        if (!guardada.isEmpty()) {
            r.put("metodo", "exportación registrada en la última fusión");
            JsonNode registro = JsonJs.leerObjeto(guardada.get(0));
            r.set("esperada", registro.deepCopy());
            JsonNode manifiesto = carpeta.existe(CarpetaExportacion.MANIFIESTO)
                    ? carpeta.leer(CarpetaExportacion.MANIFIESTO, JsonJs.objetoVacio()) : JsonJs.objetoVacio();
            boolean igual = !registro.path("ficheros").isEmpty()
                    && registro.path("ficheros").equals(manifiesto.path("ficheros"));
            r.put("verificada", igual);
            r.put("detalle", igual ? "La carpeta base es la exportación de SharePoint importada la última vez ("
                    + registro.path("fecha").asText() + ")."
                    : "La carpeta base NO es la exportación de SharePoint importada la última vez (" + registro.path("fecha").asText()
                    + ", " + registro.path("carpeta").asText() + "). Usa esa carpeta como base.");
            return r;
        }
        r.put("metodo", "auditoría de la última importación (ensayos)");
        Map<String, String> importados = new HashMap<>();
        bd.jdbc().query("SELECT DISTINCT ON (clave) clave, despues FROM auditoria WHERE entidad = 'ensayo' AND operacion = ? "
                + "ORDER BY clave, id DESC", (java.sql.ResultSet rs) -> {
                    while (rs.next()) {
                        if (rs.getString(2) != null) {
                            importados.put(rs.getString(1), rs.getString(2));
                        }
                    }
                    return null;
                }, Auditoria.IMPORTACION);
        Map<String, String> enBase = new LinkedHashMap<>();
        for (JsonNode fila : carpeta.leer("planner.json", JsonJs.mapper().createArrayNode())) {
            String uid = fila.path("uid").asText("");
            JsonNode data = fila.get("data");
            if (!uid.isEmpty() && data != null && !data.isNull()) {
                enBase.putIfAbsent(uid, data.isTextual() ? data.textValue() : JsonJs.escribir(data));
            }
        }
        int iguales = 0, distintos = 0, soloEnBase = 0;
        ArrayNode ejemplos = JsonJs.mapper().createArrayNode();
        for (Map.Entry<String, String> e : enBase.entrySet()) {
            String importado = importados.remove(e.getKey());
            if (importado == null) {
                soloEnBase++;
                anadirEjemplo(ejemplos, "solo en la carpeta base: " + e.getKey());
            } else if (mismoJson(importado, e.getValue())) {
                iguales++;
            } else {
                distintos++;
                anadirEjemplo(ejemplos, "distinto de lo importado: " + e.getKey());
            }
        }
        int soloImportados = importados.size();
        importados.keySet().forEach(uid -> anadirEjemplo(ejemplos, "importado pero no está en la carpeta base: " + uid));
        r.put("ensayosIguales", iguales).put("ensayosDistintos", distintos).put("soloEnBase", soloEnBase)
                .put("soloImportados", soloImportados);
        r.set("ejemplos", ejemplos);
        boolean sinRastro = iguales == 0 && distintos == 0 && soloImportados == 0;
        boolean ok = !sinRastro && distintos == 0 && soloEnBase == 0 && soloImportados == 0;
        r.put("verificada", ok);
        String detalle;
        if (ok) {
            detalle = "Los " + iguales + " ensayos de la carpeta base son exactamente los que se importaron.";
        } else if (sinRastro) {
            detalle = "No hay rastro de la importación en la auditoría: no se puede comprobar la carpeta base.";
        } else {
            detalle = "La carpeta base no coincide con la importación: " + distintos + " ensayos distintos, " + soloEnBase
                    + " que no se importaron y " + soloImportados + " importados que no están en ella ("
                    + iguales + " iguales).";
        }
        r.put("detalle", detalle);
        return r;
    }

    private static void anadirEjemplo(ArrayNode ejemplos, String texto) {
        if (ejemplos.size() < 20) {
            ejemplos.add(texto);
        }
    }

    private static boolean mismoJson(String a, String b) {
        if (Objects.equals(a, b)) {
            return true;
        }
        try {
            return JsonJs.mapper().readTree(a).equals(JsonJs.mapper().readTree(b));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return false;
        }
    }

    /** Tras una fusión aplicada: guarda qué exportación de SharePoint se importó (será la base la próxima vez). */
    public void registrarExportacion(Path carpeta, ObjectNode manifiesto) {
        ObjectNode registro = JsonJs.objetoVacio();
        registro.put("fecha", manifiesto.path("fecha").asText(""));
        registro.put("carpeta", carpeta.toAbsolutePath().toString());
        registro.put("importadaEn", java.time.Instant.now().toString());
        registro.set("ficheros", manifiesto.path("ficheros").deepCopy());
        bd.enTransaccion(() -> {
            bd.jdbc().update("INSERT INTO configuracion_app (clave, valor) VALUES (?, ?) ON CONFLICT (clave) DO UPDATE SET valor = EXCLUDED.valor",
                    CLAVE_ULTIMA_EXPORTACION, JsonJs.escribir(registro));
            Auditoria.anotar(bd, IMPORTACION, "configuracion_app", CLAVE_ULTIMA_EXPORTACION, Auditoria.MODIFICACION, null,
                    JsonJs.escribir(registro));
            return null;
        });
    }

    private void bloquearTodo() {
        for (String recurso : List.of("importacion", "catalogos", "usuarios", "recursos", "kpi", "tipos-equipo")) {
            bd.bloquear(recurso);
        }
    }

    private void cargar(CarpetaExportacion datos, ObjectNode tablas, ArrayNode avisos) {
        tablas.set("ensayos", ensayos(datos.leer("planner.json", JsonJs.mapper().createArrayNode()), avisos).json());
        JsonNode cfg = datos.leer("configuracion.json", JsonJs.objetoVacio());
        tablas.set("responsables", Reconciliador.reconciliar(bd, "responsable", "nombre", List.of("seq"),
                titulos(cfg.path("responsibles"), "nombre", null, avisos, "responsables"), true, "responsable", IMPORTACION,
                Auditoria.IMPORTACION).json());
        tablas.set("proyectos", Reconciliador.reconciliar(bd, "proyecto", "titulo",
                List.of("seq", "orden_facturacion", "numero_proyecto"),
                separarNumeros(titulos(cfg.path("projects"), "titulo", cfg.path("projectOrders"), avisos, "proyectos")), true,
                "proyecto", IMPORTACION, Auditoria.IMPORTACION).json());
        tablas.set("bancos", Reconciliador.reconciliar(bd, "banco", "nombre",
                List.of("group_key", "group_name", "dot", "org", "seq", "tipo_equipo", "area"), bancos(cfg.path("benches"), avisos),
                true, "banco", IMPORTACION, Auditoria.IMPORTACION).json());
        tablas.set("nombresArea", nombresArea(cfg.path("areaNames")).json());
        tablas.set("usuarios", Reconciliador.reconciliar(bd, "usuario", "email", List.of("titulo", "rol", "nombre_responsable"),
                sinRepetidos(UsuariosPostgres.filas(datos.leer("usuarios.json", JsonJs.mapper().createArrayNode())), "email",
                        avisos, "usuarios"), true, "usuario", IMPORTACION, Auditoria.IMPORTACION).json());
        JsonNode recursos = datos.leer("recursos.json", JsonJs.objetoVacio());
        tablas.set("recursos", Reconciliador.reconciliar(bd, "recurso", "nombre", RecursosPostgres.COLUMNAS_RECURSO,
                sinRepetidos(RecursosPostgres.filasRecursos(recursos, false), "nombre", avisos, "recursos"), true, "recurso",
                IMPORTACION, Auditoria.IMPORTACION).json());
        tablas.set("bloqueos", Reconciliador.reconciliar(bd, "bloqueo_recurso", "id",
                List.of("titulo", "recurso", "inicio", "fin", "motivo", "nota", "autor"), bloqueos(recursos.path("blocks"), avisos),
                true, "bloqueo_recurso", IMPORTACION, Auditoria.IMPORTACION).json());
        bd.jdbc().queryForObject("SELECT setval('bloqueo_recurso_id_seq', COALESCE((SELECT MAX(id) FROM bloqueo_recurso), 0) + 1, false)",
                Long.class);
        tablas.set("kpi", kpi(datos.leer("kpis.json", JsonJs.mapper().createArrayNode())).json());
        tablas.set("aprobaciones", Reconciliador.reconciliar(bd, "aprobacion", "id",
                List.of("estado", "color", "raw", "solicitante", "servicio"), aprobaciones(datos.leer("aprobaciones.json",
                        JsonJs.mapper().createArrayNode()), avisos), true, "aprobacion", IMPORTACION, Auditoria.IMPORTACION).json());
        List<TipoEquipo> tipos = new ArrayList<>();
        for (JsonNode n : datos.leer("tipos-equipo.json", JsonJs.objetoVacio()).path("tipos")) {
            tipos.add(TipoEquipo.desdeJson(n));
        }
        tablas.set("tiposEquipo", Reconciliador.reconciliar(bd, "tipo_equipo", "clave", TiposEquipoPostgres.COLUMNAS,
                sinRepetidos(TiposEquipoPostgres.filas(tipos), "clave", avisos, "tipos de equipo"), true, "tipo_equipo",
                IMPORTACION, Auditoria.IMPORTACION).json());
        tablas.set("version", version(datos.leer("version.json", JsonJs.objetoVacio()).path("published")).json());
        tablas.set("actividad", actividad(datos.leer(CarpetaExportacion.ACTIVIDAD, JsonJs.mapper().createArrayNode())).json());
    }

    // ---- ensayos -------------------------------------------------------------------

    private Reconciliador.Recuento ensayos(JsonNode planner, ArrayNode avisos) {
        Map<String, String> entrada = new LinkedHashMap<>();
        for (JsonNode fila : planner) {
            String uid = fila.path("uid").asText("");
            if (uid.isEmpty()) {
                avisos.add("planner.json: se ignora una fila sin uid");
                continue;
            }
            JsonNode data = fila.get("data");
            String texto = data == null || data.isNull() ? null : (data.isTextual() ? data.textValue() : JsonJs.escribir(data));
            if (entrada.containsKey(uid)) {
                avisos.add("planner.json: el ensayo " + uid + " está repetido; vale la primera fila");
                continue;
            }
            entrada.put(uid, texto);
        }
        record Actual(String texto, long orden) {
        }
        Map<String, Actual> actuales = new HashMap<>();
        bd.jdbc().query("SELECT uid, data_json, orden FROM ensayo FOR UPDATE", (java.sql.ResultSet rs) -> {
            while (rs.next()) {
                actuales.put(rs.getString(1), new Actual(rs.getString(2), rs.getLong(3)));
            }
            return null;
        });
        int creados = 0, actualizados = 0, borrados = 0;
        long orden = 0;
        for (Map.Entry<String, String> e : entrada.entrySet()) {
            orden++;
            String uid = e.getKey();
            String texto = e.getValue();
            Actual actual = actuales.remove(uid);
            List<Object> valores = new ArrayList<>();
            if (actual == null) {
                valores.add(uid);
                valores.add(orden);
                valores.add(texto);
                valores.addAll(java.util.Arrays.asList(ColumnasEnsayo.de(texto).valores()));
                valores.add(IMPORTACION.email());
                bd.jdbc().update("INSERT INTO ensayo (uid, orden, data_json, " + ColumnasEnsayo.LISTA + ", actualizado_por) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", valores.toArray());
                Auditoria.anotar(bd, IMPORTACION, "ensayo", uid, Auditoria.IMPORTACION, null, texto);
                creados++;
            } else if (!Objects.equals(actual.texto(), texto)) {
                valores.add(texto);
                valores.addAll(java.util.Arrays.asList(ColumnasEnsayo.de(texto).valores()));
                valores.add(orden);
                valores.add(IMPORTACION.email());
                valores.add(uid);
                bd.jdbc().update("UPDATE ensayo SET data_json = ?, version = version + 1, " + ColumnasEnsayo.ASIGNACIONES
                        + ", orden = ?, actualizado_en = now(), actualizado_por = ? WHERE uid = ?", valores.toArray());
                Auditoria.anotar(bd, IMPORTACION, "ensayo", uid, Auditoria.IMPORTACION, actual.texto(), texto);
                actualizados++;
            } else if (actual.orden() != orden) {
                bd.jdbc().update("UPDATE ensayo SET orden = ? WHERE uid = ?", orden, uid);
            }
        }
        for (Map.Entry<String, Actual> sobra : actuales.entrySet()) {
            bd.jdbc().update("DELETE FROM ensayo WHERE uid = ?", sobra.getKey());
            Auditoria.anotar(bd, IMPORTACION, "ensayo", sobra.getKey(), Auditoria.IMPORTACION, sobra.getValue().texto(), null);
            borrados++;
        }
        bd.jdbc().queryForObject("SELECT setval('ensayo_orden_seq', COALESCE((SELECT MAX(orden) FROM ensayo), 0) + 1, false)",
                Long.class);
        return new Reconciliador.Recuento(creados, actualizados, borrados);
    }

    // ---- catálogos -----------------------------------------------------------------

    /** Responsables o proyectos: título + Seq = posición (+ orden de facturación de los proyectos). */
    private static List<Map<String, Object>> titulos(JsonNode lista, String columna, JsonNode ordenes, ArrayNode avisos, String que) {
        List<Map<String, Object>> filas = new ArrayList<>();
        int i = 0;
        for (JsonNode n : lista) {
            i++;
            if (n.asText("").isEmpty()) {
                continue;
            }
            Map<String, Object> f = new LinkedHashMap<>();
            f.put(columna, n.asText());
            f.put("seq", (double) i);
            if (ordenes != null) {
                String orden = ordenes.path(n.asText()).asText("").trim();
                f.put("orden_facturacion", orden.isEmpty() ? null : orden);
            }
            filas.add(f);
        }
        return sinRepetidos(filas, columna, avisos, que);
    }

    /**
     * Proyectos de SharePoint «80001755 - Nombre»: en el servidor el nombre va sin
     * número (columna numero_proyecto). Solo se separa la forma exacta, para que
     * la etiqueta pública siga siendo el título de SharePoint y los ensayos, las
     * órdenes de facturación y la verificación no cambien. Si el nombre quedaría
     * igual que el de otro proyecto, se deja el título entero (la pantalla de
     * proyectos lo marca para corregirlo).
     */
    static List<Map<String, Object>> separarNumeros(List<Map<String, Object>> filas) {
        Map<String, Integer> usos = new HashMap<>();
        java.util.Set<String> titulos = new java.util.HashSet<>();
        for (Map<String, Object> f : filas) {
            String titulo = (String) f.get("titulo");
            titulos.add(titulo);
            NombresProyecto.Partes p = NombresProyecto.separarExacto(titulo);
            if (p != null) {
                usos.merge(p.nombre(), 1, Integer::sum);
            }
        }
        List<Map<String, Object>> salida = new ArrayList<>();
        for (Map<String, Object> f : filas) {
            Map<String, Object> copia = new LinkedHashMap<>(f);
            String titulo = (String) f.get("titulo");
            NombresProyecto.Partes p = NombresProyecto.separarExacto(titulo);
            if (p != null && usos.get(p.nombre()) == 1 && !titulos.contains(p.nombre())) {
                copia.put("titulo", p.nombre());
                copia.put("numero_proyecto", p.numero());
            }
            salida.add(copia);
        }
        return salida;
    }

    private static List<Map<String, Object>> bancos(JsonNode lista, ArrayNode avisos) {
        List<Map<String, Object>> filas = new ArrayList<>();
        for (JsonNode b : lista) {
            if (!b.hasNonNull("name") || b.get("name").asText().isEmpty()) {
                continue;
            }
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("nombre", b.get("name").asText());
            f.put("group_key", ValoresJson.texto(b.get("groupKey")));
            f.put("group_name", ValoresJson.texto(b.get("groupName")));
            f.put("dot", ValoresJson.texto(b.get("dot")));
            f.put("org", ValoresJson.texto(b.get("org")));
            f.put("seq", ValoresJson.numero(b.get("seq")));
            f.put("tipo_equipo", b.path("equipmentType").asText("").trim());
            String area = b.path("area").asText("").trim();
            f.put("area", area.isEmpty() ? "" : CatalogosPostgres.valorArea(b));
            filas.add(f);
        }
        return sinRepetidos(filas, "nombre", avisos, "bancos");
    }

    private Reconciliador.Recuento nombresArea(JsonNode pedidos) {
        Map<String, String> deseados = new LinkedHashMap<>();
        for (String codigo : Areas.CODIGOS) {
            String nombre = pedidos.path(codigo).asText("").trim();
            if (!nombre.isEmpty()) {
                deseados.put(codigo, nombre);
            }
        }
        Map<String, String> actuales = new LinkedHashMap<>();
        bd.jdbc().query("SELECT codigo, nombre FROM nombre_area", (java.sql.ResultSet rs) -> {
            while (rs.next()) {
                actuales.put(rs.getString(1), rs.getString(2));
            }
            return null;
        });
        int creados = 0, actualizados = 0, borrados = 0;
        for (Map.Entry<String, String> d : deseados.entrySet()) {
            String actual = actuales.remove(d.getKey());
            if (actual == null) {
                bd.jdbc().update("INSERT INTO nombre_area (codigo, nombre) VALUES (?, ?)", d.getKey(), d.getValue());
                creados++;
            } else if (!actual.equals(d.getValue())) {
                bd.jdbc().update("UPDATE nombre_area SET nombre = ? WHERE codigo = ?", d.getValue(), d.getKey());
                actualizados++;
            } else {
                continue;
            }
            Auditoria.anotar(bd, IMPORTACION, "nombre_area", d.getKey(), Auditoria.IMPORTACION, actual, d.getValue());
        }
        for (Map.Entry<String, String> sobra : actuales.entrySet()) {
            if (!Areas.CODIGOS.contains(sobra.getKey())) {
                continue; // nombre de un área creada desde la aplicación (D56): SharePoint no la tiene, se conserva
            }
            bd.jdbc().update("DELETE FROM nombre_area WHERE codigo = ?", sobra.getKey());
            Auditoria.anotar(bd, IMPORTACION, "nombre_area", sobra.getKey(), Auditoria.IMPORTACION, sobra.getValue(), null);
            borrados++;
        }
        return new Reconciliador.Recuento(creados, actualizados, borrados);
    }

    /** Quita las filas con clave repetida (vale la primera) y lo anota en los avisos. */
    private static List<Map<String, Object>> sinRepetidos(List<Map<String, Object>> filas, String clave, ArrayNode avisos, String que) {
        Set<String> vistas = new LinkedHashSet<>();
        List<Map<String, Object>> salida = new ArrayList<>();
        for (Map<String, Object> f : filas) {
            String k = Reconciliador.textoClave(f.get(clave));
            if (vistas.add(k)) {
                salida.add(f);
            } else {
                avisos.add(que + ": «" + k + "» está repetido; vale el primero");
            }
        }
        return salida;
    }

    // ---- recursos, KPI, aprobaciones, versión y actividad -----------------------------

    private static List<Map<String, Object>> bloqueos(JsonNode lista, ArrayNode avisos) {
        List<Map<String, Object>> filas = new ArrayList<>();
        for (JsonNode b : lista) {
            long id = b.path("id").asLong(0);
            if (id <= 0) {
                avisos.add("recursos.json: se ignora un bloqueo sin id");
                continue;
            }
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("id", id);
            f.put("titulo", ValoresJson.texto(b.get("title")));
            f.put("recurso", ValoresJson.texto(b.get("resource")));
            f.put("inicio", RecursosPostgres.fecha(b.get("start")));
            f.put("fin", RecursosPostgres.fecha(b.get("end")));
            f.put("motivo", ValoresJson.texto(b.get("reason")));
            f.put("nota", ValoresJson.texto(b.get("note")));
            f.put("autor", ValoresJson.texto(b.get("author")));
            filas.add(f);
        }
        return sinRepetidos(filas, "id", avisos, "bloqueos");
    }

    /** El histórico de KPI no tiene clave propia: si cambia algo, se sustituye entero (en el mismo orden). */
    private Reconciliador.Recuento kpi(JsonNode filas) {
        List<JsonNode> actuales = bd.jdbc().query("SELECT fila_json FROM kpi_semana ORDER BY id",
                (rs, i) -> HistoricoKpiPostgres.leerFila(rs.getString(1)));
        List<JsonNode> nuevas = new ArrayList<>();
        filas.forEach(nuevas::add);
        if (VerificacionParidad.canonico(JsonJs.mapper().valueToTree(actuales))
                .equals(VerificacionParidad.canonico(JsonJs.mapper().valueToTree(nuevas)))) {
            return Reconciliador.Recuento.NINGUNO;
        }
        bd.jdbc().update("DELETE FROM kpi_semana");
        for (JsonNode f : nuevas) {
            bd.jdbc().update("INSERT INTO kpi_semana (anio, cw, org, fila_json) VALUES (?, ?, ?, ?)",
                    HistoricoKpiPostgres.entero(f.get("Year")), HistoricoKpiPostgres.entero(f.get("CW")),
                    HistoricoKpiPostgres.org(f.get("Org")), HistoricoKpiPostgres.escribirFila(f));
        }
        Auditoria.anotar(bd, IMPORTACION, "kpi_semana", "*", Auditoria.IMPORTACION, actuales.size() + " filas", nuevas.size() + " filas");
        return new Reconciliador.Recuento(nuevas.size(), 0, actuales.size());
    }

    private static List<Map<String, Object>> aprobaciones(JsonNode lista, ArrayNode avisos) {
        List<Map<String, Object>> filas = new ArrayList<>();
        for (JsonNode a : lista) {
            if (!a.path("id").canConvertToInt() || a.path("id").asInt(0) <= 0) {
                avisos.add("aprobaciones.json: se ignora una aprobación sin id");
                continue;
            }
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("id", a.path("id").asInt());
            f.put("estado", ValoresJson.texto(a.get("status")));
            f.put("color", ValoresJson.texto(a.get("color")));
            f.put("raw", ValoresJson.texto(a.get("raw")));
            f.put("solicitante", ValoresJson.texto(a.get("requester")));
            f.put("servicio", ValoresJson.texto(a.get("service")));
            filas.add(f);
        }
        return sinRepetidos(filas, "id", avisos, "aprobaciones");
    }

    private Reconciliador.Recuento version(JsonNode publicada) {
        String v = publicada == null || publicada.isNull() ? "" : publicada.asText("").trim();
        List<String> actual = bd.jdbc().queryForList("SELECT valor FROM configuracion_app WHERE clave = ?", String.class,
                ColaboracionPostgres.CLAVE_VERSION);
        String antes = actual.isEmpty() ? null : actual.get(0);
        if (v.isEmpty()) {
            if (antes == null) {
                return Reconciliador.Recuento.NINGUNO;
            }
            bd.jdbc().update("DELETE FROM configuracion_app WHERE clave = ?", ColaboracionPostgres.CLAVE_VERSION);
            return new Reconciliador.Recuento(0, 0, 1);
        }
        if (v.equals(antes)) {
            return Reconciliador.Recuento.NINGUNO;
        }
        bd.jdbc().update("INSERT INTO configuracion_app (clave, valor) VALUES (?, ?) ON CONFLICT (clave) DO UPDATE SET valor = EXCLUDED.valor",
                ColaboracionPostgres.CLAVE_VERSION, v);
        return antes == null ? new Reconciliador.Recuento(1, 0, 0) : new Reconciliador.Recuento(0, 1, 0);
    }

    /** Solo se añaden las filas que aún no están (huella del contenido + nº de repetición). */
    private Reconciliador.Recuento actividad(JsonNode filas) {
        Set<String> existentes = new java.util.HashSet<>(bd.jdbc().queryForList(
                "SELECT huella FROM registro_actividad WHERE huella IS NOT NULL", String.class));
        Map<String, Integer> repeticiones = new HashMap<>();
        int creados = 0;
        for (JsonNode f : filas) {
            String contenido = String.join("\u001f", f.path("when").asText(""), f.path("email").asText(""),
                    f.path("name").asText(""), f.path("action").asText(""), f.path("detail").asText(""));
            int n = repeticiones.merge(contenido, 1, Integer::sum);
            String huella = sha256(contenido + "\u001f" + n);
            if (!existentes.add(huella)) {
                continue;
            }
            bd.jdbc().update("INSERT INTO registro_actividad (cuando, email, nombre, accion, detalle, huella) VALUES (?, ?, ?, ?, ?, ?)",
                    f.path("when").asText(""), ValoresJson.texto(f.get("email")), ValoresJson.texto(f.get("name")),
                    ValoresJson.texto(f.get("action")), ValoresJson.texto(f.get("detail")), huella);
            creados++;
        }
        return new Reconciliador.Recuento(creados, 0, 0);
    }

    static String sha256(String texto) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
