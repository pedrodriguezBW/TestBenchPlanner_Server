package com.borgwarner.tbp.infraestructura.postgresql;

import com.borgwarner.tbp.aplicacion.puertos.ConsultaAprobaciones;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.operaciones.ReglaNoCumplida;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Aprobaciones de servicios en la tabla «aprobacion». En el servidor no se
 * puede leer la base de aprobaciones de SharePoint: se importa una vez y desde
 * el corte la gestiona un Admin desde la aplicación (plan §9.2, D54), igual
 * que antes se editaba la lista de SharePoint a mano.
 * Mismo formato de respuesta que el adaptador de SharePoint.
 *
 * Altas, cambios y bajas van en una transacción con cerrojo propio, quedan en
 * «auditoria» (antes y después) y se rechazan si otra persona ha cambiado la
 * aprobación desde que se abrió para editarla.
 */
public class AprobacionesPostgres implements ConsultaAprobaciones {

    private static final Pattern NUMERO = Pattern.compile("\\d+");
    private static final String CERROJO = "aprobacion";
    private static final String COLUMNAS = "id, estado, color, raw, solicitante, servicio";

    /** Estados de la lista de SharePoint («Global status»), tal como se escriben. */
    public static final List<String> ESTADOS = List.of("Approved", "Pending", "Rejected", "NA");
    static final int ID_MAXIMO = 999_999_999;
    static final int LARGO_MAXIMO = 500;

    private final BaseDatos bd;

    public AprobacionesPostgres(BaseDatos bd) {
        this.bd = bd;
    }

    @Override
    public ArrayNode todas() {
        ArrayNode salida = JsonJs.mapper().createArrayNode();
        bd.jdbc().query("SELECT " + COLUMNAS + " FROM aprobacion ORDER BY id DESC", (java.sql.ResultSet rs) -> {
            while (rs.next()) {
                salida.add(objeto(rs));
            }
            return null;
        });
        return salida;
    }

    private static ObjectNode objeto(java.sql.ResultSet rs) throws java.sql.SQLException {
        ObjectNode a = JsonJs.objetoVacio();
        a.put("id", rs.getInt("id"));
        a.set("status", ValoresJson.nodo(rs.getString("estado")));
        a.set("color", ValoresJson.nodo(rs.getString("color")));
        a.set("raw", ValoresJson.nodo(rs.getString("raw")));
        a.set("requester", ValoresJson.nodo(rs.getString("solicitante")));
        a.set("service", ValoresJson.nodo(rs.getString("servicio")));
        return a;
    }

