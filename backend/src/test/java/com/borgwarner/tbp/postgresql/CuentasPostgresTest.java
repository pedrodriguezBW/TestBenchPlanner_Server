package com.borgwarner.tbp.postgresql;

import com.borgwarner.tbp.configuracion.PropiedadesAutenticacion;
import com.borgwarner.tbp.infraestructura.postgresql.CuentasPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Hito S3 · cuentas en PostgreSQL sin servidor web: reglas, bloqueo, sellos y rescate del Admin. */
class CuentasPostgresTest {

    private static final String ADMIN = "usuario01@ejemplo.local";
    private static final String EDITOR = "usuario07@ejemplo.local";
    private static BaseDePruebas base;
    private static CuentasPostgres cuentas;

    @BeforeAll
    static void abrir() {
        base = new BaseDePruebas();
        cuentas = new CuentasPostgres(base.bd, new PropiedadesAutenticacion(3, Duration.ofMinutes(15), Duration.ofMinutes(30), 10));
    }

    @AfterAll
    static void cerrar() {
        base.close();
    }

    @BeforeEach
    void datos() {
        base.importador.reiniciar(PostgresPruebas.escenario());
    }

    @Test
    void reglasDeLaClave() {
        assertNotNull(cuentas.motivoRechazo(EDITOR, "corta"));
        assertNotNull(cuentas.motivoRechazo(EDITOR, EDITOR));
        assertNotNull(cuentas.motivoRechazo(EDITOR, "aaaaaaaaaaaa"));
        assertNotNull(cuentas.motivoRechazo(EDITOR, "x".repeat(40) + "yz12" + "w".repeat(40)));
        assertNull(cuentas.motivoRechazo(EDITOR, "Una-clave-buena-7"));
    }

    @Test
    void laTemporalSoloSirveParaCambiarla() {
        String temporal = cuentas.claveTemporal(CuentasPostgres.INSTALACION, EDITOR);
        CuentasPostgres.Acceso a = cuentas.autenticar(EDITOR.toUpperCase(), temporal);
        assertEquals(CuentasPostgres.Resultado.CORRECTO, a.resultado(), "el email no distingue mayúsculas");
        assertTrue(a.debeCambiar());
        int sello = cuentas.cambiarClave(EDITOR, temporal, "Una-clave-buena-7");
        assertNotEquals(a.sello(), sello);
        assertEquals(sello, cuentas.sello(EDITOR));
        CuentasPostgres.Acceso b = cuentas.autenticar(EDITOR, "Una-clave-buena-7");
        assertEquals(CuentasPostgres.Resultado.CORRECTO, b.resultado());
        assertFalse(b.debeCambiar());
        assertEquals(CuentasPostgres.Resultado.INCORRECTO, cuentas.autenticar(EDITOR, temporal).resultado());
        assertThrows(CuentasPostgres.ClaveNoValida.class, () -> cuentas.cambiarClave(EDITOR, "Una-clave-buena-7", "Una-clave-buena-7"));
    }

    @Test
    void cambiarConLaActualMalCuentaComoFallo() {
        String temporal = cuentas.claveTemporal(CuentasPostgres.INSTALACION, EDITOR);
        assertThrows(CuentasPostgres.ClaveNoValida.class, () -> cuentas.cambiarClave(EDITOR, "no-es", "Una-clave-buena-7"));
        assertThrows(CuentasPostgres.ClaveNoValida.class, () -> cuentas.cambiarClave(EDITOR, "no-es", "Una-clave-buena-7"));
        CuentasPostgres.ClaveNoValida tercera = assertThrows(CuentasPostgres.ClaveNoValida.class,
                () -> cuentas.cambiarClave(EDITOR, "no-es", "Una-clave-buena-7"));
        assertTrue(tercera.bloqueada());
        assertEquals(CuentasPostgres.Resultado.BLOQUEADA, cuentas.autenticar(EDITOR, temporal).resultado());
        assertTrue(cuentas.desbloquear(CuentasPostgres.INSTALACION, EDITOR));
        assertEquals(CuentasPostgres.Resultado.CORRECTO, cuentas.autenticar(EDITOR, temporal).resultado());
    }

    @Test
    void unaTemporalNuevaCierraLasSesionesYDesbloquea() {
        cuentas.claveTemporal(CuentasPostgres.INSTALACION, EDITOR);
        Integer antes = cuentas.sello(EDITOR);
        for (int i = 0; i < 3; i++) {
            cuentas.autenticar(EDITOR, "mal");
        }
        String otra = cuentas.claveTemporal(CuentasPostgres.INSTALACION, EDITOR);
        assertNotEquals(antes, cuentas.sello(EDITOR));
        assertEquals(CuentasPostgres.Resultado.CORRECTO, cuentas.autenticar(EDITOR, otra).resultado());
    }

    @Test
    void unEmailSinCuentaSeBloqueaIgual() {
        String nadie = "fantasma@ejemplo.local";
        assertEquals(CuentasPostgres.Resultado.INCORRECTO, cuentas.autenticar(nadie, "a").resultado());
        assertEquals(CuentasPostgres.Resultado.INCORRECTO, cuentas.autenticar(nadie, "b").resultado());
        assertEquals(CuentasPostgres.Resultado.BLOQUEADA, cuentas.autenticar(nadie, "c").resultado());
        assertEquals(CuentasPostgres.Resultado.BLOQUEADA, cuentas.autenticar(nadie, "d").resultado());
    }

    @Test
    void sinEstarEnLaListaNoHayTemporal() {
        assertThrows(IllegalArgumentException.class, () -> cuentas.claveTemporal(CuentasPostgres.INSTALACION, "nadie@ejemplo.local"));
    }

    @Test
    void elPrimerAdminSeCreaOSeRescata() {
        assertFalse(cuentas.hayAdminConClave());
        String hecho = cuentas.establecerAdmin("Nueva.Persona@Ejemplo.local", "Nueva Persona", "Clave-admin-123");
        assertTrue(hecho.contains("añadido"), hecho);
        assertEquals("Admin", base.bd.jdbc().queryForObject("SELECT rol FROM usuario WHERE email = 'nueva.persona@ejemplo.local'", String.class));
        assertTrue(cuentas.hayAdminConClave());
        CuentasPostgres.Acceso a = cuentas.autenticar("nueva.persona@ejemplo.local", "Clave-admin-123");
        assertEquals(CuentasPostgres.Resultado.CORRECTO, a.resultado());
        assertFalse(a.debeCambiar(), "la eligió en el servidor");
        assertTrue(cuentas.establecerAdmin(EDITOR, "", "Clave-admin-123").contains("a Admin"));
        assertTrue(cuentas.establecerAdmin(ADMIN, "", "Clave-admin-123").contains("ya era Admin"));
        assertThrows(CuentasPostgres.ClaveNoValida.class, () -> cuentas.establecerAdmin(ADMIN, "", "corta"));
    }

    @Test
    void elEstadoNoEnsenaClaves() {
        cuentas.claveTemporal(CuentasPostgres.INSTALACION, EDITOR);
        String json = cuentas.estados().toString();
        assertTrue(json.contains(EDITOR));
        assertFalse(json.contains("$2a$"), "sin hashes");
        assertFalse(json.contains("hash"));
    }

    @Test
    void reimportarNoBorraLasCuentas() {
        cuentas.establecerAdmin(ADMIN, "", "Clave-admin-123");
        base.importador.importar(PostgresPruebas.escenario(), false);
        assertEquals(CuentasPostgres.Resultado.CORRECTO, cuentas.autenticar(ADMIN, "Clave-admin-123").resultado());
    }
}
