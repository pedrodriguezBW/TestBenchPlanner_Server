package com.borgwarner.tbp.infraestructura.postgresql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;

/** Conversión entre valores JSON de las listas y valores de columna. */
final class ValoresJson {

    private ValoresJson() {
    }

    /** Texto de un nodo (null si es null o no viene). */
    static String texto(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            return null;
        }
        return v.isValueNode() ? v.asText() : v.toString();
    }

    /** Número de un nodo («_num»: vacío o no numérico = null). */
    static Double numero(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            return null;
        }
        if (v.isNumber()) {
            return v.doubleValue();
        }
        String t = v.asText("").trim();
        if (t.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Nodo de texto o null. */
    static JsonNode nodo(String v) {
        return v == null ? NullNode.getInstance() : TextNode.valueOf(v);
    }

    /** Número como lo devolvería SharePoint: entero si no tiene decimales. */
    static JsonNode nodo(Double v) {
        if (v == null) {
            return NullNode.getInstance();
        }
        if (v == Math.rint(v) && !Double.isInfinite(v)) {
            long l = v.longValue();
            return l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE ? IntNode.valueOf((int) l) : LongNode.valueOf(l);
        }
        return DoubleNode.valueOf(v);
    }

    /** Double de una columna que puede ser NULL. */
    static Double columnaNumero(java.sql.ResultSet rs, String columna) throws java.sql.SQLException {
        double d = rs.getDouble(columna);
        return rs.wasNull() ? null : d;
    }
}
