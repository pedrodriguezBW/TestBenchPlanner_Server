package com.borgwarner.tbp.facturacion;

import com.borgwarner.tbp.dominio.calendario.Calendario;
import com.borgwarner.tbp.dominio.calendario.Fechas;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.facturacion.HorarioEnsayo;
import com.borgwarner.tbp.dominio.facturacion.HorarioEnsayo.Parada;
import com.borgwarner.tbp.dominio.facturacion.HorasTrabajo;
import com.borgwarner.tbp.dominio.operaciones.ReglaNoCumplida;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Horas de trabajo de un ensayo según su horario (Working Hours, D59) y las
 * reglas de su validación. Incluye los dos ejemplos del documento de Pablo
 * (parada de 8 h a las 18:00 con jornada de 16 h).
 */
class HorasTrabajoTest {

    /** Calendario de Vigo de 2026 (6 y 8 de diciembre son festivos). */
    private static final Calendario VIGO = Calendario.porDefecto(LocalDate.of(2026, 1, 1));
    private static final Predicate<LocalDate> LABORABLE = VIGO::esLaborable;

    private static LocalDate d(String texto) {
        return LocalDate.parse(texto);
    }

    private static LocalDateTime t(String texto) {
        return LocalDateTime.parse(texto);
    }

    private static HorarioEnsayo horario(int jornada, String finDeSemana, Double setup, List<Parada> paradas) {
        return new HorarioEnsayo(jornada, null, null, finDeSemana, setup, paradas, null);
    }

    private static HorasTrabajo horas(HorarioEnsayo h, String inicio, String fin, boolean finDeSemana) {
        return new HorasTrabajo(h, d(inicio), d(fin), finDeSemana, LABORABLE);
    }

    @Test
    void todoPorDefecto_esElCalculoPorDiasCompletos() {
        // 16–25 ene: 10 días × 24 h; 5 en el periodo de enero y 5 en el de febrero.
        HorasTrabajo h = horas(HorarioEnsayo.POR_DEFECTO, "2026-01-16", "2026-01-25", true);
        assertEquals(240, h.horasFacturables(), 1e-9);
        assertEquals(Map.of(YearMonth.of(2026, 1), 120.0, YearMonth.of(2026, 2), 120.0), h.porPeriodo());
        assertEquals(t("2026-01-16T00:00"), h.inicio());
        assertEquals(t("2026-01-26T00:00"), h.fin());
        assertEquals(h.inicio(), h.inicioFacturable());
        assertEquals(0, h.horasSetup(), 1e-9);
        assertEquals(0, h.horasParadas(), 1e-9);
    }

    @Test
    void jornadasDe8yDe16Horas() {
        HorasTrabajo ocho = horas(horario(8, null, null, List.of()), "2026-11-16", "2026-11-18", true);
        assertEquals(24, ocho.horasFacturables(), 1e-9);
        assertEquals(t("2026-11-16T06:00"), ocho.inicio());
        assertEquals(t("2026-11-18T14:00"), ocho.fin());
        // Antes de las 06:00 aún no ha empezado; de 06:00 a 14:00 trabaja; después, fuera de la jornada.
        assertEquals("-".repeat(12) + "w".repeat(16) + "n".repeat(20), ocho.franjasDelDia(d("2026-11-16")));
        HorasTrabajo dieciseis = horas(horario(16, null, null, List.of()), "2026-11-16", "2026-11-18", true);
        assertEquals(48, dieciseis.horasFacturables(), 1e-9);
        assertEquals(t("2026-11-18T22:00"), dieciseis.fin());
    }

