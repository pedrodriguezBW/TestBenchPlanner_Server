package com.borgwarner.tbp.dominio.facturacion;

import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.operaciones.ReglaNoCumplida;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Horario de trabajo de un ensayo (Configuration › Test Calendar / Working
 * Hours, D59). Solo existe con PostgreSQL; sin registro el ensayo trabaja con
 * los valores por defecto ({@link #POR_DEFECTO}).
 *
 * @param jornada     horas de la jornada: 24 (00:00–24:00, por defecto), 8 (un turno: mañana 06:00–14:00 o tarde 14:00–22:00)
 *                    o 16 (dos turnos, 06:00–22:00)
 * @param horaInicio  minuto del día en que arranca el ensayo el día de inicio (null = inicio de la jornada)
 * @param horaFin     minuto del día en que termina el día de fin, hasta 1440 = 24:00 (null = fin de la jornada)
 * @param finDeSemana «si» (trabaja fines de semana y festivos), «no» (no los trabaja) o null (como diga la opción Weekend del ensayo)
 * @param setupHoras  horas de setup (null = las de la columna antigua setupHours del ensayo)
 * @param paradas     paradas del ensayo (por franjas o por horas)
 * @param nota        nota libre (null si no hay)
 * @param turno       con jornada de 8 h, «manana» (por defecto, null) o «tarde»; con otras jornadas, siempre null
 */
public record HorarioEnsayo(int jornada, Integer horaInicio, Integer horaFin, String finDeSemana, Double setupHoras,
                            List<Parada> paradas, String nota, String turno) {

    public static final String SI = "si";
    public static final String NO = "no";
    public static final String POR_FRANJAS = "franjas";
    public static final String POR_HORAS = "horas";
    public static final List<Integer> JORNADAS = List.of(24, 8, 16);
    public static final String MANANA = "manana";
    public static final String TARDE = "tarde";
    static final int MINUTOS_DIA = 1440;
    static final int LARGO_MOTIVO = 200;
    static final int LARGO_NOTA = 2000;
    static final int MAXIMO_PARADAS = 2000;
    static final double MAXIMO_HORAS = 24 * 3660;

    /** Sin registro: 24 h, de 00:00 a 24:00, fines de semana como el ensayo, setup de la columna antigua, sin paradas. */
    public static final HorarioEnsayo POR_DEFECTO = new HorarioEnsayo(24, null, null, null, null, List.of(), null);

    private static final Pattern HORA = Pattern.compile("^(\\d{1,2}):(\\d{2})$");
    private static final DateTimeFormatter FECHA_HORA = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm", Locale.ROOT);

    public HorarioEnsayo {
        paradas = paradas == null ? List.of() : List.copyOf(paradas);
        finDeSemana = finDeSemana == null || finDeSemana.isBlank() ? null : finDeSemana.trim().toLowerCase(Locale.ROOT);
        nota = nota == null || nota.isBlank() ? null : nota.trim();
        turno = jornada != 8 || turno == null || turno.isBlank() || MANANA.equalsIgnoreCase(turno.trim())
                ? null : turno.trim().toLowerCase(Locale.ROOT);
    }

    /** Sin turno (mañana por defecto con jornada de 8 h). */
    public HorarioEnsayo(int jornada, Integer horaInicio, Integer horaFin, String finDeSemana, Double setupHoras,
                         List<Parada> paradas, String nota) {
        this(jornada, horaInicio, horaFin, finDeSemana, setupHoras, paradas, nota, null);
    }

    /** ¿Jornada de 8 h en el turno de tarde (14:00–22:00)? */
    public boolean deTarde() {
        return jornada == 8 && TARDE.equals(turno);
    }

    /** Minuto del día en que empieza la jornada de este horario (con el turno de tarde, 14:00). */
    public int inicioJornada() {
        return deTarde() ? 840 : inicioJornada(jornada);
    }

    /** Minuto del día en que acaba la jornada de este horario (con el turno de tarde, 22:00). */
    public int finJornada() {
        return deTarde() ? 1320 : finJornada(jornada);
    }

    /**
     * Una parada. Por franjas: [inicio, fin). Por horas: «horas» desde inicio;
     * si afecta a los turnos solo cuenta el tiempo dentro de la jornada (la
     * parada se alarga hasta consumir esas horas de turno); si no, son horas de reloj.
     */
    public record Parada(LocalDateTime inicio, LocalDateTime fin, Double horas, boolean afectaTurnos, String metodo, String motivo) {
        public Parada {
            metodo = POR_FRANJAS.equals(metodo) ? POR_FRANJAS : POR_HORAS;
            motivo = motivo == null || motivo.isBlank() ? null : motivo.trim();
        }

        public static Parada franja(LocalDateTime inicio, LocalDateTime fin) {
            return new Parada(inicio, fin, null, false, POR_FRANJAS, null);
        }

        public static Parada horas(LocalDateTime inicio, double horas, boolean afectaTurnos, String motivo) {
            return new Parada(inicio, null, horas, afectaTurnos, POR_HORAS, motivo);
        }

        public boolean porFranjas() {
            return POR_FRANJAS.equals(metodo);
        }
    }

    // ---- jornada ------------------------------------------------------------------------------

    /** Minuto del día en que empieza la jornada: 0 (24 h) o 360 (06:00, jornadas de 8 y 16 h). */
    public static int inicioJornada(int jornada) {
        return jornada == 24 ? 0 : 360;
    }

    /** Minuto del día en que acaba la jornada: 1440 (24:00), 840 (14:00) o 1320 (22:00). */
    public static int finJornada(int jornada) {
        return switch (jornada) {
            case 8 -> 840;
            case 16 -> 1320;
            default -> MINUTOS_DIA;
        };
    }

    /** Minuto de arranque el día de inicio (el elegido o el inicio de la jornada). */
    public int minutoInicio() {
        return horaInicio != null ? horaInicio : inicioJornada();
    }

    /** Minuto de fin el día de fin (el elegido o el fin de la jornada). */
    public int minutoFin() {
        return horaFin != null ? horaFin : finJornada();
    }

    /** ¿Se trabajan fines de semana y festivos? Lo elegido aquí o, si no, la opción Weekend del ensayo. */
    public boolean trabajaFinesDeSemana(boolean finDeSemanaEnsayo) {
        if (SI.equals(finDeSemana)) {
            return true;
        }
        if (NO.equals(finDeSemana)) {
            return false;
        }
        return finDeSemanaEnsayo;
    }

    /** true si lo elegido aquí contradice la opción Weekend del ensayo (aviso ⚠ en la tabla del banco). */
    public boolean anulaFinDeSemana(boolean finDeSemanaEnsayo) {
        return finDeSemana != null && trabajaFinesDeSemana(finDeSemanaEnsayo) != finDeSemanaEnsayo;
    }

    /** El mismo horario con otras horas de setup. */
    public HorarioEnsayo conSetup(Double horas) {
        return new HorarioEnsayo(jornada, horaInicio, horaFin, finDeSemana, horas, paradas, nota, turno);
    }

    /** Setup efectivo: el de aquí o, si no hay, el de la columna antigua (null o negativo = 0). */
    public double setupEfectivo(Double setupAntiguo) {
        Double s = setupHoras != null ? setupHoras : setupAntiguo;
        return s == null || s.isNaN() || s < 0 ? 0 : s;
    }

    // ---- texto ------------------------------------------------------------------------------

    /** 390 → «06:30»; 1440 → «24:00». */
    public static String hora(int minutos) {
        return String.format(Locale.ROOT, "%02d:%02d", minutos / 60, minutos % 60);
    }

    /** «06:30» → 390; «24:00» → 1440. Rechaza lo que no sea una hora válida. */
    public static int minutos(String texto) {
        Matcher m = HORA.matcher(texto == null ? "" : texto.trim());
        if (!m.matches()) {
            throw new ReglaNoCumplida("Hora no válida (HH:MM): " + texto);
        }
        int h = Integer.parseInt(m.group(1));
        int min = Integer.parseInt(m.group(2));
        if (min > 59 || h > 24 || (h == 24 && min > 0)) {
            throw new ReglaNoCumplida("Hora no válida (HH:MM): " + texto);
        }
        return h * 60 + min;
    }

    public static String fechaHora(LocalDateTime t) {
        return t == null ? null : t.format(FECHA_HORA);
    }

    /** «2026-11-18T06:30» (también admite segundos, que deben ser 0). */
    public static LocalDateTime fechaHora(String texto) {
        String t = texto == null ? "" : texto.trim();
        try {
            return LocalDateTime.parse(t.length() > 16 ? t : t + ":00");
        } catch (DateTimeParseException e) {
            throw new ReglaNoCumplida("Fecha y hora no válidas (aaaa-mm-ddTHH:MM): " + texto);
        }
    }

    // ---- JSON -------------------------------------------------------------------------------

    private static String texto(JsonNode n, String campo) {
        JsonNode v = n == null ? null : n.get(campo);
        if (v == null || v.isNull() || v.isMissingNode()) {
            return null;
        }
        String t = v.asText("").trim();
        return t.isEmpty() ? null : t;
    }

    private static Double numero(JsonNode n, String campo) {
        String t = texto(n, campo);
        if (t == null) {
            return null;
        }
        try {
            double d = Double.parseDouble(t.replace(',', '.'));
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new NumberFormatException(t);
            }
            return d;
        } catch (NumberFormatException e) {
            throw new ReglaNoCumplida("Número no válido en «" + campo + "»: " + t);
        }
    }

    /**
     * Lee el horario del formato de la API y de la tabla: {jornada, horaInicio, horaFin,
     * finDeSemana, setupHoras, paradas: [{inicio, fin?, horas?, afectaTurnos, metodo, motivo?}], nota}.
     * Solo comprueba el formato; las reglas las comprueba {@link #validar}.
     */
    public static HorarioEnsayo desdeJson(JsonNode n) {
        Double j = numero(n, "jornada");
        int jornada = j == null ? 24 : (int) Math.round(j);
        if (j != null && j != jornada) {
            throw new ReglaNoCumplida("Jornada no válida: " + j);
        }
        String hi = texto(n, "horaInicio");
        String hf = texto(n, "horaFin");
        List<Parada> paradas = new ArrayList<>();
        JsonNode lista = n == null ? null : n.get("paradas");
        if (lista != null && lista.isArray()) {
            for (JsonNode p : lista) {
                String inicio = texto(p, "inicio");
                if (inicio == null) {
                    throw new ReglaNoCumplida("Falta el inicio de una parada.");
                }
                String fin = texto(p, "fin");
                String metodo = POR_FRANJAS.equals(texto(p, "metodo")) ? POR_FRANJAS : POR_HORAS;
                paradas.add(new Parada(fechaHora(inicio), fin == null ? null : fechaHora(fin), numero(p, "horas"),
                        p.path("afectaTurnos").asBoolean(false), metodo, texto(p, "motivo")));
            }
        } else if (lista != null && !lista.isNull()) {
            throw new ReglaNoCumplida("Las paradas tienen que ser una lista.");
        }
        return new HorarioEnsayo(jornada, hi == null ? null : minutos(hi), hf == null ? null : minutos(hf), texto(n, "finDeSemana"),
                numero(n, "setupHoras"), paradas, texto(n, "nota"), texto(n, "turno"));
    }

    public static ObjectNode paradaJson(Parada p) {
        ObjectNode o = JsonJs.objetoVacio();
        o.put("inicio", fechaHora(p.inicio()));
        if (p.fin() != null) {
            o.put("fin", fechaHora(p.fin()));
        }
        if (p.horas() != null) {
            o.put("horas", p.horas());
        }
        o.put("afectaTurnos", p.afectaTurnos());
        o.put("metodo", p.metodo());
        if (p.motivo() != null) {
            o.put("motivo", p.motivo());
        }
        return o;
    }

    /** Formato de la API y de la tabla (las horas en «HH:MM», null = por defecto). */
    public ObjectNode json() {
        ObjectNode o = JsonJs.objetoVacio();
        o.put("jornada", jornada);
        if (turno == null) {
            o.putNull("turno");
        } else {
            o.put("turno", turno);
        }
        if (horaInicio == null) {
            o.putNull("horaInicio");
        } else {
            o.put("horaInicio", hora(horaInicio));
        }
        if (horaFin == null) {
            o.putNull("horaFin");
        } else {
            o.put("horaFin", hora(horaFin));
        }
        if (finDeSemana == null) {
            o.putNull("finDeSemana");
        } else {
            o.put("finDeSemana", finDeSemana);
        }
        if (setupHoras == null) {
            o.putNull("setupHoras");
        } else {
            o.put("setupHoras", setupHoras);
        }
        ArrayNode lista = o.putArray("paradas");
        paradas.forEach(p -> lista.add(paradaJson(p)));
        if (nota == null) {
            o.putNull("nota");
        } else {
            o.put("nota", nota);
        }
        return o;
    }

    /** Solo las paradas en JSON (columna jsonb «paradas»). */
    public ArrayNode paradasJson() {
        ArrayNode lista = JsonJs.mapper().createArrayNode();
        paradas.forEach(p -> lista.add(paradaJson(p)));
        return lista;
    }

    // ---- reglas -----------------------------------------------------------------------------

    private static boolean enMediaHora(int minutos) {
        return minutos % 30 == 0;
    }

    private static boolean enMediaHora(LocalDateTime t) {
        return t.getSecond() == 0 && t.getNano() == 0 && t.getMinute() % 30 == 0;
    }

    private static boolean multiploDeMedia(double horas) {
        return Math.abs(horas * 2 - Math.rint(horas * 2)) < 1e-9;
    }

    /**
     * Comprueba las reglas (D59). inicio y fin: fechas del ensayo (inclusive); si
     * faltan no se comprueba que las paradas caigan dentro. Lanza ReglaNoCumplida
     * con todos los errores juntos.
     */
    public HorarioEnsayo validar(LocalDate inicio, LocalDate fin) {
        List<String> errores = new ArrayList<>();
        if (!JORNADAS.contains(jornada)) {
            errores.add("La jornada tiene que ser de 24, 8 o 16 horas.");
        } else {
            if (turno != null && !TARDE.equals(turno)) {
                errores.add("El turno tiene que ser «manana» o «tarde».");
            }
            int desde = inicioJornada();
            int hasta = finJornada();
            String ventana = hora(desde) + "–" + hora(hasta);
            if (horaInicio != null && (!enMediaHora(horaInicio) || horaInicio < desde || horaInicio >= hasta)) {
                errores.add("La hora de inicio tiene que ser una hora o media hora dentro de la jornada (" + ventana + ").");
            }
            if (horaFin != null && (!enMediaHora(horaFin) || horaFin <= desde || horaFin > hasta)) {
                errores.add("La hora de fin tiene que ser una hora o media hora dentro de la jornada (" + ventana + ").");
            }
            if (inicio != null && inicio.equals(fin) && minutoInicio() >= minutoFin()) {
                errores.add("El ensayo empieza y acaba el mismo día: la hora de inicio tiene que ser anterior a la de fin.");
            }
        }
        if (finDeSemana != null && !SI.equals(finDeSemana) && !NO.equals(finDeSemana)) {
            errores.add("Fines de semana y festivos: «si», «no» o como el ensayo.");
        }
        if (setupHoras != null && (setupHoras < 0 || !multiploDeMedia(setupHoras) || setupHoras > MAXIMO_HORAS)) {
            errores.add("Las horas de setup tienen que ser 0 o más, en medias horas.");
        }
        if (nota != null && nota.length() > LARGO_NOTA) {
            errores.add("La nota admite como máximo " + LARGO_NOTA + " caracteres.");
        }
        if (paradas.size() > MAXIMO_PARADAS) {
            errores.add("Demasiadas paradas (máximo " + MAXIMO_PARADAS + ").");
        }
        LocalDateTime desdeEnsayo = inicio == null ? null : inicio.atStartOfDay();
        LocalDateTime hastaEnsayo = fin == null ? null : fin.plusDays(1).atStartOfDay();
        int n = 0;
        for (Parada p : paradas) {
            n++;
            String cual = "Parada " + n + ": ";
            if (p.inicio() == null) {
                errores.add(cual + "falta el inicio.");
                continue;
            }
            if (!enMediaHora(p.inicio())) {
                errores.add(cual + "tiene que empezar en una hora o media hora.");
            }
            if (desdeEnsayo != null && hastaEnsayo != null && (p.inicio().isBefore(desdeEnsayo) || !p.inicio().isBefore(hastaEnsayo))) {
                errores.add(cual + "tiene que empezar entre las fechas del ensayo.");
            }
            if (p.porFranjas()) {
                if (p.fin() == null || !p.fin().isAfter(p.inicio())) {
                    errores.add(cual + "el fin tiene que ser posterior al inicio.");
                } else {
                    if (!enMediaHora(p.fin())) {
                        errores.add(cual + "tiene que acabar en una hora o media hora.");
                    }
                    if (hastaEnsayo != null && p.fin().isAfter(hastaEnsayo)) {
                        errores.add(cual + "tiene que acabar dentro de las fechas del ensayo.");
                    }
                }
            } else if (p.horas() == null || p.horas() <= 0 || !multiploDeMedia(p.horas()) || p.horas() > MAXIMO_HORAS) {
                errores.add(cual + "la duración tiene que ser mayor que 0 y en medias horas.");
            }
            if (p.motivo() != null && p.motivo().length() > LARGO_MOTIVO) {
                errores.add(cual + "el motivo admite como máximo " + LARGO_MOTIVO + " caracteres.");
            }
        }
        if (!errores.isEmpty()) {
            throw new ReglaNoCumplida(String.join(" ", errores));
        }
        return this;
    }
}
