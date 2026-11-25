package com.borgwarner.tbp.infraestructura.postgresql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NombresProyectoTest {

    @Test
    void numerosDeLaSerie8EnFormatoLargo() {
        assertEquals("80000834", NombresProyecto.numeroLargo("80834"));
        assertEquals("80001886", NombresProyecto.numeroLargo("81886"));
        assertEquals("80001786", NombresProyecto.numeroLargo("801786"));
        assertEquals("80000475", NombresProyecto.numeroLargo("8000475"));
        assertEquals("80001786", NombresProyecto.numeroLargo("800001786"));
        assertEquals("80000834", NombresProyecto.numeroLargo(" 80000834 "));
        // Otras series y valores raros no se tocan.
        assertEquals("10766", NombresProyecto.numeroLargo("10766"));
        assertEquals("00877", NombresProyecto.numeroLargo("00877"));
        assertEquals("805", NombresProyecto.numeroLargo("805"));
        assertEquals("463801054", NombresProyecto.numeroLargo("463801054"));
        assertEquals("", NombresProyecto.numeroLargo(null));
    }

    @Test
    void etiquetaYSeparacion() {
        assertEquals("80001755 - Rimac - eCooler", NombresProyecto.etiqueta("Rimac - eCooler", "80001755"));
        assertEquals("Lab", NombresProyecto.etiqueta("Lab", ""));
        // Sin separar: la etiqueta es el nombre tal cual.
        assertEquals("80002613 VW EGR", NombresProyecto.etiqueta("80002613 VW EGR", "80002613"));
        assertEquals("00042 - Prueba", NombresProyecto.etiqueta("00042 - Prueba", "00043"));

        NombresProyecto.Partes p = NombresProyecto.separarExacto("80001755 - Rimac - eCooler");
        assertEquals("80001755", p.numero());
        assertEquals("Rimac - eCooler", p.nombre());
        assertNull(NombresProyecto.separarExacto("80001755 -Rimac"));
        assertNull(NombresProyecto.separarExacto("80002613 VW EGR"));
        assertNull(NombresProyecto.separarExacto("Lab"));
        // No se separa si la etiqueta no vuelve a dar el mismo texto.
        assertNull(NombresProyecto.separarExacto("80001755 - 80001755 Rimac"));
        assertNull(NombresProyecto.separarExacto("80002000 - 12345-A Proto"));

        NombresProyecto.Partes nuevo = NombresProyecto.separarNuevo("80834 - Stellantis Small");
        assertEquals("80000834", nuevo.numero());
        assertEquals("Stellantis Small", nuevo.nombre());
        assertEquals("", NombresProyecto.separarNuevo("Mejoras laboratorio").numero());

        assertTrue(NombresProyecto.nombreConNumero("80001755 -Rimac"));
        assertTrue(NombresProyecto.nombreConNumero("80002613 VW EGR"));
        assertFalse(NombresProyecto.nombreConNumero("Rimac 2026"));
    }
}
