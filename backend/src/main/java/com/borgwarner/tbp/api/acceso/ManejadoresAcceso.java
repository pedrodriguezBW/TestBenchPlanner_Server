package com.borgwarner.tbp.api.acceso;

import com.borgwarner.tbp.aplicacion.puertos.Colaboracion;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;

import java.io.IOException;

/**
 * Qué pasa al entrar, al fallar, al salir y al pedir algo sin sesión
 * (modo servidor, hito S3).
 */
public class ManejadoresAcceso {

    private static final Logger LOG = LoggerFactory.getLogger(ManejadoresAcceso.class);

    private final Colaboracion colaboracion;

    public ManejadoresAcceso(Colaboracion colaboracion) {
        this.colaboracion = colaboracion;
    }

    /** Sesión nueva: anota la entrada y lleva a la aplicación (o a cambiar la contraseña temporal). */
    public void exito(HttpServletRequest peticion, HttpServletResponse respuesta, Authentication autenticacion) throws IOException {
        HttpSession sesion = peticion.getSession(true);
        boolean debeCambiar = false;
        if (autenticacion.getDetails() instanceof ProveedorAutenticacion.DatosAcceso d) {
            debeCambiar = d.debeCambiar();
            sesion.setAttribute(SesionServidor.SELLO, d.sello());
        }
        sesion.setAttribute(SesionServidor.DEBE_CAMBIAR, debeCambiar);
        sesion.setAttribute(SesionServidor.ULTIMA_ACTIVIDAD, System.currentTimeMillis());
        if (autenticacion.getPrincipal() instanceof CuentaAutenticada c) {
            LOG.info("Inicio de sesión de {}", c.email());
            colaboracion.anotarActividadSinEsperar(c.email(), c.nombre(), "login", debeCambiar ? "temporary password" : "");
        }
        respuesta.sendRedirect(peticion.getContextPath() + (debeCambiar ? "/cambiar-clave" : "/"));
    }

    /** Email o contraseña incorrectos, o cuenta bloqueada: vuelta al formulario con el motivo. */
    public void fallo(HttpServletRequest peticion, HttpServletResponse respuesta, AuthenticationException error) throws IOException {
        String usuario = peticion.getParameter("usuario");
        usuario = usuario == null ? "" : usuario.trim();
        LOG.warn("Inicio de sesión fallido ({}) para «{}» desde {}", error instanceof LockedException ? "bloqueada" : "incorrecto",
                usuario.length() > 80 ? usuario.substring(0, 80) : usuario, peticion.getRemoteAddr());
        respuesta.sendRedirect(peticion.getContextPath() + (error instanceof LockedException ? "/login?bloqueada" : "/login?error"));
    }

    /** Sin sesión: la API responde 401 (la interfaz lleva al formulario); una página, directamente al formulario. */
    public void sinSesion(HttpServletRequest peticion, HttpServletResponse respuesta, AuthenticationException error) throws IOException {
        if (SesionServidor.esApi(peticion)) {
            SesionServidor.json(respuesta, HttpServletResponse.SC_UNAUTHORIZED, "sin-sesion",
                    "Tu sesión ha terminado: vuelve a entrar con tu email y contraseña.");
            return;
        }
        respuesta.sendRedirect(peticion.getContextPath() + "/login");
    }

    /** Cerrar sesión (botón «Log out»). */
    public void salida(HttpServletRequest peticion, HttpServletResponse respuesta, Authentication autenticacion) {
        if (autenticacion != null && autenticacion.getPrincipal() instanceof CuentaAutenticada c) {
            LOG.info("Cierre de sesión de {}", c.email());
            colaboracion.anotarActividadSinEsperar(c.email(), c.nombre(), "logout", "");
        }
    }
}
