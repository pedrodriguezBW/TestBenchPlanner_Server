package com.borgwarner.tbp.infraestructura.migracion;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Fusión de tres vías entre dos copias de los datos que han evolucionado por
 * separado a partir de un estado común:
 *
 *   BASE        = exportación de SharePoint con la que se cargó PostgreSQL
 *   SHAREPOINT  = exportación actual de SharePoint
 *   LOCAL       = estado actual de PostgreSQL (exportado con los mismos adaptadores)
 *
 * Trabaja sobre árboles JSON genéricos (Map / List / String / Number / Boolean
 * / null), sin dependencias de Jackson, para poder probarla de forma aislada.
 *
 * Reglas, campo a campo:
 *  - Solo cambia un lado respecto a la base → se toma ese cambio.
 *  - Los dos lados cambian igual → sin conflicto.
 *  - Los dos lados cambian distinto → si los dos son objetos se baja un nivel;
 *    si no, CONFLICTO (se resuelve con el fichero de resoluciones).
 *
 * Listas con identidad (ensayos por uid, usuarios por email, bancos por
 * nombre…) se fusionan elemento a elemento:
 *  - Alta en un solo lado → se añade.
 *  - Borrado en PostgreSQL y sin cambios en SharePoint → sigue borrado (la
 *    importación no quita nada de la base).
 *  - Borrado en SharePoint → NUNCA se borra de la base automáticamente: se
 *    conserva salvo que la resolución diga «borrar».
 *  - Borrado en un lado y modificado en el otro → se conserva la versión
 *    modificada salvo que la resolución diga «borrar».
 *  Todos los borrados quedan anotados (BORRADO / BORRADO_MODIFICADO).
 *
 * El DataJson de cada ensayo se fusiona por campos (no como texto). Si el
 * resultado coincide con uno de los lados, se conserva su texto original byte a
 * byte (así no se reescriben filas sin cambios).
 *
 * D65:
 *  - Elementos protegidos (usuarios con perfil de Timing R&T o cuenta, proyectos
 *    que usa Timing): nunca se borran, aunque falten en SharePoint o la
 *    resolución diga «borrar».
 *  - Histórico de KPI: cada semana (Year|CW|Org) es una foto y no se mezcla
 *    campo a campo. Si cambió en los dos lados, vale entera la del servidor.
 */
public final class FusionTresVias {

    /** Tipos de decisión. Solo CAMPO sin resolver bloquea la importación. */
    public static final String CAMPO = "CAMPO";
    public static final String BORRADO = "BORRADO";
    public static final String BORRADO_MODIFICADO = "BORRADO_MODIFICADO";

    /** Valores admitidos en «elegir». */
    public static final String LOCAL = "local";
    public static final String SHAREPOINT = "sharepoint";
    public static final String CONSERVAR = "conservar";
    public static final String BORRAR = "borrar";

    /** Lo que se escribe en el informe cuando un valor no existe en un lado. */
    public static final String TEXTO_AUSENTE = "(no existe)";

    /** Marca de «no existe» (distinta del null de JSON). */
    static final Object AUSENTE = new Object() {
        @Override
        public String toString() {
            return TEXTO_AUSENTE;
        }
    };

    public static final String ACTIVIDAD = "actividad.json";
    public static final String PLANNER = "planner.json";

    /** Ficheros que se fusionan (el de actividad se une aparte: solo se añade). */
    public static final List<String> FICHEROS = List.of(PLANNER, "configuracion.json", "usuarios.json", "recursos.json",
            "kpis.json", "aprobaciones.json", "tipos-equipo.json", "version.json");

    /**
     * Una decisión de la fusión: conflicto de campo o borrado en un lado.
     *
     * @param elegido   valor aplicado («local», «sharepoint», «conservar», «borrar») o vacío si está pendiente
     * @param pendiente true = conflicto sin resolver (bloquea la importación)
     */
    public record Decision(String id, String tipo, String fichero, String elemento, String campo, String ladoBorrado,
                           Object base, Object sharepoint, Object local, String elegido, boolean pendiente) {
    }

