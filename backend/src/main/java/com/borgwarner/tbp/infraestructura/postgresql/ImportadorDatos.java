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
                cargar(datos, tablas, avisos);
                List<String> diferencias = verificacion.comparar(carpeta);
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
        tablas.set("proyectos", Reconciliador.reconciliar(bd, "proyecto", "titulo", List.of("seq", "orden_facturacion"),
                titulos(cfg.path("projects"), "titulo", cfg.path("projectOrders"), avisos, "proyectos"), true, "proyecto",
                IMPORTACION, Auditoria.IMPORTACION).json());
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