    @Test
    void jornadaDe8Horas_turnoDeTarde() {
        HorarioEnsayo tarde = new HorarioEnsayo(8, null, null, null, null, List.of(), null, HorarioEnsayo.TARDE);
        assertTrue(tarde.deTarde());
        HorasTrabajo h = horas(tarde, "2026-11-16", "2026-11-17", true);
        assertEquals(16, h.horasFacturables(), 1e-9);
        assertEquals(t("2026-11-16T14:00"), h.inicio());
        assertEquals(t("2026-11-17T22:00"), h.fin());
        assertEquals("-".repeat(28) + "w".repeat(16) + "n".repeat(4), h.franjasDelDia(d("2026-11-16")));
        // Parada de 4 h a las 20:00 que afecta a los turnos: 2 h hasta las 22:00 y 2 h desde las 14:00 del día siguiente.
        Parada p = Parada.horas(t("2026-11-16T20:00"), 4, true, null);
        HorasTrabajo conParada = horas(new HorarioEnsayo(8, null, null, null, null, List.of(p), null, HorarioEnsayo.TARDE),
                "2026-11-16", "2026-11-17", true);
        assertEquals(t("2026-11-17T16:00"), conParada.paradas().get(0).fin());
        assertEquals(12, conParada.horasFacturables(), 1e-9);
        // El turno solo cuenta con jornada de 8 h; «manana» es lo mismo que null.
        assertNull(new HorarioEnsayo(16, null, null, null, null, List.of(), null, HorarioEnsayo.TARDE).turno());
        assertNull(new HorarioEnsayo(8, null, null, null, null, List.of(), null, "Manana").turno());
        // Inicio fuera del turno de tarde: no válido.
        assertThrows(ReglaNoCumplida.class, () -> new HorarioEnsayo(8, 10 * 60, null, null, null, List.of(), null, HorarioEnsayo.TARDE)
                .validar(d("2026-11-16"), d("2026-11-17")));
        assertThrows(ReglaNoCumplida.class, () -> new HorarioEnsayo(8, null, null, null, null, List.of(), null, "noche")
                .validar(d("2026-11-16"), d("2026-11-17")));
        HorarioEnsayo ida = HorarioEnsayo.desdeJson(JsonJs.leer(JsonJs.escribir(tarde.json())));
        assertEquals(tarde, ida);
    }

    @Test
    void horasDeInicioYFinElegidas() {
        HorarioEnsayo h = new HorarioEnsayo(16, 10 * 60, 18 * 60 + 30, null, null, List.of(), null);
        HorasTrabajo ht = horas(h, "2026-11-16", "2026-11-17", true);
        // Día 1: 10:00–22:00 = 12 h; día 2: 06:00–18:30 = 12,5 h.
        assertEquals(24.5, ht.horasFacturables(), 1e-9);
        assertEquals(t("2026-11-16T10:00"), ht.inicio());
        assertEquals(t("2026-11-17T18:30"), ht.fin());
    }

    @Test
    void weekendNo_saltaFinesDeSemanaYFestivos() {
        // Vie 4 dic, sáb 5, dom 6 (festivo), lun 7, mar 8 (festivo): con Weekend = No solo el 4 y el 7.
        assertEquals(48, horas(HorarioEnsayo.POR_DEFECTO, "2026-12-04", "2026-12-08", false).horasFacturables(), 1e-9);
        // Con Weekend = Yes se trabajan todos (también los festivos, como en la planificación).
        assertEquals(120, horas(HorarioEnsayo.POR_DEFECTO, "2026-12-04", "2026-12-08", true).horasFacturables(), 1e-9);
        assertEquals("n".repeat(48), horas(HorarioEnsayo.POR_DEFECTO, "2026-12-04", "2026-12-08", false).franjasDelDia(d("2026-12-05")));
    }

    @Test
    void workingHoursAnulaLaOpcionWeekend() {
        HorarioEnsayo si = horario(24, HorarioEnsayo.SI, null, List.of());
        HorarioEnsayo no = horario(24, HorarioEnsayo.NO, null, List.of());
        assertEquals(120, horas(si, "2026-12-04", "2026-12-08", false).horasFacturables(), 1e-9);
        assertEquals(48, horas(no, "2026-12-04", "2026-12-08", true).horasFacturables(), 1e-9);
        assertTrue(si.anulaFinDeSemana(false));
        assertFalse(si.anulaFinDeSemana(true), "dice lo mismo que el ensayo: sin aviso");
        assertTrue(no.anulaFinDeSemana(true));
        assertFalse(HorarioEnsayo.POR_DEFECTO.anulaFinDeSemana(false));
    }

