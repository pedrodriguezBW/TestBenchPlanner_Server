package com.borgwarner.tbp.integracion;

import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.infraestructura.postgresql.BaseDatos;
import com.borgwarner.tbp.infraestructura.postgresql.CuentasPostgres;
import com.borgwarner.tbp.infraestructura.postgresql.ImportadorDatos;
import com.borgwarner.tbp.postgresql.PostgresPruebas;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hito S2 · modo servidor con PostgreSQL: sin /clasico ni API antigua, la
 * interfaz nueva sigue funcionando, el reinicio de pruebas no existe y el
 * calendario de festivos es común y solo lo cambia un Admin (D46).
 * Desde el hito S3 cada persona entra con su cuenta (InicioSesionPostgresTest).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ModoServidorPostgresTest {

    private static final String ADMIN = "usuario01@ejemplo.local";
    private static final String EDITOR = "usuario07@ejemplo.local";
    private static final String CLAVE = "Clave-de-prueba-1";

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) throws IOException {
        Path carpeta = Files.createTempDirectory("tbp-servidor");
        PostgresPruebas.registrar(registro);
        registro.add("planner.postgresql.reinicio-pruebas", () -> "false"); // como en el servidor
        registro.add("planner.servidor.activo", () -> "true");
        registro.add("planner.fecha-fija", () -> "2026-11-05");
        registro.add("planner.carpeta-usuario", carpeta::toString);
    }

    @LocalServerPort
    int puerto;

    @Autowired
    ImportadorDatos importador;

    @Autowired
    CuentasPostgres cuentas;

    @Autowired
    BaseDatos bd;

    private NavegadorPruebas admin;

    @BeforeEach
    void datos() {
        importador.reiniciar(PostgresPruebas.escenario());
        cuentas.establecerAdmin(ADMIN, "", CLAVE);
        cuentas.cambiarClave(EDITOR, cuentas.claveTemporal(CuentasPostgres.INSTALACION, EDITOR), CLAVE);
        admin = como(ADMIN);
    }

    private NavegadorPruebas como(String email) {
        NavegadorPruebas n = new NavegadorPruebas(puerto);
        assertEquals("/", NavegadorPruebas.destino(n.entrar(email, CLAVE)));
        return n;
    }

    @Test
    void laSesionIndicaElModoServidor() {
        JsonNode s = admin.leer("/api/v1/sesion");
        assertTrue(s.path("modoServidor").asBoolean());
        assertEquals(ADMIN, s.path("email").asText());
    }

    @Test
    void sinVersionClasicaNiSuApi() {
        assertEquals(404, admin.get("/clasico").statusCode());
        for (String ruta : new String[]{"/api/load", "/api/save", "/api/load_config", "/api/save_config", "/api/save_users", "/api/whoami"}) {
            HttpResponse<String> r = admin.json("POST", ruta, null);
            assertEquals(404, r.statusCode(), ruta);
            assertTrue(r.body().contains("no-disponible"), ruta);
        }
        // Lo que sigue enviando la interfaz nueva.
        assertEquals(200, admin.json("POST", "/api/heartbeat", JsonJs.objetoVacio().put("pestana", "p1")).statusCode());
        assertEquals(200, admin.json("POST", "/api/shutdown", JsonJs.objetoVacio().put("pestana", "p1")).statusCode());
        assertEquals(200, admin.get("/api/v1/planificacion").statusCode());
    }

    @Test
    void laFotoDeSoloLecturaTieneSuPlantilla() {
        HttpResponse<String> r = admin.get("/api/v1/exportaciones/plantilla-solo-lectura");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("BW_APP_MARKER"));
    }

    @Test
    void noHayReinicioDePruebas() {
        // Sin controlador: 404 o 405 según cómo lo resuelva Spring; nunca se atiende.
        int estado = admin.json("POST", "/api/v1/local/reinicio", null).statusCode();
        assertTrue(estado >= 400 && estado < 500, "estado " + estado);
        assertEquals(281, bd.jdbc().queryForObject("SELECT count(*) FROM ensayo", Integer.class));
    }

    @Test
    void elCalendarioEsComunYSoloLoCambiaUnAdmin() {
        JsonNode cal = admin.leer("/api/v1/calendario");
        assertTrue(cal.path("compartido").asBoolean());
        assertEquals(2026, cal.path("anio").asInt());
        assertTrue(cal.path("festivos").size() > 10, "festivos de Vigo del año");

        ObjectNode nuevo = (ObjectNode) cal.deepCopy();
        ((ArrayNode) nuevo.get("festivos")).addObject().put("fecha", "2026-11-06").put("nombre", "Puente de prueba");
        assertEquals(200, admin.json("PUT", "/api/v1/calendario", nuevo).statusCode());

        // Otra persona (Editor) ve el mismo calendario, pero no lo puede cambiar.
        NavegadorPruebas editor = como(EDITOR);
        JsonNode visto = editor.leer("/api/v1/calendario");
        assertTrue(visto.path("festivos").toString().contains("2026-11-06"));
        assertEquals(403, editor.json("PUT", "/api/v1/calendario", cal).statusCode());
        assertEquals(403, editor.json("POST", "/api/v1/calendario/anio", JsonJs.objetoVacio().put("anio", 2027)).statusCode());
        JsonNode igual = editor.leer("/api/v1/calendario");
        assertEquals(2026, igual.path("anio").asInt());
        assertTrue(igual.path("festivos").toString().contains("2026-11-06"));

        // En la base, con su auditoría.
        assertEquals(1, bd.jdbc().queryForObject("SELECT count(*) FROM festivo WHERE fecha = DATE '2026-11-06'", Integer.class));
        assertTrue(bd.jdbc().queryForObject("SELECT count(*) FROM auditoria WHERE entidad = 'calendario' AND operacion = 'MODIFICACION'",
                Integer.class) >= 1);
        assertEquals(ADMIN, bd.jdbc().queryForObject("SELECT actualizado_por FROM calendario WHERE id = 1", String.class));
    }

    @Test
    void elPlanUsaElCalendarioComun() {
        JsonNode cal = admin.leer("/api/v1/calendario");
        ObjectNode sinFestivos = (ObjectNode) cal.deepCopy();
        sinFestivos.putArray("festivos");
        assertEquals(200, admin.json("PUT", "/api/v1/calendario", sinFestivos).statusCode());
        JsonNode vista = como(EDITOR).leer("/api/v1/planificacion");
        assertTrue(vista.path("calendario").path("festivos").isArray());
        assertEquals(0, vista.path("calendario").path("festivos").size(), "sin festivos para nadie");
        assertFalse(vista.path("calendario").path("festivos").toString().contains("2026-12-25"), "Navidad ya no es festivo para nadie");
    }

    @Test
    void laSaludSeConsultaSinSesionYNoDaDatos() {
        NavegadorPruebas anonimo = new NavegadorPruebas(puerto);
        HttpResponse<String> r = anonimo.get("/api/v1/salud");
        assertEquals(200, r.statusCode(), r.body());
        JsonNode salud = JsonJs.leer(r.body());
        assertEquals("ok", salud.path("estado").asText());
        assertEquals("ok", salud.path("baseDatos").asText());
        assertEquals("postgresql", salud.path("almacen").asText());
        assertTrue(salud.path("modoServidor").asBoolean());
        assertFalse(r.body().contains("@"), "sin emails ni datos de personas");
        // El resto de la API sigue pidiendo sesión.
        assertEquals(401, anonimo.get("/api/v1/sesion").statusCode());
    }
}
