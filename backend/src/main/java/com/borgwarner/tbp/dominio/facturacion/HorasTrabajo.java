package com.borgwarner.tbp.dominio.facturacion;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Horas de trabajo de un ensayo según su horario (D59, D60), en medias horas.
 *
 * El ensayo va de su día de inicio (a la hora de inicio) a su día de fin (a la
 * hora de fin). Una media hora es «de turno» si el día se trabaja (fines de
 * semana y festivos según el horario o la opción Weekend del ensayo) y cae
 * dentro de la jornada (24 h: 00–24; 8 h: 06–14 de mañana o 14–22 de tarde; 16 h: 06–22). Es facturable
 * si es de turno, está dentro del ensayo, después del setup y fuera de las
 * paradas. Horas = medias horas / 2.
 *
 * Una media hora es «con personal» si es de turno y cae entre la apertura y el
 * cierre de la planta (06:00–22:00): el banco puede seguir en marcha de noche
 * (jornada de 24 h), pero nadie puede hacer el setup ni arreglar una parada.
 *
 *   - Setup: consume medias horas con personal (no paradas) desde el arranque;
 *     el inicio facturable es el final de la última (puede pasar al día o turno
 *     siguiente). No se factura.
 *   - Paradas por franjas: [inicio, fin).
 *   - Paradas por horas sin afectar a los turnos: [inicio, inicio + horas) de reloj.
 *   - Paradas por horas que afectan a los turnos: desde el inicio se consumen
 *     solo medias horas con personal hasta sumar las horas; todo el tramo es
 *     parada, también la noche aunque la jornada sea de 24 h (D59).
 *   - Las paradas que se solapan cuentan una vez.
 *
 * Con todo por defecto (24 h, fines de semana trabajados, sin setup ni
 * paradas) da lo mismo que el cálculo por días completos: días × 24.
 */
public final class HorasTrabajo {

    /** Medias horas de un día. */
    public static final int FRANJAS_DIA = 48;

    /** Apertura y cierre de la planta (medias horas del día): 06:00–22:00. */
    public static final int APERTURA_PLANTA = 12;
    public static final int CIERRE_PLANTA = 44;

    /** Estado de una media hora (rejilla de la pantalla Working Hours). */
    public static final char FUERA = '-';        // fuera de las fechas del ensayo
    public static final char SIN_TURNO = 'n';    // dentro, pero no se trabaja (fuera de la jornada o día no laborable)
    public static final char SETUP = 's';
    public static final char PARADA = 'p';
    public static final char TRABAJO = 'w';      // facturable

    /** Un tramo [inicio, fin). */
    public record Tramo(LocalDateTime inicio, LocalDateTime fin) {
        public double horas() {
            return ChronoUnit.MINUTES.between(inicio, fin) / 60.0;
        }
    }

    private final HorarioEnsayo horario;
    private final LocalDate primerDia;
    private final int franjas;
    private final boolean[] enTurno;
    private final boolean[] parada;
    private final boolean[] setup;
    private final int primera;        // primera media hora del ensayo (hora de inicio)
    private final int ultima;         // media hora siguiente a la última del ensayo (hora de fin)
    private final int facturableDesde;
    private final List<Tramo> paradas;