    /** Resultado: ficheros fusionados (nombre → árbol), decisiones, recuentos y avisos. */
    public record Resultado(Map<String, Object> ficheros, List<Decision> decisiones,
                            Map<String, Map<String, Integer>> recuentos, List<String> avisos) {

        public List<Decision> pendientes() {
            return decisiones.stream().filter(Decision::pendiente).toList();
        }
    }

    /** Resoluciones leídas del fichero de conflictos editado a mano. */
    public record Resoluciones(Map<String, String> elegidas, String porDefecto) {

        public static final Resoluciones NINGUNA = new Resoluciones(Map.of(), "");

        /**
         * Lee el árbol del fichero conflictos.json: {"porDefecto": "", "decisiones": [{"id": …, "elegir": …}]}.
         */
        @SuppressWarnings("unchecked")
        public static Resoluciones de(Object arbol) {
            if (!(arbol instanceof Map<?, ?> raiz)) {
                throw new IllegalArgumentException("El fichero de resoluciones no es un objeto JSON");
            }
            String porDefecto = texto(raiz.get("porDefecto")).trim().toLowerCase(Locale.ROOT);
            if (!porDefecto.isEmpty() && !porDefecto.equals(LOCAL) && !porDefecto.equals(SHAREPOINT)) {
                throw new IllegalArgumentException("porDefecto debe ser \"\", \"local\" o \"sharepoint\" (es \"" + porDefecto + "\")");
            }
            Map<String, String> elegidas = new HashMap<>();
            Object lista = raiz.get("decisiones");
            if (lista instanceof List<?> decisiones) {
                for (Object o : decisiones) {
                    if (!(o instanceof Map<?, ?> d)) {
                        continue;
                    }
                    String id = texto(d.get("id"));
                    String elegir = texto(d.get("elegir")).trim().toLowerCase(Locale.ROOT);
                    if (!id.isEmpty() && !elegir.isEmpty()) {
                        elegidas.put(id, elegir);
                    }
                }
            }
            return new Resoluciones(Map.copyOf(elegidas), porDefecto);
        }

        private static String texto(Object o) {
            return o == null ? "" : o.toString();
        }
    }

    private final Function<String, Object> lectorJson;
    private final Function<Object, String> escritorJson;
    private final Resoluciones resoluciones;

    // Estado de una ejecución.
    private final List<Decision> decisiones = new ArrayList<>();
    private final Map<String, Map<String, Integer>> recuentos = new LinkedHashMap<>();
    private final List<String> avisos = new ArrayList<>();
    private final Set<String> idsUsados = new LinkedHashSet<>();

    /**
     * @param lectorJson   texto JSON → árbol (para el DataJson de los ensayos); puede lanzar excepción si no es JSON
     * @param escritorJson árbol → texto JSON con el formato de JSON.stringify
     * @param resoluciones resoluciones de conflictos (o {@link Resoluciones#NINGUNA})
     */
    public FusionTresVias(Function<String, Object> lectorJson, Function<Object, String> escritorJson, Resoluciones resoluciones) {
        this(lectorJson, escritorJson, resoluciones, Map.of());
    }

    /**
     * @param protegidos claves que nunca se borran, por lista («usuarios.json#», «configuracion.json#projects»)
     */
    public FusionTresVias(Function<String, Object> lectorJson, Function<Object, String> escritorJson, Resoluciones resoluciones,
                          Map<String, Set<String>> protegidos) {
        this.lectorJson = lectorJson;
        this.escritorJson = escritorJson;
        this.resoluciones = resoluciones == null ? Resoluciones.NINGUNA : resoluciones;
        this.protegidos = protegidos == null ? Map.of() : protegidos;
    }

    private final Map<String, Set<String>> protegidos;

    /** Histórico de KPI: una semana es una foto entera (D65). */
    static final String KPIS = "kpis.json";

