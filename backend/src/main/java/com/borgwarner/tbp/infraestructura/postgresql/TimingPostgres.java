
package com.borgwarner.tbp.infraestructura.postgresql;

import com.borgwarner.tbp.aplicacion.SinPermiso;
import com.borgwarner.tbp.aplicacion.puertos.Reloj;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.operaciones.ReglaNoCumplida;
import com.borgwarner.tbp.dominio.usuarios.Usuario;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;

@Service
@ConditionalOnProperty(name = "planner.servidor.activo", havingValue = "true")
public class TimingPostgres {

    public static final String CAMARA_CLIMATICA = "Cámara Climática";
    public static final List<String> TIPOS = List.of("EGM", "HEAT", "TEST", "CAL", "e-Cooler", "LAB", "IIGG");
    private static final List<String> CAMPOS_TEXTO = List.of("descripcion", "piezasCodigo", "ruta", "otrosEquipos");
    private static final String ABIERTAS = "datos->>'estado' IN ('pendiente', 'en-proceso')";
    private static final String COLUMNAS = "id, origen_id, codigo, version, datos";
    private static final String NOMBRES = " || jsonb_build_object("
            + "'responsableNombre', (SELECT titulo FROM usuario WHERE email = datos->>'responsable'), "
            + "'tecnicoNombre', (SELECT titulo FROM usuario WHERE email = datos->>'tecnico'))";

    public static final class Conflicto extends RuntimeException {
        public Conflicto() {
            super("Otra persona ha cambiado estos datos. Recarga antes de guardar.");
        }
    }

    public record Acceso(Usuario usuario, List<String> perfiles) {
        String email() { return usuario.email(); }
        boolean solicita() { return perfiles.contains("solicitante"); }
        boolean tecnico() { return perfiles.contains("tecnico"); }
        boolean coordina() { return perfiles.contains("coordinador"); }
        boolean administra() { return usuario.esAdmin() && !perfiles.isEmpty(); }
        boolean gestiona() { return coordina() || administra(); }
    }

    record Tarea(long id, Long origen, String codigo, int version, ObjectNode datos) {
        ObjectNode json() {
            ObjectNode objeto = datos.deepCopy();
            return objeto.put("id", id).put("codigo", codigo).put("version", version).put("origenId", origen);
        }
    }

    private final BaseDatos bd;
    private final Reloj reloj;

    public TimingPostgres(BaseDatos bd, Reloj reloj) {
        this.bd = bd;
        this.reloj = reloj;
    }

    public List<String> perfiles(String email) {
        return bd.jdbc().queryForList("SELECT perfil FROM timing_perfil WHERE email = ? ORDER BY perfil", String.class, email);
    }

    public Acceso acceso(Usuario usuario) {
        List<String> perfiles = perfiles(usuario.email());
        permiso(!perfiles.isEmpty());
        return new Acceso(usuario, perfiles);
    }

    public ObjectNode opciones(Acceso acceso) {
        ObjectNode opciones = JsonJs.objetoVacio().put("hoy", reloj.hoy().toString());
        TIPOS.forEach(opciones.putArray("tipos")::add);
        ArrayNode proyectos = opciones.putArray("proyectos");
        bd.jdbc().query("SELECT id, titulo, numero_proyecto FROM proyecto ORDER BY titulo", (ResultSet rs) -> {
            proyectos.addObject().put("id", rs.getLong(1)).put("nombre", rs.getString(2)).put("cuenta", rs.getString(3));
        });
        ArrayNode personas = opciones.putArray("personas");
        bd.jdbc().query("SELECT u.email, u.titulo, bool_or(p.perfil = 'tecnico') AS tecnico, "
                + "bool_or(p.perfil = 'solicitante') AS responsable FROM usuario u "
                + "JOIN timing_perfil p ON u.email = p.email GROUP BY u.email, u.titulo ORDER BY u.titulo",
                (ResultSet rs) -> {
                    if (acceso.gestiona() || acceso.email().equals(rs.getString(1))) {
                        personas.addObject().put("email", rs.getString(1)).put("nombre", rs.getString(2))
                                .put("tecnico", rs.getBoolean(3)).put("responsable", rs.getBoolean(4));
                    }
                });
        ArrayNode equipos = opciones.putArray("equipos");
        String filtro = acceso.gestiona() ? "" : " WHERE activo";
        bd.jdbc().query("SELECT clase, nombre, activo, version FROM timing_equipo" + filtro + " ORDER BY clase, nombre",
                (ResultSet rs) -> {
                    equipos.addObject().put("clase", rs.getString(1)).put("nombre", rs.getString(2))
                            .put("activo", rs.getBoolean(3)).put("version", rs.getInt(4));
                });
        return opciones;
    }

