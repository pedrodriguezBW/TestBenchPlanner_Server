package com.borgwarner.tbp.integracion;

import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.infraestructura.postgresql.BaseDatos;
import com.borgwarner.tbp.infraestructura.postgresql.CuentasPostgres;
import com.borgwarner.tbp.infraestructura.postgresql.ImportadorDatos;
import com.borgwarner.tbp.postgresql.PostgresPruebas;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Configuration › Test Calendar / Working Hours (D59) y facturación por horas
 * (D60) con PostgreSQL en modo servidor: un Lab User (o un Admin) guarda y
 * restablece el horario de un ensayo, con comprobación de cambios concurrentes
 * y auditoría; un Editor y un Read only lo ven pero no lo cambian (403); volver
 * a importar desde SharePoint no borra los horarios; el primer mes de
 * facturación por horas solo lo cambia un Admin.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HorariosPostgresTest {

    static final String ADMIN = "usuario01@ejemplo.local";
    static final String EDITOR = "usuario07@ejemplo.local";
    static final String LAB = "usuario08@ejemplo.local";
    static final String LECTOR = "usuario09@ejemplo.local";
    static final String CLAVE = "Clave-de-prueba-1";

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) throws IOException {
        Path carpeta = Files.createTempDirectory("tbp-horarios");
        PostgresPruebas.registrar(registro);
        registro.add("planner.postgresql.reinicio-pruebas", () -> "false");
        registro.add("planner.servidor.activo", () -> "true");
        registro.add("planner.fecha-fija", () -> "2026-11-05");
        registro.add("planner.carpeta-usuario", carpeta::toString);
        registro.add("planner.postgresql.usuario-email", () -> "nadie@ejemplo.local");
    }

    @LocalServerPort
    int puerto;

    @Autowired
    ImportadorDatos importador;

    @Autowired
    CuentasPostgres cuentas;

    @Autowired
    BaseDatos bd;

    @BeforeEach
    void datos() {
        importador.reiniciar(PostgresPruebas.escenario());
        bd.jdbc().update("UPDATE usuario SET rol = 'LabUser' WHERE email = ?", LAB);
        bd.jdbc().update("UPDATE usuario SET rol = 'ReadOnly' WHERE email = ?", LECTOR);
        cuentas.establecerAdmin(ADMIN, "", CLAVE);
        for (String email : List.of(EDITOR, LAB, LECTOR)) {
            String temporal = cuentas.claveTemporal(CuentasPostgres.INSTALACION, email);
            cuentas.cambiarClave(email, temporal, CLAVE);
        }
    }

    private NavegadorPruebas dentro(String email) {
        NavegadorPruebas n = new NavegadorPruebas(puerto);
        assertEquals(302, n.entrar(email, CLAVE).statusCode());
        return n;
    }

    private int auditorias(String id, String operacion, String email) {
        Integer n = bd.jdbc().queryForObject("SELECT count(*) FROM auditoria WHERE entidad = 'horario' AND clave = ? "
                + "AND operacion = ? AND usuario_email = ?", Integer.class, id, operacion, email);
        return n == null ? 0 : n;
    }

    /** Primer ensayo de MD2 con fechas que duran al menos una semana (así tiene días laborables). */
    private static JsonNode ensayoConFechas(NavegadorPruebas n) {
        for (JsonNode e : n.leer("/api/v1/horarios?banco=MD2").path("ensayos")) {
            if (e.path("inicio").isNull() || e.path("fin").isNull() || e.path("especial").asBoolean()) {
                continue;
            }
            LocalDate inicio = LocalDate.parse(e.path("inicio").asText());
            LocalDate fin = LocalDate.parse(e.path("fin").asText());
            if (!fin.isBefore(inicio.plusDays(6))) {
                return e;
            }
        }
        throw new AssertionError("MD2 no tiene ensayos de una semana o más con fechas");
    }

    /** Cuerpo de un guardado: jornada de 16 h, fines de semana al revés que el ensayo y 2 h de setup. */
    private static ObjectNode cuerpo(JsonNode ensayo, long version) {
        ObjectNode c = JsonJs.objetoVacio();
        ObjectNode h = c.putObject("horario");
        h.put("jornada", 16);
        h.put("finDeSemana", ensayo.path("finDeSemanaEnsayo").asBoolean() ? "no" : "si");
        h.put("setupHoras", 2);
        h.putArray("paradas");
        h.put("nota", "Turno doble");
        c.putObject("anterior").put("version", version);
        return c;
    }

    @Test
    void unLabUserGuardaYRestableceElHorario() {
        NavegadorPruebas lab = dentro(LAB);
        JsonNode gestion = lab.leer("/api/v1/horarios/gestion");
        assertTrue(gestion.path("editable").asBoolean(), "con PostgreSQL hay horarios");
        assertTrue(gestion.path("puedeEditar").asBoolean());
        JsonNode ensayo = ensayoConFechas(lab);
        String id = ensayo.path("id").asText();
        assertFalse(ensayo.path("guardado").asBoolean());
        assertEquals(0, ensayo.path("version").asLong());
        assertEquals(24, ensayo.path("horario").path("jornada").asInt(), "por defecto, 24 h");
        assertEquals("00:00", ensayo.path("horario").path("horaInicioEfectiva").asText());

        HttpResponse<String> r = lab.json("PUT", "/api/v1/horarios/" + id, cuerpo(ensayo, 0));
        assertEquals(200, r.statusCode(), r.body());
        JsonNode guardado = JsonJs.leer(r.body());
        assertTrue(guardado.path("guardado").asBoolean());
        assertEquals(1, guardado.path("version").asLong());
        assertEquals(16, guardado.path("horario").path("jornada").asInt());
        assertEquals("06:00", guardado.path("horario").path("horaInicioEfectiva").asText());
        assertEquals("22:00", guardado.path("horario").path("horaFinEfectiva").asText());
        assertTrue(guardado.path("anulaFinDeSemana").asBoolean());
        assertEquals(LAB, guardado.path("modificadoPor").asText());
        assertEquals(1, auditorias(id, "ALTA", LAB));

        // Aviso ⚠ para la tabla del banco.
        JsonNode avisos = lab.leer("/api/v1/horarios/avisos");
        assertTrue(avisos.path("ensayos").path(id).path("anulaFinDeSemana").asBoolean());
        assertEquals(16, avisos.path("ensayos").path(id).path("jornada").asInt());

        // Vista previa: con el guardado (cuerpo vacío) y con uno sin guardar no válido (422).
        HttpResponse<String> previa = lab.json("POST", "/api/v1/horarios/" + id + "/vista-previa", JsonJs.objetoVacio());
        assertEquals(200, previa.statusCode(), previa.body());
        JsonNode p = JsonJs.leer(previa.body());
        assertNotNull(p.get("horasFacturables"));
        assertEquals(2, p.path("horasSetup").asDouble(), 1e-9);
        assertTrue(p.path("dias").size() > 0);
        assertEquals(48, p.path("dias").get(0).path("franjas").asText().length());
        ObjectNode malo = JsonJs.objetoVacio();
        malo.putObject("horario").put("jornada", 12);
        assertEquals(422, lab.json("POST", "/api/v1/horarios/" + id + "/vista-previa", malo).statusCode());

        // Otra persona lo había cambiado (versión antigua): se rechaza.
        HttpResponse<String> conflicto = lab.json("PUT", "/api/v1/horarios/" + id, cuerpo(ensayo, 0));
        assertEquals(422, conflicto.statusCode());
        assertTrue(JsonJs.leer(conflicto.body()).path("mensaje").asText().contains("Otra persona"));

        // Un Admin también lo cambia.
        NavegadorPruebas admin = dentro(ADMIN);
        ObjectNode cambio = cuerpo(ensayo, 1);
        ((ObjectNode) cambio.get("horario")).put("jornada", 8);
        HttpResponse<String> deAdmin = admin.json("PUT", "/api/v1/horarios/" + id, cambio);
        assertEquals(200, deAdmin.statusCode(), deAdmin.body());
        assertEquals(2, JsonJs.leer(deAdmin.body()).path("version").asLong());
        assertEquals(1, auditorias(id, "MODIFICACION", ADMIN));

        // Restablecer: vuelve a los valores por defecto.
        assertEquals(422, lab.json("POST", "/api/v1/horarios/" + id + "/restablecer",
                JsonJs.objetoVacio().set("anterior", JsonJs.objetoVacio().put("version", 1))).statusCode(), "versión antigua");
        HttpResponse<String> reset = lab.json("POST", "/api/v1/horarios/" + id + "/restablecer",
                JsonJs.objetoVacio().set("anterior", JsonJs.objetoVacio().put("version", 2)));
        assertEquals(200, reset.statusCode(), reset.body());
        assertFalse(JsonJs.leer(reset.body()).path("guardado").asBoolean());
        assertEquals(1, auditorias(id, "BAJA", LAB));
        assertFalse(lab.leer("/api/v1/horarios/avisos").path("ensayos").has(id));
    }

    @Test
    void unEditorYUnReadOnlyNoVenNiCambianElHorario() {
        JsonNode ensayo = ensayoConFechas(dentro(LAB));
        String id = ensayo.path("id").asText();
        // D62: la pestaña Working Hours y la hoja mensual solo las ven un Admin o un Lab User.
        for (String quien : List.of(EDITOR, LECTOR)) {
            NavegadorPruebas n = dentro(quien);
            assertEquals(403, n.get("/api/v1/horarios/gestion").statusCode(), quien);
            assertEquals(403, n.get("/api/v1/horarios?banco=MD2").statusCode(), quien);
            assertEquals(403, n.json("POST", "/api/v1/horarios/" + id + "/vista-previa", JsonJs.objetoVacio()).statusCode(), quien);
            assertEquals(403, n.json("PUT", "/api/v1/horarios/" + id, cuerpo(ensayo, 0)).statusCode(), quien);
            assertEquals(403, n.json("POST", "/api/v1/horarios/" + id + "/restablecer", JsonJs.objetoVacio()).statusCode(), quien);
            assertEquals(200, n.get("/api/v1/horarios/avisos").statusCode(), "los avisos de la tabla del banco los ven todos");
            assertEquals(403, n.get("/api/v1/facturacion/hoja?mes=2026-10").statusCode(), quien);
            assertEquals(403, n.get("/api/v1/facturacion/hoja/exportar?mes=2026-10&formato=excel").statusCode(), quien);
            assertEquals(200, n.get("/api/v1/facturacion/registro?anio=2026").statusCode(), "Tests by person la ven todos");
        }
        Integer filas = bd.jdbc().queryForObject("SELECT count(*) FROM horario_ensayo", Integer.class);
        assertEquals(0, filas == null ? -1 : filas);
    }

    @Test
    void volverAImportarNoBorraLosHorarios() {
        NavegadorPruebas lab = dentro(LAB);
        JsonNode ensayo = ensayoConFechas(lab);
        String id = ensayo.path("id").asText();
        assertEquals(200, lab.json("PUT", "/api/v1/horarios/" + id, cuerpo(ensayo, 0)).statusCode());
        importador.importar(PostgresPruebas.escenario(), false);
        Integer filas = bd.jdbc().queryForObject("SELECT count(*) FROM horario_ensayo WHERE ensayo_id = ?", Integer.class, id);
        assertEquals(1, filas == null ? 0 : filas);
    }

    @Test
    void elPrimerMesDeFacturacionPorHorasLoCambiaUnAdmin() {
        NavegadorPruebas admin = dentro(ADMIN);
        NavegadorPruebas lab = dentro(LAB);
        // Tras el reinicio de pruebas no hay clave: todo por días.
        assertTrue(admin.leer("/api/v1/facturacion/horas-desde").path("facturacionHorasDesde").isNull());
        assertFalse(admin.leer("/api/v1/facturacion/hoja?mes=2026-10").path("porHoras").asBoolean());

        assertEquals(403, lab.json("PUT", "/api/v1/facturacion/horas-desde", JsonJs.objetoVacio().put("mes", "2026-10")).statusCode());
        assertEquals(422, admin.json("PUT", "/api/v1/facturacion/horas-desde", JsonJs.objetoVacio().put("mes", "octubre")).statusCode());
        HttpResponse<String> r = admin.json("PUT", "/api/v1/facturacion/horas-desde", JsonJs.objetoVacio().put("mes", "2026-10"));
        assertEquals(200, r.statusCode(), r.body());
        assertEquals("2026-10", JsonJs.leer(r.body()).path("facturacionHorasDesde").asText());

        JsonNode octubre = admin.leer("/api/v1/facturacion/hoja?mes=2026-10");
        assertTrue(octubre.path("porHoras").asBoolean());
        assertEquals("2026-10", octubre.path("facturacionHorasDesde").asText());
        octubre.path("lineas").forEach(l -> assertTrue(List.of("horas", "dias").contains(l.path("calculo").asText())));
        JsonNode septiembre = admin.leer("/api/v1/facturacion/hoja?mes=2026-09");
        assertFalse(septiembre.path("porHoras").asBoolean());
        septiembre.path("lineas").forEach(l -> assertEquals("dias", l.path("calculo").asText()));

        HttpResponse<String> ninguno = admin.json("PUT", "/api/v1/facturacion/horas-desde", JsonJs.objetoVacio().putNull("mes"));
        assertEquals(200, ninguno.statusCode(), ninguno.body());
        assertTrue(JsonJs.leer(ninguno.body()).path("facturacionHorasDesde").isNull());
    }
}