    /**
     * Fusiona las tres copias. Cada mapa es nombre de fichero → árbol; un
     * fichero que no está en un mapa se considera inexistente en esa copia.
     */
    public Resultado fusionar(Map<String, Object> base, Map<String, Object> sharepoint, Map<String, Object> local) {
        decisiones.clear();
        recuentos.clear();
        avisos.clear();
        idsUsados.clear();
        Map<String, Object> salida = new LinkedHashMap<>();
        for (String fichero : FICHEROS) {
            Object b = base.containsKey(fichero) ? base.get(fichero) : AUSENTE;
            Object s = sharepoint.containsKey(fichero) ? sharepoint.get(fichero) : AUSENTE;
            Object l = local.containsKey(fichero) ? local.get(fichero) : AUSENTE;
            boolean sinBase = b == AUSENTE && s != AUSENTE && l != AUSENTE;
            if (sinBase) {
                // Sin estado común: en las diferencias manda PostgreSQL y se añade lo que solo esté en SharePoint.
                avisos.add(fichero + ": no está en la base común; se conserva PostgreSQL y se añade lo nuevo de SharePoint");
            }
            Contexto c = new Contexto(fichero, "", sinBase);
            Object r = PLANNER.equals(fichero) ? fusionarPlanner(c, b, s, l) : fusionar(c, "", b, s, l);
            if (r != AUSENTE) {
                salida.put(fichero, r);
            }
        }
        Object actividad = unirActividad(local.get(ACTIVIDAD), sharepoint.get(ACTIVIDAD));
        if (actividad != null) {
            salida.put(ACTIVIDAD, actividad);
        }
        for (String id : resoluciones.elegidas().keySet()) {
            if (!idsUsados.contains(id)) {
                avisos.add("resolución sin efecto (ese conflicto ya no existe): " + id);
            }
        }
        return new Resultado(salida, List.copyOf(decisiones), copiaRecuentos(), List.copyOf(avisos));
    }

    // ---- fusión genérica --------------------------------------------------------------

    /** Dónde estamos: fichero y elemento de lista (p. ej. «uid=abc»), para el informe y los ids. */
    private record Contexto(String fichero, String elemento, boolean sinBase) {
        Contexto con(String otro) {
            return new Contexto(fichero, elemento.isEmpty() ? otro : elemento + " / " + otro, sinBase);
        }
    }

    private Object fusionar(Contexto c, String ruta, Object b, Object s, Object l) {
        Function<Object, String> clave = claveDeLista(c.fichero(), ruta);
        if (clave != null && esListaOAusente(b) && s instanceof List<?> && l instanceof List<?>) {
            return fusionarLista(c, ruta, comoLista(b), (List<?>) s, (List<?>) l, clave, null);
        }
        if (igual(s, l)) {
            return l;
        }
        if (ruta.isEmpty() && c.elemento().isEmpty() && s instanceof Map<?, ?> sm0 && l instanceof Map<?, ?> lm0) {
            // Raíz de un fichero: siempre se baja, para que sus listas con identidad apliquen la regla de borrados.
            Map<?, ?> bm = b instanceof Map<?, ?> m ? m : Collections.emptyMap();
            return fusionarObjetos(c, ruta, bm, sm0, lm0);
        }
        if (igual(b, s)) {
            return l;
        }
        if (igual(b, l)) {
            contar(c.fichero(), "camposDesdeSharePoint");
            return s;
        }
        if (s instanceof Map<?, ?> sm && l instanceof Map<?, ?> lm) {
            Map<?, ?> bm = b instanceof Map<?, ?> m ? m : Collections.emptyMap();
            return fusionarObjetos(c, ruta, bm, sm, lm);
        }
        return conflicto(c, ruta, b, s, l);
    }

    private Map<String, Object> fusionarObjetos(Contexto c, String ruta, Map<?, ?> b, Map<?, ?> s, Map<?, ?> l) {
        Set<String> claves = new LinkedHashSet<>();
        l.keySet().forEach(k -> claves.add(String.valueOf(k)));
        s.keySet().forEach(k -> claves.add(String.valueOf(k)));
        Map<String, Object> salida = new LinkedHashMap<>();
        for (String k : claves) {
            Object r = fusionar(c, ruta.isEmpty() ? k : ruta + "/" + k, valor(b, k), valor(s, k), valor(l, k));
            if (r != AUSENTE) {
                salida.put(k, r);
            }
        }
        return salida;
    }

