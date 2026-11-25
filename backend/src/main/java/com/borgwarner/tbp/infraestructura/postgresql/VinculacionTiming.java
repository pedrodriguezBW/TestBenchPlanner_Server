package com.borgwarner.tbp.infraestructura.postgresql;

import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.operaciones.ReglaNoCumplida;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.ResultSet;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Vinculación de los datos heredados de Timing R&T (Excel) con las personas y
 * los proyectos de TestBenchPlanner.
 *
 * Al importar, cada tarea guarda el nombre original del responsable y del
 * técnico (responsableOrigen / tecnicoOrigen) y el proyecto y número originales
 * (proyectoOrigen / cuentaOrigen). Si en ese momento no había correspondencia,
 * el campo queda vacío. Esta clase vuelve a buscarla cada vez que se guardan
 * usuarios o proyectos, al arrancar el servidor y bajo demanda:
 *
 * - Solo rellena lo que sigue vacío y conserva el nombre original. Un valor
 *   elegido a mano en la aplicación borra el nombre original y ya no se toca.
 * - Personas: correo, nombre o nombre de responsable del usuario, sin
 *   distinguir acentos ni mayúsculas; si coincide con varios usuarios no se vincula.
 * - Proyectos: nombre y número, o un número que solo tenga un proyecto.
 * - Las correspondencias manuales (tabla timing_vinculo) mandan sobre las
 *   automáticas y también las usa la importación del Excel.
 * - La vinculación no cuenta como edición: si la tarea no se había tocado desde
 *   la importación, una nueva importación la sigue actualizando.
 */
public final class VinculacionTiming {

    public static final String PERSONA = "persona";
    public static final String PROYECTO = "proyecto";
    static final String CERROJO = "timing-importacion";
    private static final String SISTEMA = "sistema";
    private static final List<String> PERSONAS = List.of("responsable", "tecnico");

    private VinculacionTiming() {
    }

