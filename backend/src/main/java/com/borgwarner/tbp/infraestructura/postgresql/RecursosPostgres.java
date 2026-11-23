package com.borgwarner.tbp.infraestructura.postgresql;

import com.borgwarner.tbp.aplicacion.puertos.AlmacenEnsayos;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenRecursos;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenUsuarios;
import com.borgwarner.tbp.aplicacion.puertos.Colaboracion;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.listas.ProteccionListas;
import com.borgwarner.tbp.dominio.operaciones.ReglaNoCumplida;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Equipos auxiliares (tabla «recurso») y sus bloqueos («bloqueo_recurso»), con el formato de SharePoint. */
public class RecursosPostgres implements AlmacenRecursos {

    private final BaseDatos bd;
    private final Colaboracion colaboracion;
    private final boolean permitirVacias;

    public RecursosPostgres(BaseDatos bd, Colaboracion colaboracion, boolean permitirVacias) {
        this.bd = bd;
        this.colaboracion = colaboracion;
        this.permitirVacias = permitirVacias;
    }

    @Override
    public ObjectNode leer() {
        ObjectNode salida = JsonJs.objetoVacio();
        ArrayNode recursos = salida.putArray("resources");
        ArrayNode bloqueos = salida.putArray("blocks");
        bd.jdbc().query("SELECT nombre, group_key, group_name, dot, codigo, org, seq FROM recurso" + CatalogosPostgres.ORDEN_SEQ,
                (java.sql.ResultSet rs) -> {
                    while (rs.next()) {
                        ObjectNode r = recursos.addObject();
                        r.put("name", rs.getString("nombre"));
                        r.set("groupKey", ValoresJson.nodo(rs.getString("group_key")));
                        r.set("groupName", ValoresJson.nodo(rs.getString("group_name")));
                        r.set("dot", ValoresJson.nodo(rs.getString("dot")));
                        r.set("code", ValoresJson.nodo(rs.getString("codigo")));
                        r.set("org", ValoresJson.nodo(rs.getString("org")));
                        r.set("seq", ValoresJson.nodo(ValoresJson.columnaNumero(rs, "seq")));
                    }
                    return null;
                });
        bd.jdbc().query("SELECT id, titulo, recurso, inicio, fin, motivo, nota, autor FROM bloqueo_recurso ORDER BY id",
                (java.sql.ResultSet rs) -> {
                    while (rs.next()) {
                        ObjectNode b = bloqueos.addObject();
                        b.set("id", ValoresJson.nodo((double) rs.getLong("id")));
                        b.set("title", ValoresJson.nodo(rs.getString("titulo")));
                        b.set("resource", ValoresJson.nodo(rs.getString("recurso")));
                        java.sql.Date inicio = rs.getDate("inicio");
                        java.sql.Date fin = rs.getDate("fin");
                        b.put("start", inicio == null ? "" : inicio.toLocalDate().toString());
                        b.put("end", fin == null ? "" : fin.toLocalDate().toString());
                        b.set("reason", ValoresJson.nodo(rs.getString("motivo")));
                        b.set("note", ValoresJson.nodo(rs.getString("nota")));
                        b.set("author", ValoresJson.nodo(rs.getString("autor")));
                    }
                    return null;
                });
        return salida;
    }

    /** Filas de «recurso» a partir de {resources:[…]} (Seq = posición si no trae). */
    static List<Map<String, Object>> filasRecursos(JsonNode entrada) {
        return filasRecursos(entrada, true);
    }

    /**
     * @param seqPorPosicion true al guardar desde la app (como SharePoint: sin Seq = su posición);
     *                       false al importar (sin Seq se queda sin Seq, igual que en la lista)
     */
    static List<Map<String, Object>> filasRecursos(JsonNode entrada, boolean seqPorPosicion) {
        List<Map<String, Object>> filas = new ArrayList<>();
        int i = 0;
        for (JsonNode r : entrada.path("resources")) {
            i++;
            if (!r.hasNonNull("name") || r.get("name").asText().isEmpty()) {
                continue;
            }
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("nombre", r.get("name").asText());
            f.put("group_key", ValoresJson.texto(r.get("groupKey")));
            f.put("group_name", ValoresJson.texto(r.get("groupName")));
            f.put("dot", ValoresJson.texto(r.get("dot")));
            f.put("codigo", ValoresJson.texto(r.get("code")));
            f.put("org", ValoresJson.texto(r.get("org")));
            f.put("seq", r.hasNonNull("seq") ? ValoresJson.numero(r.get("seq")) : (seqPorPosicion ? Double.valueOf(i) : null));
            filas.add(f);
        }
        return filas;
    }

    static final List<String> COLUMNAS_RECURSO = List.of("group_key", "group_name", "dot", "codigo", "org", "seq");

