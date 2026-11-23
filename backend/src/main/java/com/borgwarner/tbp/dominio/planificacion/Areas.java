package com.borgwarner.tbp.dominio.planificacion;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Áreas: nivel por encima de las categorías de bancos.
 *
 * Cada categoría pertenece a un área, guardada en la columna «Area» de
 * TestBenchBenches (el mismo valor en todos los bancos de la categoría). Una
 * columna vacía o un valor desconocido cuenta como Bancos Térmicos, así que
 * los bancos de antes no necesitan migración.
 *
 * De serie hay tres áreas, en este orden: Bancos Térmicos, HVCH - CTAG y
 * HVCH - STC. Con PostgreSQL, un Admin o un Lab User puede crear más, cambiarles
 * el nombre y borrar las que no tengan bancos (D56); con SharePoint y en local
 * son siempre las tres de serie.
 *
 * Cada área elige su organización al crearla: «banco a banco» (como los Bancos
 * Térmicos: BW o CTAG en cada banco) o una organización fija para todos sus
 * bancos (HVCH - CTAG lleva CTAG y HVCH - STC lleva STC, para que la interfaz
 * clásica, que trata un banco sin organización como BW, no los cuente como BW).
 */
public final class Areas {

    public static final String TERMICOS = "THERMAL";
    public static final String HVCH_CTAG = "HVCH-CTAG";
    public static final String HVCH_STC = "HVCH-STC";

    /** Códigos de serie en el orden en que se muestran. */
    public static final List<String> CODIGOS = List.of(TERMICOS, HVCH_CTAG, HVCH_STC);

    /** Nombres de serie (se pueden renombrar). */
    public static final Map<String, String> NOMBRES_DE_SERIE;

    /** Organizaciones que se pueden fijar en un área nueva (y las que se eligen banco a banco: BW y CTAG). */
    public static final List<String> ORGANIZACIONES = List.of("BW", "CTAG", "STC");

    /** Códigos que no puede tener un área: son filtros de los KPI (ALL y las organizaciones de Bancos Térmicos). */
    static final List<String> RESERVADOS = List.of("ALL", "BW", "CTAG");

    /** Largo máximo del nombre de un área. */
    public static final int LARGO_MAXIMO_NOMBRE = 60;

    static {
        Map<String, String> nombres = new LinkedHashMap<>();
        nombres.put(TERMICOS, "Thermal Benches");
        nombres.put(HVCH_CTAG, "HVCH - CTAG");
        nombres.put(HVCH_STC, "HVCH - STC");
        NOMBRES_DE_SERIE = java.util.Collections.unmodifiableMap(nombres);
    }

    /**
     * Un área.
     *
     * @param codigo       código guardado en la columna Area de los bancos (no cambia nunca)
     * @param nombre       nombre visible
     * @param organizacion organización fija de sus bancos, o null si se elige banco a banco
     */
    public record Definicion(String codigo, String nombre, String organizacion) {

        /** true si la organización se elige banco a banco (BW / CTAG). */
        public boolean conOrganizacion() {
            return organizacion == null;
        }

        public Definicion conNombre(String otro) {
            return new Definicion(codigo, otro, organizacion);
        }
    }

    /** Las tres áreas de serie con sus nombres de serie. */
    public static final List<Definicion> DE_SERIE = List.of(
            new Definicion(TERMICOS, "Thermal Benches", null),
            new Definicion(HVCH_CTAG, "HVCH - CTAG", "CTAG"),
            new Definicion(HVCH_STC, "HVCH - STC", "STC"));

    private Areas() {
    }

    /**
     * Forma de código de un valor guardado (mayúsculas, guiones en lugar de
     * espacios y subrayados), sin comprobar que el área exista. Vacío = Bancos Térmicos.
     */
    public static String formatear(String valor) {
        if (valor == null) {
            return TERMICOS;
        }
        String v = valor.trim().toUpperCase(Locale.ROOT).replace(' ', '-').replace('_', '-');
        while (v.contains("--")) {
            v = v.replace("--", "-");
        }
        return v.isEmpty() ? TERMICOS : v;
    }

    /** Código de área a partir del valor guardado: vacío o desconocido (entre las de serie) = Bancos Térmicos. */
    public static String normalizar(String valor) {
        return normalizar(valor, CODIGOS);
    }

