package com.borgwarner.tbp.infraestructura.postgresql;

import com.borgwarner.tbp.aplicacion.puertos.AlmacenCalendario;
import com.borgwarner.tbp.dominio.calendario.Calendario;
import com.borgwarner.tbp.dominio.calendario.Fechas;
import com.borgwarner.tbp.dominio.calendario.Festivo;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Calendario de festivos COMÚN en la base de datos (tablas calendario y
 * festivo, migración V2). Con PostgreSQL la aplicación es compartida, así que
 * el calendario deja de ser de cada PC (D6) y pasa a ser uno para todos que
 * solo cambia un Admin (D46; la comprobación del rol está en la API).
 *
 * Si todavía no hay calendario, la primera lectura guarda el de un PC nuevo
 * (festivos de Vigo del año de hoy), igual que el fichero calendario.json.
 * Cada cambio queda en «auditoria» con el antes y el después.
 */
public class CalendarioPostgres implements AlmacenCalendario {

    private static final String CERROJO = "calendario";

    private final BaseDatos bd;

    public CalendarioPostgres(BaseDatos bd) {
        this.bd = bd;
    }

    @Override
    public boolean compartido() {
        return true;
    }

    @Override
    public Calendario leer(LocalDate hoy) {
        Calendario guardado = leerGuardado();
        if (guardado != null) {
            return guardado;
        }
        Calendario nuevo = Calendario.porDefecto(hoy);
        return bd.enTransaccion(() -> {
            bd.bloquear(CERROJO);
            Calendario otro = leerGuardado(); // otra petición lo ha creado mientras tanto
            if (otro != null) {
                return otro;
            }
            escribir(nuevo, null);
            return nuevo;
        });
    }

    @Override
    public void guardar(Calendario calendario) {
        bd.enTransaccion(() -> {
            bd.bloquear(CERROJO);
            escribir(calendario, leerGuardado());
            return null;
        });
    }

    private Calendario leerGuardado() {
        List<Map<String, Object>> fila = bd.jdbc().queryForList("SELECT anio, saltar_festivos FROM calendario WHERE id = 1");
        if (fila.isEmpty()) {
            return null;
        }
        List<Festivo> festivos = bd.jdbc().query("SELECT to_char(fecha, 'YYYY-MM-DD') AS fecha, nombre FROM festivo ORDER BY fecha",
                (rs, i) -> new Festivo(rs.getString("fecha"), rs.getString("nombre")));
        return new Calendario(((Number) fila.get(0).get("anio")).intValue(), Boolean.TRUE.equals(fila.get(0).get("saltar_festivos")),
                festivos);
    }

    /** Sustituye el calendario guardado (dentro de la transacción y con el cerrojo ya tomado). */
    private void escribir(Calendario calendario, Calendario anterior) {
        var actor = bd.actor();
        bd.jdbc().update("INSERT INTO calendario (id, anio, saltar_festivos, actualizado_en, actualizado_por) VALUES (1, ?, ?, now(), ?) "
                        + "ON CONFLICT (id) DO UPDATE SET anio = EXCLUDED.anio, saltar_festivos = EXCLUDED.saltar_festivos, "
                        + "actualizado_en = now(), actualizado_por = EXCLUDED.actualizado_por",
                calendario.anio(), calendario.saltarFestivos(), actor.email());
        bd.jdbc().update("DELETE FROM festivo");
        // Una fecha no válida no cuenta como festivo (el dominio también la ignora) y
        // una fecha repetida se queda con el primer nombre, como en la lista.
        Map<LocalDate, String> festivos = new LinkedHashMap<>();
        for (Festivo f : calendario.festivos()) {
            LocalDate dia = Fechas.dia(f.date());
            if (dia != null) {
                festivos.putIfAbsent(dia, f.name() == null ? "" : f.name());
            }
        }
        List<Object[]> filas = new ArrayList<>();
        festivos.forEach((dia, nombre) -> filas.add(new Object[]{dia, nombre}));
        if (!filas.isEmpty()) {
            bd.jdbc().batchUpdate("INSERT INTO festivo (fecha, nombre) VALUES (?, ?)", filas);
        }
        Auditoria.anotar(bd, actor, "calendario", "1", anterior == null ? Auditoria.ALTA : Auditoria.MODIFICACION,
                anterior == null ? null : describir(anterior), describir(calendario));
    }

    /** Mismo formato que calendario.json (y que la copia de seguridad). */
    static String describir(Calendario c) {
        ObjectNode raiz = JsonJs.objetoVacio();
        raiz.put("year", c.anio());
        raiz.put("skipHolidays", c.saltarFestivos());
        ArrayNode lista = raiz.putArray("holidays");
        for (Festivo f : c.festivos()) {
            lista.addObject().put("date", f.date()).put("name", f.name());
        }
        return JsonJs.escribir(raiz);
    }
}
