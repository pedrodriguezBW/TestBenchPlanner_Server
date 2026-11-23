package com.borgwarner.tbp.infraestructura.postgresql;

import com.borgwarner.tbp.aplicacion.puertos.AlmacenUsuarios;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.listas.ProteccionListas;
import com.borgwarner.tbp.dominio.usuarios.Usuario;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        bd.jdbc().query("SELECT titulo, email, rol, nombre_responsable FROM usuario ORDER BY id", (java.sql.ResultSet rs) -> {
            while (rs.next()) {
                ObjectNode u = salida.addObject();
                u.put("title", rs.getString("titulo") == null ? "" : rs.getString("titulo"));
                u.put("email", rs.getString("email").trim().toLowerCase());
                u.put("role", Usuario.normalizarRol(rs.getString("rol") == null ? "" : rs.getString("rol")));
                u.put("responsibleName", rs.getString("nombre_responsable") == null ? "" : rs.getString("nombre_responsable"));
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
            // Quien sale de la lista pierde su cuenta (hito S3).
            bd.jdbc().update("DELETE FROM credencial c WHERE NOT EXISTS (SELECT 1 FROM usuario u WHERE u.email = c.email)");
            return r;
        });
    }
}
