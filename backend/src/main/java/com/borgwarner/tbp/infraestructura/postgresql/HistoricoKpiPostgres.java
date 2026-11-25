package com.borgwarner.tbp.infraestructura.postgresql;

import com.borgwarner.tbp.aplicacion.puertos.AlmacenHistoricoKpi;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.infraestructura.comun.FilaKpi;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.UncheckedIOException;
import java.util.List;

/**
 * Histórico semanal de KPI: una fila por semana y organización. Año, semana y
 * organización van en columnas (para consultar); la fila completa, con los
 * nombres de columna de la lista de SharePoint, en fila_json.
 */
public class HistoricoKpiPostgres implements AlmacenHistoricoKpi {

    private final BaseDatos bd;

    public HistoricoKpiPostgres(BaseDatos bd) {
        this.bd = bd;
    }

    @Override
    public List<JsonNode> leer() {
        return bd.jdbc().query("SELECT fila_json FROM kpi_semana ORDER BY anio ASC NULLS FIRST, cw ASC NULLS FIRST, id",
                (rs, i) -> leerFila(rs.getString(1)));
    }

    static JsonNode leerFila(String texto) {
        try {
            return JsonJs.mapper().readTree(texto);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException("Fila de KPI ilegible en la base de datos", e);
        }
    }

    /** Texto JSON de una fila (mismo formato numérico que el almacén local). */
    static String escribirFila(JsonNode fila) {
        try {
            return JsonJs.mapper().writeValueAsString(fila);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Integer entero(JsonNode v) {
        Double d = ValoresJson.numero(v);
        return d == null ? null : (int) Math.round(d);
    }

    static String org(JsonNode v) {
        return v == null || v.isNull() ? null : v.asText();
    }

    @Override
    public ObjectNode guardarFotos(List<ObjectNode> fotos) {
        return bd.enTransaccion(() -> {
            bd.bloquear("kpi");
            int creadas = 0, actualizadas = 0;
            for (ObjectNode foto : fotos) {
                ObjectNode fila = FilaKpi.desdeFoto(foto);
                FilaKpi.anadirOrigen(fila, foto);
                fila.remove("Title");
                Integer anio = entero(fila.get("Year"));
                Integer cw = entero(fila.get("CW"));
                String org = org(fila.get("Org"));
                String texto = escribirFila(fila);
                int n = anio == null || cw == null || org == null ? 0
                        : bd.jdbc().update("UPDATE kpi_semana SET fila_json = ? WHERE anio = ? AND cw = ? AND org = ?", texto, anio, cw, org);
                if (n > 0) {
                    actualizadas++;
                } else {
                    bd.jdbc().update("INSERT INTO kpi_semana (anio, cw, org, fila_json) VALUES (?, ?, ?, ?)", anio, cw, org, texto);
                    creadas++;
                }
            }
            return JsonJs.objetoVacio().put("ok", true).put("created", creadas).put("updated", actualizadas);
        });
    }
}
