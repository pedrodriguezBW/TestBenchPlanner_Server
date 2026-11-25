package com.borgwarner.tbp.dominio;

import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.kpi.EstadoFotos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Estado de la foto semanal de KPI (D64): cerrada, provisional, en curso, importada. */
class EstadoFotosTest {

    private static ObjectNode fila(int anio, int cw, String org, String snapDate, String snapAt, String origen) {
        ObjectNode f = JsonJs.objetoVacio().put("Year", anio).put("CW", cw).put("Org", org)
                .put("DelayedDeclared", 0).put("SnapDate", snapDate);
        if (snapAt != null) {
            f.put("SnapAt", snapAt);
        }
        if (origen != null) {
            f.put("SnapSource", origen);
        }
        return f;
    }

    @Test
    void domingoDeLaSemanaIso() {
        assertEquals(LocalDate.of(2026, 11, 29), EstadoFotos.domingo(2026, 48));
        // CW53 de 2026 termina el domingo 3 de enero de 2027 (año de la semana ISO, D8).
        assertEquals(LocalDate.of(2027, 1, 3), EstadoFotos.domingo(2026, 53));
    }

    @Test
    void estadosSegunFechaYOrigen() {
        List<JsonNode> filas = List.of(
                // CW46: tarea automática el domingo 15-nov a las 23:55 (Madrid) -> cerrada.
                fila(2026, 46, "THERMAL", "2026-11-15T00:00:00Z", "2026-11-15T22:55:00Z", "auto"),
                // CW47: pulsado el viernes y el servidor apagado el fin de semana -> provisional.
                fila(2026, 47, "THERMAL", "2026-11-20T00:00:00Z", "2026-11-20T16:30:00Z", "manual"),
                // CW45: fila de SharePoint, sin origen -> importada (no se marca).
                fila(2026, 45, "THERMAL", "2026-11-06T00:00:00Z", null, null),
                // CW48: la semana de hoy -> en curso.
                fila(2026, 48, "THERMAL", "2026-11-25T00:00:00Z", "2026-11-25T09:00:00Z", "export"));
        List<EstadoFotos.Estado> e = EstadoFotos.semanas(filas, "THERMAL", LocalDate.of(2026, 11, 25));
        assertEquals(4, e.size());
        assertEquals(new EstadoFotos.Estado(2026, 45, "2026-11-06", null, null, EstadoFotos.IMPORTADA), e.get(0));
        assertEquals(new EstadoFotos.Estado(2026, 46, "2026-11-15", "23:55", "auto", EstadoFotos.CERRADA), e.get(1));
        assertEquals(new EstadoFotos.Estado(2026, 47, "2026-11-20", "17:30", "manual", EstadoFotos.PROVISIONAL), e.get(2));
        assertEquals(EstadoFotos.EN_CURSO, e.get(3).estado());
        assertEquals("10:00", e.get(3).hora());
    }

    @Test
    void thermalAntiguoUsaLaFilaAll() {
        List<JsonNode> filas = List.of(fila(2025, 10, "ALL", "2025-03-09T00:00:00Z", "2025-03-09T22:55:00Z", "auto"));
        List<EstadoFotos.Estado> e = EstadoFotos.semanas(filas, "THERMAL", LocalDate.of(2026, 11, 25));
        assertEquals(1, e.size());
        assertEquals(EstadoFotos.CERRADA, e.get(0).estado());
        assertNull(EstadoFotos.semanas(List.of(), "THERMAL", LocalDate.of(2026, 11, 25)).stream().findFirst().orElse(null));
    }
}