    public ArrayNode lista(Acceso acceso, String vista) {
        List<Object> parametros = new ArrayList<>();
        String base = switch (vista) {
            case "solicitudes" -> propio(acceso.solicita(), "responsable", acceso, parametros);
            case "cola" -> propio(acceso.tecnico(), "tecnico", acceso, parametros);
            case "planificacion" -> permitido(acceso.gestiona());
            case "historico" -> filtroLectura(acceso, parametros);
            default -> throw new IllegalArgumentException("Vista de Timing R&T desconocida.");
        };
        String estado = "historico".equals(vista) ? "NOT (" + ABIERTAS + ")" : ABIERTAS;
        var salida = JsonJs.mapper().createArrayNode();
        bd.jdbc().query("SELECT id, origen_id, codigo, version, "
                        + "datos - ARRAY['ruta', 'comentarioCierre', 'motivoAjuste', 'otrosEquipos', 'legado'] "
                        + "|| jsonb_build_object('descripcion', left(datos->>'descripcion', 140))" + NOMBRES + " AS datos "
                        + "FROM timing_tarea WHERE (" + base + ") AND " + estado
                        + " ORDER BY (datos->>'prioridad')::numeric NULLS LAST, id",
                (ResultSet rs) -> { salida.add(leer(rs).json()); }, parametros.toArray());
        return salida;
    }

    private static String permitido(boolean condicion) {
        permiso(condicion);
        return "true";
    }

    private static String propio(boolean autorizado, String campo, Acceso acceso, List<Object> parametros) {
        permiso(autorizado);
        parametros.add(acceso.email());
        return "datos->>'" + campo + "' = ?";
    }

    private static String filtroLectura(Acceso acceso, List<Object> parametros) {
        if (acceso.gestiona()) {
            return "true";
        }
        List<String> partes = new ArrayList<>();
        if (acceso.solicita()) {
            partes.add(propio(true, "responsable", acceso, parametros));
        }
        if (acceso.tecnico()) {
            partes.add(propio(true, "tecnico", acceso, parametros));
        }
        return String.join(" OR ", partes);
    }

    public ObjectNode detalle(Acceso acceso, long id) {
        return bd.enTransaccion(() -> leerDetalle(acceso, id));
    }

    private ObjectNode leerDetalle(Acceso acceso, long id) {
        List<Object> parametros = new ArrayList<>();
        parametros.add(id);
        String filtro = filtroLectura(acceso, parametros);
        List<Tarea> filas = bd.jdbc().query("SELECT id, origen_id, codigo, version, datos" + NOMBRES
                        + " AS datos FROM timing_tarea WHERE id = ? AND (" + filtro + ") FOR SHARE",
                (rs, n) -> leer(rs), parametros.toArray());
        if (filas.isEmpty()) {
            throw new NoSuchElementException("La tarea no existe o no tienes acceso.");
        }
        ObjectNode salida = filas.get(0).json();
        ArrayNode avances = salida.putArray("avances");
        bd.jdbc().query("SELECT a.*, coalesce(u.titulo, a.autor) AS nombre FROM timing_avance a "
                        + "LEFT JOIN usuario u ON u.email = a.autor WHERE a.tarea = ? ORDER BY a.fecha, a.id",
                (ResultSet rs) -> {
                    avances.addObject().put("autor", rs.getString("nombre"))
                            .put("fecha", rs.getString("fecha")).put("horas", rs.getBigDecimal("horas"))
                            .put("pendientes", rs.getBigDecimal("pendientes")).put("comentario", rs.getString("comentario"));
                }, id);
        ArrayNode historial = salida.putArray("historial");
        bd.jdbc().query("SELECT h.*, coalesce(u.titulo, h.autor) AS nombre FROM timing_historial h "
                        + "LEFT JOIN usuario u ON u.email = h.autor WHERE h.tarea = ? ORDER BY h.id DESC",
                (ResultSet rs) -> {
                    ObjectNode evento = historial.addObject().put("autor", rs.getString("nombre"))
                            .put("fecha", rs.getTimestamp("fecha").toInstant().toString()).put("accion", rs.getString("accion"));
                    evento.set("datos", JsonJs.leer(rs.getString("datos")));
                }, id);
        salida.put("horasRegistradas", horas(id));
        return salida;
    }