    /** Ídem con los códigos de las áreas que existen. */
    public static String normalizar(String valor, Collection<String> codigos) {
        String v = formatear(valor);
        return codigos.contains(v) ? v : TERMICOS;
    }

    /** ¿Se elige la organización banco a banco? (áreas de serie) */
    public static boolean esConOrganizacion(String area) {
        return organizacionFija(area) == null;
    }

    /** Organización fija de los bancos de un área de serie, o null si se elige banco a banco (Térmicos). */
    public static String organizacionFija(String area) {
        return organizacionFija(area, DE_SERIE);
    }

    /** Ídem entre las áreas indicadas (un código desconocido cuenta como Bancos Térmicos). */
    public static String organizacionFija(String area, List<Definicion> areas) {
        Definicion d = buscar(normalizar(area, codigos(areas)), areas);
        return d == null ? null : d.organizacion();
    }

    /** Códigos de las áreas, en su orden. */
    public static List<String> codigos(List<Definicion> areas) {
        return areas.stream().map(Definicion::codigo).toList();
    }

    /** El área con ese código (ya normalizado), o null. */
    public static Definicion buscar(String codigo, List<Definicion> areas) {
        for (Definicion d : areas) {
            if (d.codigo().equals(codigo)) {
                return d;
            }
        }
        return null;
    }

    /** Nombres de las áreas de serie: los guardados sobre los de serie, en el orden fijo. */
    public static Map<String, String> nombres(Map<String, String> guardados) {
        Map<String, String> nombres = new LinkedHashMap<>();
        for (Definicion d : definiciones(null, guardados)) {
            nombres.put(d.codigo(), d.nombre());
        }
        return nombres;
    }

    /**
     * Áreas con su nombre: las guardadas (o las de serie si no hay ninguna
     * guardada) con los nombres guardados encima. Bancos Térmicos está siempre:
     * si falta en la lista guardada se pone la primera. Un código repetido vale
     * la primera vez.
     *
     * @param guardadas áreas guardadas (código y organización; el nombre se ignora), o null
     * @param nombres   código → nombre guardado (los que falten: el de serie o el código)
     */
    public static List<Definicion> definiciones(List<Definicion> guardadas, Map<String, String> nombres) {
        List<Definicion> base = guardadas == null || guardadas.isEmpty() ? DE_SERIE : guardadas;
        List<Definicion> salida = new ArrayList<>();
        java.util.Set<String> vistos = new java.util.HashSet<>();
        if (base.stream().noneMatch(d -> TERMICOS.equals(d.codigo()))) {
            salida.add(DE_SERIE.get(0));
            vistos.add(TERMICOS);
        }
        for (Definicion d : base) {
            if (d == null || d.codigo() == null || d.codigo().isEmpty() || !vistos.add(d.codigo())) {
                continue;
            }
            // Bancos Térmicos se elige siempre banco a banco: es el área de los bancos sin área.
            salida.add(new Definicion(d.codigo(), null, TERMICOS.equals(d.codigo()) ? null : d.organizacion()));
        }
        List<Definicion> conNombres = new ArrayList<>();
        for (Definicion d : salida) {
            String guardado = nombres == null ? null : nombres.get(d.codigo());
            String nombre = guardado == null || guardado.trim().isEmpty()
                    ? NOMBRES_DE_SERIE.getOrDefault(d.codigo(), d.codigo()) : guardado.trim();
            conNombres.add(d.conNombre(nombre));
        }
        return List.copyOf(conNombres);
    }

    /**
     * Código para un área nueva a partir de su nombre: letras y cifras en
     * mayúsculas separadas por guiones; si ya existe, se le añade -2, -3…
     */
    public static String codigoNuevo(String nombre, Collection<String> existentes) {
        String base = java.text.Normalizer.normalize(nombre == null ? "" : nombre, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (base.isEmpty()) {
            base = "AREA";
        }
        if (base.length() > 30) {
            base = base.substring(0, 30).replaceAll("-+$", "");
        }
        String codigo = base;
        for (int i = 2; existentes.contains(codigo) || RESERVADOS.contains(codigo); i++) {
            codigo = base + "-" + i;
        }
        return codigo;
    }
}