    /**
     * @param horario           horario del ensayo (el setup ya resuelto en setupHoras; null = 0)
     * @param inicio            día de inicio del ensayo
     * @param fin               día de fin del ensayo (inclusive)
     * @param finDeSemanaEnsayo opción Weekend del ensayo
     * @param laborable         día laborable del calendario común (ni fin de semana ni festivo)
     */
    public HorasTrabajo(HorarioEnsayo horario, LocalDate inicio, LocalDate fin, boolean finDeSemanaEnsayo, Predicate<LocalDate> laborable) {
        this.horario = horario == null ? HorarioEnsayo.POR_DEFECTO : horario;
        this.primerDia = inicio;
        long dias = ChronoUnit.DAYS.between(inicio, fin) + 1;
        this.franjas = (int) Math.max(0, dias) * FRANJAS_DIA;
        this.enTurno = new boolean[franjas];
        this.parada = new boolean[franjas];
        this.setup = new boolean[franjas];
        boolean finesDeSemana = this.horario.trabajaFinesDeSemana(finDeSemanaEnsayo);
        int desde = this.horario.inicioJornada() / 30;
        int hasta = this.horario.finJornada() / 30;
        for (int d = 0; d * FRANJAS_DIA < franjas; d++) {
            LocalDate dia = inicio.plusDays(d);
            if (!finesDeSemana && !laborable.test(dia)) {
                continue;
            }
            for (int f = desde; f < hasta; f++) {
                enTurno[d * FRANJAS_DIA + f] = true;
            }
        }
        this.primera = franjas == 0 ? 0 : Math.min(franjas, this.horario.minutoInicio() / 30);
        this.ultima = franjas == 0 ? 0 : Math.max(primera, franjas - FRANJAS_DIA + this.horario.minutoFin() / 30);

        for (HorarioEnsayo.Parada p : this.horario.paradas()) {
            marcarParada(p);
        }
        this.paradas = tramos(parada);

        // Setup: medias horas con personal, no paradas, desde el arranque.
        int necesarias = (int) Math.round(this.horario.setupEfectivo(null) * 2);
        int i = primera;
        int consumidas = 0;
        while (consumidas < necesarias && i < ultima) {
            if (conPersonal(i) && !parada[i]) {
                setup[i] = true;
                consumidas++;
            }
            i++;
        }
        this.facturableDesde = consumidas < necesarias ? ultima : i;
    }

    /** Media hora de turno con la planta abierta (06:00–22:00). */
    private boolean conPersonal(long i) {
        int f = (int) (i % FRANJAS_DIA);
        return enTurno[(int) i] && f >= APERTURA_PLANTA && f < CIERRE_PLANTA;
    }

    /** Índice de la media hora que empieza en t (puede quedar fuera de 0..franjas). */
    private long indice(LocalDateTime t) {
        return ChronoUnit.MINUTES.between(primerDia.atStartOfDay(), t) / 30;
    }

    private LocalDateTime instante(int i) {
        return primerDia.atStartOfDay().plusMinutes(30L * i);
    }

    private void marcarParada(HorarioEnsayo.Parada p) {
        if (p.inicio() == null) {
            return;
        }
        long desde = indice(p.inicio());
        long hasta;
        if (p.porFranjas()) {
            if (p.fin() == null) {
                return;
            }
            hasta = indice(p.fin());
        } else {
            long necesarias = Math.round((p.horas() == null ? 0 : p.horas()) * 2);
            if (!p.afectaTurnos()) {
                hasta = desde + necesarias;
            } else {
                long i = Math.max(desde, 0);
                long consumidas = 0;
                while (consumidas < necesarias && i < franjas) {
                    if (conPersonal(i)) {
                        consumidas++;
                    }
                    i++;
                }
                hasta = i;
            }
        }
        for (long i = Math.max(0, desde); i < Math.min(franjas, hasta); i++) {
            parada[(int) i] = true;
        }
    }

    private List<Tramo> tramos(boolean[] marcas) {
        List<Tramo> lista = new ArrayList<>();
        int i = 0;
        while (i < marcas.length) {
            if (!marcas[i]) {
                i++;
                continue;
            }
            int j = i;
            while (j < marcas.length && marcas[j]) {
                j++;
            }
            lista.add(new Tramo(instante(i), instante(j)));
            i = j;
        }
        return lista;
    }

    private boolean facturable(int i) {
        return i >= facturableDesde && i < ultima && enTurno[i] && !parada[i];
    }

    // ---- resultados ---------------------------------------------------------------------------

    public HorarioEnsayo horario() {
        return horario;
    }

    /** Arranque del ensayo (día de inicio a la hora de inicio). */
    public LocalDateTime inicio() {
        return instante(primera);
    }

    /** Fin del ensayo (día de fin a la hora de fin). */
    public LocalDateTime fin() {
        return instante(ultima);
    }

    /** Inicio facturable: cuando acaba el setup. */
    public LocalDateTime inicioFacturable() {
        return instante(facturableDesde);
    }

    /** Paradas resueltas y fusionadas (las que se solapan, en un solo tramo). */
    public List<Tramo> paradas() {
        return paradas;
    }

    public boolean tieneParadas() {
        return !horario.paradas().isEmpty();
    }

