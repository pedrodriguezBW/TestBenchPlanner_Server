package com.borgwarner.tbp.integracion;

import com.borgwarner.tbp.postgresql.PostgresPruebas;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Las mismas pruebas que CopiaSeguridadTest con planner.almacen=postgresql. */
class CopiaSeguridadPostgresTest extends CopiaSeguridadTest {

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registro) {
        PostgresPruebas.registrar(registro);
    }
}