    @Test
    void elSetupPasaAlTurnoSiguienteYRetrasaElInicioFacturable() {
        // Jornada de 8 h (06–14) y 10 h de setup: 8 h el primer día y 2 h el segundo.
        HorasTrabajo h = horas(horario(8, null, 10.0, List.of()), "2026-11-16", "2026-11-17", true);
        assertEquals(t("2026-11-17T08:00"), h.inicioFacturable());
        assertEquals(10, h.horasSetup(), 1e-9);
        assertEquals(6, h.horasFacturables(), 1e-9);
        assertEquals(16, h.horasDeTurno(), 1e-9, "ni el setup");
        String dia2 = h.franjasDelDia(d("2026-11-17"));
        assertEquals("ssss", dia2.substring(12, 16));
        assertEquals("wwwwwwwwwwww", dia2.substring(16, 28));
        // Un setup más largo que el ensayo: nada facturable.
        assertEquals(0, horas(horario(8, null, 20.0, List.of()), "2026-11-16", "2026-11-17", true).horasFacturables(), 1e-9);
    }

    @Test
    void ejemploDelDocumento_paradaQueAfectaALosTurnos() {
        // Jornada de 16 h (06–22): parada de 8 h a las 18:00 que afecta a los turnos.
        // Consume 4 h hasta las 22:00 y otras 4 h desde las 06:00: se reanuda a las 10:00.
        Parada p = Parada.horas(t("2026-11-16T18:00"), 8, true, "Avería");
        HorasTrabajo h = horas(horario(16, null, null, List.of(p)), "2026-11-16", "2026-11-18", true);
        assertEquals(List.of(new HorasTrabajo.Tramo(t("2026-11-16T18:00"), t("2026-11-17T10:00"))), h.paradas());
        assertEquals(16, h.paradas().get(0).horas(), 1e-9, "todo el tramo 18:00→10:00 es parada");
        assertEquals(8, h.horasParadas(), 1e-9, "horas de turno perdidas");
        assertEquals(48 - 8, h.horasFacturables(), 1e-9);
        assertEquals(48, h.horasDeTurno(), 1e-9, "las horas de turno no descuentan las paradas");
        assertEquals(4, h.horasDeTurno(t("2026-11-17T00:00"), t("2026-11-17T10:00")), 1e-9);
        assertEquals(0, h.horasFacturables(t("2026-11-17T00:00"), t("2026-11-17T10:00")), 1e-9);
        assertEquals(0.5, h.horasFacturables(t("2026-11-17T10:00"), t("2026-11-17T10:30")), 1e-9);
    }

    @Test
    void ejemploDelDocumento_paradaQueNoAfectaALosTurnos() {
        // La misma parada sin afectar a los turnos: 8 h de reloj, se reanuda a las 02:00.
        Parada p = Parada.horas(t("2026-11-16T18:00"), 8, false, null);
        HorasTrabajo h = horas(horario(16, null, null, List.of(p)), "2026-11-16", "2026-11-18", true);
        assertEquals(List.of(new HorasTrabajo.Tramo(t("2026-11-16T18:00"), t("2026-11-17T02:00"))), h.paradas());
        assertEquals(4, h.horasParadas(), 1e-9, "solo 18:00–22:00 estaba en turno");
        assertEquals(48 - 4, h.horasFacturables(), 1e-9);
        assertEquals(4, h.horasFacturables(t("2026-11-17T00:00"), t("2026-11-17T10:00")), 1e-9, "a las 06:00 ya se trabaja");
    }

    @Test
    void paradasPorFranjasYSolapadas() {
        Parada franja = Parada.franja(t("2026-11-16T10:00"), t("2026-11-16T12:30"));
        assertEquals(21.5, horas(horario(24, null, null, List.of(franja)), "2026-11-16", "2026-11-16", true).horasFacturables(), 1e-9);
        // 10:00–12:00 por franjas y 3 h desde las 11:00: se fusionan en 10:00–14:00 (4 h, contadas una vez).
        Parada a = Parada.franja(t("2026-11-16T10:00"), t("2026-11-16T12:00"));
        Parada b = Parada.horas(t("2026-11-16T11:00"), 3, false, null);
        HorasTrabajo h = horas(horario(24, null, null, List.of(a, b)), "2026-11-16", "2026-11-16", true);
        assertEquals(List.of(new HorasTrabajo.Tramo(t("2026-11-16T10:00"), t("2026-11-16T14:00"))), h.paradas());
        assertEquals(20, h.horasFacturables(), 1e-9);
        assertEquals(4, h.horasParadas(), 1e-9);
        assertEquals('p', h.franjasDelDia(d("2026-11-16")).charAt(20));
    }

