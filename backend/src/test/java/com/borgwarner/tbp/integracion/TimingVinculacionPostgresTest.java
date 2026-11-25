package com.borgwarner.tbp.integracion;

import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.infraestructura.postgresql.BaseDatos;
import com.borgwarner.tbp.infraestructura.postgresql.CuentasPostgres;
import com.borgwarner.tbp.postgresql.PostgresPruebas;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

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

/**
 * Vinculación automática y manual de las tareas importadas del Excel de Timing
 * R&T con los usuarios y proyectos dados de alta después de la importación.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TimingVinculacionPostgresTest {

    private static final String ADMIN = "coordina-vinculos@ejemplo.local";
    private static final String SOLICITANTE = "solicita-vinculos@ejemplo.local";
    private static final String LAURA = "laura.vinculos@ejemplo.local";
    private static final String JESUS = "jesus.vinculos@ejemplo.local";
    private static final String CLAVE = "Clave-de-prueba-1";
    private static final String API = "/api/v1/timing";

    @LocalServerPort int puerto;
    @Autowired BaseDatos bd;
    @Autowired CuentasPostgres cuentas;

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) throws IOException {
        PostgresPruebas.registrar(registro);
        Path carpeta = Files.createTempDirectory("tbp-timing-vinculos");
        registro.add("planner.carpeta-usuario", carpeta::toString);
        registro.add("planner.servidor.activo", () -> "true");
        registro.add("planner.postgresql.reinicio-pruebas", () -> "false");
        registro.add("planner.fecha-fija", () -> "2026-11-24");
    }

    @BeforeAll
    void preparar() {
        cuentas.establecerAdmin(ADMIN, "Coordinador de vínculos", CLAVE);
        cuentas.establecerAdmin(SOLICITANTE, "Solicitante de vínculos", CLAVE);
        bd.jdbc().update("UPDATE usuario SET rol = 'ReadOnly' WHERE email = ?", SOLICITANTE);
        bd.jdbc().update("INSERT INTO timing_perfil (email, perfil) VALUES (?, 'coordinador'), (?, 'solicitante')",
                ADMIN, SOLICITANTE);
        bd.jdbc().update("INSERT INTO proyecto (titulo) VALUES ('00771 - Catálogo de vínculos')");
    }

    @Test
    void vinculaPersonasYProyectosAlDarlosDeAltaSinConflictosAlReimportar() throws IOException {
        var admin = entrar(ADMIN);
        byte[] excel = libro(List.of(
                List.of("920001", "HEAT-920001", "Sí", "MEB21 Vinculos", "82771", "HEAT", "Fugado", "Laura Ézara",
                        "Jesus Ramos", "2", "0"),
                List.of("920002", "HEAT-920002", "Terminado", "Ford Vinculos", "80772", "HEAT", "Teardown", "laura ezara",
                        "", "3", "3"),
                List.of("920003", "TEST-920003", "No", "Proyecto Manual", "80773", "TEST", "Montaje", "Laura Ézara",
                        "Jesús Ramos", "1", "0")));
        var informe = hecho(admin.xlsx(API + "/importacion?simular=false", excel));
        assertEquals(3, informe.path("altas").asInt());
        long abierta = id(920001);
        long cerrada = id(920002);
        long manual = id(920003);
        assertEquals("", tarea(admin, abierta).path("responsable").asText());

        estado(403, entrar(SOLICITANTE).get(API + "/vinculacion"));
        JsonNode pendientes = hecho(admin.get(API + "/vinculacion"));
        JsonNode laura = buscar(pendientes.path("personas"), "nombre", "Laura Ézara");
        assertEquals(3, laura.path("responsable").asInt());
        assertEquals(2, laura.path("abiertas").asInt());
        assertEquals(2, buscar(pendientes.path("personas"), "nombre", "Jesus Ramos").path("tecnico").asInt());
        // Los números cortos del Excel se guardan en formato largo (82771 → 80002771).
        assertEquals(1, buscar(pendientes.path("proyectos"), "cuenta", "80002771").path("tareas").asInt());

        // Un coordinador elige a mano el responsable de una tarea: la vinculación ya no la toca.
        JsonNode elegida = tarea(admin, manual);
        hecho(operacion(admin, elegida, "editar", objeto().put("responsable", SOLICITANTE)));

        // Alta de las dos personas en Users (como lo hace un Admin) con su perfil de Timing R&T.
        ArrayNode usuarios = (ArrayNode) admin.leer("/api/v1/usuarios");
        usuarios.forEach(n -> ((ObjectNode) n).remove("timingRoles"));
        usuarios.addObject().put("title", "Laura Ezara").put("email", LAURA).put("role", "ReadOnly")
                .put("responsibleName", "").putArray("timingRoles").add("solicitante");
        usuarios.addObject().put("title", "Jesús Ramos").put("email", JESUS).put("role", "ReadOnly")
                .put("responsibleName", "").putArray("timingRoles").add("tecnico");
        JsonNode guardado = hecho(admin.json("PUT", "/api/v1/usuarios", objeto().set("users", usuarios)));
        assertEquals(3, guardado.path("timingVinculadas").asInt());

        JsonNode vinculada = tarea(admin, abierta);
        assertEquals(LAURA, vinculada.path("responsable").asText());
        assertEquals(JESUS, vinculada.path("tecnico").asText());
        assertEquals("Laura Ézara", vinculada.path("responsableOrigen").asText());
        assertTrue(vinculada.path("historial").toString().contains("vinculada"));
        assertEquals(LAURA, tarea(admin, cerrada).path("responsable").asText());
        JsonNode respetada = tarea(admin, manual);
        assertEquals(SOLICITANTE, respetada.path("responsable").asText());
        assertEquals(JESUS, respetada.path("tecnico").asText());

        // Proyecto que aparece después en el catálogo con el mismo número: se vincula al guardar el número.
        bd.jdbc().update("INSERT INTO proyecto (titulo) VALUES ('VW MEB21 de vínculos')");
        hecho(admin.json("PUT", "/api/v1/proyectos/numero", objeto().put("proyecto", "VW MEB21 de vínculos")
                .put("anterior", "").put("numero", "82771")));
        vinculada = tarea(admin, abierta);
        assertEquals("VW MEB21 de vínculos", vinculada.path("proyecto").asText());
        assertEquals("80002771", vinculada.path("cuenta").asText());
        assertTrue(vinculada.path("proyectoId").asLong() > 0);

        // Proyecto con otro número en el catálogo: correspondencia manual.
        JsonNode resultado = hecho(admin.json("POST", API + "/vinculos", objeto().put("tipo", "proyecto")
                .put("nombre", "Ford Vinculos").put("cuenta", "80772").put("destino", "00771 - Catálogo de vínculos")));
        assertEquals(1, resultado.path("vinculadas").asInt());
        JsonNode ford = tarea(admin, cerrada);
        assertEquals("00771 - Catálogo de vínculos", ford.path("proyecto").asText());
        assertEquals("00771", ford.path("cuenta").asText());
        assertEquals("80000772", ford.path("cuentaOrigen").asText());
        pendientes = hecho(admin.get(API + "/vinculacion"));
        assertEquals("00771 - Catálogo de vínculos", buscar(pendientes.path("vinculos"), "cuenta", "80000772").path("destino").asText());
        assertNull(buscar(pendientes.path("proyectos"), "cuenta", "80000772"));

        // La importación usa las mismas correspondencias: nada cambia y no hay conflictos.
        var repetida = hecho(admin.xlsx(API + "/importacion?simular=false", excel));
        assertEquals(1, repetida.path("conflictos").asInt(), repetida.toString()); // la editada a mano
        assertEquals(2, repetida.path("sinCambios").asInt(), repetida.toString());
        assertEquals(LAURA, tarea(admin, abierta).path("responsable").asText());

        // El técnico ya ve su tarea en su cola.
        cuentas.establecerAdmin(JESUS, "Jesús Ramos", CLAVE);
        bd.jdbc().update("UPDATE usuario SET rol = 'ReadOnly' WHERE email = ?", JESUS);
        JsonNode cola = hecho(entrar(JESUS).get(API + "/tareas?vista=cola"));
        assertTrue(cola.toString().contains("HEAT-920001"));

        // Quitar la correspondencia deshace lo vinculado con ella, igual que lo dejaría la importación.
        var quitada = hecho(admin.json("POST", API + "/vinculos/borrar", objeto().put("tipo", "proyecto")
                .put("nombre", "Ford Vinculos").put("cuenta", "80772")));
        assertEquals(1, quitada.path("vinculadas").asInt());
        ford = tarea(admin, cerrada);
        assertEquals("Ford Vinculos", ford.path("proyecto").asText());
        assertEquals("80000772", ford.path("cuenta").asText());
        assertTrue(ford.path("proyectoId").isNull() || ford.path("proyectoId").isMissingNode());
        repetida = hecho(admin.xlsx(API + "/importacion?simular=false", excel));
        assertEquals(2, repetida.path("sinCambios").asInt(), repetida.toString());
        assertEquals(1, repetida.path("conflictos").asInt(), repetida.toString());
    }

    @Test
    void unaCorrespondenciaManualDePersonaSePuedeCorregir() throws IOException {
        var admin = entrar(ADMIN);
        hecho(admin.xlsx(API + "/importacion?simular=false", libro(List.of(
                List.of("920101", "LAB-920101", "No", "", "", "LAB", "Genérica", "Nombre Distinto", "", "1", "0")))));
        long id = id(920101);
        estado(422, admin.json("POST", API + "/vinculos", objeto().put("tipo", "persona").put("nombre", "Nombre Distinto")
                .put("destino", "no-existe@ejemplo.local")));
        hecho(admin.json("POST", API + "/vinculos", objeto().put("tipo", "persona").put("nombre", "nombre distinto")
                .put("destino", SOLICITANTE)));
        assertEquals(SOLICITANTE, tarea(admin, id).path("responsable").asText());
        hecho(admin.json("POST", API + "/vinculos", objeto().put("tipo", "persona").put("nombre", "Nombre Distinto")
                .put("destino", ADMIN)));
        assertEquals(ADMIN, tarea(admin, id).path("responsable").asText());
        assertEquals(0, hecho(admin.json("POST", API + "/vinculacion", "{}")).path("vinculadas").asInt());
    }

    @Test
    void renombrarUnProyectoActualizaEnsayosYTareasSinPerderElVinculo() throws IOException {
        var admin = entrar(ADMIN);
        long proyecto = bd.jdbc().queryForObject("INSERT INTO proyecto (titulo, numero_proyecto) "
                + "VALUES ('Renombre de prueba', '80000999') RETURNING id", Long.class);
        String ensayo = "{\"uid\":\"rrenomb1\",\"project\":\"80000999 - Renombre de prueba\",\"comments\":\"sin cambios\"}";
        bd.jdbc().update("INSERT INTO ensayo (uid, data_json, proyecto) VALUES ('rrenomb1', ?, '80000999 - Renombre de prueba')", ensayo);
        JsonNode catalogos = admin.leer("/api/v1/catalogos");
        assertTrue(catalogos.path("projects").toString().contains("\"80000999 - Renombre de prueba\""));
        JsonNode ficha = buscar(catalogos.path("projectItems"), "id", String.valueOf(proyecto));
        assertEquals("Renombre de prueba", ficha.path("name").asText());
        assertEquals("80000999", ficha.path("number").asText());

        // El Excel trae el número corto: se guarda largo y se vincula por nombre y número.
        byte[] excel = libro(List.of(List.of("920201", "TEST-920201", "No", "Renombre de prueba", "80999", "TEST", "Montaje",
                "", "", "1", "0")));
        hecho(admin.xlsx(API + "/importacion?simular=false", excel));
        long idTarea = id(920201);
        assertEquals(proyecto, tarea(admin, idTarea).path("proyectoId").asLong());
        assertEquals("80000999", tarea(admin, idTarea).path("cuenta").asText());

        // El nombre no puede llevar el número, ni repetir el de otro proyecto.
        estado(422, admin.json("PUT", "/api/v1/proyectos/editar", objeto().put("id", proyecto).put("nombre", "81000 - Otro")
                .put("numero", "80000999").put("anteriorNombre", "Renombre de prueba").put("anteriorNumero", "80000999")));
        bd.jdbc().update("INSERT INTO proyecto (titulo) VALUES ('Nombre ocupado de prueba')");
        estado(422, admin.json("PUT", "/api/v1/proyectos/editar", objeto().put("id", proyecto)
                .put("nombre", "Nombre ocupado de prueba").put("numero", "80000999")
                .put("anteriorNombre", "Renombre de prueba").put("anteriorNumero", "80000999")));
        estado(403, entrar(SOLICITANTE).json("PUT", "/api/v1/proyectos/editar", objeto().put("id", proyecto)
                .put("nombre", "X").put("numero", "").put("anteriorNombre", "Renombre de prueba").put("anteriorNumero", "80000999")));

        JsonNode r = hecho(admin.json("PUT", "/api/v1/proyectos/editar", objeto().put("id", proyecto)
                .put("nombre", "Renombrado de prueba").put("numero", "81000")
                .put("anteriorNombre", "Renombre de prueba").put("anteriorNumero", "80000999")));
        assertEquals("80001000 - Renombrado de prueba", r.path("label").asText());
        assertEquals(1, r.path("tests").asInt());
        assertEquals(1, r.path("timingTasks").asInt());
        // El ensayo solo cambia en «project» (el resto del texto se conserva tal cual).
        assertEquals(ensayo.replace("80000999 - Renombre de prueba", "80001000 - Renombrado de prueba"),
                bd.jdbc().queryForObject("SELECT data_json FROM ensayo WHERE uid = 'rrenomb1'", String.class));
        assertEquals("80001000 - Renombrado de prueba",
                bd.jdbc().queryForObject("SELECT proyecto FROM ensayo WHERE uid = 'rrenomb1'", String.class));
        JsonNode renombrada = tarea(admin, idTarea);
        assertEquals("Renombrado de prueba", renombrada.path("proyecto").asText());
        assertEquals("80001000", renombrada.path("cuenta").asText());
        assertEquals(proyecto, renombrada.path("proyectoId").asLong());
        assertTrue(admin.leer("/api/v1/catalogos").path("projects").toString().contains("\"80001000 - Renombrado de prueba\""));
        // Con los datos ya vistos: «El proyecto ha cambiado».
        estado(422, admin.json("PUT", "/api/v1/proyectos/editar", objeto().put("id", proyecto).put("nombre", "Otro nombre")
                .put("numero", "").put("anteriorNombre", "Renombre de prueba").put("anteriorNumero", "80000999")));

        // Reimportar el Excel (que sigue con el nombre y número antiguos) no deshace el vínculo.
        var repetida = hecho(admin.xlsx(API + "/importacion?simular=false", excel));
        assertEquals(1, repetida.path("sinCambios").asInt(), repetida.toString());
        assertEquals(proyecto, tarea(admin, idTarea).path("proyectoId").asLong());
        bd.jdbc().update("DELETE FROM ensayo WHERE uid = 'rrenomb1'");
    }

    @Test
    void importaElOrdenDiarioYElCoordinadorLoCambiaEnElPanel() throws IOException {
        var admin = entrar(ADMIN);
        bd.jdbc().update("INSERT INTO timing_perfil (email, perfil) VALUES (?, 'tecnico') ON CONFLICT DO NOTHING", ADMIN);
        var cabecera = List.of("ID", "ID Tarea", "Disp.", "Proyecto", "Cuenta Proyecto", "EGM/HEATERS", "Tarea", "Responsable",
                "Asignado a", "OrdenDIario", "Prio");
        byte[] excel = libro(cabecera, List.of(
                List.of("920301", "EGM-920301", "En proceso", "", "", "EGM", "Primera", "", "Coordinador de vínculos", "0", "100"),
                List.of("920302", "EGM-920302", "Si", "", "", "EGM", "Segunda", "", "Coordinador de vínculos", "9", "1"),
                List.of("920303", "LAB-920303", "Si", "", "", "LAB", "Ausencias", "", "Coordinador de vínculos", "99", "999")));
        hecho(admin.xlsx(API + "/importacion?simular=false", excel));
        JsonNode primera = tarea(admin, id(920301));
        JsonNode segunda = tarea(admin, id(920302));
        assertEquals(0, primera.path("ordenDiario").asInt());
        assertEquals(9, segunda.path("ordenDiario").asInt());
        assertEquals(99, tarea(admin, id(920303)).path("ordenDiario").asInt());
        // Reimportar el mismo Excel: sin cambios ni conflictos.
        var repetida = hecho(admin.xlsx(API + "/importacion?simular=false", excel));
        assertEquals(3, repetida.path("sinCambios").asInt(), repetida.toString());

        // Panel del coordinador: la segunda pasa delante de la primera.
        ObjectNode cuerpo = objeto().put("tecnico", ADMIN);
        cuerpo.putArray("tareas")
                .add(objeto().put("id", segunda.path("id").asLong()).put("version", segunda.path("version").asInt()).put("ordenDiario", 1))
                .add(objeto().put("id", primera.path("id").asLong()).put("version", primera.path("version").asInt()).put("ordenDiario", 2));
        estado(403, entrar(SOLICITANTE).json("POST", API + "/orden-diario", cuerpo));
        assertEquals(2, hecho(admin.json("POST", API + "/orden-diario", cuerpo)).path("cambiadas").asInt());
        assertEquals(1, tarea(admin, id(920302)).path("ordenDiario").asInt());
        assertTrue(tarea(admin, id(920302)).path("historial").toString().contains("orden-diario"));
        // Con una versión ya superada no se guarda nada.
        estado(409, admin.json("POST", API + "/orden-diario", cuerpo));
        // Otro técnico: rechazado.
        ObjectNode ajeno = objeto().put("tecnico", SOLICITANTE);
        JsonNode actual = tarea(admin, id(920301));
        ajeno.putArray("tareas").add(objeto().put("id", actual.path("id").asLong()).put("version", actual.path("version").asInt()).put("ordenDiario", 3));
        estado(422, admin.json("POST", API + "/orden-diario", ajeno));
    }

    private long id(long origen) {
        return bd.jdbc().queryForObject("SELECT id FROM timing_tarea WHERE origen_id = ?", Long.class, origen);
    }

    private static JsonNode tarea(NavegadorPruebas n, long id) {
        return n.leer(API + "/tareas/" + id);
    }

    private static JsonNode buscar(JsonNode lista, String campo, String valor) {
        for (JsonNode n : lista) {
            if (valor.equals(n.path(campo).asText())) return n;
        }
        return null;
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
        return libro(List.of("ID", "ID Tarea", "Disp.", "Proyecto", "Cuenta Proyecto", "EGM/HEATERS",
                "Tarea", "Responsable", "Asignado a", "Tiempo estimado de tarea (h)", "Horas_Totales"), datos);
    }

    private static byte[] libro(List<String> cabecera, List<List<String>> datos) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            entrada(zip, "xl/workbook.xml", """
                    <workbook xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                    <sheets><sheet name="query" r:id="r1"/></sheets></workbook>
                    """);
            entrada(zip, "xl/_rels/workbook.xml.rels", "<Relationships><Relationship Id=\"r1\" Target=\"worksheets/sheet1.xml\"/></Relationships>");
            StringBuilder hoja = new StringBuilder("<worksheet><sheetData>");
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