    /** Horas facturables en [desde, hasta). */
    public double horasFacturables(LocalDateTime desde, LocalDateTime hasta) {
        int a = (int) Math.max(0, Math.min(franjas, indice(desde)));
        int b = (int) Math.max(0, Math.min(franjas, indice(hasta)));
        int n = 0;
        for (int i = a; i < b; i++) {
            if (facturable(i)) {
                n++;
            }
        }
        return n / 2.0;
    }

    /**
     * Horas de turno del ensayo en [desde, hasta): el tiempo que trabaja según su horario
     * (jornada, fines de semana y horas de inicio y fin), SIN descontar setup ni paradas.
     */
    public double horasDeTurno(LocalDateTime desde, LocalDateTime hasta) {
        int a = (int) Math.max(primera, Math.min(franjas, indice(desde)));
        int b = (int) Math.max(0, Math.min(ultima, indice(hasta)));
        int n = 0;
        for (int i = a; i < b; i++) {
            if (enTurno[i]) {
                n++;
            }
        }
        return n / 2.0;
    }

    /** Horas de turno de todo el ensayo (sin descontar setup ni paradas). */
    public double horasDeTurno() {
        return horasDeTurno(instante(0), instante(franjas));
    }

    /** Horas facturables de todo el ensayo. */
    public double horasFacturables() {
        return horasFacturables(instante(0), instante(franjas));
    }

    /** Horas de turno dentro del ensayo que se pierden por las paradas (no se facturan). */
    public double horasParadas() {
        int n = 0;
        for (int i = primera; i < ultima; i++) {
            if (enTurno[i] && parada[i]) {
                n++;
            }
        }
        return n / 2.0;
    }

    /** Horas de setup consumidas (las pedidas o menos si el ensayo acaba antes). */
    public double horasSetup() {
        int n = 0;
        for (boolean s : setup) {
            if (s) {
                n++;
            }
        }
        return n / 2.0;
    }

    /** Horas facturables por periodo de facturación (del 21 al 21 a las 00:00), en orden. */
    public Map<YearMonth, Double> porPeriodo() {
        Map<YearMonth, Double> m = new LinkedHashMap<>();
        if (franjas == 0) {
            return m;
        }
        YearMonth mes = HojaMensual.periodoDe(primerDia);
        YearMonth ultimo = HojaMensual.periodoDe(primerDia.plusDays(franjas / FRANJAS_DIA - 1L));
        while (!mes.isAfter(ultimo)) {
            m.put(mes, horasFacturables(inicioPeriodo(mes), finPeriodo(mes)));
            mes = mes.plusMonths(1);
        }
        return m;
    }

    /** 00:00 del primer día del periodo de facturación del mes. */
    public static LocalDateTime inicioPeriodo(YearMonth mes) {
        return HojaMensual.inicioPeriodo(mes).atStartOfDay();
    }

    /** 00:00 del día siguiente al último del periodo (el 21 del mes). */
    public static LocalDateTime finPeriodo(YearMonth mes) {
        return HojaMensual.finPeriodo(mes).plusDays(1).atStartOfDay();
    }

    /** Estado de cada media hora de un día ({@link #FRANJAS_DIA} caracteres). */
    public String franjasDelDia(LocalDate dia) {
        StringBuilder sb = new StringBuilder(FRANJAS_DIA);
        long base = ChronoUnit.DAYS.between(primerDia, dia) * FRANJAS_DIA;
        for (int f = 0; f < FRANJAS_DIA; f++) {
            long i = base + f;
            char c;
            if (i < 0 || i >= franjas || i < primera || i >= ultima) {
                c = FUERA;
            } else if (parada[(int) i]) {
                c = PARADA;
            } else if (!enTurno[(int) i]) {
                c = SIN_TURNO;
            } else if (setup[(int) i]) {
                c = SETUP;
            } else if (i < facturableDesde) {
                c = SIN_TURNO;
            } else {
                c = TRABAJO;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    public LocalDate primerDia() {
        return primerDia;
    }

    /** Último día del ensayo. */
    public LocalDate ultimoDia() {
        return primerDia.plusDays(Math.max(0, franjas / FRANJAS_DIA - 1L));
    }
}