    public ObjectNode crear(Acceso acceso, JsonNode cuerpo) {
        permiso(acceso.solicita() || acceso.coordina());
        return bd.enTransaccion(() -> {
            ObjectNode datos = inicial(acceso.email());
            editarDatos(acceso, datos, cuerpo);
            datos.put("disponible", false);
            return insertar(acceso, datos, "creada");
        });
    }

    public ObjectNode operar(Acceso acceso, long id, String accion, JsonNode cuerpo) {
        return bd.enTransaccion(() -> {
            Tarea tarea = bloquear(acceso, id);
            if (cuerpo.path("version").asInt(-1) != tarea.version()) {
                throw new Conflicto();
            }
            ObjectNode datos = tarea.datos();
            boolean propietario = propietario(acceso, datos);
            boolean asignado = asignado(acceso, datos);
            String estado = texto(datos, "estado");
            boolean abierta = "pendiente".equals(estado) || "en-proceso".equals(estado);
            ObjectNode evento = JsonJs.objetoVacio();
            switch (accion) {
                case "editar" -> {
                    permiso(acceso.gestiona() || propietario);
                    regla(abierta, "La tarea está cerrada.");
                    editarDatos(acceso, datos, cuerpo);
                    if (datos.path("disponible").asBoolean()) {
                        exigirCompleta(datos);
                    }
                }
                case "disponibilidad" -> {
                    permiso(acceso.gestiona() || propietario);
                    regla("pendiente".equals(estado), "La disponibilidad se cambia antes de iniciar la tarea.");
                    regla(cuerpo.path("disponible").isBoolean(), "Indica la disponibilidad.");
                    boolean disponible = cuerpo.path("disponible").asBoolean();
                    if (disponible) {
                        validarProyectoDisponible(datos);
                        exigirCompleta(datos);
                        regla(perfiles(texto(datos, "responsable")).contains("solicitante"),
                                "Selecciona un responsable habilitado antes de marcar como disponible.");
                    }
                    datos.put("disponible", disponible);
                    evento.put("disponible", disponible);
                }
                case "planificar" -> {
                    permiso(acceso.coordina());
                    regla(abierta, "La tarea está cerrada.");
                    planificar(datos, cuerpo, evento);
                }
                case "iniciar" -> {
                    permiso(asignado);
                    regla("pendiente".equals(estado) && datos.path("disponible").asBoolean(), "La tarea debe estar pendiente y disponible.");
                    datos.put("estado", "en-proceso");
                }
                case "avance" -> {
                    permiso(asignado);
                    exigirEnCurso(datos);
                    registrarAvance(acceso, id, datos, cuerpo, evento);
                }
                case "cerrar" -> {
                    permiso(asignado);
                    exigirEnCurso(datos);
                    cerrar(id, datos, cuerpo, evento);
                }
                case "reabrir" -> {
                    permiso(acceso.gestiona());
                    regla("cerrada".equals(estado), "Solo se puede reabrir una tarea cerrada.");
                    evento.put("motivo", obligatorio(cuerpo, "motivo", "Indica el motivo de reapertura."));
                    datos.remove(List.of("fechaCierre", "horasReales", "horasPendientes", "comentarioCierre", "motivoAjuste"));
                    datos.put("estado", "pendiente").put("disponible", false);
                }
                case "cancelar" -> {
                    permiso(acceso.coordina() || (propietario && "pendiente".equals(estado) && !datos.path("disponible").asBoolean()));
                    regla(abierta, "La tarea está cerrada.");
                    datos.put("estado", "cancelada").put("disponible", false);
                }
                case "duplicar" -> {
                    permiso(propietario);
                    ObjectNode copia = inicial(acceso.email());
                    for (String campo : List.of("tipo", "proyectoId", "proyecto", "cuenta", "descripcion", "banco",
                            "camara", "piezasCodigo", "piezasCantidad", "ruta", "otrosEquipos", "horasEstimadas", "fechaEnsayo")) {
                        if (datos.has(campo)) {
                            copia.set(campo, datos.get(campo));
                        }
                    }
                    return insertar(acceso, copia, "duplicada");
                }
                default -> throw new IllegalArgumentException("Acción de Timing R&T desconocida.");
            }
            if ("editar".equals(accion)) {
                evento.set("cambios", datos.deepCopy().without("legado"));
            }
            bd.jdbc().update("UPDATE timing_tarea SET datos = ?::jsonb, version = version + 1 WHERE id = ?", datos.toString(), id);
            anotar(acceso, id, accion, evento);
            return new Tarea(id, tarea.origen(), tarea.codigo(), tarea.version() + 1, datos).json();
        });
    }

