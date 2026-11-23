package com.borgwarner.tbp.postgresql;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/** El MISMO contrato de los almacenes contra PostgreSQL (escenario cargado con el importador). */
class ContratoAlmacenesPostgresTest extends ContratoAlmacenes {

    private static BaseDePruebas base;

    @BeforeAll
    static void abrir() {
        base = new BaseDePruebas();
    }

    @AfterAll
    static void cerrar() {
        base.close();
    }

    @Override
    protected Almacenes preparar() {
        base.importador.reiniciar(PostgresPruebas.escenario());
        return base.almacenes;
    }
}
