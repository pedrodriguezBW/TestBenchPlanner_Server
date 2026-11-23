package com.borgwarner.tbp.infraestructura.postgresql;

import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Copia relacional de algunos campos del DataJson de un ensayo (para filtrar,
 * ordenar y cruzar desde SQL o pgAdmin). La verdad sigue siendo el DataJson:
 * estas columnas se recalculan en cada guardado y nunca se leen para planificar.
 * Para añadir otra: migración nueva con la columna + una línea aquí.
 */
public record ColumnasEnsayo(String banco, String organizacion, String tipo, String responsable, String proyecto,
                             Boolean cerrado, String approvalId, LocalDate disponible, LocalDate inicioReal,
                             LocalDate finReal, LocalDate fechaCierre) {

    public static final ColumnasEnsayo VACIAS = new ColumnasEnsayo(null, null, null, null, null, null, null, null, null, null, null);

    /** Lista de columnas en el orden de valores(). */
    public static final String LISTA = "banco, organizacion, tipo, responsable, proyecto, cerrado, approval_id, "
            + "disponible, inicio_real, fin_real, fecha_cierre";
    public static final String ASIGNACIONES = "banco = ?, organizacion = ?, tipo = ?, responsable = ?, proyecto = ?, "
            + "cerrado = ?, approval_id = ?, disponible = ?, inicio_real = ?, fin_real = ?, fecha_cierre = ?";

    public static ColumnasEnsayo de(String dataJson) {
        ObjectNode e = JsonJs.leerObjeto(dataJson);
        if (e == null) {
            return VACIAS;
        }
        JsonNode cerrado = e.get("done");
        return new ColumnasEnsayo(texto(e, "_bench"), texto(e, "_benchOrg"), texto(e, "type"), texto(e, "resp"),
                texto(e, "project"), cerrado == null || cerrado.isNull() ? null : cerrado.asBoolean(),
                texto(e, "approvalId"), fecha(e, "avail"), fecha(e, "actualStart"), fecha(e, "trueEnd"), fecha(e, "doneDate"));
    }

    public Object[] valores() {
        return new Object[]{banco, organizacion, tipo, responsable, proyecto, cerrado, approvalId, disponible, inicioReal,
                finReal, fechaCierre};
    }

    private static String texto(JsonNode e, String campo) {
        JsonNode v = e.get(campo);
        if (v == null || v.isNull() || v.isContainerNode()) {
            return null;
        }
        String t = v.asText("").trim();
        return t.isEmpty() ? null : t;
    }

    /** aaaa-mm-dd al principio del texto; cualquier otra cosa = sin fecha. */
    private static LocalDate fecha(JsonNode e, String campo) {
        String t = texto(e, campo);
        if (t == null || t.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(t.substring(0, 10));
        } catch (DateTimeParseException ex) {
            return null;
        }
    }
}
