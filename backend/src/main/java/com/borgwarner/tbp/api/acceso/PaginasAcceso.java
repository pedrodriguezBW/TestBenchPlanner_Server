package com.borgwarner.tbp.api.acceso;

/**
 * Páginas del inicio de sesión (HTML sencillo, sin depender de la interfaz
 * React compilada): entrar y cambiar la contraseña.
 */
final class PaginasAcceso {

    private PaginasAcceso() {
    }

    static String escapar(String texto) {
        if (texto == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(texto.length());
        for (char c : texto.toCharArray()) {
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String marco(String titulo, String cuerpo) {
        return "<!DOCTYPE html><html lang=\"es\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>TestBenchPlanner · " + escapar(titulo) + "</title><style>"
                + "body{margin:0;font-family:'Segoe UI',Arial,sans-serif;background:#0f1720;color:#e6edf3;"
                + "display:flex;min-height:100vh;align-items:center;justify-content:center}"
                + ".caja{background:#16212c;border:1px solid #2a3a4a;border-radius:10px;padding:28px 32px;width:360px;"
                + "box-shadow:0 8px 30px rgba(0,0,0,.35)}"
                + "h1{font-size:20px;margin:0 0 4px}h2{font-size:14px;font-weight:400;color:#9fb1c2;margin:0 0 20px}"
                + "label{display:block;font-size:13px;color:#9fb1c2;margin:14px 0 4px}"
                + "input{width:100%;box-sizing:border-box;padding:9px 10px;border-radius:6px;border:1px solid #34485c;"
                + "background:#0f1720;color:#e6edf3;font-size:14px}"
                + "input:focus{outline:2px solid #3b82f6;border-color:#3b82f6}"
                + "button{margin-top:20px;width:100%;padding:10px;border:0;border-radius:6px;background:#2563eb;color:#fff;"
                + "font-size:15px;font-weight:600;cursor:pointer}button:hover{background:#1d4ed8}"
                + ".aviso{padding:9px 11px;border-radius:6px;font-size:13px;margin-bottom:6px}"
                + ".error{background:#3b1d22;border:1px solid #7f2d37;color:#ffc9cf}"
                + ".info{background:#172a3d;border:1px solid #2d5277;color:#cfe5ff}"
                + ".nota{font-size:12px;color:#8193a5;margin-top:16px;line-height:1.45}"
                + "a{color:#7cc4ff}img{height:26px;vertical-align:middle;margin-right:8px}"
                + "</style></head><body><div class=\"caja\">" + cuerpo + "</div></body></html>";
    }

    private static String aviso(String clase, String texto) {
        return texto == null || texto.isEmpty() ? "" : "<div class=\"aviso " + clase + "\">" + escapar(texto) + "</div>";
    }

    static String entrar(String error, String info) {
        return marco("Entrar",
                "<h1><img src=\"/logo.png\" alt=\"\" onerror=\"this.remove()\">TestBenchPlanner</h1>"
                        + "<h2>Inicia sesión con tu cuenta de la aplicación</h2>"
                        + aviso("error", error) + aviso("info", info)
                        + "<form method=\"post\" action=\"/login\" autocomplete=\"on\">"
                        + "<label for=\"usuario\">Email</label>"
                        + "<input id=\"usuario\" name=\"usuario\" type=\"email\" autocomplete=\"username\" required autofocus>"
                        + "<label for=\"clave\">Contraseña</label>"
                        + "<input id=\"clave\" name=\"clave\" type=\"password\" autocomplete=\"current-password\" required>"
                        + "<button type=\"submit\">Entrar</button></form>"
                        + "<div class=\"nota\">Es una contraseña de TestBenchPlanner, no la de Windows. "
                        + "¿Primer acceso o la has olvidado? Pide a un Admin una contraseña temporal "
                        + "(Configuration › Users/Rights Management).</div>");
    }

    static String cambiar(String nombre, boolean obligatorio, int longitudMinima, String error) {
        String explicacion = obligatorio
                ? "Has entrado con una contraseña temporal. Elige ahora tu contraseña personal para seguir."
                : "Escribe tu contraseña actual y la nueva.";
        return marco("Cambiar contraseña",
                "<h1>Cambiar contraseña</h1><h2>" + escapar(nombre) + "</h2>"
                        + aviso(obligatorio ? "info" : "", obligatorio ? explicacion : "")
                        + aviso("error", error)
                        + "<form method=\"post\" action=\"/cambiar-clave\">"
                        + "<label for=\"actual\">Contraseña actual" + (obligatorio ? " (la temporal)" : "") + "</label>"
                        + "<input id=\"actual\" name=\"actual\" type=\"password\" autocomplete=\"current-password\" required autofocus>"
                        + "<label for=\"nueva\">Contraseña nueva</label>"
                        + "<input id=\"nueva\" name=\"nueva\" type=\"password\" autocomplete=\"new-password\" required minlength=\""
                        + longitudMinima + "\">"
                        + "<label for=\"repetida\">Repite la contraseña nueva</label>"
                        + "<input id=\"repetida\" name=\"repetida\" type=\"password\" autocomplete=\"new-password\" required minlength=\""
                        + longitudMinima + "\">"
                        + "<button type=\"submit\">Guardar contraseña</button></form>"
                        + "<div class=\"nota\">Al menos " + longitudMinima + " caracteres y distinta de tu email. "
                        + "Al cambiarla se cierran tus otras sesiones abiertas."
                        + (obligatorio ? "" : " <a href=\"/\">Volver sin cambiarla</a>")
                        + "</div><form method=\"post\" action=\"/logout\" style=\"margin-top:6px\">"
                        + "<button type=\"submit\" style=\"background:transparent;color:#9fb1c2;margin-top:6px;font-weight:400\">"
                        + "Cerrar sesión</button></form>");
    }
}
