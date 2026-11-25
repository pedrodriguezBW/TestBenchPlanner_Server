package com.borgwarner.tbp.dominio.kpi;

import com.borgwarner.tbp.dominio.calendario.Fechas;
import com.borgwarner.tbp.dominio.comun.ValorJs;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Estado de la foto de cada semana del histórico (D64), en el mismo orden que
 * {@link HistoricoKpi#semanas}:
 *
 *  - «en-curso»: la semana de hoy; la foto se sigue sobrescribiendo
 *    (botón, export y tarea automática de cada noche).
 *  - «cerrada»: la última foto se tomó el domingo de esa semana o después
 *    (la tarea automática del domingo por la noche la cierra).
 *  - «provisional»: semana pasada cuya última foto es anterior al domingo
 *    (p. ej. el servidor estuvo apagado esa noche): sus datos son los de ese día.
 *  - «importada»: fila anterior a D64 (SharePoint o 4.9.4), sin origen guardado;
 *    no se sabe cómo se tomó y no se marca.
 */
public final class EstadoFotos {

    public static final String EN_CURSO = "en-curso";
    public static final String CERRADA = "cerrada";
    public static final String PROVISIONAL = "provisional";
    public static final String IMPORTADA = "importada";

    /**
     * Una semana: fecha (yyyy-MM-dd) y hora (HH:mm, Madrid; null si no se sabe)
     * de la última foto, su origen (manual, auto, export o null) y el estado.
     */
    public record Estado(int year, int cw, String fecha, String hora, String origen, String estado) {
    }

    private EstadoFotos() {
    }

    public static List<Estado> semanas(List<JsonNode> filas, String organizacion, LocalDate hoy) {
        Map<String, JsonNode> porClave = new HashMap<>();
        for (JsonNode r : filas) {
            String org = ValorJs.verdadero(r.get("Org")) ? r.get("Org").asText() : "ALL";
            porClave.put(clave((int) Math.round(r.path("Year").asDouble()), (int) Math.round(r.path("CW").asDouble()), org), r);
        }
        int anioHoy = Fechas.anioDeSemanaIso(hoy);
        int cwHoy = Fechas.semanaIso(hoy);
        List<Estado> salida = new ArrayList<>();
        for (HistoricoKpi.Semana s : HistoricoKpi.semanas(filas, organizacion)) {
            if (Double.isNaN(s.year()) || Double.isNaN(s.cw())) {
                continue;
            }
            int anio = (int) Math.round(s.year());
            int cw = (int) Math.round(s.cw());
            // THERMAL de semanas anteriores a las áreas: la fila es la ALL (ver HistoricoKpi).
            JsonNode fila = porClave.getOrDefault(clave(anio, cw, s.org()), porClave.get(clave(anio, cw, "ALL")));
            salida.add(estado(anio, cw, fila, anio == anioHoy && cw == cwHoy));
        }
        return salida;
    }

    static Estado estado(int anio, int cw, JsonNode fila, boolean enCurso) {
        ZonedDateTime instante = fila == null ? null : instante(fila.path("SnapAt").asText(""));
        LocalDate fecha = instante != null ? instante.toLocalDate()
                : fila == null ? null : Fechas.dia(fila.path("SnapDate").asText(""));
        String hora = instante == null ? null : String.format("%02d:%02d", instante.getHour(), instante.getMinute());
        String origen = fila != null && fila.hasNonNull("SnapSource") ? fila.get("SnapSource").asText() : null;
        String estado;
        if (enCurso) {
            estado = EN_CURSO;
        } else if (origen == null) {
            estado = IMPORTADA;
        } else if (fecha != null && !fecha.isBefore(domingo(anio, cw))) {
            estado = CERRADA;
        } else {
            estado = PROVISIONAL;
        }
        return new Estado(anio, cw, fecha == null ? null : fecha.toString(), hora, origen, estado);
    }

    /** Domingo de la semana ISO (año de la semana ISO, D8). */
    public static LocalDate domingo(int anio, int cw) {
        return LocalDate.of(anio, 1, 4).with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, cw).with(DayOfWeek.SUNDAY);
    }

    private static ZonedDateTime instante(String texto) {
        if (texto.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(texto).atZone(Fechas.ZONA);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String clave(int anio, int cw, String org) {
        return anio + "/" + cw + "/" + org;
    }
}