    private void editarDatos(Acceso acceso, ObjectNode datos, JsonNode cuerpo) {
        CAMPOS_TEXTO.forEach(campo -> copiarTexto(datos, cuerpo, campo));
        if (cuerpo.has("responsable") && acceso.gestiona()) {
            String responsable = texto(cuerpo, "responsable");
            if (!responsable.equals(texto(datos, "responsable")) && !responsable.isEmpty()) {
                regla(perfiles(responsable).contains("solicitante"), "Selecciona un solicitante habilitado.");
                datos.remove("responsableOrigen");
            }
            datos.put("responsable", responsable);
        }
        if (cuerpo.has("tipo")) {
            String tipo = texto(cuerpo, "tipo");
            regla(tipo.isEmpty() || TIPOS.contains(tipo) || tipo.equals(texto(datos, "tipo")), "Selecciona un tipo de tarea válido.");
            datos.put("tipo", tipo);
        }
        if (cuerpo.has("proyectoId")) {
            long seleccionado = identificadorOpcional(cuerpo.get("proyectoId"));
            if (seleccionado != datos.path("proyectoId").asLong() || (seleccionado == 0 && cuerpo.path("limpiarProyecto").asBoolean())) {
                if (seleccionado == 0) {
                    datos.remove(List.of("proyectoId", "proyecto", "cuenta"));
                } else {
                    elegirProyecto(datos, seleccionado);
                }
            }
        }
        if (cuerpo.has("banco")) {
            String banco = texto(cuerpo, "banco");
            if ("NA".equalsIgnoreCase(banco)) {
                banco = "";
            }
            validarEquipo(datos, "banco", banco);
            if (!CAMARA_CLIMATICA.equals(banco) || !banco.equals(texto(datos, "banco"))) {
                datos.remove("camara");
            }
            datos.put("banco", banco);
        }
        if (cuerpo.has("camara") && CAMARA_CLIMATICA.equals(texto(datos, "banco"))) {
            String camara = texto(cuerpo, "camara");
            validarEquipo(datos, "camara", camara);
            datos.put("camara", camara);
        }
        copiarNumero(datos, cuerpo, "piezasCantidad", false);
        copiarNumero(datos, cuerpo, "horasEstimadas", false);
        if (cuerpo.has("fechaEnsayo")) {
            datos.put("fechaEnsayo", fechaOpcional(cuerpo, "fechaEnsayo"));
        }
    }

    private void elegirProyecto(ObjectNode datos, long id) {
        List<ObjectNode> proyectos = bd.jdbc().query("SELECT titulo, numero_proyecto FROM proyecto WHERE id = ?",
                (rs, n) -> JsonJs.objetoVacio().put("proyecto", rs.getString(1)).put("cuenta", rs.getString(2)), id);
        regla(!proyectos.isEmpty(), "Selecciona un proyecto del catálogo compartido.");
        datos.setAll(proyectos.get(0));
        datos.put("proyectoId", id);
    }

