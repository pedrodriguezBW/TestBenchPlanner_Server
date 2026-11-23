package com.borgwarner.tbp.api.comun;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Hito S2: qué rutas cierra el modo servidor. */
class FiltroModoServidorTest {

    @Test
    void cierraLaVersionClasicaYSuApi() {
        assertEquals(FiltroModoServidor.Bloqueo.CLASICA, FiltroModoServidor.bloqueo("/clasico", true));
        assertEquals(FiltroModoServidor.Bloqueo.API_ANTIGUA, FiltroModoServidor.bloqueo("/api/save", true));
        assertEquals(FiltroModoServidor.Bloqueo.API_ANTIGUA, FiltroModoServidor.bloqueo("/api/load_config", true));
        assertEquals(FiltroModoServidor.Bloqueo.API_ANTIGUA, FiltroModoServidor.bloqueo("/api/save_users", true));
    }

    @Test
    void dejaPasarLaInterfazNueva() {
        assertNull(FiltroModoServidor.bloqueo("/", true));
        assertNull(FiltroModoServidor.bloqueo("/assets/index.js", true));
        assertNull(FiltroModoServidor.bloqueo("/api/v1/planificacion", true));
        assertNull(FiltroModoServidor.bloqueo("/api/v1/exportaciones/plantilla-solo-lectura", true));
        assertNull(FiltroModoServidor.bloqueo("/api/heartbeat", true));
        assertNull(FiltroModoServidor.bloqueo("/api/shutdown", true));
        assertNull(FiltroModoServidor.bloqueo("/clasicos", true));
    }

    @Test
    void sinInterfazCompiladaAvisaEnLugarDeMandarAClasico() {
        assertEquals(FiltroModoServidor.Bloqueo.SIN_INTERFAZ, FiltroModoServidor.bloqueo("/", false));
        assertNull(FiltroModoServidor.bloqueo("/api/v1/sesion", false));
    }
}
