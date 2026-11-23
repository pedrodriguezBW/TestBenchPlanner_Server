package com.borgwarner.tbp.api.acceso;

import com.borgwarner.tbp.infraestructura.postgresql.CuentasPostgres;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Optional;

/**
 * Formulario de entrada y cambio de contraseña del modo servidor (hito S3).
 * POST /login y POST /logout los atiende Spring Security.
 */
@RestController
@ConditionalOnProperty(prefix = "planner.servidor", name = "activo", havingValue = "true")
public class AccesoController {

    private final CuentasPostgres cuentas;

    public AccesoController(CuentasPostgres cuentas) {
        this.cuentas = cuentas;
    }

    @GetMapping(value = "/login", produces = MediaType.TEXT_HTML_VALUE)
    public String entrar(HttpServletRequest peticion, HttpServletResponse respuesta) throws IOException {
        if (IdentidadSesion.cuenta().isPresent()) {
            respuesta.sendRedirect("/");
            return null;
        }
        String error = null;
        String info = null;
        if (peticion.getParameter("error") != null) {
            error = "Email o contraseña incorrectos.";
        } else if (peticion.getParameter("bloqueada") != null) {
            error = "Cuenta bloqueada temporalmente por demasiados intentos fallidos. Prueba de nuevo dentro de "
                    + Math.max(1, cuentas.propiedades().bloqueo().toMinutes()) + " minutos o pide a un Admin que la desbloquee.";
        } else if (peticion.getParameter("inactividad") != null) {
            info = "La sesión se ha cerrado tras " + Math.max(1, cuentas.propiedades().inactividad().toMinutes())
                    + " minutos sin actividad. Vuelve a entrar.";
        } else if (peticion.getParameter("caducada") != null) {
            info = "Tu sesión ha terminado. Vuelve a entrar.";
        } else if (peticion.getParameter("salida") != null) {
            info = "Has cerrado la sesión.";
        } else if (peticion.getParameter("clave-cambiada") != null) {
            info = "Contraseña cambiada.";
        }
        return PaginasAcceso.entrar(error, info);
    }

    @GetMapping(value = "/cambiar-clave", produces = MediaType.TEXT_HTML_VALUE)
    public String formularioCambio(HttpServletRequest peticion) {
        Optional<CuentaAutenticada> cuenta = IdentidadSesion.cuenta();
        return PaginasAcceso.cambiar(cuenta.map(c -> c.nombre().isEmpty() ? c.email() : c.nombre() + " · " + c.email()).orElse(""),
                obligatorio(peticion), cuentas.propiedades().longitudMinimaClave(), null);
    }

    @PostMapping(value = "/cambiar-clave", produces = MediaType.TEXT_HTML_VALUE)
    public String cambiar(@RequestParam(defaultValue = "") String actual, @RequestParam(defaultValue = "") String nueva,
                          @RequestParam(defaultValue = "") String repetida, HttpServletRequest peticion,
                          HttpServletResponse respuesta) throws IOException {
        Optional<CuentaAutenticada> cuenta = IdentidadSesion.cuenta();
        if (cuenta.isEmpty()) {
            respuesta.sendRedirect("/login");
            return null;
        }
        String nombre = cuenta.get().nombre().isEmpty() ? cuenta.get().email() : cuenta.get().nombre() + " · " + cuenta.get().email();
        boolean obligatorio = obligatorio(peticion);
        int minimo = cuentas.propiedades().longitudMinimaClave();
        if (!nueva.equals(repetida)) {
            return PaginasAcceso.cambiar(nombre, obligatorio, minimo, "Las dos contraseñas nuevas no coinciden.");
        }
        try {
            int sello = cuentas.cambiarClave(cuenta.get().email(), actual, nueva);
            HttpSession sesion = peticion.getSession(false);
            SesionServidor.atributo(sesion, SesionServidor.SELLO, sello);
            SesionServidor.atributo(sesion, SesionServidor.DEBE_CAMBIAR, false);
            respuesta.sendRedirect("/");
            return null;
        } catch (CuentasPostgres.ClaveNoValida e) {
            if (e.bloqueada()) {
                HttpSession sesion = peticion.getSession(false);
                if (sesion != null) {
                    sesion.invalidate();
                }
                SecurityContextHolder.clearContext();
                respuesta.sendRedirect("/login?bloqueada");
                return null;
            }
            return PaginasAcceso.cambiar(nombre, obligatorio, minimo, e.getMessage());
        }
    }

    private static boolean obligatorio(HttpServletRequest peticion) {
        HttpSession sesion = peticion.getSession(false);
        return sesion != null && Boolean.TRUE.equals(sesion.getAttribute(SesionServidor.DEBE_CAMBIAR));
    }
}
