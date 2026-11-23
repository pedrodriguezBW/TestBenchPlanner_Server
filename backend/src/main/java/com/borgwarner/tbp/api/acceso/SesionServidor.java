package com.borgwarner.tbp.api.acceso;

import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Atributos de la sesión del modo servidor y respuestas comunes del inicio de sesión. */
public final class SesionServidor {

    /** Sello de la contraseña con la que se entró: si cambia, la sesión termina. */
    public static final String SELLO = "tbp.sello";
    /** La persona entró con una contraseña temporal: tiene que cambiarla antes de seguir. */
    public static final String DEBE_CAMBIAR = "tbp.debeCambiar";
    /** Última vez (ms) que la persona tocó la aplicación (no cuentan los sondeos automáticos). */
    public static final String ULTIMA_ACTIVIDAD = "tbp.ultimaActividad";
    /** Cabecera de la interfaz: segundos desde el último clic o tecla en esa pestaña. */
    public static final String CABECERA_INACTIVO = "X-TBP-Inactivo";

    private SesionServidor() {
    }

    public static String ruta(HttpServletRequest peticion) {
        String uri = peticion.getRequestURI();
        String contexto = peticion.getContextPath();
        return contexto != null && !contexto.isEmpty() && uri.startsWith(contexto) ? uri.substring(contexto.length()) : uri;
    }

    public static boolean esApi(HttpServletRequest peticion) {
        return ruta(peticion).startsWith("/api/");
    }

    public static void atributo(HttpSession sesion, String nombre, Object valor) {
        if (sesion != null) {
            sesion.setAttribute(nombre, valor);
        }
    }

    /** Respuesta JSON de error con el formato del resto de la API ({error, mensaje}). */
    public static void json(HttpServletResponse respuesta, int estado, String error, String mensaje) throws IOException {
        json(respuesta, estado, error, mensaje, null);
    }

    /** motivo (sesión terminada): «inactividad» o «caducada», para que la interfaz lo enseñe en el formulario. */
    public static void json(HttpServletResponse respuesta, int estado, String error, String mensaje, String motivo) throws IOException {
        respuesta.setStatus(estado);
        respuesta.setCharacterEncoding(StandardCharsets.UTF_8.name());
        respuesta.setContentType("application/json;charset=UTF-8");
        ObjectNode cuerpo = JsonJs.objetoVacio().put("error", error).put("mensaje", mensaje);
        if (motivo != null) {
            cuerpo.put("motivo", motivo);
        }
        respuesta.getWriter().write(JsonJs.escribir(cuerpo));
    }
}