    private void validarProyectoDisponible(ObjectNode datos) {
        long id = datos.path("proyectoId").asLong();
        if (id > 0) {
            if (texto(datos, "cuenta").isEmpty()) {
                elegirProyecto(datos, id);
            } else {
                regla(Boolean.TRUE.equals(bd.jdbc().queryForObject("SELECT EXISTS (SELECT 1 FROM proyecto WHERE id = ?)",
                        Boolean.class, id)), "Selecciona un proyecto vigente antes de marcar como disponible.");
            }
        }
    }

    static List<String> faltantes(JsonNode datos) {
        List<String> faltan = new ArrayList<>();
        if (texto(datos, "responsable").isEmpty()) faltan.add("responsable");
        if (texto(datos, "tipo").isEmpty()) faltan.add("tipo");
        if (datos.path("proyectoId").asLong() <= 0 || texto(datos, "cuenta").isEmpty()) faltan.add("proyecto con cuenta");
        if (texto(datos, "descripcion").isEmpty()) faltan.add("descripción");
        if (!datos.path("horasEstimadas").isNumber()) faltan.add("horas estimadas");
        if (!texto(datos, "banco").isEmpty() && texto(datos, "fechaEnsayo").isEmpty()) faltan.add("fecha programada del ensayo");
        if (CAMARA_CLIMATICA.equals(texto(datos, "banco")) && texto(datos, "camara").isEmpty()) faltan.add("cámara climática");
        return faltan;
    }

    private static void exigirCompleta(JsonNode datos) {
        List<String> faltan = faltantes(datos);
        regla(faltan.isEmpty(), "Completa estos campos antes de marcar como disponible: " + String.join(", ", faltan) + ".");
    }

    private void planificar(ObjectNode datos, JsonNode cuerpo, ObjectNode evento) {
        copiarNumero(datos, cuerpo, "prioridad", true);
        String antes = texto(datos, "tecnico");
        if (cuerpo.has("tecnico")) {
            String tecnico = texto(cuerpo, "tecnico");
            if (!tecnico.equals(antes)) {
                regla(tecnico.isEmpty() || perfiles(tecnico).contains("tecnico"), "Selecciona un técnico habilitado.");
                datos.put("tecnico", tecnico);
                datos.remove("tecnicoOrigen");
                evento.put("tecnicoAnterior", antes);
            }
        }
        if (cuerpo.has("diaTrabajo")) {
            datos.put("diaTrabajo", fechaOpcional(cuerpo, "diaTrabajo"));
        }
        copiarNumero(datos, cuerpo, "ordenDiario", true);
        if (cuerpo.has("tecnico") && texto(datos, "tecnico").isEmpty()) {
            datos.remove(List.of("ordenDiario", "diaTrabajo"));
        }
        if (datos.path("ordenDiario").isNumber()) {
            regla(!texto(datos, "tecnico").isEmpty() && !texto(datos, "diaTrabajo").isEmpty(),
                    "Indica el técnico y el día antes de fijar el orden diario.");
        }
        for (String campo : List.of("tecnico", "diaTrabajo", "ordenDiario", "prioridad")) {
            evento.set(campo, datos.get(campo));
        }
    }

    private void registrarAvance(Acceso acceso, long id, ObjectNode datos, JsonNode cuerpo, ObjectNode evento) {
        LocalDate fecha = fecha(cuerpo, "fecha", true);
        regla(!fecha.isAfter(reloj.hoy()), "La fecha del avance no puede estar en el futuro.");
        BigDecimal horas = numero(cuerpo, "horas", false);
        regla(horas != null && horas.signum() > 0, "Indica las horas trabajadas.");
        BigDecimal pendientes = numero(cuerpo, "pendientes", false);
        String comentario = texto(cuerpo, "comentario");
        bd.jdbc().update("INSERT INTO timing_avance (tarea, autor, fecha, horas, pendientes, comentario) VALUES (?, ?, ?, ?, ?, ?)",
                id, acceso.email(), fecha, horas, pendientes, comentario);
        datos.put("horasPendientes", pendientes);
        evento.put("fecha", fecha.toString()).put("horas", horas).put("pendientes", pendientes);
    }

