package com.borgwarner.tbp.api.acceso;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Hito S3 · piezas del inicio de sesión que no necesitan servidor. */
class FiltroOrigenTest {

    @Test
    void mismoOrigenSoloSiCoincideServidorYPuerto() {
        assertTrue(FiltroOrigen.mismoOrigen("http://VGELTOFF0212:5001", "vgeltoff0212:5001"));
        assertTrue(FiltroOrigen.mismoOrigen("https://servidor", "servidor"));
        assertFalse(FiltroOrigen.mismoOrigen("http://servidor:5002", "servidor:5001"));
        assertFalse(FiltroOrigen.mismoOrigen("http://malo.example", "servidor:5001"));
        assertFalse(FiltroOrigen.mismoOrigen("null", "servidor:5001"));
        assertFalse(FiltroOrigen.mismoOrigen("http://servidor:5001", null));
    }

    @Test
    void segundosInactivoQueDiceLaInterfaz() {
        MockHttpServletRequest p = new MockHttpServletRequest();
        assertEquals(0, FiltroSesion.segundosInactivo(p), "sin cabecera = actividad ahora");
        p.addHeader(SesionServidor.CABECERA_INACTIVO, "42");
        assertEquals(42, FiltroSesion.segundosInactivo(p));
        MockHttpServletRequest negativo = new MockHttpServletRequest();
        negativo.addHeader(SesionServidor.CABECERA_INACTIVO, "-5");
        assertEquals(0, FiltroSesion.segundosInactivo(negativo));
        MockHttpServletRequest texto = new MockHttpServletRequest();
        texto.addHeader(SesionServidor.CABECERA_INACTIVO, "mucho");
        assertEquals(0, FiltroSesion.segundosInactivo(texto));
    }

    @Test
    void lasPaginasDeAccesoEscapanLoQueEnsenan() {
        String html = PaginasAcceso.cambiar("<script>alert(1)</script>", true, 10, "\"x\"");
        assertFalse(html.contains("<script>alert(1)"));
        assertTrue(html.contains("&lt;script&gt;"));
    }
}
