package com.borgwarner.tbp.postgresql;

import com.borgwarner.tbp.TestBenchPlannerApplication;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Paridad de punta a punta: la MISMA aplicación arrancada dos veces sobre los
 * mismos datos, una con el almacén local (= lo que había en SharePoint al
 * exportar) y otra con PostgreSQL (= lo importado). Todas las lecturas de la
 * API deben dar lo mismo, antes y después de la misma serie de escrituras.
 *
 * Los datos son el escenario anonimizado con la forma exacta de una
 * exportación de SharePoint (configuracion.json con projectOrders, areaNames,
 * equipmentType y area). Las marcas de tiempo (aaaa-mm-ddThh:mm…) se comparan
 * enmascaradas: las dos aplicaciones no escriben en el mismo segundo. El área
 * «THERMAL» guardada cuenta como vacía (ver sinInstantes).
 */
class ParidadApiPostgresTest {

    private static final Pattern INSTANTE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:?\\d{2})?");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static ConfigurableApplicationContext local;
    private static ConfigurableApplicationContext postgres;
    private static String urlLocal;
    private static String urlPostgres;

    /** Lecturas que se comparan (método, ruta). */
    private static final List<String[]> LECTURAS = List.of(
            new String[]{"GET", "/api/v1/sesion"},
            new String[]{"GET", "/api/v1/planificacion"},
            new String[]{"GET", "/api/v1/catalogos"},
            new String[]{"GET", "/api/v1/usuarios"},
            new String[]{"GET", "/api/v1/recursos"},
            new String[]{"GET", "/api/v1/aprobaciones"},
            new String[]{"GET", "/api/v1/version"},
            new String[]{"GET", "/api/v1/actividad"},
            new String[]{"GET", "/api/v1/indicadores/bloques?lunes=2026-11-02"},
            new String[]{"GET", "/api/v1/indicadores/bloques?lunes=2026-11-02&org=BW"},
            new String[]{"GET", "/api/v1/indicadores/tabla-ocupacion?desde=2026-10-01&hasta=2026-12-31"},
            new String[]{"GET", "/api/v1/indicadores/tendencia"},
            new String[]{"GET", "/api/v1/indicadores/ocupacion?desde=2026-01-01&hasta=2026-12-31"},
            new String[]{"GET", "/api/v1/indicadores/historico"},
            new String[]{"GET", "/api/v1/indicadores/estadisticas"},
            new String[]{"GET", "/api/v1/facturacion/tipos-equipo"},
            new String[]{"GET", "/api/v1/facturacion/hoja?mes=2026-10"},
            new String[]{"GET", "/api/v1/exportaciones/copia"},
            new String[]{"POST", "/api/load"},
            new String[]{"POST", "/api/load_config"},
            new String[]{"POST", "/api/load_kpis"},
            new String[]{"POST", "/api/load_resources"},
            new String[]{"POST", "/api/approvals_all"});

    @BeforeAll
    static void arrancar() throws IOException {
        Path tmp = Files.createTempDirectory("tbp-paridad");
        Path escenario = prepararEscenario(tmp.resolve("escenarios").resolve("exportacion-sharepoint"));
        Map<String, Object> comunes = new HashMap<>();
        comunes.put("server.port", "0");
        comunes.put("server.address", "127.0.0.1");
        comunes.put("planner.fecha-fija", "2026-11-05");
        comunes.put("planner.local.escenario-inicial", escenario.toString());
        comunes.put("planner.local.reiniciar-al-arrancar", "true");
        comunes.put("planner.local.usuario-email", "usuario01@ejemplo.local");

        Map<String, Object> pLocal = new HashMap<>(comunes);
        pLocal.put("planner.almacen", "local");
        pLocal.put("planner.carpeta-usuario", tmp.resolve("pc-local").toString());
        pLocal.put("planner.local.carpeta-datos", tmp.resolve("datos-local").toString());
        local = new SpringApplicationBuilder(TestBenchPlannerApplication.class).run(argumentos(pLocal));
        urlLocal = "http://127.0.0.1:" + local.getEnvironment().getProperty("local.server.port");

        PostgresPruebas.Base base = PostgresPruebas.nuevaBase();
        Map<String, Object> pPg = new HashMap<>(comunes);
        pPg.put("planner.almacen", "postgresql");
        pPg.put("planner.carpeta-usuario", tmp.resolve("pc-postgres").toString());
        pPg.put("planner.postgresql.url", base.url());
        pPg.put("planner.postgresql.clave-app", PostgresPruebas.CLAVE);
        pPg.put("planner.postgresql.clave-propietario", PostgresPruebas.CLAVE);
        pPg.put("planner.postgresql.reinicio-pruebas", "true");
        postgres = new SpringApplicationBuilder(TestBenchPlannerApplication.class).run(argumentos(pPg));
        urlPostgres = "http://127.0.0.1:" + postgres.getEnvironment().getProperty("local.server.port");
    }

    /** Como argumentos de la línea de órdenes: mandan sobre application.yml (las «default properties» no). */
    private static String[] argumentos(Map<String, Object> propiedades) {
        return propiedades.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new);
    }

    @AfterAll
    static void parar() {
        if (local != null) {
            local.close();
        }
        if (postgres != null) {
            postgres.close();
        }
    }

    /** Copia del escenario con la forma de una exportación de SharePoint. */
    private static Path prepararEscenario(Path destino) throws IOException {
        Files.createDirectories(destino);
        Path origen = PostgresPruebas.escenario();
        try (var ficheros = Files.list(origen)) {
            for (Path f : ficheros.toList()) {
                Files.copy(f, destino.resolve(f.getFileName()));
            }
        }
        ObjectNode cfg = (ObjectNode) JsonJs.mapper().readTree(destino.resolve("configuracion.json").toFile());
        if (!cfg.has("projectOrders")) {
            cfg.putObject("projectOrders");
        }
        if (!cfg.has("areaNames")) {
            cfg.putObject("areaNames");
        }
        cfg.get("benches").forEach(b -> {
            ObjectNode o = (ObjectNode) b;
            if (!o.has("equipmentType")) {
                o.put("equipmentType", "");
            }
            if (!o.has("area")) {
                o.put("area", "");
            }
        });
        Files.writeString(destino.resolve("configuracion.json"), JsonJs.mapper().writeValueAsString(cfg));
        return destino;
    }

    private record Respuesta(int estado, JsonNode cuerpo) {
    }

    private static Respuesta pedir(String base, String metodo, String ruta, JsonNode cuerpo) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + ruta)).header("Content-Type", "application/json")
                    .header("X-TBP-Pestana", "paridad");
            HttpRequest.BodyPublisher datos = HttpRequest.BodyPublishers.ofString(cuerpo == null ? "{}" : JsonJs.escribir(cuerpo));
            b = switch (metodo) {
                case "GET" -> b.GET();
                case "PUT" -> b.PUT(datos);
                default -> b.POST(datos);
            };
            HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode n = r.body() == null || r.body().isBlank() ? TextNode.valueOf("") : JsonJs.leer(r.body());
            return new Respuesta(r.statusCode(), n == null ? TextNode.valueOf(r.body()) : n);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Las marcas de tiempo, enmascaradas. */
    private static JsonNode sinInstantes(JsonNode n) {
        if (n == null) {
            return null;
        }
        if (n.isTextual()) {
            return TextNode.valueOf(INSTANTE.matcher(n.textValue()).replaceAll("<instante>"));
        }
        if (n.isArray()) {
            ArrayNode a = JsonJs.mapper().createArrayNode();
            n.forEach(x -> a.add(sinInstantes(x)));
            return a;
        }
        if (n.isObject()) {
            ObjectNode o = JsonJs.objetoVacio();
            n.fields().forEachRemaining(e -> {
                // Área de un banco guardada como Thermal: el modo local guarda el código «THERMAL» y
                // SharePoint y PostgreSQL la dejan vacía (columna Area vacía = Thermal Benches). Es lo mismo.
                boolean termico = e.getKey().equals("area") && e.getValue().isTextual() && e.getValue().textValue().equals("THERMAL");
                o.set(e.getKey(), termico ? TextNode.valueOf("") : sinInstantes(e.getValue()));
            });
            return o;
        }
        return n;
    }

    /** Hace lo mismo en las dos aplicaciones y comprueba que responden igual. */
    private static Respuesta enLasDos(String metodo, String ruta, JsonNode cuerpo) {
        Respuesta a = pedir(urlLocal, metodo, ruta, cuerpo);
        Respuesta b = pedir(urlPostgres, metodo, ruta, cuerpo);
        assertEquals(a.estado(), b.estado(), metodo + " " + ruta + ": estado distinto");
        JsonNode x = sinInstantes(a.cuerpo());
        JsonNode y = sinInstantes(b.cuerpo());
        if (!x.equals(y)) {
            String sx = JsonJs.escribir(x), sy = JsonJs.escribir(y);
            int i = 0;
            while (i < Math.min(sx.length(), sy.length()) && sx.charAt(i) == sy.charAt(i)) {
                i++;
            }
            int desde = Math.max(0, i - 200);
            throw new AssertionError(metodo + " " + ruta + ": respuestas distintas a partir de la posición " + i
                    + "\n local:      …" + sx.substring(desde, Math.min(sx.length(), i + 200))
                    + "\n postgresql: …" + sy.substring(desde, Math.min(sy.length(), i + 200)));
        }
        return a;
    }

    /**
     * Igual que enLasDos pero comparando solo el estado y «ok»: al guardar listas, el
     * almacén local responde {ok:true} y SharePoint/PostgreSQL añaden recuentos.
     */
    private static Respuesta enLasDosEstado(String metodo, String ruta, JsonNode cuerpo) {
        Respuesta a = pedir(urlLocal, metodo, ruta, cuerpo);
        Respuesta b = pedir(urlPostgres, metodo, ruta, cuerpo);
        assertEquals(a.estado(), b.estado(), metodo + " " + ruta + ": estado distinto");
        assertEquals(a.cuerpo().path("ok").asBoolean(), b.cuerpo().path("ok").asBoolean(), metodo + " " + ruta + ": ok distinto");
        return a;
    }

    private static void compararLecturas() {
        List<String> fallos = new ArrayList<>();
        for (String[] l : LECTURAS) {
            try {
                enLasDos(l[0], l[1], null);
            } catch (AssertionError e) {
                fallos.add(e.getMessage());
            }
        }
        assertTrue(fallos.isEmpty(), String.join("\n\n", fallos));
    }

    @Test
    void mismasRespuestasAntesYDespuesDeLasMismasEscrituras() {
        compararLecturas();

        // 1) Editar un campo de un ensayo con su versión.
        JsonNode md2 = enLasDos("GET", "/api/v1/planificacion", null).cuerpo().path("bancos").path("MD2");
        String id = md2.get(1).path("id").asText();
        ObjectNode cambio = JsonJs.objetoVacio().put("version", md2.get(1).path("version").asText());
        cambio.putArray("cambios").addObject().put("campo", "comments").put("valor", "Paridad PostgreSQL");
        assertEquals(200, enLasDos("POST", "/api/v1/bancos/MD2/ensayos/" + id + "/campos", cambio).estado());
        // … y el conflicto: otro valor con la versión antigua (repetir el mismo valor
        // no cambia nada y, como en SharePoint, no se comprueba la versión).
        ObjectNode antiguo = JsonJs.objetoVacio().put("version", md2.get(1).path("version").asText());
        antiguo.putArray("cambios").addObject().put("campo", "comments").put("valor", "Paridad PostgreSQL (otra persona)");
        assertEquals(409, enLasDos("POST", "/api/v1/bancos/MD2/ensayos/" + id + "/campos", antiguo).estado());

        // 2) Alta de un ensayo con el id que enseña la interfaz.
        ObjectNode alta = JsonJs.objetoVacio().put("tipo", "anadir");
        alta.putArray("idsPropuestos").add("rparida1"); // «r» + 7: si no, cada almacén genera uno aleatorio distinto
        assertEquals(200, enLasDos("POST", "/api/v1/bancos/MD2/operaciones", alta).estado());

        // 3) Catálogos por la API clásica (leer y guardar con un proyecto más).
        ObjectNode cfg = (ObjectNode) enLasDos("POST", "/api/load_config", null).cuerpo();
        ((ArrayNode) cfg.get("projects")).add("99999 - Proyecto de paridad");
        assertEquals(200, enLasDosEstado("POST", "/api/save_config", cfg).estado());

        // 4) Bloqueo de un equipo auxiliar.
        JsonNode recursos = enLasDos("GET", "/api/v1/recursos", null).cuerpo();
        ObjectNode bloqueo = JsonJs.objetoVacio().put("title", "BLK-paridad")
                .put("resource", recursos.path("resources").get(0).path("name").asText())
                .put("start", "2026-11-09").put("end", "2026-11-13").put("reason", "Mantenimiento").put("note", "paridad");
        assertEquals(200, enLasDos("POST", "/api/v1/recursos/bloqueos", bloqueo).estado());

        // 5) Foto semanal de KPI.
        assertEquals(200, enLasDos("POST", "/api/v1/indicadores/foto-semanal", null).estado());

        // 6) Usuarios (whoami antes, como la interfaz).
        enLasDos("POST", "/api/whoami", null);
        JsonNode lista = enLasDos("GET", "/api/v1/usuarios", null).cuerpo();
        ObjectNode guardar = JsonJs.objetoVacio();
        ArrayNode users = guardar.putArray("users");
        lista.forEach(u -> users.add((JsonNode) u.deepCopy()));
        users.addObject().put("title", "Persona Paridad").put("email", "paridad@ejemplo.local").put("role", "editor")
                .put("responsibleName", "");
        assertEquals(200, enLasDosEstado("POST", "/api/save_users", guardar).estado());

        compararLecturas();
    }
}