    private void cerrar(long id, ObjectNode datos, JsonNode cuerpo, ObjectNode evento) {
        BigDecimal suma = horas(id);
        BigDecimal informadas = numero(cuerpo, "horasReales", false);
        BigDecimal reales = informadas == null ? suma : informadas;
        String motivo = suma.compareTo(reales) == 0 ? "" : obligatorio(cuerpo, "motivo", "Indica por qué cambias el total de horas.");
        datos.put("estado", "cerrada").put("fechaCierre", reloj.hoy().toString()).put("horasReales", reales)
                .put("horasPendientes", BigDecimal.ZERO).put("comentarioCierre", texto(cuerpo, "comentario")).put("motivoAjuste", motivo);
        evento.put("fecha", reloj.hoy().toString()).put("horasRegistradas", suma).put("horasReales", reales)
                .put("motivo", motivo).put("comentario", texto(datos, "comentarioCierre"));
    }

    private BigDecimal horas(long id) {
        return bd.jdbc().queryForObject("SELECT coalesce(sum(horas), 0) FROM timing_avance WHERE tarea = ?", BigDecimal.class, id);
    }

    public ObjectNode guardarEquipo(Acceso acceso, JsonNode cuerpo) {
        permiso(acceso.gestiona());
        String clase = texto(cuerpo, "clase");
        String nombre = obligatorio(cuerpo, "nombre", "Indica el nombre del equipo.");
        regla(List.of("banco", "camara").contains(clase) && nombre.length() <= 140
                && !"NA".equalsIgnoreCase(nombre), "El nombre o el tipo del equipo no es válido.");
        int version = cuerpo.path("version").asInt();
        boolean nuevo = version == 0;
        boolean activo = nuevo || cuerpo.path("activo").asBoolean();
        regla(activo || !("banco".equals(clase) && CAMARA_CLIMATICA.equals(nombre)),
                "La categoría Cámara Climática está disponible para todas las cámaras.");
        return bd.enTransaccion(() -> {
            int cambios = nuevo
                    ? bd.jdbc().update("INSERT INTO timing_equipo (clase, nombre) VALUES (?, ?) ON CONFLICT DO NOTHING", clase, nombre)
                    : bd.jdbc().update("UPDATE timing_equipo SET activo = ?, version = version + 1 "
                            + "WHERE clase = ? AND nombre = ? AND version = ?", activo, clase, nombre, version);
            if (cambios == 0) {
                throw new Conflicto();
            }
            Auditoria.anotar(bd, bd.actor(), "timing_equipo", clase + "/" + nombre,
                    nuevo ? Auditoria.ALTA : Auditoria.MODIFICACION, null, activo ? "activo" : "inactivo");
            return JsonJs.objetoVacio().put("clase", clase).put("nombre", nombre).put("activo", activo).put("version", version + 1);
        });
    }

