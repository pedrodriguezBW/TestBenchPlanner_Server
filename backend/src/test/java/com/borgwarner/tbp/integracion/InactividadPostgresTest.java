package com.borgwarner.tbp.integracion;

import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.infraestructura.postgresql.CuentasPostgres;
import com.borgwarner.tbp.infraestructura.postgresql.ImportadorDatos;
import com.borgwarner.tbp.postgresql.PostgresPruebas;
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

/**
 * Hito S3 · cierre de sesión por inactividad (aquí 3 s en lugar de 30 min).
 * Los sondeos automáticos de la interfaz (X-TBP-Inactivo alto) no alargan la
 * sesión; un clic o una tecla (X-TBP-Inactivo bajo) sí.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InactividadPostgresTest {

    private static final String ADMIN = "usuario01@ejemplo.local";
    private static final String CLAVE = "Clave-de-prueba-1";

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) throws IOException {
        Path carpeta = Files.createTempDirectory("tbp-inactividad");
        PostgresPruebas.registrar(registro);
        registro.add("planner.postgresql.reinicio-pruebas", () -> "false");
        registro.add("planner.servidor.activo", () -> "true");
        registro.add("planner.fecha-fija", () -> "2026-11-05");
        registro.add("planner.carpeta-usuario", carpeta::toString);
        registro.add("planner.autenticacion.inactividad", () -> "3s");
        registro.add("planner.autenticacion.intentos-maximos", () -> "2");
        registro.add("planner.autenticacion.bloqueo", () -> "2s");
    }

    @LocalServerPort
    int puerto;

    @Autowired
    ImportadorDatos importador;

    @Autowired
    CuentasPostgres cuentas;

    @BeforeEach
    void datos() {
        importador.reiniciar(PostgresPruebas.escenario());
        cuentas.establecerAdmin(ADMIN, "", CLAVE);
    }

    private NavegadorPruebas dentro() {
        NavegadorPruebas n = new NavegadorPruebas(puerto);
        assertEquals("/", NavegadorPruebas.destino(n.entrar(ADMIN, CLAVE)));
        return n;
    }

    private static void esperar(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void losSondeosNoAlarganLaSesion() {
        NavegadorPruebas n = dentro();
        assertEquals(200, n.get("/api/v1/sesion", "X-TBP-Inactivo", "0").statusCode());
        esperar(1500);
        // Relectura automática: la persona no ha tocado nada desde hace rato.
        assertEquals(200, n.get("/api/v1/planificacion", "X-TBP-Inactivo", "600").statusCode());
        esperar(2200);
        HttpResponse<String> r = n.get("/api/v1/planificacion", "X-TBP-Inactivo", "600");
        assertEquals(401, r.statusCode());
        assertEquals("sin-sesion", JsonJs.leer(r.body()).path("error").asText());
        assertEquals("inactividad", JsonJs.leer(r.body()).path("motivo").asText());
        // Una página (no la API) lleva al formulario con el motivo.
        NavegadorPruebas otra = dentro();
        esperar(3600);
        assertEquals("/login?inactividad", NavegadorPruebas.destino(otra.get("/")));
    }

    @Test
    void usarLaAplicacionMantieneLaSesion() {
        NavegadorPruebas n = dentro();
        for (int i = 0; i < 5; i++) {
            esperar(1000);
            assertEquals(200, n.get("/api/v1/sesion", "X-TBP-Inactivo", "0").statusCode(), "vuelta " + i);
        }
    }

    @Test
    void elBloqueoTemporalCaduca() {
        NavegadorPruebas n = new NavegadorPruebas(puerto);
        assertEquals("/login?error", NavegadorPruebas.destino(n.entrar(ADMIN, "mal-1")));
        assertEquals("/login?bloqueada", NavegadorPruebas.destino(n.entrar(ADMIN, "mal-2")));
        assertEquals("/login?bloqueada", NavegadorPruebas.destino(n.entrar(ADMIN, CLAVE)));
        esperar(2500);
        assertEquals("/", NavegadorPruebas.destino(n.entrar(ADMIN, CLAVE)));
    }
}
