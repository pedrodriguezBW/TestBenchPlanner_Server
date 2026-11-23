package com.borgwarner.tbp.infraestructura.postgresql;

import com.borgwarner.tbp.aplicacion.puertos.AlmacenHorarios;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.facturacion.HorarioEnsayo;
import com.borgwarner.tbp.dominio.operaciones.ReglaNoCumplida;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Horario de trabajo de los ensayos en la tabla «horario_ensayo» (V4, D59) y
 * primer mes de facturación por horas en «configuracion_app» (D60).
 *
 * La tabla va por id de ensayo SIN clave foránea a «ensayo» (como «credencial»
 * en V3): volver a importar los ensayos desde SharePoint no borra los horarios.
 * Cambios y restablecimientos van en una transacción con cerrojo propio, quedan
 * en «auditoria» (entidad «horario», antes y después) y se rechazan si otra
 * persona ha cambiado el horario desde que se abrió (columna «version»).
 */
public class HorariosPostgres implements AlmacenHorarios {

    private static final String CERROJO = "horarios";
    private static final String COLUMNAS = "ensayo_id, jornada, turno, hora_inicio, hora_fin, fin_de_semana, setup_horas, "
            + "paradas::text AS paradas, nota, modificado_por, modificado_en, version";
    static final int LARGO_ID = 100;

    private final BaseDatos bd;

    public HorariosPostgres(BaseDatos bd) {
        this.bd = bd;
    }

    @Override
    public boolean editable() {
        return true;
    }

    private static Guardado fila(ResultSet rs) throws SQLException {
        ObjectNode datos = JsonJs.objetoVacio();
        datos.put("jornada", rs.getInt("jornada"));
        datos.put("turno", rs.getString("turno"));
        datos.put("horaInicio", rs.getString("hora_inicio"));
        datos.put("horaFin", rs.getString("hora_fin"));
        datos.put("finDeSemana", rs.getString("fin_de_semana"));
        Double setup = ValoresJson.columnaNumero(rs, "setup_horas");
        if (setup == null) {
            datos.putNull("setupHoras");
        } else {
            datos.put("setupHoras", setup.doubleValue());
        }
        String paradas = rs.getString("paradas");
        datos.set("paradas", paradas == null || paradas.isBlank() ? JsonJs.mapper().createArrayNode() : JsonJs.leer(paradas));
        datos.put("nota", rs.getString("nota"));
        Timestamp cuando = rs.getTimestamp("modificado_en");
        return new Guardado(rs.getString("ensayo_id"), HorarioEnsayo.desdeJson(datos), rs.getLong("version"),
                rs.getString("modificado_por"), cuando == null ? null : cuando.toInstant().toString());
    }

    @Override
    public Map<String, Guardado> todos() {
        Map<String, Guardado> salida = new LinkedHashMap<>();
        bd.jdbc().query("SELECT " + COLUMNAS + " FROM horario_ensayo ORDER BY ensayo_id", (ResultSet rs) -> {
            while (rs.next()) {
                Guardado g = fila(rs);
                salida.put(g.ensayoId(), g);
            }
            return null;
        });
        return salida;
    }

    @Override
    public Guardado leer(String ensayoId) {
        List<Guardado> filas = bd.jdbc().query("SELECT " + COLUMNAS + " FROM horario_ensayo WHERE ensayo_id = ?",
                (rs, i) -> fila(rs), ensayoId);
        return filas.isEmpty() ? null : filas.get(0);
    }

    private static String id(String ensayoId) {
        String id = ensayoId == null ? "" : ensayoId.trim();
        if (id.isEmpty() || id.length() > LARGO_ID) {
            throw new ReglaNoCumplida("Id de ensayo no válido.");
        }
        return id;
    }

    /** Lo guardado; rechaza si otra persona lo ha cambiado desde que se abrió (version de «anterior»). */
    private Guardado comprobar(String id, JsonNode anterior) {
        Guardado actual = leer(id);
        if (anterior != null && anterior.isObject()) {
            long vista = anterior.path("version").asLong(0);
            long guardada = actual == null ? 0 : actual.version();
            if (vista != guardada) {
                throw new ReglaNoCumplida("Otra persona ha cambiado el horario de este ensayo mientras lo editabas. "
                        + "Vuelve a cargarlo para ver el valor actual.");
            }
        }
        return actual;
    }

    private static String texto(Guardado g) {
        return g == null ? null : JsonJs.escribir(g.horario().json());
    }

