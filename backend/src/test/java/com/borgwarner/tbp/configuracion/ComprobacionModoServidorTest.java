package com.borgwarner.tbp.configuracion;

import com.borgwarner.tbp.TestBenchPlannerApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Hito S2: el modo servidor no arranca con una configuración insegura. */
class ComprobacionModoServidorTest {

    @Test
    void fueraDelModoServidorNoSeExigeNada() {
        assertNull(ComprobacionModoServidor.motivoParaNoArrancar(false, "local", true));
        assertNull(ComprobacionModoServidor.motivoParaNoArrancar(false, "sharepoint", false));
    }

    @Test
    void elServidorSoloFuncionaConPostgresql() {
        assertNull(ComprobacionModoServidor.motivoParaNoArrancar(true, "postgresql", false));
        assertNotNull(ComprobacionModoServidor.motivoParaNoArrancar(true, "sharepoint", false));
        assertNotNull(ComprobacionModoServidor.motivoParaNoArrancar(true, "local", false));
        assertNotNull(ComprobacionModoServidor.motivoParaNoArrancar(true, null, false));
    }

    @Test
    void elServidorNoAdmiteElReinicioDePruebas() {
        assertNotNull(ComprobacionModoServidor.motivoParaNoArrancar(true, "postgresql", true));
    }

    @Test
    void laAplicacionNoArrancaYDicePorQue() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new SpringApplicationBuilder(TestBenchPlannerApplication.class)
                .run("--planner.servidor.activo=true", "--planner.almacen=sharepoint", "--server.port=0"));
        assertTrue(e.getMessage().contains("postgresql"), e.getMessage());
    }
}