    /** Clave de comparación: sin acentos, sin mayúsculas y con los espacios normalizados. */
    static String clave(String valor) {
        return Normalizer.normalize(valor == null ? "" : valor.strip(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** Nombre normalizado + número en formato largo (80834 y 80000834 son el mismo proyecto). */
    static String claveProyecto(String nombre, String cuenta) {
        return clave(nombre) + "|" + NombresProyecto.numeroLargo(cuenta);
    }

    record Proyecto(long id, String titulo, String cuenta) {
        /** Número del catálogo; si el catálogo no tiene, el del Excel. */
        String cuentaPara(String original) {
            return cuenta.isBlank() ? (original == null ? "" : original) : cuenta;
        }
    }

    /** Personas, proyectos y correspondencias manuales, leídos una vez por operación. */
    static final class Referencias {
        private final Map<String, Set<String>> usuarios = new HashMap<>();
        private final Map<String, List<Proyecto>> porCuenta = new HashMap<>();
        private final Map<String, List<Proyecto>> porTitulo = new HashMap<>();
        private final Map<String, Proyecto> porTituloExacto = new HashMap<>();
        private final Map<Long, Proyecto> porId = new HashMap<>();
        private final Set<String> correos = new LinkedHashSet<>();
        private final Map<String, String> manualPersonas = new HashMap<>();
        private final Map<String, String> manualProyectos = new HashMap<>();

        Referencias(BaseDatos bd) {
            bd.jdbc().query("SELECT email, titulo, nombre_responsable FROM usuario", (ResultSet rs) -> {
                String email = rs.getString(1);
                correos.add(email);
                for (String alias : new String[]{email, rs.getString(2), rs.getString(3)}) {
                    if (alias != null && !alias.isBlank()) {
                        usuarios.computeIfAbsent(clave(alias), k -> new LinkedHashSet<>()).add(email);
                    }
                }
            });
            bd.jdbc().query("SELECT id, titulo, coalesce(numero_proyecto, '') FROM proyecto", (ResultSet rs) -> {
                Proyecto p = new Proyecto(rs.getLong(1), rs.getString(2), NombresProyecto.numeroLargo(rs.getString(3)));
                porTitulo.computeIfAbsent(clave(p.titulo()), k -> new ArrayList<>()).add(p);
                porTituloExacto.put(p.titulo(), p);
                porId.put(p.id(), p);
                if (!p.cuenta().isBlank()) {
                    porCuenta.computeIfAbsent(p.cuenta(), k -> new ArrayList<>()).add(p);
                }
            });
            bd.jdbc().query("SELECT tipo, origen, destino FROM timing_vinculo", (ResultSet rs) -> {
                (PERSONA.equals(rs.getString(1)) ? manualPersonas : manualProyectos).put(rs.getString(2), rs.getString(3));
            });
        }

        /** Correo del usuario que corresponde a un nombre del Excel, o "" si no hay uno único. */
        String email(String nombre) {
            if (nombre == null || nombre.isBlank()) return "";
            String manual = manualPersonas.get(clave(nombre));
            if (manual != null && correos.contains(manual)) return manual;
            Set<String> coincidencias = usuarios.getOrDefault(clave(nombre), Set.of());
            return coincidencias.size() == 1 ? coincidencias.iterator().next() : "";
        }

        /** Usuarios que coinciden automáticamente con el nombre (más de uno = ambiguo). */
        int coincidencias(String nombre) {
            return usuarios.getOrDefault(clave(nombre), Set.of()).size();
        }

        /** Proyecto del catálogo que corresponde al nombre y número del Excel, o null. */
        Proyecto proyecto(String nombre, String cuenta) {
            String n = nombre == null ? "" : nombre;
            String c = NombresProyecto.numeroLargo(cuenta);
            if (n.isBlank() && c.isEmpty()) return null;
            String manual = manualProyectos.get(claveProyecto(n, c));
            if (manual != null && porTituloExacto.containsKey(manual)) return porTituloExacto.get(manual);
            List<Proyecto> titulos = porTitulo.getOrDefault(clave(n), List.of());
            List<Proyecto> exactos = titulos.stream().filter(p -> p.cuenta().equals(c)).toList();
            if (exactos.size() == 1) return exactos.get(0);
            List<Proyecto> mismaCuenta = c.isEmpty() ? List.of() : porCuenta.getOrDefault(c, List.of());
            return titulos.isEmpty() && mismaCuenta.size() == 1 ? mismaCuenta.get(0) : null;
        }

        boolean existeUsuario(String email) {
            return correos.contains(email);
        }

        Proyecto proyectoPorTitulo(String titulo) {
            return porTituloExacto.get(titulo);
        }

        Proyecto proyectoPorId(long id) {
            return porId.get(id);
        }
    }

    /** Pone los datos del proyecto del catálogo en la tarea (el número original si el catálogo no tiene). */
    static void ponerProyecto(ObjectNode datos, Proyecto proyecto, String cuentaOrigen) {
        datos.put("proyectoId", proyecto.id()).put("proyecto", proyecto.titulo()).put("cuenta", proyecto.cuentaPara(cuentaOrigen));
    }

    /**
     * Un proyecto del catálogo ha cambiado de nombre o número: se actualiza en
     * sus tareas (proyecto y número) y en las correspondencias manuales que lo
     * usan. Como la vinculación, no cuenta como edición para la reimportación.
     * Devuelve cuántas tareas han cambiado.
     */
    static int renombrarProyecto(BaseDatos bd, long id, String anterior, String nombre, String numero) {
        bd.bloquear(CERROJO);
        bd.jdbc().update("UPDATE timing_vinculo SET destino = ? WHERE tipo = 'proyecto' AND destino = ?", nombre, anterior);
        List<Fila> filas = bd.jdbc().query("SELECT id, datos FROM timing_tarea WHERE datos->>'proyectoId' = ? ORDER BY id FOR UPDATE",
                (rs, n) -> new Fila(rs.getLong(1), JsonJs.leerObjeto(rs.getString(2))), String.valueOf(id));
        int total = 0;
        for (Fila fila : filas) {
            ObjectNode datos = fila.datos();
            String cuenta = numero == null || numero.isBlank() ? texto(datos, "cuenta") : numero;
            if (nombre.equals(texto(datos, "proyecto")) && cuenta.equals(texto(datos, "cuenta"))) {
                continue;
            }
            ArrayNode cambios = JsonJs.mapper().createArrayNode();
            cambios.addObject().put("campo", "proyecto")
                    .put("origen", NombresProyecto.etiqueta(texto(datos, "proyecto"), texto(datos, "cuenta")))
                    .put("destino", NombresProyecto.etiqueta(nombre, cuenta));
            datos.put("proyecto", nombre).put("cuenta", cuenta);
            guardar(bd, fila, cambios, "proyecto-renombrado");
            total++;
        }
        return total;
    }

    private record Fila(long id, ObjectNode datos) {
    }

    /** Busca correspondencias para todo lo que sigue sin vincular. Devuelve cuántas tareas se han vinculado. */
    public static int vincular(BaseDatos bd) {
        return bd.enTransaccion(() -> {
            bd.bloquear(CERROJO);
            Referencias ref = new Referencias(bd);
            int total = 0;
            List<Fila> filas = bd.jdbc().query("SELECT id, datos FROM timing_tarea WHERE "
                            + "(coalesce(datos->>'responsable', '') = '' AND coalesce(datos->>'responsableOrigen', '') <> '') OR "
                            + "(coalesce(datos->>'tecnico', '') = '' AND coalesce(datos->>'tecnicoOrigen', '') <> '') OR "
                            + "(coalesce(datos->>'proyectoId', '') IN ('', '0') AND (coalesce(datos->>'proyectoOrigen', '') <> '' "
                            + "OR coalesce(datos->>'cuentaOrigen', '') <> '')) ORDER BY id FOR UPDATE",
                    (rs, n) -> new Fila(rs.getLong(1), JsonJs.leerObjeto(rs.getString(2))));
            for (Fila fila : filas) {
                ArrayNode cambios = JsonJs.mapper().createArrayNode();
                ObjectNode datos = fila.datos();
                for (String campo : PERSONAS) {
                    String origen = texto(datos, campo + "Origen");
                    if (texto(datos, campo).isEmpty() && !origen.isEmpty()) {
                        String email = ref.email(origen);
                        if (!email.isEmpty()) {
                            datos.put(campo, email);
                            cambios.addObject().put("campo", campo).put("origen", origen).put("destino", email);
                        }
                    }
                }
                if (sinProyecto(datos) && (datos.has("proyectoOrigen") || datos.has("cuentaOrigen"))) {
                    Proyecto p = ref.proyecto(texto(datos, "proyectoOrigen"), texto(datos, "cuentaOrigen"));
                    if (p != null) {
                        ponerProyecto(datos, p, texto(datos, "cuentaOrigen"));
                        cambios.addObject().put("campo", "proyecto").put("origen", origenProyecto(datos)).put("destino", p.titulo());
                    }
                }
                if (!cambios.isEmpty()) {
                    guardar(bd, fila, cambios);
                    total++;
                }
            }
            return total;
        });
    }

    private static void guardar(BaseDatos bd, Fila fila, ArrayNode cambios) {
        guardar(bd, fila, cambios, "vinculada");
    }

    private static void guardar(BaseDatos bd, Fila fila, ArrayNode cambios, String accion) {
        // Si nadie la ha editado desde la importación, sigue contando como importada (sin conflicto al reimportar).
        bd.jdbc().update("UPDATE timing_tarea SET datos = ?::jsonb, version = version + 1, "
                + "version_importada = CASE WHEN version_importada = version THEN version_importada + 1 ELSE version_importada END "
                + "WHERE id = ?", fila.datos().toString(), fila.id());
        ObjectNode evento = JsonJs.objetoVacio();
        evento.set("vinculos", cambios);
        bd.jdbc().update("INSERT INTO timing_historial (tarea, autor, accion, datos) VALUES (?, ?, ?, ?::jsonb)",
                fila.id(), autor(bd), accion, evento.toString());
    }

    /** Nombres y proyectos del Excel que siguen sin vincular, con sus recuentos, y las correspondencias manuales. */
    public static ObjectNode sinVincular(BaseDatos bd) {
        Referencias ref = new Referencias(bd);
        Map<String, ObjectNode> personas = new LinkedHashMap<>();
        Map<String, ObjectNode> proyectos = new LinkedHashMap<>();
        bd.jdbc().query("SELECT datos->>'estado', datos->>'responsable', datos->>'responsableOrigen', datos->>'tecnico', "
                + "datos->>'tecnicoOrigen', datos->>'proyectoId', datos->>'proyectoOrigen', datos->>'cuentaOrigen' "
                + "FROM timing_tarea ORDER BY id", (ResultSet rs) -> {
            boolean abierta = List.of("pendiente", "en-proceso").contains(rs.getString(1));
            for (int i : new int[]{2, 4}) {
                String actual = rs.getString(i);
                String origen = rs.getString(i + 1);
                if ((actual == null || actual.isBlank()) && origen != null && !origen.isBlank()) {
                    ObjectNode p = personas.computeIfAbsent(clave(origen), k -> JsonJs.objetoVacio().put("nombre", origen.strip())
                            .put("responsable", 0).put("tecnico", 0).put("abiertas", 0)
                            .put("coincidencias", ref.coincidencias(origen)));
                    String campo = i == 2 ? "responsable" : "tecnico";
                    p.put(campo, p.path(campo).asInt() + 1);
                    if (abierta) p.put("abiertas", p.path("abiertas").asInt() + 1);
                }
            }
            String id = rs.getString(6);
            String nombre = rs.getString(7) == null ? "" : rs.getString(7).strip();
            String cuenta = rs.getString(8) == null ? "" : rs.getString(8).strip();
            if ((id == null || id.isBlank() || "0".equals(id)) && !(nombre.isEmpty() && cuenta.isEmpty())) {
                ObjectNode p = proyectos.computeIfAbsent(claveProyecto(nombre, cuenta), k -> JsonJs.objetoVacio()
                        .put("nombre", nombre).put("cuenta", cuenta).put("tareas", 0).put("abiertas", 0));
                p.put("tareas", p.path("tareas").asInt() + 1);
                if (abierta) p.put("abiertas", p.path("abiertas").asInt() + 1);
            }
        });
        Comparator<ObjectNode> orden = Comparator.<ObjectNode>comparingInt(n -> -n.path("abiertas").asInt())
                .thenComparing(n -> n.path("nombre").asText().toLowerCase(Locale.ROOT));
        ObjectNode salida = JsonJs.objetoVacio();
        ArrayNode listaPersonas = salida.putArray("personas");
        personas.values().stream().sorted(orden).forEach(listaPersonas::add);
        ArrayNode listaProyectos = salida.putArray("proyectos");
        proyectos.values().stream().sorted(orden).forEach(listaProyectos::add);
        ArrayNode manuales = salida.putArray("vinculos");
        bd.jdbc().query("SELECT v.tipo, v.nombre, v.cuenta, v.destino, coalesce(u.titulo, v.destino), coalesce(a.titulo, v.autor), v.fecha "
                + "FROM timing_vinculo v LEFT JOIN usuario u ON v.tipo = 'persona' AND u.email = v.destino "
                + "LEFT JOIN usuario a ON a.email = v.autor ORDER BY v.tipo, lower(v.nombre), v.cuenta", (ResultSet rs) -> {
            boolean persona = PERSONA.equals(rs.getString(1));
            boolean vigente = persona ? ref.existeUsuario(rs.getString(4)) : ref.proyectoPorTitulo(rs.getString(4)) != null;
            manuales.addObject().put("tipo", rs.getString(1)).put("nombre", rs.getString(2)).put("cuenta", rs.getString(3))
                    .put("destino", rs.getString(4)).put("destinoNombre", rs.getString(5)).put("vigente", vigente)
                    .put("autor", rs.getString(6)).put("fecha", rs.getTimestamp(7).toInstant().toString());
        });
        return salida;
    }

    /**
     * Guarda una correspondencia manual y la aplica a las tareas de ese nombre o
     * proyecto que siguen sin vincular o que estaban vinculadas por la
     * correspondencia manual anterior del mismo origen (así se puede corregir).
     * Lo elegido a mano en una tarea no se toca.
     */
    public static ObjectNode guardarVinculo(BaseDatos bd, JsonNode cuerpo) {
        String tipo = cuerpo.path("tipo").asText("");
        String nombre = cuerpo.path("nombre").asText("").strip();
        String cuenta = PROYECTO.equals(tipo) ? NombresProyecto.numeroLargo(cuerpo.path("cuenta").asText("")) : "";
        String destino = cuerpo.path("destino").asText("").strip();
        regla(List.of(PERSONA, PROYECTO).contains(tipo), "Tipo de vínculo desconocido.");
        regla(!(nombre.isEmpty() && cuenta.isEmpty()) && nombre.length() <= 400 && cuenta.length() <= 100,
                "Indica el nombre del Excel que quieres vincular.");
        return bd.enTransaccion(() -> {
            bd.bloquear(CERROJO);
            Referencias ref = new Referencias(bd);
            if (PERSONA.equals(tipo)) {
                regla(ref.existeUsuario(destino), "Selecciona un usuario de TestBenchPlanner.");
            } else {
                regla(ref.proyectoPorTitulo(destino) != null, "Selecciona un proyecto del catálogo.");
            }
            String origen = PERSONA.equals(tipo) ? clave(nombre) : claveProyecto(nombre, cuenta);
            List<String> anteriores = bd.jdbc().queryForList("SELECT destino FROM timing_vinculo WHERE tipo = ? AND origen = ?",
                    String.class, tipo, origen);
            String anterior = anteriores.isEmpty() ? null : anteriores.get(0);
            bd.jdbc().update("INSERT INTO timing_vinculo (tipo, origen, nombre, cuenta, destino, autor) VALUES (?, ?, ?, ?, ?, ?) "
                    + "ON CONFLICT (tipo, origen) DO UPDATE SET nombre = EXCLUDED.nombre, cuenta = EXCLUDED.cuenta, "
                    + "destino = EXCLUDED.destino, autor = EXCLUDED.autor, fecha = now()",
                    tipo, origen, nombre, cuenta, destino, autor(bd));
            Auditoria.anotar(bd, bd.actor(), "timing_vinculo", descripcion(tipo, nombre, cuenta),
                    anterior == null ? Auditoria.ALTA : Auditoria.MODIFICACION, anterior, destino);
            int tareas = aplicar(bd, new Referencias(bd), tipo, origen, anterior, destino);
            return JsonJs.objetoVacio().put("vinculadas", tareas + vincular(bd));
        });
    }

    /** Quita una correspondencia manual y deshace lo que se vinculó con ella. */
    public static ObjectNode borrarVinculo(BaseDatos bd, JsonNode cuerpo) {
        String tipo = cuerpo.path("tipo").asText("");
        String nombre = cuerpo.path("nombre").asText("").strip();
        String cuenta = PROYECTO.equals(tipo) ? NombresProyecto.numeroLargo(cuerpo.path("cuenta").asText("")) : "";
        regla(List.of(PERSONA, PROYECTO).contains(tipo), "Tipo de vínculo desconocido.");
        String origen = PERSONA.equals(tipo) ? clave(nombre) : claveProyecto(nombre, cuenta);
        return bd.enTransaccion(() -> {
            bd.bloquear(CERROJO);
            List<String> anteriores = bd.jdbc().queryForList(
                    "DELETE FROM timing_vinculo WHERE tipo = ? AND origen = ? RETURNING destino", String.class, tipo, origen);
            regla(!anteriores.isEmpty(), "La correspondencia ya no existe. Recarga la página.");
            Auditoria.anotar(bd, bd.actor(), "timing_vinculo", descripcion(tipo, nombre, cuenta), Auditoria.BAJA,
                    anteriores.get(0), null);
            // Las tareas que se vincularon con ella vuelven a la correspondencia automática (o quedan sin vincular),
            // igual que las dejaría una nueva importación del Excel.
            int tareas = aplicar(bd, new Referencias(bd), tipo, origen, anteriores.get(0), null);
            return JsonJs.objetoVacio().put("ok", true).put("vinculadas", tareas);
        });
    }

    /**
     * Aplica a las tareas de un origen el destino de su correspondencia manual
     * (destino null = se ha quitado: vuelven a la correspondencia automática o
     * quedan sin vincular). Solo toca las que siguen sin vincular o las que
     * apuntaban al destino anterior; lo elegido a mano en la tarea no se toca.
     */
    private static int aplicar(BaseDatos bd, Referencias ref, String tipo, String origen, String anterior, String destino) {
        String filtro = PERSONA.equals(tipo)
                ? "coalesce(datos->>'responsableOrigen', '') <> '' OR coalesce(datos->>'tecnicoOrigen', '') <> ''"
                : "datos ? 'proyectoOrigen' OR datos ? 'cuentaOrigen'";
        List<Fila> filas = bd.jdbc().query("SELECT id, datos FROM timing_tarea WHERE " + filtro + " ORDER BY id FOR UPDATE",
                (rs, n) -> new Fila(rs.getLong(1), JsonJs.leerObjeto(rs.getString(2))));
        Proyecto previo = PROYECTO.equals(tipo) && anterior != null ? ref.proyectoPorTitulo(anterior) : null;
        boolean manual = destino != null;
        int total = 0;
        for (Fila fila : filas) {
            ObjectNode datos = fila.datos();
            ArrayNode cambios = JsonJs.mapper().createArrayNode();
            if (PERSONA.equals(tipo)) {
                for (String campo : PERSONAS) {
                    String nombreOrigen = texto(datos, campo + "Origen");
                    String actual = texto(datos, campo);
                    if (nombreOrigen.isEmpty() || !clave(nombreOrigen).equals(origen)
                            || !(actual.isEmpty() || actual.equals(anterior))) {
                        continue;
                    }
                    String nuevo = manual ? destino : ref.email(nombreOrigen);
                    if (!nuevo.equals(actual)) {
                        datos.put(campo, nuevo);
                        cambios.addObject().put("campo", campo).put("origen", nombreOrigen).put("destino", nuevo)
                                .put("manual", manual);
                    }
                }
            } else {
                String nombreOrigen = texto(datos, "proyectoOrigen");
                String cuentaOrigen = texto(datos, "cuentaOrigen");
                long actual = datos.path("proyectoId").asLong();
                boolean libre = actual <= 0 || (previo != null && previo.id() == actual);
                if (!claveProyecto(nombreOrigen, cuentaOrigen).equals(origen) || !libre) {
                    continue;
                }
                Proyecto nuevo = manual ? ref.proyectoPorTitulo(destino) : ref.proyecto(nombreOrigen, cuentaOrigen);
                if (nuevo != null && nuevo.id() != actual) {
                    ponerProyecto(datos, nuevo, cuentaOrigen);
                    cambios.addObject().put("campo", "proyecto").put("origen", origenProyecto(datos)).put("destino", nuevo.titulo())
                            .put("manual", manual);
                } else if (nuevo == null && actual > 0) {
                    // Sin correspondencia: como la deja la importación (proyecto y número del Excel, sin enlace).
                    datos.put("proyectoId", (Long) null).put("proyecto", nombreOrigen).put("cuenta", cuentaOrigen);
                    cambios.addObject().put("campo", "proyecto").put("origen", origenProyecto(datos)).put("destino", "")
                            .put("manual", false);
                }
            }
            if (!cambios.isEmpty()) {
                guardar(bd, fila, cambios);
                total++;
            }
        }
        return total;
    }

    private static boolean sinProyecto(JsonNode datos) {
        return datos.path("proyectoId").asLong() <= 0;
    }

    private static String origenProyecto(JsonNode datos) {
        String nombre = texto(datos, "proyectoOrigen");
        String cuenta = texto(datos, "cuentaOrigen");
        return cuenta.isEmpty() ? nombre : nombre.isEmpty() ? cuenta : nombre + " · " + cuenta;
    }

    private static String descripcion(String tipo, String nombre, String cuenta) {
        return tipo + "/" + nombre + (cuenta.isEmpty() ? "" : " · " + cuenta);
    }

    private static String autor(BaseDatos bd) {
        String email = bd.actor().email();
        return email == null || email.isBlank() ? SISTEMA : email;
    }

    private static String texto(JsonNode datos, String campo) {
        JsonNode valor = datos.path(campo);
        return valor.isNull() || valor.isMissingNode() ? "" : valor.asText("").strip();
    }

    private static void regla(boolean valida, String mensaje) {
        if (!valida) throw new ReglaNoCumplida(mensaje);
    }
}