    private Object conflicto(Contexto c, String ruta, Object b, Object s, Object l) {
        String id = c.fichero() + "|" + c.elemento() + "|" + ruta;
        idsUsados.add(id);
        String elegido = resoluciones.elegidas().getOrDefault(id, "");
        if (!elegido.isEmpty() && !elegido.equals(LOCAL) && !elegido.equals(SHAREPOINT)) {
            throw new IllegalArgumentException("Resolución no válida para " + id + ": \"" + elegido
                    + "\" (en un conflicto de campo solo vale \"local\" o \"sharepoint\")");
        }
        if (elegido.isEmpty()) {
            elegido = c.sinBase() ? LOCAL : resoluciones.porDefecto();
        }
        boolean pendiente = elegido.isEmpty();
        decisiones.add(new Decision(id, CAMPO, c.fichero(), c.elemento(), ruta, "", b, s, l, elegido, pendiente));
        contar(c.fichero(), pendiente ? "conflictosPendientes" : "conflictosResueltos");
        return SHAREPOINT.equals(elegido) ? s : l;
    }

    // ---- listas con identidad ---------------------------------------------------------

    /**
     * Fusiona elemento a elemento. Si alguna copia tiene claves repetidas o
     * elementos sin clave, la lista se trata como un valor único.
     *
     * @param transformar si no es null, se aplica a cada elemento fusionado (DataJson de los ensayos)
     */
    private Object fusionarLista(Contexto c, String ruta, List<?> b, List<?> s, List<?> l, Function<Object, String> clave,
                                 ElementoFusionado transformar) {
        Map<String, Object> bi = indexar(b, clave);
        Map<String, Object> si = indexar(s, clave);
        Map<String, Object> li = indexar(l, clave);
        if (bi == null || si == null || li == null) {
            avisos.add(c.fichero() + (ruta.isEmpty() ? "" : " " + ruta) + ": hay claves repetidas o vacías; la lista se compara entera");
            if (igual(s, l) || igual(b, s)) {
                return l;
            }
            if (igual(b, l)) {
                contar(c.fichero(), "camposDesdeSharePoint");
                return s;
            }
            return conflicto(c, ruta, b, s, l);
        }
        Set<String> orden = new LinkedHashSet<>(li.keySet());
        orden.addAll(si.keySet());
        List<Object> salida = new ArrayList<>();
        String nombreClave = ruta.isEmpty() ? nombreClaveRaiz(c.fichero()) : ruta;
        for (String k : orden) {
            Object bx = bi.getOrDefault(k, AUSENTE);
            Object sx = si.getOrDefault(k, AUSENTE);
            Object lx = li.getOrDefault(k, AUSENTE);
            Contexto ce = c.con(nombreClave + "=" + k);
            Object r;
            if (bx == AUSENTE) {
                if (lx == AUSENTE) {
                    contar(c.fichero(), "altasDesdeSharePoint");
                    r = sx;
                } else if (sx == AUSENTE) {
                    r = lx;
                } else {
                    // Alta en los dos lados con la misma clave: se fusiona sin base.
                    r = fusionarElemento(ce, AUSENTE, sx, lx);
                }
            } else if (sx == AUSENTE && lx == AUSENTE) {
                contar(c.fichero(), "borradosEnAmbos");
                continue;
            } else if (sx == AUSENTE || lx == AUSENTE) {
                r = borrado(ce, bx, sx, lx, protegido(c.fichero(), ruta, k));
                if (r == AUSENTE) {
                    continue;
                }
            } else {
                r = fusionarElemento(ce, bx, sx, lx);
            }
            if (transformar != null) {
                r = transformar.aplicar(k, r);
            }
            salida.add(r);
        }
        return salida;
    }

