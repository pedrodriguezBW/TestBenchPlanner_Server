package com.borgwarner.tbp.integracion;

import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.postgresql.PostgresPruebas;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Las mismas pruebas que ApiLocalTest con planner.almacen=postgresql. */
class ApiPostgresTest extends ApiLocalTest {

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registro) {
        PostgresPruebas.registrar(registro);
    }

    /** Cada prueba parte del escenario original (en la base, en vez de copiar planner.json). */
    @BeforeEach
    @Override
    void reiniciar() {
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setContentType(MediaType.APPLICATION_JSON);
        String cuerpo = JsonJs.escribir(JsonJs.objetoVacio().put("fecha", "2026-11-05").put("email", "usuario01@ejemplo.local"));
        assertEquals(200, http.postForEntity("http://127.0.0.1:" + puerto + "/api/v1/local/reinicio",
                new HttpEntity<>(cuerpo, cabeceras), String.class).getStatusCode().value());
    }
}
