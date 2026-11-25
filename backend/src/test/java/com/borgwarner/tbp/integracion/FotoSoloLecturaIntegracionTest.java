package com.borgwarner.tbp.integracion;

import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 📤 Export read-only: los indicadores precalculados banco a banco y semana a
 * semana, sumados como lo hace la foto en el navegador, dan lo mismo que los
 * endpoints del dashboard (KPI, ocupación, By Responsible y estadísticas).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FotoSoloLecturaIntegracionTest {

    private static Path carpeta;

    private static Path escenario() {
        return Path.of(System.getProperty("escenarios.dir", "../datos/escenarios"), "sharepoint-2026-11-05");
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) throws IOException {
        carpeta = Files.createTempDirectory("tbp-foto");
        registro.add("planner.fecha-fija", () -> "2026-11-05");
        registro.add("planner.carpeta-usuario", () -> carpeta.toString());
        registro.add("planner.local.carpeta-datos", () -> carpeta.resolve("datos").toString());
        registro.add("planner.local.escenario-inicial",
                () -> escenario().toString());
        registro.add("planner.local.reiniciar-al-arrancar", () -> "true");
        registro.add("planner.local.usuario-email", () -> "usuario01@ejemplo.local");
    }

    @LocalServerPort
    int puerto;

    @Autowired
    TestRestTemplate http;

    private JsonNode get(String ruta) {
        return http.getForObject("http://127.0.0.1:" + puerto + ruta, JsonNode.class);
    }

    @BeforeEach
    void empezar() {
        // Sin el escenario anonimizado (no viaja en todos los zip) no hay datos con los que comparar.
        Assumptions.assumeTrue(Files.isDirectory(escenario()), "falta el escenario " + escenario());
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setContentType(MediaType.APPLICATION_JSON);
        ObjectNode cuerpo = JsonJs.objetoVacio().put("escenario", "sharepoint-2026-11-05")
                .put("fecha", "2026-11-05").put("email", "usuario01@ejemplo.local");
        http.exchange("http://127.0.0.1:" + puerto + "/api/v1/local/reinicio", HttpMethod.POST,
                new HttpEntity<>(JsonJs.escribir(cuerpo), cabeceras), JsonNode.class);
    }

    private static int indice(JsonNode foto, String lunes) {
        for (int i = 0; i < foto.path("lunes").size(); i++) {
            if (lunes.equals(foto.path("lunes").get(i).asText())) {
                return i;
            }
        }
        return -1;
    }

    /** Suma de [bancos con carga, corriendo, en retraso, sugerencias] de todos los bancos de la foto. */
    private static int[] sumaKpi(JsonNode foto, int semana, String responsable) {
        int[] suma = new int[4];
        for (Iterator<JsonNode> it = foto.path("bancos").elements(); it.hasNext(); ) {
            JsonNode s = it.next().get(semana);
            JsonNode v = responsable == null ? s.path("k") : s.path("r").path(responsable);
            for (int i = 0; i < 4 && v.isArray(); i++) {
                suma[i] += v.get(i).asInt();
            }
        }
        return suma;
    }

    private static void comparar(JsonNode bloque, int total, int[] suma) {
        assertEquals(bloque.path("total").asInt(), total);
        assertEquals(bloque.path("benches").asInt(), suma[0]);
        assertEquals(bloque.path("accr").asInt(), suma[1]);
        assertEquals(bloque.path("delayed").asInt(), suma[2]);
        assertEquals(bloque.path("sug").asInt(), suma[3]);
    }

    @Test
    void losKpiYLaOcupacionSumadosPorBancoCoincidenConElDashboard() {
        JsonNode foto = get("/api/v1/indicadores/foto-solo-lectura");
        assertEquals("THERMAL", foto.path("area").asText());
        int total = foto.path("bancos").size();
        assertTrue(total > 0);
        int semanasConCarga = 0;
        for (String lunes : List.of("2026-10-26", "2026-11-02", "2026-11-09", "2026-11-16", "2026-01-05", "2026-12-21")) {
            int i = indice(foto, lunes);
            assertTrue(i >= 0, "la foto incluye la semana " + lunes);
            JsonNode bloques = get("/api/v1/indicadores/bloques?org=THERMAL&lunes=" + lunes);
            comparar(bloques.path("estaSemana"), total, sumaKpi(foto, i, null));
            comparar(bloques.path("siguiente"), total, sumaKpi(foto, i + 1, null));

            String domingo = LocalDate.parse(lunes).plusDays(6).toString();
            JsonNode tabla = get("/api/v1/indicadores/tabla-ocupacion?org=THERMAL&desde=" + lunes + "&hasta=" + domingo);
            long busy = 0;
            int trabajando = 0, inicia = 0, acaba = 0;
            for (Iterator<JsonNode> it = foto.path("bancos").elements(); it.hasNext(); ) {
                JsonNode o = it.next().get(i).path("o");
                trabajando += o.get(0).asInt();
                inicia += o.get(1).asInt();
                acaba += o.get(2).asInt();
                busy += o.get(3).asLong();
            }
            int trabajandoTabla = 0, iniciaTabla = 0, acabaTabla = 0;
            for (JsonNode g : tabla.path("grupos")) {
                trabajandoTabla += g.path("trabajando").asInt();
                iniciaTabla += g.path("inicia").asInt();
                acabaTabla += g.path("acaba").asInt();
            }
            assertEquals(tabla.path("busy").asLong(), busy);
            assertEquals(tabla.path("cap").asLong(), 7L * 24 * total);
            assertEquals(trabajandoTabla, trabajando);
            assertEquals(iniciaTabla, inicia);
            assertEquals(acabaTabla, acaba);
            if (busy > 0) {
                semanasConCarga++;
            }
        }
        assertTrue(semanasConCarga > 0, "el escenario tiene ocupación en alguna de las semanas comprobadas");
    }

    @Test
    void porResponsableCoincideConByResponsible() {
        JsonNode foto = get("/api/v1/indicadores/foto-solo-lectura");
        int total = foto.path("bancos").size();
        int i = indice(foto, "2026-11-02");
        List<String> responsables = new ArrayList<>();
        for (Iterator<JsonNode> it = foto.path("bancos").elements(); it.hasNext(); ) {
            for (JsonNode semana : it.next()) {
                semana.path("r").fieldNames().forEachRemaining(r -> {
                    if (!responsables.contains(r)) {
                        responsables.add(r);
                    }
                });
            }
        }
        assertFalse(responsables.isEmpty());
        for (String r : responsables.subList(0, Math.min(4, responsables.size()))) {
            // Variable de plantilla: Spring la codifica una sola vez (nombres con tildes o «ñ»).
            JsonNode bloques = http.getForObject("http://127.0.0.1:" + puerto
                    + "/api/v1/indicadores/bloques?org=THERMAL&lunes=2026-11-02&responsable={r}", JsonNode.class, r);
            comparar(bloques.path("estaSemana"), total, sumaKpi(foto, i, r));
            comparar(bloques.path("siguiente"), total, sumaKpi(foto, i + 1, r));
        }
    }

    @Test
    void historicoYEstadisticasDeThermal() {
        JsonNode foto = get("/api/v1/indicadores/foto-solo-lectura");
        for (String org : List.of("THERMAL", "BW", "CTAG")) {
            assertEquals(get("/api/v1/indicadores/historico?org=" + org), foto.path("historico").path(org));
            assertEquals(get("/api/v1/indicadores/estado-fotos?org=" + org), foto.path("estadoFotos").path(org));
        }
        JsonNode todas = get("/api/v1/indicadores/estadisticas?org=THERMAL&desde=1900-01-01&hasta=2999-12-31");
        assertEquals(todas, foto.path("estadisticas"));
    }
}