    private boolean protegido(String fichero, String ruta, String clave) {
        Set<String> claves = protegidos.get(fichero + "#" + ruta);
        return claves != null && claves.contains(clave);
    }

    private Object fusionarElemento(Contexto c, Object b, Object s, Object l) {
        if (igual(s, l) || igual(b, s)) {
            return l;
        }
        if (igual(b, l)) {
            contar(c.fichero(), "elementosDesdeSharePoint");
            return s;
        }
        if (KPIS.equals(c.fichero()) && c.elemento().indexOf(" / ") < 0) {
            // D65: la semana cambió en los dos lados; no se mezclan dos fotos: vale la del servidor.
            contar(c.fichero(), "semanasDelServidor");
            avisos.add(KPIS + " " + c.elemento() + ": la semana cambió en SharePoint y en el servidor; se conserva entera la del servidor");
            return l;
        }
        if (s instanceof Map<?, ?> sm && l instanceof Map<?, ?> lm) {
            contar(c.fichero(), "elementosFusionados");
            Map<?, ?> bm = b instanceof Map<?, ?> m ? m : Collections.emptyMap();
            return fusionarObjetos(c, "", bm, sm, lm);
        }
        return conflicto(c, "", b, s, l);
    }

    /** Borrado en un lado: se conserva salvo que la resolución diga «borrar» (y el elemento no esté protegido). */
    private Object borrado(Contexto c, Object b, Object s, Object l, boolean protegido) {
        boolean enSharePoint = s == AUSENTE;
        Object queda = enSharePoint ? l : s;
        String tipo = igual(b, queda) ? BORRADO : BORRADO_MODIFICADO;
        String id = c.fichero() + "|" + c.elemento() + "|" + (enSharePoint ? "borrado-en-sharepoint" : "borrado-en-local");
        idsUsados.add(id);
        String elegido = resoluciones.elegidas().getOrDefault(id, "");
        if (!elegido.isEmpty() && !elegido.equals(CONSERVAR) && !elegido.equals(BORRAR)) {
            throw new IllegalArgumentException("Resolución no válida para " + id + ": \"" + elegido
                    + "\" (en un borrado solo vale \"conservar\" o \"borrar\")");
        }
        if (elegido.isEmpty()) {
            // Por defecto: un borrado hecho en el servidor sin cambios en SharePoint se respeta (no se pierde
            // nada de la base); cualquier otro caso se conserva, para que la importación nunca borre datos sola.
            elegido = !enSharePoint && BORRADO.equals(tipo) ? BORRAR : CONSERVAR;
        }
        if (enSharePoint && protegido && BORRAR.equals(elegido)) {
            avisos.add(c.fichero() + " " + c.elemento() + ": no se borra aunque falte en SharePoint (lo usa Timing R&T o tiene cuenta en el servidor)");
            elegido = CONSERVAR;
        }
        decisiones.add(new Decision(id, tipo, c.fichero(), c.elemento(), "", enSharePoint ? SHAREPOINT : LOCAL, b, s, l,
                elegido, false));
        if (BORRAR.equals(elegido)) {
            contar(c.fichero(), enSharePoint ? "borradosDeSharePointAplicados" : "borradosDelServidorRespetados");
            return AUSENTE;
        }
        contar(c.fichero(), "borradosConservados");
        return queda;
    }

    @FunctionalInterface
    private interface ElementoFusionado {
        Object aplicar(String clave, Object elemento);
    }

    // ---- ensayos (planner.json) -------------------------------------------------------

    /**
     * Ensayos: lista por uid; el texto DataJson se convierte en objeto para
     * fusionarlo por campos y al final se vuelve a escribir.
     */
    private Object fusionarPlanner(Contexto c, Object b, Object s, Object l) {
        if (!(esListaOAusente(b) && s instanceof List<?> && l instanceof List<?>)) {
            return fusionar(c, "", b, s, l);
        }
        Map<String, String> textoLocal = new HashMap<>();
        Map<String, String> textoSharePoint = new HashMap<>();
        List<Object> bp = desplegar(comoLista(b), null);
        List<Object> sp = desplegar((List<?>) s, textoSharePoint);
        List<Object> lp = desplegar((List<?>) l, textoLocal);
        return fusionarLista(c, "", bp, sp, lp, claveDeLista(PLANNER, ""), (uid, elemento) -> plegar(uid, elemento,
                textoLocal, textoSharePoint));
    }