    private void escribir(String id, HorarioEnsayo h, String actor) {
        bd.jdbc().update("INSERT INTO horario_ensayo (ensayo_id, jornada, turno, hora_inicio, hora_fin, fin_de_semana, setup_horas, paradas, nota, "
                        + "modificado_por, modificado_en, version) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, now(), 1) "
                        + "ON CONFLICT (ensayo_id) DO UPDATE SET jornada = EXCLUDED.jornada, turno = EXCLUDED.turno, hora_inicio = EXCLUDED.hora_inicio, "
                        + "hora_fin = EXCLUDED.hora_fin, fin_de_semana = EXCLUDED.fin_de_semana, setup_horas = EXCLUDED.setup_horas, "
                        + "paradas = EXCLUDED.paradas, nota = EXCLUDED.nota, modificado_por = EXCLUDED.modificado_por, "
                        + "modificado_en = now(), version = horario_ensayo.version + 1",
                id, h.jornada(), h.turno(), h.horaInicio() == null ? null : HorarioEnsayo.hora(h.horaInicio()),
                h.horaFin() == null ? null : HorarioEnsayo.hora(h.horaFin()), h.finDeSemana(),
                h.setupHoras() == null ? null : java.math.BigDecimal.valueOf(h.setupHoras()),
                JsonJs.escribir(h.paradasJson()), h.nota(), actor);
    }

    @Override
    public Guardado guardar(String ensayoId, HorarioEnsayo horario, JsonNode anterior) {
        String id = id(ensayoId);
        return bd.enTransaccion(() -> {
            bd.bloquear(CERROJO);
            Guardado actual = comprobar(id, anterior);
            escribir(id, horario, bd.actor().email());
            Guardado nuevo = leer(id);
            String antes = texto(actual);
            String despues = texto(nuevo);
            if (actual == null) {
                Auditoria.anotar(bd, bd.actor(), "horario", id, Auditoria.ALTA, null, despues);
            } else if (!despues.equals(antes)) {
                Auditoria.anotar(bd, bd.actor(), "horario", id, Auditoria.MODIFICACION, antes, despues);
            }
            return nuevo;
        });
    }

    @Override
    public void restablecer(String ensayoId, JsonNode anterior) {
        String id = id(ensayoId);
        bd.enTransaccion(() -> {
            bd.bloquear(CERROJO);
            Guardado actual = comprobar(id, anterior);
            if (actual != null) {
                bd.jdbc().update("DELETE FROM horario_ensayo WHERE ensayo_id = ?", id);
                Auditoria.anotar(bd, bd.actor(), "horario", id, Auditoria.BAJA, texto(actual), null);
            }
            return null;
        });
    }

    // ---- primer mes de facturación por horas (D60) ----------------------------------------

    @Override
    public YearMonth facturacionHorasDesde() {
        List<String> v = bd.jdbc().queryForList("SELECT valor FROM configuracion_app WHERE clave = ?", String.class, CLAVE_FACTURACION_HORAS);
        String valor = v.isEmpty() || v.get(0) == null ? "" : v.get(0).trim();
        if (valor.isEmpty()) {
            return null;
        }
        try {
            return YearMonth.parse(valor);
        } catch (DateTimeParseException e) {
            return null; // un valor escrito a mano que no es «aaaa-mm»: como si no hubiera (cálculo por días)
        }
    }

    @Override
    public YearMonth cambiarFacturacionHorasDesde(YearMonth mes) {
        bd.enTransaccion(() -> {
            bd.bloquear(CERROJO);
            YearMonth antes = facturacionHorasDesde();
            String valor = mes == null ? null : mes.toString();
            if (valor == null) {
                bd.jdbc().update("DELETE FROM configuracion_app WHERE clave = ?", CLAVE_FACTURACION_HORAS);
            } else {
                bd.jdbc().update("INSERT INTO configuracion_app (clave, valor) VALUES (?, ?) "
                        + "ON CONFLICT (clave) DO UPDATE SET valor = EXCLUDED.valor", CLAVE_FACTURACION_HORAS, valor);
            }
            Auditoria.anotar(bd, bd.actor(), "configuracion_app", CLAVE_FACTURACION_HORAS, Auditoria.MODIFICACION,
                    antes == null ? null : antes.toString(), valor);
            return null;
        });
        return facturacionHorasDesde();
    }

    // ---- copia de seguridad ---------------------------------------------------------------

    @Override
    public int restaurar(Map<String, HorarioEnsayo> horarios) {
        return bd.enTransaccion(() -> {
            bd.bloquear(CERROJO);
            Map<String, Guardado> antes = todos();
            bd.jdbc().update("DELETE FROM horario_ensayo");
            String actor = bd.actor().email();
            for (Map.Entry<String, HorarioEnsayo> x : horarios.entrySet()) {
                escribir(id(x.getKey()), x.getValue(), actor);
            }
            Map<String, Guardado> despues = todos();
            for (String id : antes.keySet()) {
                if (!despues.containsKey(id)) {
                    Auditoria.anotar(bd, bd.actor(), "horario", id, Auditoria.IMPORTACION, texto(antes.get(id)), null);
                }
            }
            for (Map.Entry<String, Guardado> x : despues.entrySet()) {
                String previo = texto(antes.get(x.getKey()));
                String nuevo = texto(x.getValue());
                if (!nuevo.equals(previo)) {
                    Auditoria.anotar(bd, bd.actor(), "horario", x.getKey(), Auditoria.IMPORTACION, previo, nuevo);
                }
            }
            return despues.size();
        });
    }
}
