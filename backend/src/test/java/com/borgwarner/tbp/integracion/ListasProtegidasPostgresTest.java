package com.borgwarner.tbp.integracion;

import com.borgwarner.tbp.aplicacion.RegistroLecturas;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.postgresql.PostgresPruebas;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D11 con PostgreSQL (equivalente a ListasProtegidasTest, que mira los
 * ficheros del modo local): las listas compartidas no se reescriben sin una
 * lectura correcta previa, si la lectura llegó vacía ni para dejarlas vacías.
 * En esos casos las tablas quedan EXACTAMENTE igual.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ListasProtegidasPostgresTest {

    private static PostgresPruebas.Base base;

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) throws IOException {
        Path carpeta = Files.createTempDirectory("tbp-listas-pg");
        registro.add("planner.fecha-fija", () -> "2026-11-05");
        registro.add("planner.carpeta-usuario", carpeta::toString);
        registro.add("planner.local.escenario-inicial", () -> PostgresPruebas.escenario().toString());
        registro.add("planner.local.reiniciar-al-arrancar", () -> "true");
        registro.add("planner.local.usuario-email", () -> "usuario01@ejemplo.local");
        base = PostgresPruebas.registrar(registro);
    }

    @LocalServerPort
    int puerto;

    @Autowired
    TestRestTemplate http;

    @Autowired
    RegistroLecturas lecturas;

    @BeforeEach
    void reiniciar() {
        post("/api/v1/local/reinicio", JsonJs.objetoVacio().put("fecha", "2026-11-05").put("email", "usuario01@ejemplo.local"));
        lecturas.olvidarTodo();
    }

    private ResponseEntity<JsonNode> post(String ruta, JsonNode cuerpo) {
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity("http://127.0.0.1:" + puerto + ruta,
                new HttpEntity<>(cuerpo == null ? "{}" : JsonJs.escribir(cuerpo), cabeceras), JsonNode.class);
    }

    /** Huella del contenido de las tablas de listas compartidas. */
    private static String huella() throws SQLException {
        try (Connection c = PostgresPruebas.conectar(base, "tbp_consulta"); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT md5(string_agg(t, '|' ORDER BY t)) FROM ("
                     + "SELECT 'p' || titulo || coalesce(seq::text, '') AS t FROM tbp.proyecto UNION ALL "
                     + "SELECT 'b' || nombre || coalesce(seq::text, '') || coalesce(org, '') FROM tbp.banco UNION ALL "
                     + "SELECT 'r' || nombre FROM tbp.responsable UNION ALL "
                     + "SELECT 'u' || email || rol FROM tbp.usuario UNION ALL "
                     + "SELECT 'q' || nombre FROM tbp.recurso) x")) {
            rs.next();
            return rs.getString(1);
        }
    }

    private ObjectNode configuracionSinUnProyecto() {
        ObjectNode cfg = (ObjectNode) post("/api/load_config", null).getBody();
        ((ArrayNode) cfg.get("projects")).remove(0);
        return cfg;
    }

    @Test
    void sinLecturaPreviaNoSeEscribe() throws SQLException {
        ObjectNode cfg = configuracionSinUnProyecto();
        lecturas.olvidarTodo();
        String antes = huella();
        assertEquals(412, post("/api/save_config", cfg).getStatusCode().value());
        assertEquals(antes, huella());
    }

    @Test
    void conLecturaPreviaSeGuarda() throws SQLException {
        ObjectNode cfg = configuracionSinUnProyecto();
        String antes = huella();
        assertEquals(200, post("/api/save_config", cfg).getStatusCode().value());
        assertEquals(cfg.get("projects").size(), post("/api/load_config", null).getBody().get("projects").size());
        assertTrue(!antes.equals(huella()));
    }

    @Test
    void vaciarProyectosNoSeEscribe() throws SQLException {
        ObjectNode cfg = (ObjectNode) post("/api/load_config", null).getBody();
        cfg.putArray("projects");
        String antes = huella();
        ResponseEntity<JsonNode> r = post("/api/save_config", cfg);
        assertEquals(412, r.getStatusCode().value());
        assertTrue(r.getBody().path("mensaje").asText().contains("vacía"));
        assertEquals(antes, huella());
    }

    @Test
    void lecturaVaciaNoHabilitaLaEscritura() throws SQLException {
        ObjectNode cfg = (ObjectNode) post("/api/load_config", null).getBody();
        PostgresPruebas.ejecutarComoSuperusuario(base, "DELETE FROM tbp.banco");
        post("/api/load_config", null);
        String antes = huella();
        assertEquals(412, post("/api/save_config", cfg).getStatusCode().value());
        assertEquals(antes, huella());
    }

    @Test
    void usuariosSoloTrasWhoamiYNuncaVacios() throws SQLException {
        String antes = huella();
        ObjectNode vacio = JsonJs.objetoVacio();
        vacio.putArray("users");
        assertEquals(412, post("/api/save_users", vacio).getStatusCode().value());   // sin whoami
        post("/api/whoami", null);
        assertEquals(412, post("/api/save_users", vacio).getStatusCode().value());   // dejarla vacía
        assertEquals(antes, huella());
    }

    @Test
    void recursosSoloTrasLeerlos() throws SQLException {
        JsonNode recursos = post("/api/load_resources", null).getBody();
        lecturas.olvidarTodo();
        String antes = huella();
        assertEquals(412, post("/api/save_resources", recursos).getStatusCode().value());
        assertEquals(antes, huella());
        post("/api/load_resources", null);
        assertEquals(200, post("/api/save_resources", recursos).getStatusCode().value());
    }
}