    /** Copia la lista con «data» convertido en árbol (si es JSON válido); guarda el texto original. */
    private List<Object> desplegar(List<?> filas, Map<String, String> textos) {
        List<Object> salida = new ArrayList<>();
        for (Object o : filas) {
            if (!(o instanceof Map<?, ?> fila)) {
                salida.add(o);
                continue;
            }
            Map<String, Object> copia = new LinkedHashMap<>();
            fila.forEach((k, v) -> copia.put(String.valueOf(k), v));
            Object data = copia.get("data");
            if (data instanceof String texto) {
                Object arbol = leerSeguro(texto);
                if (arbol instanceof Map<?, ?>) {
                    copia.put("data", arbol);
                }
                if (textos != null && copia.get("uid") != null) {
                    textos.put(String.valueOf(copia.get("uid")), texto);
                }
            }
            salida.add(copia);
        }
        return salida;
    }

    /** Vuelve a dejar «data» como texto: el original de un lado si coincide, o uno nuevo. */
    private Object plegar(String uid, Object elemento, Map<String, String> textoLocal, Map<String, String> textoSharePoint) {
        if (!(elemento instanceof Map<?, ?> fila) || !(fila.get("data") instanceof Map<?, ?> data)) {
            return elemento;
        }
        Map<String, Object> salida = new LinkedHashMap<>();
        fila.forEach((k, v) -> salida.put(String.valueOf(k), v));
        String texto = null;
        for (String candidato : new String[]{textoLocal.get(uid), textoSharePoint.get(uid)}) {
            if (candidato != null && igual(leerSeguro(candidato), data)) {
                texto = candidato;
                break;
            }
        }
        salida.put("data", texto != null ? texto : escritorJson.apply(data));
        return salida;
    }

