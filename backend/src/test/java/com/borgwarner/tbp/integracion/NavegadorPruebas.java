package com.borgwarner.tbp.integracion;

import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Un navegador mínimo para las pruebas del inicio de sesión: guarda sus
 * cookies (sesión), no sigue redirecciones (para comprobar adónde manda) y
 * envía Origin como lo haría el navegador desde la propia aplicación.
 */
final class NavegadorPruebas {

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final String base;
    private final Map<String, String> cookies = new LinkedHashMap<>();

    NavegadorPruebas(int puerto) {
        this.base = "http://127.0.0.1:" + puerto;
    }

    String origen() {
        return base;
    }

    HttpResponse<String> get(String ruta, String... cabeceras) {
        return enviar(peticion(ruta, cabeceras).GET());
    }

    HttpResponse<String> json(String metodo, String ruta, Object cuerpo, String... cabeceras) {
        String texto = cuerpo == null ? "{}" : cuerpo instanceof JsonNode n ? JsonJs.escribir(n) : cuerpo.toString();
        HttpRequest.Builder b = peticion(ruta, cabeceras).header("Content-Type", "application/json");
        boolean conOrigen = false;
        for (int i = 0; i + 1 < cabeceras.length; i += 2) {
            conOrigen |= "Origin".equalsIgnoreCase(cabeceras[i]);
        }
        if (!conOrigen) {
            b.header("Origin", base); // como el navegador desde la propia aplicación
        }
        return enviar(b.method(metodo, HttpRequest.BodyPublishers.ofString(texto)));
    }

    HttpResponse<String> formulario(String ruta, Map<String, String> campos) {
        String cuerpo = campos.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return enviar(peticion(ruta).header("Content-Type", "application/x-www-form-urlencoded").header("Origin", base)
                .POST(HttpRequest.BodyPublishers.ofString(cuerpo)));
    }

    HttpResponse<String> xlsx(String ruta, byte[] bytes) {
        String separador = "tbp-timing-pruebas";
        String inicio = "--" + separador + "\r\nContent-Disposition: form-data; name=\"archivo\"; filename=\"tareas.xlsx\"\r\n\r\n";
        String fin = "\r\n--" + separador + "--\r\n";
        HttpRequest.BodyPublisher contenido = HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofString(inicio),
                HttpRequest.BodyPublishers.ofByteArray(bytes), HttpRequest.BodyPublishers.ofString(fin));
        return enviar(peticion(ruta).header("Content-Type", "multipart/form-data; boundary=" + separador)
                .header("Origin", base).POST(contenido));
    }

    HttpResponse<String> entrar(String email, String clave) {
        return formulario("/login", Map.of("usuario", email, "clave", clave));
    }

    HttpResponse<String> salir() {
        return formulario("/logout", Map.of());
    }

    JsonNode leer(String ruta) {
        HttpResponse<String> r = get(ruta);
        if (r.statusCode() != 200) {
            throw new AssertionError("GET " + ruta + " → " + r.statusCode() + " " + r.body());
        }
        return JsonJs.leer(r.body());
    }

    /** Ruta (con consulta) a la que redirige una respuesta 302, sin el servidor. */
    static String destino(HttpResponse<String> r) {
        String lugar = r.headers().firstValue("Location").orElse("");
        return lugar.replaceFirst("^https?://[^/]+", "");
    }

    private HttpRequest.Builder peticion(String ruta, String... cabeceras) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + ruta));
        if (!cookies.isEmpty()) {
            b.header("Cookie", cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("; ")));
        }
        for (int i = 0; i + 1 < cabeceras.length; i += 2) {
            b.header(cabeceras[i], cabeceras[i + 1]);
        }
        return b;
    }

    private HttpResponse<String> enviar(HttpRequest.Builder b) {
        try {
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            for (String cookie : r.headers().allValues("Set-Cookie")) {
                String par = cookie.split(";", 2)[0];
                int igual = par.indexOf('=');
                if (igual > 0) {
                    String nombre = par.substring(0, igual).trim();
                    String valor = par.substring(igual + 1).trim();
                    if (valor.isEmpty() || cookie.toLowerCase().contains("max-age=0")) {
                        cookies.remove(nombre);
                    } else {
                        cookies.put(nombre, valor);
                    }
                }
            }
            return r;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