    @Test
    void elSetupNoAvanzaDuranteUnaParada() {
        Parada p = Parada.franja(t("2026-11-16T06:00"), t("2026-11-16T08:00"));
        HorasTrabajo h = horas(horario(24, null, 1.0, List.of(p)), "2026-11-16", "2026-11-16", true);
        assertEquals(t("2026-11-16T09:00"), h.inicioFacturable());
        assertEquals(15, h.horasFacturables(), 1e-9);
    }

    @Test
    void jornadaDe24h_elSetupSeHaceConLaPlantaAbierta() {
        // Arranque a las 00:00 y 8 h de setup: nadie lo hace de noche → setup 06:00–14:00, facturable desde las 14:00.
        HorasTrabajo h = horas(horario(24, null, 8.0, List.of()), "2026-11-16", "2026-11-17", true);
        assertEquals(t("2026-11-16T14:00"), h.inicioFacturable());
        assertEquals(8, h.horasSetup(), 1e-9);
        assertEquals(10 + 24, h.horasFacturables(), 1e-9);
        String dia1 = h.franjasDelDia(d("2026-11-16"));
        assertEquals("n".repeat(12) + "s".repeat(16) + "w".repeat(20), dia1);
    }

    @Test
    void jornadaDe24h_paradaQueAfectaALosTurnosDescuentaLaNoche() {
        // Banco de 24 h, parada de 8 h a las 20:00 que afecta a los turnos: 2 h hasta las 22:00 (cierra la planta),
        // la noche sigue parada y 6 h desde las 06:00 → se reanuda a las 12:00 del día siguiente.
        Parada p = Parada.horas(t("2026-11-13T20:00"), 8, true, "Avería");
        HorasTrabajo h = horas(horario(24, null, null, List.of(p)), "2026-11-13", "2026-11-15", true);
        assertEquals(List.of(new HorasTrabajo.Tramo(t("2026-11-13T20:00"), t("2026-11-14T12:00"))), h.paradas());
        assertEquals(16, h.horasParadas(), 1e-9, "en 24 h todo el tramo era de turno");
        assertEquals(72 - 16, h.horasFacturables(), 1e-9);
        // Sin afectar a los turnos: 8 h de reloj, 20:00 → 04:00.
        Parada reloj = Parada.horas(t("2026-11-13T20:00"), 8, false, null);
        HorasTrabajo r = horas(horario(24, null, null, List.of(reloj)), "2026-11-13", "2026-11-15", true);
        assertEquals(t("2026-11-14T04:00"), r.paradas().get(0).fin());
        assertEquals(72 - 8, r.horasFacturables(), 1e-9);
    }

    @Test
    void periodosPartidosElDia21() {
        // Del 20 ene a las 12:00 al 21 ene a las 12:00: 12 h en el periodo de enero y 12 h en el de febrero.
        HorarioEnsayo h = new HorarioEnsayo(24, 12 * 60, 12 * 60, null, null, List.of(), null);
        HorasTrabajo ht = horas(h, "2026-01-20", "2026-01-21", true);
        assertEquals(Map.of(YearMonth.of(2026, 1), 12.0, YearMonth.of(2026, 2), 12.0), ht.porPeriodo());
        assertEquals(t("2026-01-21T00:00"), HorasTrabajo.finPeriodo(YearMonth.of(2026, 1)));
        assertEquals(t("2025-12-21T00:00"), HorasTrabajo.inicioPeriodo(YearMonth.of(2026, 1)));
    }

