package com.borgwarner.tbp.api.comun;

import com.borgwarner.tbp.configuracion.PropiedadesPlanner;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Modo servidor (hito S2): cierra lo que no es seguro con varios usuarios.
 *
 *   - /clasico: la interfaz 4.9.4 guarda con «el último que guarda gana» (D12).
 *   - La API antigua /api/* que solo usa esa interfaz (load, save, save_config…).
 *     Quedan /api/heartbeat y /api/shutdown, que la interfaz nueva sigue enviando
 *     (no apagan nada en el servidor) y toda la API nueva /api/v1/*.
 *   - «/» sin la interfaz compilada (frontend/dist): un aviso, en lugar de
 *     mandar a /clasico.
 *
 * Fuera del modo servidor no hace nada.
 */
@Component
public class FiltroModoServidor extends OncePerRequestFilter {

    /** Rutas de la API antigua que la interfaz nueva sigue usando. */
    static final Set<String> API_ANTIGUA_PERMITIDA = Set.of("/api/heartbeat", "/api/shutdown");

    private final boolean activo;
    private final boolean hayInterfaz;

    @Autowired
    public FiltroModoServidor(PropiedadesPlanner propiedades) {
        this(propiedades.servidor().activo(), new ClassPathResource("static/index.html").exists());
    }

    FiltroModoServidor(boolean activo, boolean hayInterfaz) {
        this.activo = activo;
        this.hayInterfaz = hayInterfaz;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest peticion) {
        return !activo;
    }

    /** Qué se responde a una ruta en modo servidor: null = se atiende con normalidad. */
    static Bloqueo bloqueo(String ruta, boolean hayInterfaz) {
        if (ruta.equals("/clasico") || ruta.startsWith("/clasico/")) {
            return Bloqueo.CLASICA;
        }
        if (ruta.startsWith("/api/") && !ruta.startsWith("/api/v1/") && !API_ANTIGUA_PERMITIDA.contains(ruta)) {
            return Bloqueo.API_ANTIGUA;
        }
        if (!hayInterfaz && (ruta.equals("/") || ruta.equals("/index.html"))) {
            return Bloqueo.SIN_INTERFAZ;
        }
        return null;
    }

    enum Bloqueo { CLASICA, API_ANTIGUA, SIN_INTERFAZ }

    @Override
    protected void doFilterInternal(HttpServletRequest peticion, HttpServletResponse respuesta, FilterChain cadena)
            throws ServletException, IOException {
        String ruta = peticion.getRequestURI().substring(peticion.getContextPath().length());
        Bloqueo bloqueo = bloqueo(ruta, hayInterfaz);
        if (bloqueo == null) {
            cadena.doFilter(peticion, respuesta);
            return;
        }
        respuesta.setCharacterEncoding(StandardCharsets.UTF_8.name());
        switch (bloqueo) {
            case CLASICA -> {
                respuesta.setStatus(HttpServletResponse.SC_NOT_FOUND);
                respuesta.setContentType("text/html;charset=UTF-8");
                respuesta.getWriter().write(pagina("La versión clásica no está disponible en el servidor",
                        "Con varias personas a la vez no es segura (el último que guarda gana). Usa la interfaz normal.", true));
            }
            case API_ANTIGUA -> {
                respuesta.setStatus(HttpServletResponse.SC_NOT_FOUND);
                respuesta.setContentType("application/json;charset=UTF-8");
                respuesta.getWriter().write("{\"error\":\"no-disponible\",\"mensaje\":\"La API de la versión clásica no está disponible en el servidor.\"}");
            }
            case SIN_INTERFAZ -> {
                respuesta.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                respuesta.setContentType("text/html;charset=UTF-8");
                respuesta.getWriter().write(pagina("Falta la interfaz",
                        "Esta instalación no incluye la interfaz compilada (frontend/dist). Avisa a quien administra TestBenchPlanner.", false));
            }
        }
    }

    private static String pagina(String titulo, String texto, boolean enlaceAlInicio) {
        return "<!DOCTYPE html><html lang=\"es\"><head><meta charset=\"utf-8\"><title>TestBenchPlanner</title></head>"
                + "<body style=\"font-family:Segoe UI,Arial,sans-serif;background:#0f1720;color:#e6edf3;padding:40px\">"
                + "<h2>" + titulo + "</h2><p>" + texto + "</p>"
                + (enlaceAlInicio ? "<p><a style=\"color:#7cc4ff\" href=\"/\">Ir a TestBenchPlanner</a></p>" : "") + "</body></html>";
    }
}
