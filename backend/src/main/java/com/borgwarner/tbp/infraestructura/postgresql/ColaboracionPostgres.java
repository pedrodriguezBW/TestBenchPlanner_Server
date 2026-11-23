package com.borgwarner.tbp.infraestructura.postgresql;

import com.borgwarner.tbp.aplicacion.puertos.AlmacenUsuarios;
import com.borgwarner.tbp.aplicacion.puertos.Colaboracion;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.infraestructura.comun.Versionado;
import com.borgwarner.tbp.infraestructura.local.Presencia;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Presencia (tabla «presencia»), registro de actividad visible en la app
 * («registro_actividad») y versión publicada («configuracion_app»).
 *
 * La presencia usa la hora real del servidor: desaparece el desfase de una
 * hora en verano de la 4.9.4 (D9), que solo existía por cómo SharePoint
 * guardaba LastSeen.
 */
public class ColaboracionPostgres implements Colaboracion {

    private static final Logger LOG = LoggerFactory.getLogger(ColaboracionPostgres.class);
    static final int TTL_PRESENCIA_S = 75;
    static final String CLAVE_VERSION = "CurrentVersion";

    private final BaseDatos bd;

    public ColaboracionPostgres(BaseDatos bd) {
        this.bd = bd;
    }

    @Override
    public ObjectNode latidoPresencia(AlmacenUsuarios.Identidad identidad) {
        String email = identidad == null || identidad.email() == null ? "" : identidad.email();
        try {
            if (!email.isEmpty()) {
                String nombre = identidad.nombre() == null || identidad.nombre().isEmpty() ? email : identidad.nombre();
                bd.jdbc().update("INSERT INTO presencia (email, nombre, iniciales, color, ultimo_latido) VALUES (?, ?, ?, ?, now()) "
                                + "ON CONFLICT (email) DO UPDATE SET nombre = EXCLUDED.nombre, iniciales = EXCLUDED.iniciales, "
                                + "color = EXCLUDED.color, ultimo_latido = now()",
                        email, nombre, Presencia.iniciales(identidad.nombre(), email), Presencia.color(email));
            }
            ObjectNode salida = JsonJs.objetoVacio().put("me", email);
            ArrayNode conectados = salida.putArray("online");
            List<ObjectNode> lista = new ArrayList<>();
            bd.jdbc().query("SELECT email, nombre, iniciales, color FROM presencia "
                    + "WHERE ultimo_latido > now() - (? * interval '1 second')", (java.sql.ResultSet rs) -> {
                while (rs.next()) {
                    String correo = rs.getString("email");
                    ObjectNode u = JsonJs.objetoVacio();
                    u.put("email", correo);
                    u.put("name", rs.getString("nombre") == null ? correo : rs.getString("nombre"));
                    u.put("initials", rs.getString("iniciales") == null ? Presencia.iniciales(rs.getString("nombre"), correo)
                            : rs.getString("iniciales"));
                    u.put("color", rs.getString("color") == null ? Presencia.color(correo) : rs.getString("color"));
                    u.put("me", correo.equalsIgnoreCase(email));
                    lista.add(u);
                }
                return null;
            }, TTL_PRESENCIA_S);
            lista.sort(Comparator.comparing((ObjectNode u) -> !u.path("me").asBoolean())
                    .thenComparing(u -> u.path("name").asText("").toLowerCase()));
            lista.forEach(conectados::add);
            return salida;
        } catch (RuntimeException e) {
            return JsonJs.objetoVacio().put("me", "").put("error", String.valueOf(e.getMessage()))
                    .set("online", JsonJs.mapper().createArrayNode());
        }
    }

    @Override
    public boolean anotarActividad(String email, String nombre, String accion, String detalle) {
        try {
            bd.jdbc().update("INSERT INTO registro_actividad (cuando, email, nombre, accion, detalle) VALUES (?, ?, ?, ?, ?)",
                    Versionado.instanteSinMilisegundos(Instant.now()), email == null ? "" : email,
                    nombre == null || nombre.isEmpty() ? (email == null ? "" : email) : nombre,
                    accion == null ? "" : accion, detalle == null ? "" : detalle);
            return true;
        } catch (RuntimeException e) {
            LOG.warn("No se ha podido anotar la actividad «{}»: {}", accion, e.getMessage());
            return false; // el registro nunca debe impedir un guardado
        }
    }

    @Override
    public ObjectNode leerActividad(int maximo) {
        ObjectNode salida = JsonJs.objetoVacio();
        ArrayNode filas = salida.putArray("rows");
        bd.jdbc().query("SELECT email, nombre, accion, detalle, cuando FROM registro_actividad ORDER BY id DESC LIMIT ?",
                (java.sql.ResultSet rs) -> {
                    while (rs.next()) {
                        ObjectNode f = filas.addObject();
                        f.put("email", rs.getString("email"));
                        f.put("name", rs.getString("nombre"));
                        f.put("action", rs.getString("accion"));
                        f.put("detail", rs.getString("detalle"));
                        f.put("when", rs.getString("cuando"));
                    }
                    return null;
                }, Math.max(0, maximo));
        ArrayNode columnas = salida.putArray("columns");
        List.of("Action", "Detail", "DisplayName", "Email", "WhenTs").forEach(columnas::add);
        return salida;
    }

    @Override
    public String versionPublicada() {
        List<String> v = bd.jdbc().queryForList("SELECT valor FROM configuracion_app WHERE clave = ?", String.class, CLAVE_VERSION);
        String valor = v.isEmpty() || v.get(0) == null ? "" : v.get(0).trim();
        return valor.isEmpty() ? null : valor;
    }

    /** Publica la versión solo si es estrictamente mayor que la publicada (nunca la rebaja). */
    @Override
    public boolean publicarVersion(String version) {
        String actual = versionPublicada();
        if (actual != null && Versionado.comparar(version, actual) <= 0) {
            return true;
        }
        bd.jdbc().update("INSERT INTO configuracion_app (clave, valor) VALUES (?, ?) "
                + "ON CONFLICT (clave) DO UPDATE SET valor = EXCLUDED.valor", CLAVE_VERSION, version);
        return true;
    }
}
