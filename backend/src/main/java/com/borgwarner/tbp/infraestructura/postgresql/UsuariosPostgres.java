package com.borgwarner.tbp.infraestructura.postgresql;

import com.borgwarner.tbp.aplicacion.puertos.AlmacenUsuarios;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.listas.ProteccionListas;
import com.borgwarner.tbp.dominio.operaciones.ReglaNoCumplida;
import com.borgwarner.tbp.dominio.usuarios.Usuario;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.function.Supplier;

/**
 * Usuarios y roles en la tabla «usuario» (clave: email en minúsculas).
 *
 * Identidad: en el modo servidor (hito S3) es la persona que ha iniciado
 * sesión en la petición en curso. Fuera del servidor (un solo usuario por
 * instalación) es la configurada en planner.postgresql.usuario-email, igual
 * que en el modo local.
 *
 * Quitar a alguien de la lista borra también su cuenta (contraseña): ya no
 * puede entrar y sus sesiones abiertas se cierran.
 */
public class UsuariosPostgres implements AlmacenUsuarios {

    private final BaseDatos bd;
    private final boolean permitirVacias;
    private volatile Identidad identidad;
    /** Modo servidor: identidad de la sesión de la petición en curso (null fuera del servidor). */
    private final Supplier<Identidad> deSesion;

    public UsuariosPostgres(BaseDatos bd, Identidad identidad, boolean permitirVacias) {
        this(bd, identidad, permitirVacias, null);
    }

    public UsuariosPostgres(BaseDatos bd, Identidad identidad, boolean permitirVacias, Supplier<Identidad> deSesion) {
        this.bd = bd;
        this.identidad = identidad;
        this.permitirVacias = permitirVacias;
        this.deSesion = deSesion;
    }

    /** Cambia la identidad provisional (reinicio de pruebas). */
    public void cambiarIdentidad(Identidad nueva) {
        this.identidad = nueva;
    }

    @Override
    public Identidad identidadActual() {
        if (deSesion != null) {
            Identidad i = deSesion.get();
            return i == null ? new Identidad("", "") : i;
        }
        return identidad;
    }

    @Override
    public boolean identidadPorPeticion() {
        return deSesion != null;
    }

    @Override
    public ArrayNode leer() {
        ArrayNode salida = JsonJs.mapper().createArrayNode();
        bd.jdbc().query("SELECT u.titulo, u.email, u.rol, u.nombre_responsable, "
                + "(SELECT string_agg(p.perfil, ',' ORDER BY p.perfil) FROM timing_perfil p WHERE p.email = u.email) AS timing "
                + "FROM usuario u ORDER BY u.id", (java.sql.ResultSet rs) -> {
            while (rs.next()) {
                ObjectNode u = salida.addObject();
                u.put("title", rs.getString("titulo") == null ? "" : rs.getString("titulo"));
                u.put("email", rs.getString("email").trim().toLowerCase());
                u.put("role", Usuario.normalizarRol(rs.getString("rol") == null ? "" : rs.getString("rol")));
                u.put("responsibleName", rs.getString("nombre_responsable") == null ? "" : rs.getString("nombre_responsable"));
                if (deSesion != null) {
                    ArrayNode perfiles = u.putArray("timingRoles");
                    String timing = rs.getString("timing");
                    if (timing != null) {
                        for (String perfil : timing.split(",")) {
                            perfiles.add(perfil);
                        }
                    }
                }
            }
            return null;
        });
        return salida;
    }

    /** Rol tal como se guarda en la lista: Admin, Editor, LabUser o ReadOnly. */
    static String rolGuardado(String rol) {
        return Usuario.rolGuardado(rol == null ? "" : rol);
    }

    /** Filas de la tabla a partir del formato de /api/save_users ({users:[…]}) o de una lista. */
    static List<Map<String, Object>> filas(JsonNode usuarios) {
        List<Map<String, Object>> filas = new ArrayList<>();
        for (JsonNode u : usuarios) {
            String email = u.path("email").asText("").trim().toLowerCase();
            if (email.isEmpty()) {
                continue;
            }
            String titulo = u.path("title").asText("");
            String responsable = u.path("responsibleName").asText("");
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("email", email);
            f.put("titulo", titulo.isEmpty() ? email : titulo);
            f.put("rol", rolGuardado(u.path("role").asText("")));
            f.put("nombre_responsable", responsable.isEmpty() ? null : responsable);
            filas.add(f);
        }
        return filas;
    }

    @Override
    public ObjectNode guardar(JsonNode usuarios) {
        List<Map<String, Object>> filas = filas(usuarios.path("users"));
        return bd.enTransaccion(() -> {
            bd.bloquear("usuarios");
            List<String> existentes = new ArrayList<>();
            leer().forEach(u -> existentes.add(u.path("email").asText()));
            ProteccionListas.comprobar("TestBenchUsers", existentes, filas.stream().map(f -> (String) f.get("email")).toList(),
                    permitirVacias);
            ObjectNode r = JsonJs.objetoVacio().put("ok", true).set("users", Reconciliador.reconciliar(bd, "usuario", "email",
                    List.of("titulo", "rol", "nombre_responsable"), filas, true, "usuario", bd.actor(), null).json());
            if (deSesion != null) {
                for (JsonNode usuario : usuarios.path("users")) {
                    if (usuario.has("timingRoles")) {
                        guardarPerfiles(usuario.path("email").asText().trim().toLowerCase(java.util.Locale.ROOT),
                                usuario.get("timingRoles"));
                    }
                }
            }
            // Quien sale de la lista pierde su cuenta (hito S3).
            bd.jdbc().update("DELETE FROM credencial c WHERE NOT EXISTS (SELECT 1 FROM usuario u WHERE u.email = c.email)");
            if (deSesion != null) {
                // Timing R&T: las tareas importadas del Excel con el nombre de una persona nueva se le asignan solas.
                r.put("timingVinculadas", VinculacionTiming.vincular(bd));
            }
            return r;
        });
    }

    private void guardarPerfiles(String email, JsonNode datos) {
        if (email.isBlank() || !datos.isArray()) {
            throw new ReglaNoCumplida("Indica el correo y los perfiles de Timing R&T.");
        }
        var perfiles = new LinkedHashSet<String>();
        for (JsonNode perfil : datos) {
            if (!List.of("solicitante", "tecnico", "coordinador").contains(perfil.asText())) {
                throw new ReglaNoCumplida("Perfil de Timing R&T desconocido.");
            }
            perfiles.add(perfil.asText());
        }
        List<String> anteriores = bd.jdbc().queryForList("SELECT perfil FROM timing_perfil WHERE email = ? ORDER BY perfil",
                String.class, email);
        if (perfiles.equals(new LinkedHashSet<>(anteriores))) {
            return;
        }
        bd.jdbc().update("DELETE FROM timing_perfil WHERE email = ?", email);
        for (String perfil : perfiles) {
            bd.jdbc().update("INSERT INTO timing_perfil (email, perfil) VALUES (?, ?)", email, perfil);
        }
        Auditoria.anotar(bd, bd.actor(), "timing_perfil", email, Auditoria.MODIFICACION,
                String.join(", ", anteriores), String.join(", ", perfiles));
    }
}