    @Override
    public ObjectNode guardarCatalogo(JsonNode entrada) {
        List<Map<String, Object>> filas = filasRecursos(entrada);
        return bd.enTransaccion(() -> {
            bd.bloquear("recursos");
            List<String> existentes = bd.jdbc().queryForList("SELECT nombre FROM recurso", String.class);
            ProteccionListas.comprobar("TestBenchResources", existentes,
                    filas.stream().map(f -> (String) f.get("nombre")).toList(), permitirVacias);
            return JsonJs.objetoVacio().put("ok", true).set("resources", Reconciliador.reconciliar(bd, "recurso", "nombre",
                    COLUMNAS_RECURSO, filas, true, "recurso", bd.actor(), null).json());
        });
    }

    /** Fecha de un bloqueo («aaaa-mm-dd…»); vacío = sin fecha. */
    static LocalDate fecha(JsonNode v) {
        String s = v == null || v.isNull() ? "" : v.asText("").trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
        } catch (DateTimeParseException e) {
            throw new ReglaNoCumplida("Fecha no válida en el bloqueo: «" + s + "» (aaaa-mm-dd)");
        }
    }

    @Override
    public ObjectNode guardarBloqueo(JsonNode p, AlmacenEnsayos.Actor actor) {
        String autor = actor.nombre() != null && !actor.nombre().isEmpty() ? actor.nombre() : actor.email();
        AlmacenUsuarios.Identidad quien = new AlmacenUsuarios.Identidad(actor.email(), actor.nombre());
        LocalDate inicio = fecha(p.get("start"));
        LocalDate fin = fecha(p.get("end"));
        ObjectNode resultado = bd.enTransaccion(() -> {
            long id = p.hasNonNull("id") ? p.get("id").asLong(0) : 0;
            if (id != 0) {
                List<String> antes = bd.jdbc().query("SELECT recurso || ' ' || COALESCE(inicio::text, '') || '->' || COALESCE(fin::text, '') "
                        + "FROM bloqueo_recurso WHERE id = ? FOR UPDATE", (rs, i) -> rs.getString(1), id);
                if (antes.isEmpty()) {
                    return JsonJs.objetoVacio().put("ok", false).put("id", id).put("status", 404);
                }
                bd.jdbc().update("UPDATE bloqueo_recurso SET recurso = ?, inicio = ?, fin = ?, motivo = ?, nota = ?, autor = ? WHERE id = ?",
                        p.path("resource").asText(""), inicio, fin, p.path("reason").asText(""), p.path("note").asText(""), autor, id);
                Auditoria.anotar(bd, quien, "bloqueo_recurso", String.valueOf(id), Auditoria.MODIFICACION, antes.get(0),
                        JsonJs.escribir(p));
            } else {
                String titulo = p.path("title").asText("");
                id = bd.jdbc().queryForObject("INSERT INTO bloqueo_recurso (titulo, recurso, inicio, fin, motivo, nota, autor) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id", Long.class,
                        titulo.isEmpty() ? "BLK-" + Instant.now().toEpochMilli() : titulo, p.path("resource").asText(""),
                        inicio, fin, p.path("reason").asText(""), p.path("note").asText(""), autor);
                Auditoria.anotar(bd, quien, "bloqueo_recurso", String.valueOf(id), Auditoria.ALTA, null, JsonJs.escribir(p));
            }
            return JsonJs.objetoVacio().put("ok", true).put("id", id).put("status", 204);
        });
        if (resultado.path("ok").asBoolean()) {
            colaboracion.anotarActividad(actor.email(), actor.nombre(), "res_block", p.path("resource").asText() + " "
                    + p.path("start").asText() + "->" + p.path("end").asText() + " (" + p.path("reason").asText() + ")");
        }
        return resultado;
    }

    @Override
    public ObjectNode borrarBloqueo(Object id) {
        if (id == null) {
            return JsonJs.objetoVacio().put("ok", false).put("error", "sin id");
        }
        long numero;
        try {
            numero = Long.parseLong(id.toString().trim());
        } catch (NumberFormatException e) {
            return JsonJs.objetoVacio().put("ok", false).put("error", "id no válido");
        }
        return bd.enTransaccion(() -> {
            List<String> antes = bd.jdbc().query("SELECT COALESCE(recurso, '') || ' ' || COALESCE(inicio::text, '') || '->' "
                    + "|| COALESCE(fin::text, '') FROM bloqueo_recurso WHERE id = ?", (rs, i) -> rs.getString(1), numero);
            int borradas = bd.jdbc().update("DELETE FROM bloqueo_recurso WHERE id = ?", numero);
            if (borradas > 0) {
                Auditoria.anotar(bd, bd.actor(), "bloqueo_recurso", String.valueOf(numero), Auditoria.BAJA,
                        antes.isEmpty() ? null : antes.get(0), null);
            }
            return JsonJs.objetoVacio().put("ok", borradas > 0);
        });
    }
}