    @Test
    void jsonIdaYVuelta() {
        HorarioEnsayo h = new HorarioEnsayo(16, 6 * 60, 22 * 60, HorarioEnsayo.NO, 2.5,
                List.of(Parada.horas(t("2026-11-16T18:00"), 8, true, "Avería"), Parada.franja(t("2026-11-17T06:00"), t("2026-11-17T07:30"))),
                "Nota");
        ObjectNode json = h.json();
        assertEquals("06:00", json.path("horaInicio").asText());
        assertEquals("2026-11-16T18:00", json.path("paradas").get(0).path("inicio").asText());
        assertEquals(h, HorarioEnsayo.desdeJson(JsonJs.leer(JsonJs.escribir(json))));
        assertEquals(HorarioEnsayo.POR_DEFECTO, HorarioEnsayo.desdeJson(JsonJs.objetoVacio()));
        assertEquals(1440, HorarioEnsayo.minutos("24:00"));
        assertThrows(ReglaNoCumplida.class, () -> HorarioEnsayo.minutos("24:30"));
        assertThrows(ReglaNoCumplida.class, () -> HorarioEnsayo.minutos("7h"));
    }

    @Test
    void validacion() {
        LocalDate ini = d("2026-11-16");
        LocalDate fin = d("2026-11-18");
        horario(16, null, 1.5, List.of(Parada.horas(t("2026-11-18T21:30"), 0.5, true, null))).validar(ini, fin);
        new HorarioEnsayo(24, 0, 1440, null, null, List.of(), null).validar(ini, fin);
        assertThrows(ReglaNoCumplida.class, () -> horario(12, null, null, List.of()).validar(ini, fin), "jornada");
        assertThrows(ReglaNoCumplida.class, () -> new HorarioEnsayo(8, 5 * 60, null, null, null, List.of(), null).validar(ini, fin),
                "inicio fuera de la jornada");
        assertThrows(ReglaNoCumplida.class, () -> new HorarioEnsayo(8, 6 * 60 + 15, null, null, null, List.of(), null).validar(ini, fin),
                "no es media hora");
        assertThrows(ReglaNoCumplida.class, () -> new HorarioEnsayo(16, null, 23 * 60, null, null, List.of(), null).validar(ini, fin),
                "fin fuera de la jornada");
        assertThrows(ReglaNoCumplida.class, () -> new HorarioEnsayo(24, 12 * 60, 10 * 60, null, null, List.of(), null).validar(ini, ini),
                "mismo día: inicio después del fin");
        assertThrows(ReglaNoCumplida.class, () -> horario(24, "a veces", null, List.of()).validar(ini, fin));
        assertThrows(ReglaNoCumplida.class, () -> horario(24, null, 1.25, List.of()).validar(ini, fin), "setup en medias horas");
        assertThrows(ReglaNoCumplida.class, () -> horario(24, null, -1.0, List.of()).validar(ini, fin));
        assertThrows(ReglaNoCumplida.class,
                () -> horario(24, null, null, List.of(Parada.horas(t("2026-11-19T00:00"), 1, false, null))).validar(ini, fin), "fuera del ensayo");
        assertThrows(ReglaNoCumplida.class,
                () -> horario(24, null, null, List.of(Parada.horas(t("2026-11-16T10:15"), 1, false, null))).validar(ini, fin), "media hora");
        assertThrows(ReglaNoCumplida.class,
                () -> horario(24, null, null, List.of(Parada.horas(t("2026-11-16T10:00"), 0, false, null))).validar(ini, fin), "duración 0");
        assertThrows(ReglaNoCumplida.class,
                () -> horario(24, null, null, List.of(Parada.horas(t("2026-11-16T10:00"), 1.2, false, null))).validar(ini, fin));
        assertThrows(ReglaNoCumplida.class,
                () -> horario(24, null, null, List.of(Parada.franja(t("2026-11-16T10:00"), t("2026-11-16T10:00")))).validar(ini, fin),
                "franja vacía");
        assertThrows(ReglaNoCumplida.class,
                () -> horario(24, null, null, List.of(Parada.franja(t("2026-11-18T20:00"), t("2026-11-19T02:00")))).validar(ini, fin),
                "franja que acaba fuera del ensayo");
        ReglaNoCumplida varios = assertThrows(ReglaNoCumplida.class, () -> horario(12, null, 1.25, List.of()).validar(ini, fin));
        assertTrue(varios.getMessage().contains("jornada") && varios.getMessage().contains("setup"), varios.getMessage());
        assertEquals("2026-11-16", Fechas.iso(ini));
    }
}