    /** Primer número de un Approval ID («ID 123 / x» → 123); null si no hay o es demasiado largo. */
    static Integer numeroDe(String entrada) {
        Matcher m = NUMERO.matcher(entrada == null ? "" : entrada);
        if (!m.find()) {
            return null;
        }
        try {
            return Integer.parseInt(m.group());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public ObjectNode estados(List<JsonNode> ids) {
        List<String> claves = new ArrayList<>();
        Set<Integer> numeros = new LinkedHashSet<>();
        for (JsonNode id : ids) {
            String clave = id == null || id.isNull() ? "" : id.asText();
            if (!claves.contains(clave)) {
                claves.add(clave);
                Integer n = numeroDe(clave);
                if (n != null) {
                    numeros.add(n);
                }
            }
        }
        Map<Integer, ObjectNode> encontradas = new HashMap<>();
        List<Integer> lista = new ArrayList<>(numeros);
        for (int i = 0; i < lista.size(); i += 500) {
            List<Integer> lote = lista.subList(i, Math.min(lista.size(), i + 500));
            String marcas = String.join(", ", Collections.nCopies(lote.size(), "?"));
            bd.jdbc().query("SELECT id, estado, color, raw, solicitante, servicio FROM aprobacion WHERE id IN (" + marcas + ")",
                    (java.sql.ResultSet rs) -> {
                        while (rs.next()) {
                            ObjectNode r = JsonJs.objetoVacio();
                            r.put("resolvedId", rs.getInt("id"));
                            r.set("status", ValoresJson.nodo(rs.getString("estado")));
                            r.set("color", ValoresJson.nodo(rs.getString("color")));
                            r.set("raw", ValoresJson.nodo(rs.getString("raw")));
                            r.set("requester", ValoresJson.nodo(rs.getString("solicitante")));
                            r.set("service", ValoresJson.nodo(rs.getString("servicio")));
                            encontradas.put(rs.getInt("id"), r);
                        }
                        return null;
                    }, lote.toArray());
        }
        ObjectNode salida = JsonJs.objetoVacio();
        for (String clave : claves) {
            ObjectNode r = salida.putObject(clave);
            r.put("input", clave);
            Integer n = numeroDe(clave);
            if (n == null) {
                r.putNull("resolvedId");
                r.put("status", "not_created").put("color", "red").putNull("raw");
            } else if (encontradas.containsKey(n)) {
                r.setAll(encontradas.get(n).deepCopy());
            } else {
                r.put("resolvedId", n);
                r.put("status", "not_created").put("color", "red").putNull("raw");
            }
        }
        return salida;
    }

    // ---- gestión desde la aplicación (solo PostgreSQL; el rol Admin se exige en la API) ----

    @Override
    public boolean editable() {
        return true;
    }

    /** Estado normalizado y color, como la lectura de SharePoint (map_status). */
    static String[] estadoYColor(String bruto) {
        String s = bruto == null ? "" : bruto.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "approved" -> new String[]{"approved", "green"};
            case "pending" -> new String[]{"pending", "amber"};
            case "rejected" -> new String[]{"rejected", "red"};
            case "na", "n/a" -> new String[]{"na", "red"};
            case "" -> new String[]{"not_created", "red"};
            default -> new String[]{"other:" + s, "red"};
        };
    }

    record Datos(String raw, String solicitante, String servicio) {
    }

    /** Valida y normaliza lo que llega de la interfaz. */
    static Datos datos(JsonNode cuerpo) {
        String estado = texto(cuerpo, "raw");
        String raw = null;
        for (String e : ESTADOS) {
            if (e.equalsIgnoreCase(estado)) {
                raw = e;
            }
        }
        if (raw == null) {
            throw new ReglaNoCumplida("Elige el estado de la aprobación: " + String.join(", ", ESTADOS) + ".");
        }
        String solicitante = texto(cuerpo, "requester");
        String servicio = texto(cuerpo, "service");
        if (solicitante == null) {
            throw new ReglaNoCumplida("Indica el solicitante (Requester name).");
        }
        if (solicitante.length() > LARGO_MAXIMO || (servicio != null && servicio.length() > LARGO_MAXIMO)) {
            throw new ReglaNoCumplida("El solicitante y el servicio admiten como máximo " + LARGO_MAXIMO + " caracteres.");
        }
        return new Datos(raw, solicitante, servicio);
    }

    /** Texto recortado; null si falta o queda vacío. */
    private static String texto(JsonNode cuerpo, String campo) {
        JsonNode v = cuerpo == null ? null : cuerpo.get(campo);
        if (v == null || v.isNull()) {
            return null;
        }
        String t = v.asText("").trim();
        return t.isEmpty() ? null : t;
    }

    /** Id pedido en un alta: entero positivo (número o texto con solo cifras). */
    static int idPedido(JsonNode v) {
        String t = v.asText("").trim();
        if (!t.matches("\\d{1,9}") || Integer.parseInt(t) < 1) {
            throw new ReglaNoCumplida("El ID de la aprobación tiene que ser un número entero positivo.");
        }
        return Integer.parseInt(t);
    }

    private ObjectNode leer(int id) {
        List<ObjectNode> filas = bd.jdbc().query("SELECT " + COLUMNAS + " FROM aprobacion WHERE id = ?", (rs, i) -> objeto(rs), id);
        return filas.isEmpty() ? null : filas.get(0);
    }

    @Override
    public ObjectNode crear(JsonNode cuerpo) {
        Datos d = datos(cuerpo);
        JsonNode pedido = cuerpo.get("id");
        boolean conId = pedido != null && !pedido.isNull() && !pedido.asText("").isBlank();
        Integer idElegido = conId ? idPedido(pedido) : null;
        return bd.enTransaccion(() -> {
            bd.bloquear(CERROJO);
            int id;
            if (idElegido != null) {
                if (leer(idElegido) != null) {
                    throw new ReglaNoCumplida("Ya existe la aprobación " + idElegido + ".");
                }
                id = idElegido;
            } else {
                Integer siguiente = bd.jdbc().queryForObject("SELECT COALESCE(MAX(id), 0) + 1 FROM aprobacion", Integer.class);
                id = siguiente == null ? 1 : siguiente;
                if (id > ID_MAXIMO) {
                    throw new ReglaNoCumplida("No quedan IDs de aprobación libres.");
                }
            }
            String[] ec = estadoYColor(d.raw());
            bd.jdbc().update("INSERT INTO aprobacion (" + COLUMNAS + ") VALUES (?, ?, ?, ?, ?, ?)",
                    id, ec[0], ec[1], d.raw(), d.solicitante(), d.servicio());
            ObjectNode nueva = leer(id);
            Auditoria.anotar(bd, bd.actor(), "aprobacion", String.valueOf(id), Auditoria.ALTA, null, JsonJs.escribir(nueva));
            return nueva;
        });
    }

    @Override
    public ObjectNode modificar(int id, JsonNode cuerpo) {
        Datos d = datos(cuerpo);
        return bd.enTransaccion(() -> {
            bd.bloquear(CERROJO);
            ObjectNode actual = existente(id, cuerpo.get("anterior"));
            String[] ec = estadoYColor(d.raw());
            bd.jdbc().update("UPDATE aprobacion SET estado = ?, color = ?, raw = ?, solicitante = ?, servicio = ? WHERE id = ?",
                    ec[0], ec[1], d.raw(), d.solicitante(), d.servicio(), id);
            ObjectNode nueva = leer(id);
            if (!nueva.equals(actual)) {
                Auditoria.anotar(bd, bd.actor(), "aprobacion", String.valueOf(id), Auditoria.MODIFICACION,
                        JsonJs.escribir(actual), JsonJs.escribir(nueva));
            }
            return nueva;
        });
    }

    @Override
    public void borrar(int id, JsonNode anterior) {
        bd.enTransaccion(() -> {
            bd.bloquear(CERROJO);
            ObjectNode actual = existente(id, anterior);
            bd.jdbc().update("DELETE FROM aprobacion WHERE id = ?", id);
            Auditoria.anotar(bd, bd.actor(), "aprobacion", String.valueOf(id), Auditoria.BAJA, JsonJs.escribir(actual), null);
            return null;
        });
    }

    /** La aprobación guardada; rechaza si no existe o si otra persona la ha cambiado desde que se abrió. */
    private ObjectNode existente(int id, JsonNode anterior) {
        ObjectNode actual = leer(id);
        if (actual == null) {
            throw new ReglaNoCumplida("La aprobación " + id + " ya no existe (otra persona la ha borrado). Vuelve a cargar la lista.");
        }
        if (anterior != null && anterior.isObject()) {
            for (String campo : List.of("raw", "requester", "service")) {
                if (!Objects.equals(comparable(anterior.get(campo)), comparable(actual.get(campo)))) {
                    throw new ReglaNoCumplida("Otra persona ha cambiado la aprobación " + id
                            + " mientras la editabas. Vuelve a cargar la lista para ver el valor actual.");
                }
            }
        }
        return actual;
    }

    private static String comparable(JsonNode v) {
        return v == null || v.isNull() ? "" : v.asText("").trim();
    }
}
