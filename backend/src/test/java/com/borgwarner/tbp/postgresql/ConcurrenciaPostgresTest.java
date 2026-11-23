package com.borgwarner.tbp.postgresql;

import com.borgwarner.tbp.aplicacion.puertos.ConflictoEdicion;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.planificacion.CambioFila;
import com.borgwarner.tbp.dominio.planificacion.FilaGuardada;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Varias personas guardando A LA VEZ el mismo ensayo partiendo de la misma
 * versión: exactamente una gana y las demás reciben el conflicto (409), y la
 * versión sube una sola vez.
 */
class ConcurrenciaPostgresTest {

    private static BaseDePruebas base;

    @BeforeAll
    static void abrir() {
        base = new BaseDePruebas();
        base.importador.reiniciar(PostgresPruebas.escenario());
    }

    @AfterAll
    static void cerrar() {
        base.close();
    }

    @Test
    void soloUnoGanaYLosDemasRecibenConflicto() throws Exception {
        FilaGuardada f = base.almacenes.ensayos().leer().get(10);
        long version = base.bd.jdbc().queryForObject("SELECT version FROM ensayo WHERE uid = ?", Long.class, f.uid());
        int personas = 5;
        CountDownLatch salida = new CountDownLatch(1);
        ExecutorService hilos = Executors.newFixedThreadPool(personas);
        List<Future<String>> resultados = new ArrayList<>();
        for (int i = 0; i < personas; i++) {
            ObjectNode e = JsonJs.leerObjeto(f.texto());
            e.put("comments", "persona " + i);
            String texto = JsonJs.escribir(e);
            Callable<String> guardar = () -> {
                salida.await();
                try {
                    base.almacenes.ensayos().guardar(List.of(new CambioFila(f.uid(), texto, f.texto())), true, ContratoAlmacenes.ACTOR);
                    return "ok";
                } catch (ConflictoEdicion c) {
                    return "conflicto";
                }
            };
            resultados.add(hilos.submit(guardar));
        }
        salida.countDown();
        int ok = 0, conflictos = 0;
        for (Future<String> r : resultados) {
            if ("ok".equals(r.get())) {
                ok++;
            } else {
                conflictos++;
            }
        }
        hilos.shutdown();
        assertEquals(1, ok);
        assertEquals(personas - 1, conflictos);
        assertEquals(version + 1, base.bd.jdbc().queryForObject("SELECT version FROM ensayo WHERE uid = ?", Long.class, f.uid()));
    }

    @Test
    void dosAltasDelMismoEnsayoALaVez() throws Exception {
        int personas = 4;
        CountDownLatch salida = new CountDownLatch(1);
        ExecutorService hilos = Executors.newFixedThreadPool(personas);
        List<Future<String>> resultados = new ArrayList<>();
        for (int i = 0; i < personas; i++) {
            String texto = "{\"id\":\"zz-simultaneo\",\"comments\":\"persona " + i + "\"}";
            resultados.add(hilos.submit(() -> {
                salida.await();
                try {
                    base.almacenes.ensayos().guardar(List.of(new CambioFila("zz-simultaneo", texto, null)), true, ContratoAlmacenes.ACTOR);
                    return "ok";
                } catch (ConflictoEdicion c) {
                    return "conflicto";
                }
            }));
        }
        salida.countDown();
        int ok = 0;
        for (Future<String> r : resultados) {
            ok += "ok".equals(r.get()) ? 1 : 0;
        }
        hilos.shutdown();
        assertEquals(1, ok);
        assertEquals(1, base.bd.jdbc().queryForObject("SELECT count(*) FROM ensayo WHERE uid = 'zz-simultaneo'", Integer.class));
    }
}
