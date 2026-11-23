
package com.borgwarner.tbp.integracion;

import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.usuarios.Usuario;
import com.borgwarner.tbp.infraestructura.postgresql.BaseDatos;
import com.borgwarner.tbp.infraestructura.postgresql.CuentasPostgres;
import com.borgwarner.tbp.infraestructura.postgresql.ImportadorTiming;
import com.borgwarner.tbp.infraestructura.postgresql.TimingPostgres;
import com.borgwarner.tbp.postgresql.PostgresPruebas;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TimingPostgresTest {

    private static final String ADMIN = "admin-timing@ejemplo.local";
    private static final String SIN_PERFIL = "sin-timing@ejemplo.local";
    private static final String SOLICITANTE = "solicitante@ejemplo.local";
    private static final String TECNICO = "tecnico@ejemplo.local";
    private static final String OTRO = "otro-tecnico@ejemplo.local";
    private static final String CLAVE = "Clave-de-prueba-1";
    private static final String API = "/api/v1/timing";

    @LocalServerPort int puerto;
    @Autowired BaseDatos bd;
    @Autowired CuentasPostgres cuentas;
    @Autowired TimingPostgres timing;
    @Autowired ImportadorTiming importador;
    private long proyecto;

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) throws IOException {
        PostgresPruebas.registrar(registro);
        Path carpeta = Files.createTempDirectory("tbp-timing");
        registro.add("planner.carpeta-usuario", carpeta::toString);
        registro.add("planner.servidor.activo", () -> "true");
        registro.add("planner.postgresql.reinicio-pruebas", () -> "false");
        registro.add("planner.fecha-fija", () -> "2026-11-23");
        registro.add("spring.servlet.multipart.max-file-size", () -> "12MB");
        registro.add("spring.servlet.multipart.max-request-size", () -> "13MB");
    }

    @BeforeAll
    void preparar() {
        cuentas.establecerAdmin(ADMIN, "Coordinador de prueba", CLAVE);
        cuentas.establecerAdmin(SIN_PERFIL, "Admin sin Timing", CLAVE);
        cuentas.establecerAdmin(SOLICITANTE, "Responsable de prueba", CLAVE);
        cuentas.establecerAdmin(TECNICO, "Técnico de prueba", CLAVE);
        cuentas.establecerAdmin(OTRO, "Otro técnico", CLAVE);
        bd.jdbc().update("UPDATE usuario SET rol = 'ReadOnly' WHERE email IN (?, ?, ?)", SOLICITANTE, TECNICO, OTRO);
        bd.jdbc().update("INSERT INTO timing_perfil (email, perfil) VALUES (?, 'coordinador'), (?, 'solicitante'),"
                + " (?, 'tecnico'), (?, 'tecnico')", ADMIN, SOLICITANTE, TECNICO, OTRO);
        proyecto = bd.jdbc().queryForObject("INSERT INTO proyecto (titulo) VALUES ('00042 - Proyecto de prueba') RETURNING id", Long.class);
    }

    @Test
    void creaPlanificaRegistraCierraYReabreConPermisosIndependientes() {
        var sin = entrar(SIN_PERFIL);
        assertTrue(sin.leer("/api/v1/sesion").path("timingRoles").isEmpty());
        estado(403, sin.get(API + "/opciones"));
        var admin = entrar(ADMIN);
        var responsable = entrar(SOLICITANTE);
        var tecnico = entrar(TECNICO);
        var otro = entrar(OTRO);
        assertEquals("readonly", tecnico.leer("/api/v1/sesion").path("rol").asText());
        assertEquals("tecnico", tecnico.leer("/api/v1/sesion").path("timingRoles").get(0).asText());
        estado(403, tecnico.get(API + "/tareas?vista=planificacion"));
        assertEquals("00042", admin.leer("/api/v1/catalogos").path("projectNumbers").path("00042 - Proyecto de prueba").asText());
        estado(200, admin.json("PUT", API + "/equipos", objeto().put("clase", "camara").put("nombre", "Cámara de prueba")));

        JsonNode tarea = hecho(responsable.json("POST", API + "/tareas", "{}"));
        long id = tarea.path("id").asLong();
        estado(422, operacion(responsable, tarea, "disponibilidad", objeto().put("disponible", true)));
        estado(404, otro.get(API + "/tareas/" + id));
        ObjectNode trabajo = objeto().put("tipo", "HEAT").put("descripcion", "Montaje de muestra").put("proyectoId", proyecto)
                .put("horasEstimadas", 2).put("banco", "Cámara Climática").put("fechaEnsayo", "2026-11-25");
        tarea = hecho(operacion(responsable, tarea, "editar", trabajo));
        estado(422, operacion(responsable, tarea, "disponibilidad", objeto().put("disponible", true)));
        tarea = hecho(operacion(responsable, tarea, "editar", objeto().put("camara", "Cámara de prueba")));
        JsonNode antes = tarea;
        tarea = hecho(operacion(responsable, tarea, "disponibilidad", objeto().put("disponible", true)));
        estado(409, operacion(responsable, antes, "editar", objeto().put("descripcion", "Versión desfasada")));
        estado(403, operacion(responsable, tarea, "planificar", objeto().put("tecnico", TECNICO)));
        tarea = hecho(operacion(admin, tarea, "planificar", objeto().put("tecnico", TECNICO).put("diaTrabajo", "2026-11-23")
                .put("prioridad", -0.5).put("ordenDiario", 0.3)));
        JsonNode cola = tecnico.leer(API + "/tareas?vista=cola");
        JsonNode fila = java.util.stream.StreamSupport.stream(cola.spliterator(), false)
                .filter(t -> t.path("id").asLong() == id).findFirst().orElseThrow();
        assertEquals(id, fila.path("id").asLong());
        assertEquals(-0.5, fila.path("prioridad").asDouble());
        assertEquals("2026-11-25", fila.path("fechaEnsayo").asText());
        estado(404, operacion(otro, tarea, "iniciar", objeto()));

        tarea = hecho(operacion(tecnico, tarea, "iniciar", objeto()));
        tarea = hecho(operacion(tecnico, tarea, "avance", objeto().put("fecha", "2026-11-23")
                .put("horas", 1.5).put("pendientes", 0.5).put("comentario", "Montada")));
        tarea = hecho(operacion(admin, tarea, "planificar", objeto().put("tecnico", OTRO)));
        estado(404, operacion(tecnico, tarea, "cerrar", objeto()));
        estado(422, operacion(otro, tarea, "cerrar", objeto().put("horasReales", 1.75)));
        tarea = hecho(operacion(otro, tarea, "cerrar", objeto().put("horasReales", 1.75).put("motivo", "Verificación")
                .put("comentario", "Cierre comprobado")));
        assertEquals("cerrada", tarea.path("estado").asText());
        assertEquals(1.75, tarea.path("horasReales").asDouble());
        assertEquals(1, otro.leer(API + "/tareas/" + id).path("avances").size());
        assertEquals(id, otro.leer(API + "/tareas?vista=historico").get(0).path("id").asLong());

        hecho(admin.json("PUT", "/api/v1/proyectos/numero", objeto().put("proyecto", "00042 - Proyecto de prueba")
                .put("anterior", "00042").put("numero", "00043")));
        assertEquals("00042", admin.leer(API + "/tareas/" + id).path("cuenta").asText());
        // Restituir el número para las demás pruebas de esta base de datos.
        hecho(admin.json("PUT", "/api/v1/proyectos/numero", objeto().put("proyecto", "00042 - Proyecto de prueba")
                .put("anterior", "00043").put("numero", "00042")));

        tarea = hecho(operacion(admin, tarea, "reabrir", objeto().put("motivo", "Repetir la verificación")));
        assertFalse(tarea.path("disponible").asBoolean());
        var detalle = admin.leer(API + "/tareas/" + id);
        assertEquals(1.5, detalle.path("horasRegistradas").asDouble());
        assertTrue(detalle.path("historial").toString().contains("Cierre comprobado"));
        var copia = hecho(operacion(responsable, tarea, "duplicar", objeto()));
        assertNotEquals(id, copia.path("id").asLong());
        assertTrue(copia.path("tecnico").asText().isBlank());
        hecho(operacion(responsable, copia, "cancelar", objeto()));
    }

    @Test
    void importaYRepitePorIdDeOrigenConCuentasConciliadas() throws IOException {
        var admin = entrar(ADMIN);
        var tecnico = entrar(TECNICO);
        byte[] xlsx = libro(List.of(
                List.of("910001", "TEST-DUP", "Sí", "Proyecto de prueba", "00042", "TEST", "Prueba histórica", "Responsable de prueba", "Técnico de prueba", "2", "3.5"),
                List.of("910002", "TEST-DUP", "Terminado", "Proyecto de prueba", "00042", "TEST", "Otra tarea", "Responsable de prueba", "Técnico de prueba", "2", "4")));
        estado(403, tecnico.xlsx(API + "/importacion", xlsx));
        var simular = hecho(admin.xlsx(API + "/importacion", xlsx));
        assertEquals(2, simular.path("altas").asInt());
        assertEquals(0, bd.jdbc().queryForObject("SELECT count(*) FROM timing_tarea WHERE origen_id IN (910001, 910002)", Integer.class));
        var importe = hecho(admin.xlsx(API + "/importacion?simular=false", xlsx));
        assertEquals(2, importe.path("altas").asInt());
        assertEquals(1, importe.path("cerradas").asInt());
        assertEquals(2, hecho(admin.xlsx(API + "/importacion?simular=false", xlsx)).path("sinCambios").asInt());
        long activa = bd.jdbc().queryForObject("SELECT id FROM timing_tarea WHERE origen_id = 910001", Long.class);
        long cerrada = bd.jdbc().queryForObject("SELECT id FROM timing_tarea WHERE origen_id = 910002", Long.class);
        var detalle = admin.leer(API + "/tareas/" + cerrada);
        assertEquals("4", detalle.path("legado").path("Horas_Totales").asText());
        assertFalse(detalle.has("horasReales"));
        var tarea = admin.leer(API + "/tareas/" + activa);
        assertTrue(tarea.path("disponible").asBoolean());
        hecho(operacion(admin, tarea, "planificar", objeto().put("prioridad", 22)));
        assertEquals(1, hecho(admin.xlsx(API + "/importacion?simular=false", xlsx)).path("conflictos").asInt());
        assertEquals(22, admin.leer(API + "/tareas/" + activa).path("prioridad").asInt());
    }

    @Test
    void administraPerfilesDesdeUsuariosYAplicaLaRevocacionEnLaSesion() {
        var admin = entrar(ADMIN);
        var ampliado = entrar(SIN_PERFIL);
        var usuarios = (com.fasterxml.jackson.databind.node.ArrayNode) admin.leer("/api/v1/usuarios");
        usuarios.forEach(n -> ((ObjectNode) n).remove("timingRoles"));
        ObjectNode usuario = null;
        for (JsonNode u : usuarios) {
            if (SIN_PERFIL.equals(u.path("email").asText())) usuario = (ObjectNode) u;
        }
        assertNotNull(usuario);
        usuario.putArray("timingRoles").add("solicitante").add("tecnico");
        hecho(admin.json("PUT", "/api/v1/usuarios", objeto().set("users", usuarios)));
        assertEquals(2, ampliado.leer("/api/v1/sesion").path("timingRoles").size());
        estado(200, ampliado.get(API + "/opciones"));
        usuario.putArray("timingRoles");
        hecho(admin.json("PUT", "/api/v1/usuarios", objeto().set("users", usuarios)));
        estado(403, ampliado.get(API + "/opciones"));
    }

    @Test
    @EnabledIfSystemProperty(named = "tbp.timing.excel", matches = ".+")
    void validaLaCargaYRepeticionDelExcelOriginal() throws IOException {
        var admin = entrar(ADMIN);
        byte[] excel = Files.readAllBytes(Path.of(System.getProperty("tbp.timing.excel")));
        var informe = hecho(admin.xlsx(API + "/importacion", excel));
        assertEquals(4692, informe.path("filas").asInt());
        assertEquals(4467, informe.path("cerradas").asInt());
        assertTrue(informe.path("avisos").toString().contains("Código de tarea repetido."));

        int totalAntes = bd.jdbc().queryForObject("SELECT count(*) FROM timing_tarea", Integer.class);
        var acceso = timing.acceso(new Usuario(ADMIN, "Coordinador de prueba", "", "admin"));
        class FinDePrueba extends RuntimeException {}
        assertThrows(FinDePrueba.class, () -> bd.enTransaccion(() -> {
            var carga = importador.importar(acceso, new ByteArrayInputStream(excel), false);
            assertEquals(4692, carga.path("altas").asInt());
            assertEquals(totalAntes + 4692, bd.jdbc().queryForObject("SELECT count(*) FROM timing_tarea", Integer.class));
            var repeticion = importador.importar(acceso, new ByteArrayInputStream(excel), false);
            assertEquals(4692, repeticion.path("sinCambios").asInt());
            throw new FinDePrueba();
        }));
        assertEquals(totalAntes, bd.jdbc().queryForObject("SELECT count(*) FROM timing_tarea", Integer.class));
    }

    @Test
    @EnabledIfSystemProperty(named = "tbp.timing.browser", matches = ".+")
    void cambiaDeAplicacionYAbreUnEnlaceDirectoEnElNavegador() {
        var solicitante = entrar(SOLICITANTE);
        var tarea = hecho(solicitante.json("POST", API + "/tareas", objeto().put("descripcion", "Inspección de muestra")
                .put("tipo", "HEAT").put("proyectoId", proyecto).put("horasEstimadas", 2)));
        String ruta = "#/timing/planificacion/tarea/" + tarea.path("id").asLong();
        String url = "http://127.0.0.1:" + puerto + "/";
        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions()
                .setEnv(java.util.Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
             Browser navegador = playwright.chromium().launch(new BrowserType.LaunchOptions()
                     .setChannel(System.getProperty("tbp.timing.browser")).setHeadless(true))) {
            Page pagina = navegador.newPage(new Browser.NewPageOptions().setViewportSize(1440, 920));
            pagina.navigate(url + ruta);
            pagina.locator("#usuario").fill(ADMIN);
            pagina.locator("#clave").fill(CLAVE);
            pagina.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Entrar")).click();
            var selector = pagina.locator(".apps-selector");
            assertThat(selector.getByText("TestBenchPlanner")).isVisible();
            assertThat(selector.getByText("Timing R&T")).hasAttribute("aria-current", "page");
            assertThat(pagina.locator(".timing-detalle h3")).hasText(tarea.path("codigo").asText());
            assertThat(pagina.locator(".timing-filtros-panel")).isHidden();
            pagina.locator(".timing-filtros-extra summary").click();
            assertThat(pagina.getByLabel("Disponibilidad")).isVisible();
            pagina.locator(".timing-filtros-extra summary").click();
            assertThat(pagina.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Cancelar tarea").setExact(true))).isHidden();
            pagina.getByLabel("Más acciones").click();
            assertThat(pagina.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Cancelar tarea").setExact(true))).isVisible();
            pagina.getByLabel("Más acciones").click();
            pagina.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/timing-escritorio.png")));
            selector.getByText("TestBenchPlanner").click();
            assertThat(pagina.locator("#side")).isVisible();
            assertThat(pagina.locator(".timing-menu")).hasCount(0);
            pagina.locator("#side").getByText("By Responsible").click();
            selector.getByText("Timing R&T").click();
            assertThat(pagina.locator(".timing-detalle h3")).hasText(tarea.path("codigo").asText());
            pagina.goBack();
            assertThat(pagina.locator("#side .active")).containsText("By Responsible");
            selector.getByText("Timing R&T").click();
            pagina.setViewportSize(680, 900);
            assertThat(selector.getByText("TestBenchPlanner")).isVisible();
            assertThat(selector.getByText("Timing R&T")).isVisible();
            assertThat(pagina.locator(".timing-menu")).isVisible();
            assertThat(pagina.locator(".timing-detalle h3")).hasText(tarea.path("codigo").asText());
            assertTrue(selector.boundingBox().y < pagina.locator("#cuenta-nombre").boundingBox().y);
            assertTrue(pagina.locator(".timing-detalle").boundingBox().y < pagina.locator(".timing-lista").boundingBox().y);
            pagina.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/timing-estrecho.png")));
        }
    }

    private NavegadorPruebas entrar(String email) {
        var n = new NavegadorPruebas(puerto);
        estado(302, n.entrar(email, CLAVE));
        return n;
    }

    private static ObjectNode objeto() {
        return JsonJs.objetoVacio();
    }

    private static HttpResponse<String> operacion(NavegadorPruebas n, JsonNode tarea, String accion, ObjectNode cuerpo) {
        return n.json("POST", API + "/tareas/" + tarea.path("id").asLong() + "/" + accion,
                cuerpo.put("version", tarea.path("version").asInt()));
    }

    private static JsonNode hecho(HttpResponse<String> r) {
        estado(200, r);
        return JsonJs.leer(r.body());
    }

    private static void estado(int esperado, HttpResponse<String> r) {
        assertEquals(esperado, r.statusCode(), r::body);
    }

    private static byte[] libro(List<List<String>> datos) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            entrada(zip, "xl/workbook.xml", """
                    <workbook xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                    <sheets><sheet name="query" r:id="r1"/></sheets></workbook>
                    """);
            entrada(zip, "xl/_rels/workbook.xml.rels", "<Relationships><Relationship Id=\"r1\" Target=\"worksheets/sheet1.xml\"/></Relationships>");
            StringBuilder hoja = new StringBuilder("<worksheet><sheetData>");
            var cabecera = List.of("ID", "ID Tarea", "Disp.", "Proyecto", "Cuenta Proyecto", "EGM/HEATERS",
                    "Tarea", "Responsable", "Asignado a", "Tiempo estimado de tarea (h)", "Horas_Totales");
            fila(hoja, 1, cabecera);
            for (int i = 0; i < datos.size(); i++) fila(hoja, i + 2, datos.get(i));
            entrada(zip, "xl/worksheets/sheet1.xml", hoja.append("</sheetData></worksheet>").toString());
        }
        return bytes.toByteArray();
    }

    private static void fila(StringBuilder xml, int fila, List<String> valores) {
        xml.append("<row>");
        for (int c = 0; c < valores.size(); c++) {
            xml.append("<c t=\"inlineStr\" r=\"").append((char) ('A' + c)).append(fila)
                    .append("\"><is><t>").append(valores.get(c)).append("</t></is></c>");
        }
        xml.append("</row>");
    }

    private static void entrada(ZipOutputStream zip, String nombre, String texto) throws IOException {
        zip.putNextEntry(new ZipEntry(nombre));
        zip.write(texto.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