    private void validarEquipo(JsonNode datos, String clase, String valor) {
        if (!valor.isEmpty() && !valor.equals(texto(datos, clase))) {
            regla(Boolean.TRUE.equals(bd.jdbc().queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM timing_equipo WHERE clase = ? AND nombre = ? AND activo)",
                    Boolean.class, clase, valor)), "Selecciona un equipo activo del catálogo de Timing R&T.");
        }
    }

    private ObjectNode insertar(Acceso acceso, ObjectNode datos, String accion) {
        Tarea tarea = bd.jdbc().queryForObject("WITH nuevo AS (SELECT nextval(pg_get_serial_sequence('timing_tarea', 'id')) AS id) "
                + "INSERT INTO timing_tarea (id, codigo, datos) SELECT id, 'RT-' || id, ?::jsonb FROM nuevo RETURNING " + COLUMNAS,
                (rs, n) -> leer(rs), datos.toString());
        anotar(acceso, tarea.id(), accion, JsonJs.objetoVacio());
        return tarea.json();
    }

    private Tarea bloquear(Acceso acceso, long id) {
        List<Object> parametros = new ArrayList<>();
        parametros.add(id);
        String filtro = filtroLectura(acceso, parametros);
        List<Tarea> filas = bd.jdbc().query("SELECT " + COLUMNAS + " FROM timing_tarea WHERE id = ? AND ("
                + filtro + ") FOR UPDATE", (rs, n) -> leer(rs), parametros.toArray());
        if (filas.isEmpty()) {
            throw new NoSuchElementException("La tarea no existe o no tienes acceso.");
        }
        return filas.get(0);
    }

    static Tarea leer(ResultSet rs) throws SQLException {
        return new Tarea(rs.getLong("id"), rs.getObject("origen_id", Long.class), rs.getString("codigo"), rs.getInt("version"),
                JsonJs.leerObjeto(rs.getString("datos")));
    }

    private void anotar(Acceso acceso, long id, String accion, ObjectNode datos) {
        bd.jdbc().update("INSERT INTO timing_historial (tarea, autor, accion, datos) VALUES (?, ?, ?, ?::jsonb)",
                id, acceso.email(), accion, datos.toString());
    }

    static ObjectNode inicial(String responsable) {
        return JsonJs.objetoVacio().put("responsable", responsable).put("estado", "pendiente").put("disponible", false);
    }

    private static boolean propietario(Acceso acceso, JsonNode datos) {
        return acceso.solicita() && acceso.email().equals(texto(datos, "responsable"));
    }

    private static boolean asignado(Acceso acceso, JsonNode datos) {
        return acceso.tecnico() && acceso.email().equals(texto(datos, "tecnico"));
    }

    private static void exigirEnCurso(JsonNode datos) {
        regla("en-proceso".equals(texto(datos, "estado")) && datos.path("disponible").asBoolean(),
                "Inicia la tarea disponible antes de registrar el trabajo.");
    }

    private static void copiarTexto(ObjectNode datos, JsonNode cuerpo, String campo) {
        if (cuerpo.has(campo)) {
            datos.put(campo, texto(cuerpo, campo));
        }
    }

    private static void copiarNumero(ObjectNode datos, JsonNode cuerpo, String campo, boolean conSigno) {
        if (cuerpo.has(campo)) {
            datos.put(campo, numero(cuerpo, campo, conSigno));
        }
    }

    static String texto(JsonNode cuerpo, String campo) {
        String valor = cuerpo.path(campo).asText("").trim();
        regla(valor.length() <= 16000 && valor.indexOf(0) < 0, "El campo " + campo + " contiene texto no válido o demasiado largo.");
        return valor;
    }

    private static String obligatorio(JsonNode cuerpo, String campo, String mensaje) {
        String valor = texto(cuerpo, campo);
        regla(!valor.isEmpty(), mensaje);
        return valor;
    }

    private static long identificadorOpcional(JsonNode valor) {
        if (valor == null || valor.isNull()) return 0;
        regla(valor.isIntegralNumber() && valor.canConvertToLong() && valor.asLong() > 0, "El identificador del proyecto no es válido.");
        return valor.asLong();
    }

    private static BigDecimal numero(JsonNode cuerpo, String campo, boolean conSigno) {
        JsonNode valor = cuerpo.path(campo);
        if (valor.isMissingNode() || valor.isNull()) return null;
        regla(valor.isNumber(), "Introduce un número en " + campo + ".");
        BigDecimal numero = valor.decimalValue();
        regla(numero.abs().compareTo(new BigDecimal("100000000")) <= 0 && numero.stripTrailingZeros().scale() <= 6
                && (conSigno || numero.signum() >= 0), "El valor de " + campo + " no es válido.");
        return numero;
    }

    private static LocalDate fecha(JsonNode cuerpo, String campo, boolean obligatoria) {
        String valor = texto(cuerpo, campo);
        if (valor.isEmpty() && !obligatoria) return null;
        try {
            return LocalDate.parse(valor);
        } catch (DateTimeParseException e) {
            throw new ReglaNoCumplida("Indica una fecha válida para " + campo + ".");
        }
    }

    private static String fechaOpcional(JsonNode cuerpo, String campo) {
        LocalDate valor = fecha(cuerpo, campo, false);
        return valor == null ? null : valor.toString();
    }

    private static void permiso(boolean autorizado) {
        if (!autorizado) throw new SinPermiso("Tu perfil de Timing R&T no permite esta operación.");
    }

    static void regla(boolean valida, String mensaje) {
        if (!valida) throw new ReglaNoCumplida(mensaje);
    }
}