    private Object leerSeguro(String texto) {
        try {
            return lectorJson.apply(texto);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- registro de actividad --------------------------------------------------------

    /** PostgreSQL + las filas de SharePoint que no estén (por contenido y nº de repetición). Nunca quita nada. */
    private Object unirActividad(Object local, Object sharepoint) {
        if (!(local instanceof List<?>) && !(sharepoint instanceof List<?>)) {
            return null;
        }
        List<Object> salida = new ArrayList<>();
        Map<String, Integer> presentes = new HashMap<>();
        if (local instanceof List<?> l) {
            for (Object f : l) {
                salida.add(f);
                presentes.merge(claveActividad(f), 1, Integer::sum);
            }
        }
        int nuevas = 0;
        if (sharepoint instanceof List<?> s) {
            for (Object f : s) {
                String k = claveActividad(f);
                Integer n = presentes.get(k);
                if (n != null && n > 0) {
                    presentes.put(k, n - 1);
                } else {
                    salida.add(f);
                    nuevas++;
                }
            }
        }
        if (nuevas > 0) {
            contar(ACTIVIDAD, "altasDesdeSharePoint", nuevas);
        }
        return salida;
    }

    private static String claveActividad(Object f) {
        if (!(f instanceof Map<?, ?> m)) {
            return String.valueOf(f);
        }
        return String.join("\u001f", textoClave(m.get("when")), textoClave(m.get("email")), textoClave(m.get("name")),
                textoClave(m.get("action")), textoClave(m.get("detail")));
    }

    // ---- claves de las listas ---------------------------------------------------------

    /** Clave de identidad de los elementos de cada lista conocida; null = la lista es un valor único. */
    static Function<Object, String> claveDeLista(String fichero, String ruta) {
        return switch (fichero + "#" + ruta) {
            case "planner.json#" -> campo("uid");
            case "configuracion.json#responsibles", "configuracion.json#projects" -> o -> o instanceof String t && !t.isEmpty() ? t : null;
            case "configuracion.json#benches", "recursos.json#resources" -> campo("name");
            case "usuarios.json#" -> o -> {
                String e = campo("email").apply(o);
                return e == null ? null : e.trim().toLowerCase(Locale.ROOT);
            };
            case "recursos.json#blocks", "aprobaciones.json#" -> campo("id");
            case "kpis.json#" -> o -> o instanceof Map<?, ?> m
                    ? textoClave(m.get("Year")) + "|" + textoClave(m.get("CW")) + "|" + textoClave(m.get("Org")) : null;
            case "tipos-equipo.json#tipos" -> o -> o instanceof Map<?, ?> m && m.get("titulo") != null
                    ? m.get("titulo").toString().trim().toLowerCase(Locale.ROOT) + "|" + textoClave(m.get("anio")) : null;
            default -> null;
        };
    }

    private static String nombreClaveRaiz(String fichero) {
        return switch (fichero) {
            case PLANNER -> "uid";
            case "usuarios.json" -> "email";
            case "kpis.json" -> "Year|CW|Org";
            default -> "id";
        };
    }

    private static Function<Object, String> campo(String nombre) {
        return o -> {
            if (!(o instanceof Map<?, ?> m) || m.get(nombre) == null) {
                return null;
            }
            String t = textoClave(m.get(nombre));
            return t.isEmpty() ? null : t;
        };
    }

    private static String textoClave(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof Number n) {
            return decimal(n).stripTrailingZeros().toPlainString();
        }
        return v.toString();
    }

    /** null si hay claves repetidas o elementos sin clave. */
    private static Map<String, Object> indexar(List<?> lista, Function<Object, String> clave) {
        Map<String, Object> salida = new LinkedHashMap<>();
        for (Object o : lista) {
            String k = clave.apply(o);
            if (k == null || salida.containsKey(k)) {
                return null;
            }
            salida.put(k, o);
        }
        return salida;
    }

    // ---- utilidades -------------------------------------------------------------------

    private static boolean esListaOAusente(Object o) {
        return o == AUSENTE || o instanceof List<?>;
    }

    private static List<?> comoLista(Object o) {
        return o instanceof List<?> l ? l : List.of();
    }

    private static Object valor(Map<?, ?> m, String k) {
        return m.containsKey(k) ? m.get(k) : AUSENTE;
    }

    /** Igualdad de contenido JSON: números por valor (1 = 1.0), objetos sin importar el orden de las claves. */
    static boolean igual(Object a, Object b) {
        if (a == b) {
            return true;
        }
        if (a == AUSENTE || b == AUSENTE || a == null || b == null) {
            return false;
        }
        if (a instanceof Number x && b instanceof Number y) {
            return decimal(x).compareTo(decimal(y)) == 0;
        }
        if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
            if (x.size() != y.size()) {
                return false;
            }
            for (Map.Entry<?, ?> e : x.entrySet()) {
                if (!y.containsKey(e.getKey()) || !igual(e.getValue(), y.get(e.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof List<?> x && b instanceof List<?> y) {
            if (x.size() != y.size()) {
                return false;
            }
            for (int i = 0; i < x.size(); i++) {
                if (!igual(x.get(i), y.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return Objects.equals(a, b);
    }

    private static BigDecimal decimal(Number n) {
        if (n instanceof BigDecimal d) {
            return d;
        }
        try {
            return new BigDecimal(n.toString());
        } catch (NumberFormatException e) {
            return BigDecimal.valueOf(n.doubleValue());
        }
    }

    private void contar(String fichero, String que) {
        contar(fichero, que, 1);
    }

    private void contar(String fichero, String que, int cuantos) {
        recuentos.computeIfAbsent(fichero, f -> new LinkedHashMap<>()).merge(que, cuantos, Integer::sum);
    }

    private Map<String, Map<String, Integer>> copiaRecuentos() {
        Map<String, Map<String, Integer>> copia = new LinkedHashMap<>();
        recuentos.forEach((k, v) -> copia.put(k, Map.copyOf(v)));
        return copia;
    }
}
