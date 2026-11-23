package com.borgwarner.tbp.api.acceso;

import com.borgwarner.tbp.infraestructura.postgresql.CuentasPostgres;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * Vigila cada sesión abierta del modo servidor (hito S3):
 *
 *   - Inactividad: si la persona lleva más de planner.autenticacion.inactividad
 *     sin tocar la aplicación, la sesión se cierra. Los sondeos automáticos de
 *     la interfaz (relectura cada 20 s, presencia…) NO cuentan como actividad:
 *     la interfaz manda en X-TBP-Inactivo los segundos desde el último clic o
 *     tecla, y solo eso alarga la sesión.
 *   - Contraseña cambiada (por la persona en otra sesión o por un Admin) o
 *     persona quitada de Users/Rights Management: la sesión se cierra.
 *   - Contraseña temporal: hasta cambiarla solo se puede ir a /cambiar-clave.
 */
public class FiltroSesion extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(FiltroSesion.class);
    /** Rutas que no cuentan ni se vigilan: el formulario y los avisos de pestaña de la interfaz. */
    static final Set<String> SIN_VIGILAR = Set.of("/login", "/logout", "/api/heartbeat", "/api/shutdown", "/favicon.ico", "/logo.png");
    private static final long MAXIMO_INACTIVO_DECLARADO_S = 7L * 24 * 3600;

    private final CuentasPostgres cuentas;
    private final long inactividadMs;
    private final Clock reloj;

    public FiltroSesion(CuentasPostgres cuentas, Duration inactividad, Clock reloj) {
        this.cuentas = cuentas;
        this.inactividadMs = inactividad.toMillis();
        this.reloj = reloj;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest peticion) {
        return SIN_VIGILAR.contains(SesionServidor.ruta(peticion));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest peticion, HttpServletResponse respuesta, FilterChain cadena)
            throws ServletException, IOException {
        Optional<CuentaAutenticada> cuenta = IdentidadSesion.cuenta();
        HttpSession sesion = peticion.getSession(false);
        if (cuenta.isEmpty() || sesion == null) {
            cadena.doFilter(peticion, respuesta); // sin sesión: ya lo ha decidido la autorización
            return;
        }
        long ahora = reloj.millis();
        Object ultima = sesion.getAttribute(SesionServidor.ULTIMA_ACTIVIDAD);
        long ultimaActividad = ultima instanceof Long l ? l : ahora;
        if (ahora - ultimaActividad > inactividadMs) {
            LOG.info("Sesión de {} cerrada por inactividad", cuenta.get().email());
            terminar(peticion, respuesta, sesion, "inactividad",
                    "La sesión se ha cerrado tras " + Math.max(1, inactividadMs / 60000) + " minutos sin actividad: vuelve a entrar.");
            return;
        }
        Integer sello = cuentas.sello(cuenta.get().email());
        if (sello == null || !sello.equals(sesion.getAttribute(SesionServidor.SELLO))) {
            LOG.info("Sesión de {} cerrada: contraseña cambiada o cuenta retirada", cuenta.get().email());
            terminar(peticion, respuesta, sesion, "caducada",
                    "Tu contraseña ha cambiado o tu cuenta ya no está activa: vuelve a entrar.");
            return;
        }
        long actividad = ahora - 1000L * segundosInactivo(peticion);
        if (actividad > ultimaActividad || ultima == null) {
            sesion.setAttribute(SesionServidor.ULTIMA_ACTIVIDAD, Math.min(Math.max(actividad, ultimaActividad), ahora));
        }
        if (Boolean.TRUE.equals(sesion.getAttribute(SesionServidor.DEBE_CAMBIAR))
                && !"/cambiar-clave".equals(SesionServidor.ruta(peticion))) {
            if (SesionServidor.esApi(peticion)) {
                SesionServidor.json(respuesta, HttpServletResponse.SC_FORBIDDEN, "cambiar-clave",
                        "Antes de seguir tienes que cambiar tu contraseña temporal.");
            } else {
                respuesta.sendRedirect(peticion.getContextPath() + "/cambiar-clave");
            }
            return;
        }
        cadena.doFilter(peticion, respuesta);
    }

    /** Segundos sin tocar la pestaña que dice la interfaz; 0 si no lo dice (navegación normal = actividad). */
    static long segundosInactivo(HttpServletRequest peticion) {
        String valor = peticion.getHeader(SesionServidor.CABECERA_INACTIVO);
        if (valor == null || valor.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Math.min(MAXIMO_INACTIVO_DECLARADO_S, Long.parseLong(valor.trim())));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void terminar(HttpServletRequest peticion, HttpServletResponse respuesta, HttpSession sesion, String motivo,
                                 String mensaje) throws IOException {
        try {
            sesion.invalidate();
        } catch (IllegalStateException yaCerrada) {
            // otra petición la cerró a la vez
        }
        SecurityContextHolder.clearContext();
        if (SesionServidor.esApi(peticion)) {
            SesionServidor.json(respuesta, HttpServletResponse.SC_UNAUTHORIZED, "sin-sesion", mensaje, motivo);
        } else {
            respuesta.sendRedirect(peticion.getContextPath() + "/login?" + motivo);
        }
    }
}
