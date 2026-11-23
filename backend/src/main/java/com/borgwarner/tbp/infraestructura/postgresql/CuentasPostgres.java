package com.borgwarner.tbp.infraestructura.postgresql;

import com.borgwarner.tbp.aplicacion.puertos.AlmacenUsuarios;
import com.borgwarner.tbp.configuracion.PropiedadesAutenticacion;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cuentas de la aplicación (hito S3): contraseña y estado de cada persona de
 * Users/Rights Management (tabla «credencial», V3).
 *
 *   - El email es el usuario; el rol sigue en la tabla «usuario».
 *   - Hash BCrypt; la contraseña nunca se guarda ni se escribe en el log.
 *   - Tras N intentos fallidos seguidos la cuenta queda bloqueada un tiempo
 *     (aunque luego se acierte). Un Admin puede desbloquearla antes.
 *   - El Admin no elige contraseñas: genera una temporal y la persona la
 *     cambia al entrar. Cada contraseña nueva cambia el «sello», que cierra las
 *     sesiones abiertas con la anterior.
 *   - Cada cambio queda en la auditoría (entidad «cuenta», sin contraseñas).
 */
public class CuentasPostgres {

    private static final Logger LOG = LoggerFactory.getLogger(CuentasPostgres.class);

    /** Quien actúa al crear el primer Admin desde la línea de órdenes. */
    public static final AlmacenUsuarios.Identidad INSTALACION = new AlmacenUsuarios.Identidad("instalacion", "Instalación");

    static final String ENTIDAD = "cuenta";
    static final String BLOQUEO = "BLOQUEO";
    static final String DESBLOQUEO = "DESBLOQUEO";
    static final String CLAVE_TEMPORAL = "CLAVE_TEMPORAL";
    static final String CAMBIO_CLAVE = "CAMBIO_CLAVE";
    static final String ADMIN_INSTALACION = "ADMIN_INSTALACION";

    /** BCrypt solo usa los primeros 72 bytes: más largo no se admite. */
    static final int MAXIMO_BYTES_CLAVE = 72;
    private static final String ALFABETO_TEMPORAL = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";

    public enum Resultado { CORRECTO, INCORRECTO, BLOQUEADA }

    /** Resultado de un intento de entrada. nombre = Title de Users/Rights Management. */
    public record Acceso(Resultado resultado, String email, String nombre, boolean debeCambiar, int sello,
                         Instant bloqueadaHasta) implements Serializable {
    }

    /** La contraseña nueva no cumple las reglas o la actual no es correcta (mensaje para la persona). */
    public static class ClaveNoValida extends RuntimeException {
        private final boolean bloqueada;

        public ClaveNoValida(String mensaje) {
            this(mensaje, false);
        }

        ClaveNoValida(String mensaje, boolean bloqueada) {
            super(mensaje);
            this.bloqueada = bloqueada;
        }

        /** El intento ha bloqueado la cuenta: hay que cerrar la sesión. */
        public boolean bloqueada() {
            return bloqueada;
        }
    }

    private final BaseDatos bd;
    private final PropiedadesAutenticacion propiedades;
    private final PasswordEncoder cifrado;
    private final SecureRandom azar = new SecureRandom();
    /** Para no responder más rápido cuando el email no tiene cuenta. */
    private final String hashFicticio;
    /**
     * Intentos con emails SIN cuenta (en memoria): también se «bloquean», para
     * que el mensaje de bloqueo no revele qué emails tienen cuenta.
     */
    private final Map<String, long[]> fallosSinCuenta = new ConcurrentHashMap<>();

    public CuentasPostgres(BaseDatos bd, PropiedadesAutenticacion propiedades) {
        this(bd, propiedades, new BCryptPasswordEncoder(12));
    }

    CuentasPostgres(BaseDatos bd, PropiedadesAutenticacion propiedades, PasswordEncoder cifrado) {
        this.bd = bd;
        this.propiedades = propiedades;
        this.cifrado = cifrado;
        this.hashFicticio = cifrado.encode("tbp-cuenta-inexistente");
    }

    public PropiedadesAutenticacion propiedades() {
        return propiedades;
    }

