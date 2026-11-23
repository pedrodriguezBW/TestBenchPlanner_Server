package com.borgwarner.tbp.api.acceso;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.Set;

/**
 * Protección frente a peticiones falsificadas desde otra web (CSRF) en el modo
 * servidor: una petición que cambia algo (POST, PUT, PATCH, DELETE) y dice venir
 * de otro origen se rechaza. Junto con la cookie de sesión SameSite=Lax, otra
 * página no puede guardar ni cerrar la sesión en nombre de quien la visita.
 * Sin cabecera Origin (herramientas, navegadores antiguos) se deja pasar.
 */
public class FiltroOrigen extends OncePerRequestFilter {

    private static final Set<String> SEGUROS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    @Override
    protected void doFilterInternal(HttpServletRequest peticion, HttpServletResponse respuesta, FilterChain cadena)
            throws ServletException, IOException {
        String origen = peticion.getHeader("Origin");
        if (origen != null && !SEGUROS.contains(peticion.getMethod().toUpperCase()) && !mismoOrigen(origen, peticion.getHeader("Host"))) {
            SesionServidor.json(respuesta, HttpServletResponse.SC_FORBIDDEN, "origen",
                    "Petición rechazada: no viene de TestBenchPlanner.");
            return;
        }
        cadena.doFilter(peticion, respuesta);
    }

    static boolean mismoOrigen(String origen, String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        try {
            String autoridad = URI.create(origen.trim()).getRawAuthority();
            return autoridad != null && autoridad.equalsIgnoreCase(host.trim());
        } catch (IllegalArgumentException e) {
            return false; // «null» u otro valor raro
        }
    }
}
