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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S5 · aprobaciones gestionadas desde la aplicación (plan §9.2, D54): solo un
 * Admin da de alta, cambia y borra; cada cambio queda en la auditoría con la
 * persona real; se rechaza lo que no es válido y el cambio sobre una
 * aprobación que otra persona ha tocado mientras tanto.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AprobacionesPostgresTest {

    static final String ADMIN = "usuario01@ejemplo.local";
    static final String EDITOR = "usuario07@ejemplo.local";
    static final String CLAVE = "Clave-de-prueba-1";

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) throws IOException {
        Path carpeta = Files.createTempDirectory("tbp-aprobaciones");
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
        cuentas.establecerAdmin(ADMIN, "", CLAVE);
        String temporal = cuentas.claveTemporal(CuentasPostgres.INSTALACION, EDITOR);
        cuentas.cambiarClave(EDITOR, temporal, CLAVE);
    }

    private NavegadorPruebas dentro(String email) {
        NavegadorPruebas n = new NavegadorPruebas(puerto);
        assertEquals(302, n.entrar(email, CLAVE).statusCode());
        return n;
    }

    private static ObjectNode aprobacion(String raw, String solicitante, String servicio) {
        ObjectNode o = JsonJs.objetoVacio().put("raw", raw).put("requester", solicitante);
        return servicio == null ? o.putNull("service") : o.put("service", servicio);
    }

    private int auditorias(String id, String operacion) {
        Integer n = bd.jdbc().queryForObject("SELECT count(*) FROM auditoria WHERE entidad = 'aprobacion' AND clave = ? "
                + "AND operacion = ? AND usuario_email = ?", Integer.class, id, operacion, ADMIN);
        return n == null ? 0 : n;
    }

    private String estadoDe(NavegadorPruebas n, String id) {
        HttpResponse<String> r = n.json("POST", "/api/v1/aprobaciones/estados",
                JsonJs.objetoVacio().set("ids", JsonJs.mapper().createArrayNode().add(id)));
        assertEquals(200, r.statusCode(), r.body());
        return JsonJs.leer(r.body()).path(id).path("status").asText();
    }

    @Test
    void soloUnAdminLasGestiona() {
        NavegadorPruebas editor = dentro(EDITOR);
        assertTrue(editor.leer("/api/v1/aprobaciones/gestion").path("editable").asBoolean(), "con PostgreSQL son editables");
        int antes = bd.jdbc().queryForObject("SELECT count(*) FROM aprobacion", Integer.class);
        assertEquals(403, editor.json("POST", "/api/v1/aprobaciones", aprobacion("Pending", "Responsable 01", "x")).statusCode());
        assertEquals(403, editor.json("PUT", "/api/v1/aprobaciones/3397", aprobacion("Approved", "Responsable 32", null)).statusCode());
        assertEquals(403, editor.json("POST", "/api/v1/aprobaciones/borrar", JsonJs.objetoVacio().put("id", 3397)).statusCode());
        assertEquals(antes, bd.jdbc().queryForObject("SELECT count(*) FROM aprobacion", Integer.class));
        // Leerlas y usarlas en los ensayos sigue igual para todos.
        assertEquals(antes, editor.leer("/api/v1/aprobaciones").size());
    }

    @Test
    void altaCambioYBaja() {
        NavegadorPruebas admin = dentro(ADMIN);
        int siguiente = bd.jdbc().queryForObject("SELECT max(id) + 1 FROM aprobacion", Integer.class);

        // Alta sin ID: el siguiente libre.
        HttpResponse<String> alta = admin.json("POST", "/api/v1/aprobaciones", aprobacion("pending", "  Responsable 01 ", "Ensayo de vibración"));
        assertEquals(200, alta.statusCode(), alta.body());
        JsonNode nueva = JsonJs.leer(alta.body());
        assertEquals(siguiente, nueva.path("id").asInt());
        assertEquals("Pending", nueva.path("raw").asText(), "el estado se guarda como en la lista");
        assertEquals("pending", nueva.path("status").asText());
        assertEquals("amber", nueva.path("color").asText());
        assertEquals("Responsable 01", nueva.path("requester").asText());
        assertEquals(siguiente, admin.leer("/api/v1/aprobaciones").get(0).path("id").asInt(), "la más reciente, la primera");
        String id = String.valueOf(siguiente);
        assertEquals("pending", estadoDe(admin, id));
        assertEquals(1, auditorias(id, "ALTA"));

        // Cambio con el valor que se abrió para editar.
        ObjectNode cambio = aprobacion("Approved", "Responsable 01", "Ensayo de vibración");
        cambio.set("anterior", nueva);
        HttpResponse<String> r = admin.json("PUT", "/api/v1/aprobaciones/" + id, cambio);
        assertEquals(200, r.statusCode(), r.body());
        assertEquals("green", JsonJs.leer(r.body()).path("color").asText());
        assertEquals("approved", estadoDe(admin, id));
        assertEquals(1, auditorias(id, "MODIFICACION"));

        // Otra persona la había cambiado: se rechaza y no se toca.
        ObjectNode desfasado = aprobacion("Rejected", "Responsable 01", "Ensayo de vibración");
        desfasado.set("anterior", nueva); // todavía dice Pending
        HttpResponse<String> conflicto = admin.json("PUT", "/api/v1/aprobaciones/" + id, desfasado);
        assertEquals(422, conflicto.statusCode());
        assertTrue(JsonJs.leer(conflicto.body()).path("mensaje").asText().contains("Otra persona"));
        assertEquals("approved", estadoDe(admin, id));
        assertEquals(422, admin.json("POST", "/api/v1/aprobaciones/borrar",
                JsonJs.objetoVacio().put("id", siguiente).set("anterior", nueva)).statusCode());

        // Baja: el ID deja de existir para los ensayos que lo usen.
        JsonNode actual = admin.leer("/api/v1/aprobaciones").get(0);
        HttpResponse<String> baja = admin.json("POST", "/api/v1/aprobaciones/borrar",
                JsonJs.objetoVacio().put("id", siguiente).set("anterior", actual));
        assertEquals(200, baja.statusCode(), baja.body());
        assertEquals("not_created", estadoDe(admin, id));
        assertEquals(1, auditorias(id, "BAJA"));
        assertEquals(422, admin.json("POST", "/api/v1/aprobaciones/borrar", JsonJs.objetoVacio().put("id", siguiente)).statusCode(),
                "ya no existe");
    }

    @Test
    void altaConIdElegidoYDatosNoValidos() {
        NavegadorPruebas admin = dentro(ADMIN);
        ObjectNode conId = aprobacion("NA", "Responsable 02", null);
        conId.put("id", "5000");
        HttpResponse<String> r = admin.json("POST", "/api/v1/aprobaciones", conId);
        assertEquals(200, r.statusCode(), r.body());
        assertEquals(5000, JsonJs.leer(r.body()).path("id").asInt());
        assertEquals("na", estadoDe(admin, "ID 5000"));

        assertEquals(422, admin.json("POST", "/api/v1/aprobaciones", conId).statusCode(), "ID repetido");
        ObjectNode malId = aprobacion("NA", "Responsable 02", null);
        malId.put("id", "12a");
        assertEquals(422, admin.json("POST", "/api/v1/aprobaciones", malId).statusCode(), "ID no numérico");
        assertEquals(422, admin.json("POST", "/api/v1/aprobaciones", aprobacion("Quizá", "Responsable 02", null)).statusCode(),
                "estado que no existe en la lista");
        assertEquals(422, admin.json("POST", "/api/v1/aprobaciones", aprobacion("Approved", "  ", null)).statusCode(),
                "sin solicitante");
        assertEquals(422, admin.json("PUT", "/api/v1/aprobaciones/999999", aprobacion("Approved", "Responsable 02", null)).statusCode(),
                "no existe");
    }
}