    static String normalizar(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean demasiadoLarga(String clave) {
        return clave.getBytes(StandardCharsets.UTF_8).length > MAXIMO_BYTES_CLAVE;
    }

    private boolean coincide(String clave, String hash) {
        if (clave == null || hash == null || demasiadoLarga(clave)) {
            cifrado.matches("x", hashFicticio);
            return false;
        }
        return cifrado.matches(clave, hash);
    }

    // ---- entrar ------------------------------------------------------------------

    /** Comprueba email y contraseña; anota el fallo o el acceso. */
    public Acceso autenticar(String emailEscrito, String clave) {
        String email = normalizar(emailEscrito);
        if (email.isEmpty() || clave == null || clave.isEmpty()) {
            cifrado.matches("x", hashFicticio);
            return new Acceso(Resultado.INCORRECTO, email, "", false, 0, null);
        }
        return bd.enTransaccion(() -> {
            List<Map<String, Object>> filas = bd.jdbc().queryForList(
                    "SELECT u.titulo, c.hash, c.debe_cambiar, c.sello, c.bloqueada_hasta, "
                            + "(c.bloqueada_hasta IS NOT NULL AND c.bloqueada_hasta > now()) AS bloqueada "
                            + "FROM credencial c JOIN usuario u ON u.email = c.email WHERE c.email = ? FOR UPDATE OF c", email);
            if (filas.isEmpty()) {
                cifrado.matches(clave, hashFicticio);
                Instant hasta = falloSinCuenta(email);
                return new Acceso(hasta != null ? Resultado.BLOQUEADA : Resultado.INCORRECTO, email, "", false, 0, hasta);
            }
            Map<String, Object> f = filas.get(0);
            String nombre = texto(f.get("titulo"));
            if (Boolean.TRUE.equals(f.get("bloqueada"))) {
                return new Acceso(Resultado.BLOQUEADA, email, nombre, false, 0, instante(f.get("bloqueada_hasta")));
            }
            if (!coincide(clave, texto(f.get("hash")))) {
                Instant bloqueo = anotarFallo(email, nombre);
                return new Acceso(bloqueo != null ? Resultado.BLOQUEADA : Resultado.INCORRECTO, email, nombre, false, 0, bloqueo);
            }
            bd.jdbc().update("UPDATE credencial SET intentos_fallidos = 0, bloqueada_hasta = NULL, ultimo_acceso = now() WHERE email = ?",
                    email);
            return new Acceso(Resultado.CORRECTO, email, nombre, Boolean.TRUE.equals(f.get("debe_cambiar")),
                    ((Number) f.get("sello")).intValue(), null);
        });
    }

    /**
     * Email sin cuenta: misma respuesta que una cuenta real (incorrecto y, tras
     * los mismos intentos, bloqueada el mismo tiempo). Devuelve hasta cuándo, o null.
     */
    private Instant falloSinCuenta(String email) {
        long ahora = System.currentTimeMillis();
        if (fallosSinCuenta.size() > 5000) {
            fallosSinCuenta.values().removeIf(v -> v[1] < ahora && v[0] == 0);
            if (fallosSinCuenta.size() > 5000) {
                fallosSinCuenta.clear();
            }
        }
        long[] r = fallosSinCuenta.compute(email, (k, v) -> {
            long[] e = v == null ? new long[]{0, 0} : v;      // {intentos, bloqueada hasta (ms)}
            if (e[1] > ahora) {
                return e;                                     // sigue «bloqueada»
            }
            e[0]++;
            if (e[0] >= propiedades.intentosMaximos()) {
                e[0] = 0;
                e[1] = ahora + propiedades.bloqueo().toMillis();
            }
            return e;
        });
        return r[1] > ahora ? Instant.ofEpochMilli(r[1]) : null;
    }

    /** Un intento fallido más; si llega al máximo, bloquea. Devuelve hasta cuándo, o null. */
    private Instant anotarFallo(String email, String nombre) {
        Integer intentos = bd.jdbc().queryForObject(
                "UPDATE credencial SET intentos_fallidos = intentos_fallidos + 1 WHERE email = ? RETURNING intentos_fallidos",
                Integer.class, email);
        if (intentos == null || intentos < propiedades.intentosMaximos()) {
            return null;
        }
        double segundos = propiedades.bloqueo().toMillis() / 1000.0;
        Object hasta = bd.jdbc().queryForObject("UPDATE credencial SET intentos_fallidos = 0, "
                        + "bloqueada_hasta = now() + make_interval(secs => ?) WHERE email = ? RETURNING bloqueada_hasta",
                Object.class, segundos, email);
        Auditoria.anotar(bd, new AlmacenUsuarios.Identidad(email, nombre), ENTIDAD, email, BLOQUEO, null,
                "bloqueada " + propiedades.bloqueo().toMinutes() + " min tras " + intentos + " intentos fallidos");
        LOG.warn("Cuenta {} bloqueada {} min tras {} intentos fallidos", email, propiedades.bloqueo().toMinutes(), intentos);
        return instante(hasta);
    }

    /**
     * Sello de la contraseña vigente, o null si la persona ya no tiene cuenta o
     * ya no está en Users/Rights Management (su sesión debe terminar).
     */
    public Integer sello(String email) {
        List<Integer> s = bd.jdbc().queryForList(
                "SELECT c.sello FROM credencial c JOIN usuario u ON u.email = c.email WHERE c.email = ?", Integer.class, normalizar(email));
        return s.isEmpty() ? null : s.get(0);
    }

    // ---- contraseñas ---------------------------------------------------------------

    /** Reglas para una contraseña nueva; null si vale o el motivo si no. */
    public String motivoRechazo(String email, String nueva) {
        if (nueva == null || nueva.isBlank() || nueva.length() < propiedades.longitudMinimaClave()) {
            return "La contraseña debe tener al menos " + propiedades.longitudMinimaClave() + " caracteres.";
        }
        if (demasiadoLarga(nueva)) {
            return "La contraseña es demasiado larga (máximo " + MAXIMO_BYTES_CLAVE + " caracteres sencillos).";
        }
        if (nueva.trim().equalsIgnoreCase(normalizar(email))) {
            return "La contraseña no puede ser tu email.";
        }
        if (nueva.chars().distinct().count() < 4) {
            return "La contraseña es demasiado sencilla: usa al menos 4 caracteres distintos.";
        }
        return null;
    }

    /** La persona cambia su contraseña (obligatorio tras una temporal). Devuelve el sello nuevo. */
    public int cambiarClave(String emailCuenta, String actual, String nueva) {
        String email = normalizar(emailCuenta);
        String motivo = motivoRechazo(email, nueva);
        if (motivo != null) {
            throw new ClaveNoValida(motivo);
        }
        Object r = bd.enTransaccion(() -> {
            List<Map<String, Object>> filas = bd.jdbc().queryForList(
                    "SELECT u.titulo, c.hash, (c.bloqueada_hasta IS NOT NULL AND c.bloqueada_hasta > now()) AS bloqueada "
                            + "FROM credencial c JOIN usuario u ON u.email = c.email WHERE c.email = ? FOR UPDATE OF c", email);
            if (filas.isEmpty()) {
                return new ClaveNoValida("Tu cuenta ya no existe: pide ayuda a un Admin.", true);
            }
            Map<String, Object> f = filas.get(0);
            String nombre = texto(f.get("titulo"));
            if (Boolean.TRUE.equals(f.get("bloqueada"))) {
                return new ClaveNoValida("La cuenta está bloqueada temporalmente.", true);
            }
            String hash = texto(f.get("hash"));
            if (!coincide(actual, hash)) {
                // Cuenta como intento fallido: el formulario no sirve para probar contraseñas.
                if (anotarFallo(email, nombre) != null) {
                    return new ClaveNoValida("Demasiados intentos fallidos: la cuenta queda bloqueada "
                            + propiedades.bloqueo().toMinutes() + " minutos.", true);
                }
                return new ClaveNoValida("La contraseña actual no es correcta.");
            }
            if (coincide(nueva, hash)) {
                return new ClaveNoValida("La contraseña nueva tiene que ser distinta de la actual.");
            }
            Integer sello = bd.jdbc().queryForObject("UPDATE credencial SET hash = ?, debe_cambiar = false, intentos_fallidos = 0, "
                    + "bloqueada_hasta = NULL, sello = sello + 1, cambiada_en = now() WHERE email = ? RETURNING sello",
                    Integer.class, cifrado.encode(nueva), email);
            Auditoria.anotar(bd, new AlmacenUsuarios.Identidad(email, nombre), ENTIDAD, email, CAMBIO_CLAVE, null, "contraseña cambiada");
            return sello;
        });
        if (r instanceof ClaveNoValida error) {
            throw error; // fuera de la transacción: el intento fallido queda anotado
        }
        return (Integer) r;
    }

    /**
     * Un Admin genera una contraseña temporal (alta de la cuenta o «he olvidado
     * mi contraseña»). La persona tendrá que cambiarla al entrar; sus sesiones
     * abiertas se cierran y la cuenta se desbloquea. Devuelve la contraseña,
     * que solo se enseña esta vez.
     */
    public String claveTemporal(AlmacenUsuarios.Identidad admin, String emailCuenta) {
        String email = normalizar(emailCuenta);
        String clave = generarTemporal();
        bd.enTransaccion(() -> {
            exigirEnLista(email);
            bd.jdbc().update("INSERT INTO credencial (email, hash, debe_cambiar) VALUES (?, ?, true) "
                    + "ON CONFLICT (email) DO UPDATE SET hash = EXCLUDED.hash, debe_cambiar = true, intentos_fallidos = 0, "
                    + "bloqueada_hasta = NULL, sello = credencial.sello + 1, cambiada_en = now()", email, cifrado.encode(clave));
            Auditoria.anotar(bd, admin, ENTIDAD, email, CLAVE_TEMPORAL, null, "contraseña temporal (cambio obligatorio al entrar)");
            return null;
        });
        LOG.info("Contraseña temporal para {} generada por {}", email, admin == null ? "?" : admin.email());
        return clave;
    }

    /** Un Admin quita el bloqueo temporal. false si la cuenta no existe. */
    public boolean desbloquear(AlmacenUsuarios.Identidad admin, String emailCuenta) {
        String email = normalizar(emailCuenta);
        return bd.enTransaccion(() -> {
            int n = bd.jdbc().update("UPDATE credencial SET intentos_fallidos = 0, bloqueada_hasta = NULL WHERE email = ?", email);
            if (n > 0) {
                Auditoria.anotar(bd, admin, ENTIDAD, email, DESBLOQUEO, null, "desbloqueada");
            }
            return n > 0;
        });
    }

    /**
     * Instalación o rescate, desde la línea de órdenes del servidor: deja a esa
     * persona como Admin (la añade a Users/Rights Management si no está) con la
     * contraseña indicada, sin cambio obligatorio. Devuelve lo que se ha hecho.
     */
    public String establecerAdmin(String emailCuenta, String nombre, String clave) {
        String email = normalizar(emailCuenta);
        if (email.isEmpty() || !email.contains("@")) {
            throw new ClaveNoValida("Indica un email válido.");
        }
        String motivo = motivoRechazo(email, clave);
        if (motivo != null) {
            throw new ClaveNoValida(motivo);
        }
        return bd.enTransaccion(() -> {
            bd.bloquear("usuarios");
            List<String> rol = bd.jdbc().queryForList("SELECT rol FROM usuario WHERE email = ?", String.class, email);
            String hecho;
            if (rol.isEmpty()) {
                String titulo = nombre == null || nombre.isBlank() ? email : nombre.trim();
                bd.jdbc().update("INSERT INTO usuario (email, titulo, rol) VALUES (?, ?, 'Admin')", email, titulo);
                Auditoria.anotar(bd, INSTALACION, "usuario", email, Auditoria.ALTA, null, "Admin (instalación)");
                hecho = "añadido a Users/Rights Management como Admin";
            } else if (!"Admin".equals(UsuariosPostgres.rolGuardado(rol.get(0)))) {
                bd.jdbc().update("UPDATE usuario SET rol = 'Admin' WHERE email = ?", email);
                Auditoria.anotar(bd, INSTALACION, "usuario", email, Auditoria.MODIFICACION, rol.get(0), "Admin (instalación)");
                hecho = "pasa de " + rol.get(0) + " a Admin";
            } else {
                hecho = "ya era Admin";
            }
            bd.jdbc().update("INSERT INTO credencial (email, hash, debe_cambiar) VALUES (?, ?, false) "
                    + "ON CONFLICT (email) DO UPDATE SET hash = EXCLUDED.hash, debe_cambiar = false, intentos_fallidos = 0, "
                    + "bloqueada_hasta = NULL, sello = credencial.sello + 1, cambiada_en = now()", email, cifrado.encode(clave));
            Auditoria.anotar(bd, INSTALACION, ENTIDAD, email, ADMIN_INSTALACION, null, "contraseña establecida en el servidor");
            return email + ": " + hecho + "; contraseña establecida";
        });
    }

    /** ¿Algún Admin puede entrar? (aviso al arrancar el servidor). */
    public boolean hayAdminConClave() {
        Integer n = bd.jdbc().queryForObject("SELECT count(*) FROM credencial c JOIN usuario u ON u.email = c.email "
                + "WHERE lower(u.rol) IN ('admin', 'administrator')", Integer.class);
        return n != null && n > 0;
    }

    /**
     * Estado de las cuentas de todas las personas de Users/Rights Management
     * (sin hashes): [{email, tieneClave, debeCambiar, bloqueadaHasta, intentosFallidos, ultimoAcceso, cambiadaEn}].
     */
    public ArrayNode estados() {
        ArrayNode salida = JsonJs.mapper().createArrayNode();
        bd.jdbc().query("SELECT u.email, c.email IS NOT NULL AS tiene, c.debe_cambiar, c.intentos_fallidos, "
                + "CASE WHEN c.bloqueada_hasta > now() THEN c.bloqueada_hasta END AS bloqueada_hasta, c.ultimo_acceso, c.cambiada_en "
                + "FROM usuario u LEFT JOIN credencial c ON c.email = u.email ORDER BY u.id", (java.sql.ResultSet rs) -> {
            while (rs.next()) {
                ObjectNode o = salida.addObject();
                o.put("email", rs.getString("email"));
                boolean tiene = rs.getBoolean("tiene");
                o.put("tieneClave", tiene);
                o.put("debeCambiar", tiene && rs.getBoolean("debe_cambiar"));
                o.put("intentosFallidos", tiene ? rs.getInt("intentos_fallidos") : 0);
                ponerInstante(o, "bloqueadaHasta", rs.getTimestamp("bloqueada_hasta"));
                ponerInstante(o, "ultimoAcceso", rs.getTimestamp("ultimo_acceso"));
                ponerInstante(o, "cambiadaEn", rs.getTimestamp("cambiada_en"));
            }
            return null;
        });
        return salida;
    }

    private void exigirEnLista(String email) {
        Integer n = bd.jdbc().queryForObject("SELECT count(*) FROM usuario WHERE email = ?", Integer.class, email);
        if (n == null || n == 0) {
            throw new IllegalArgumentException(email + " no está en Users/Rights Management: añádelo antes de darle contraseña.");
        }
    }

    /** 12 caracteres sin confusiones (0/O, 1/l/I) en grupos de 4: «Abcd-efgh-2345». */
    String generarTemporal() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            if (i > 0 && i % 4 == 0) {
                sb.append('-');
            }
            sb.append(ALFABETO_TEMPORAL.charAt(azar.nextInt(ALFABETO_TEMPORAL.length())));
        }
        return sb.toString();
    }

    private static void ponerInstante(ObjectNode o, String campo, Timestamp t) {
        if (t == null) {
            o.putNull(campo);
        } else {
            o.put(campo, t.toInstant().toString());
        }
    }

    private static String texto(Object o) {
        return o == null ? "" : o.toString();
    }

    private static Instant instante(Object o) {
        if (o instanceof Timestamp t) {
            return t.toInstant();
        }
        if (o instanceof OffsetDateTime d) {
            return d.toInstant();
        }
        if (o instanceof Instant i) {
            return i;
        }
        return null;
    }
}
