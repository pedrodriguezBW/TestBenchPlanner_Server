package com.borgwarner.tbp.postgresql;

import com.borgwarner.tbp.aplicacion.EstadoConexion;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenUsuarios;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.planificacion.CambioFila;
import com.borgwarner.tbp.dominio.planificacion.FilaGuardada;
import com.borgwarner.tbp.infraestructura.local.AlmacenEnsayosLocal;
import com.borgwarner.tbp.infraestructura.local.AlmacenesAuxiliaresLocales;
import com.borgwarner.tbp.infraestructura.local.DatosLocales;
import com.borgwarner.tbp.infraestructura.migracion.CarpetaExportacion;
import com.borgwarner.tbp.infraestructura.migracion.ExportadorDatos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Migración en dos pasos: exportación (con los adaptadores, aquí los del modo
 * local) → importación en PostgreSQL. Idempotencia, simulación, verificación
 * de paridad y protección del manifiesto.
 */
class ImportacionPostgresTest {

    private static BaseDePruebas base;

    @TempDir
    Path tmp;

    Path exportacion;

    @BeforeAll
    static void abrir() {
        base = new BaseDePruebas();
    }

    @AfterAll
    static void cerrar() {
        base.close();
    }

    /** Exporta el escenario anonimizado como lo haría el PC con SharePoint (mismos puertos). */
    @BeforeEach
    void exportar() {
        DatosLocales datos = new DatosLocales(tmp.resolve("origen"), PostgresPruebas.escenario(), true);
        var colaboracion = new AlmacenesAuxiliaresLocales.ColaboracionLocal(datos);
        colaboracion.anotarActividad("usuario01@ejemplo.local", "Persona 01", "connect", "");
        colaboracion.anotarActividad("usuario02@ejemplo.local", "Persona 02", "edit", "created 1, updated 0, deleted 0");
        ExportadorDatos exportador = new ExportadorDatos(new AlmacenEnsayosLocal(datos, colaboracion),
                new AlmacenesAuxiliaresLocales.Catalogos(datos, false),
                new AlmacenesAuxiliaresLocales.Usuarios(datos, new AlmacenUsuarios.Identidad("", ""), false),
                new AlmacenesAuxiliaresLocales.Recursos(datos, colaboracion, false), new AlmacenesAuxiliaresLocales.HistoricoKpi(datos),
                new AlmacenesAuxiliaresLocales.Aprobaciones(datos), new AlmacenesAuxiliaresLocales.TiposEquipo(datos), colaboracion,
                new EstadoConexion());
        exportacion = tmp.resolve("exportacion");
        exportador.exportar(exportacion, "local", "escenario de pruebas", 5000);
        // Base vacía (solo estructura) antes de cada prueba.
        base.importador.reiniciar(vacio());
    }

    /** Escenario mínimo para dejar la base vacía con reiniciar(). */
    private Path vacio() {
        Path v = tmp.resolve("vacio");
        CarpetaExportacion c = new CarpetaExportacion(v);
        c.escribir("planner.json", JsonJs.mapper().createArrayNode());
        c.escribir("configuracion.json", JsonJs.objetoVacio());
        c.escribir("usuarios.json", JsonJs.mapper().createArrayNode());
        return v;
    }

    private static int totalCambios(JsonNode informe) {
        int total = 0;
        Iterator<Map.Entry<String, JsonNode>> it = informe.path("tablas").fields();
        while (it.hasNext()) {
            JsonNode r = it.next().getValue();
            total += r.path("created").asInt() + r.path("updated").asInt() + r.path("deleted").asInt();
        }
        return total;
    }

    @Test
    void laExportacionTieneManifiestoConHuellas() {
        JsonNode m = new CarpetaExportacion(exportacion).leer(CarpetaExportacion.MANIFIESTO, null);
        assertEquals(281, m.path("recuentos").path("ensayos").asInt());
        assertEquals(2, m.path("recuentos").path("actividad").asInt());
        for (String f : CarpetaExportacion.FICHEROS) {
            assertEquals(64, m.path("ficheros").path(f).asText().length(), f);
        }
    }

    @Test
    void importaVerificaYEsIdempotente() {
        ObjectNode primera = base.importador.importar(exportacion, false);
        assertEquals("IMPORTADO", primera.path("resultado").asText(), primera.toString());
        assertTrue(primera.path("verificacion").path("ok").asBoolean());
        assertEquals(281, primera.path("tablas").path("ensayos").path("created").asInt());
        assertEquals(2, primera.path("tablas").path("actividad").path("created").asInt());
        List<Long> versiones = base.bd.jdbc().queryForList("SELECT version FROM ensayo ORDER BY uid", Long.class);

        ObjectNode segunda = base.importador.importar(exportacion, false);
        assertEquals("IMPORTADO", segunda.path("resultado").asText(), segunda.toString());
        assertEquals(0, totalCambios(segunda), "repetir la misma importación no cambia nada: " + segunda.path("tablas"));
        assertEquals(versiones, base.bd.jdbc().queryForList("SELECT version FROM ensayo ORDER BY uid", Long.class));
        assertEquals(2, base.bd.jdbc().queryForObject("SELECT count(*) FROM registro_actividad", Integer.class));
    }

    @Test
    void reimportarDeshaceLosCambiosHechosEnLaBase() {
        base.importador.importar(exportacion, false);
        FilaGuardada f = base.almacenes.ensayos().leer().get(5);
        ObjectNode e = JsonJs.leerObjeto(f.texto());
        e.put("comments", "cambiado en la base");
        base.almacenes.ensayos().guardar(List.of(new CambioFila(f.uid(), JsonJs.escribir(e), f.texto()),
                new CambioFila("zz-solo-en-la-base", "{\"id\":\"zz-solo-en-la-base\"}", null)), true, ContratoAlmacenes.ACTOR);
        ObjectNode informe = base.importador.importar(exportacion, false);
        assertEquals("IMPORTADO", informe.path("resultado").asText());
        assertEquals(1, informe.path("tablas").path("ensayos").path("updated").asInt());
        assertEquals(1, informe.path("tablas").path("ensayos").path("deleted").asInt());
        assertEquals(f.texto(), base.almacenes.ensayos().leer().get(5).texto());
        assertEquals(281, base.almacenes.ensayos().leer().size());
    }

    @Test
    void simularNoGuardaNada() {
        ObjectNode informe = base.importador.importar(exportacion, true);
        assertEquals("SIMULADO", informe.path("resultado").asText(), informe.toString());
        assertTrue(informe.path("verificacion").path("ok").asBoolean());
        assertEquals(281, informe.path("tablas").path("ensayos").path("created").asInt());
        assertEquals(0, base.bd.jdbc().queryForObject("SELECT count(*) FROM ensayo", Integer.class));
    }

    @Test
    void unFicheroRetocadoNoSeImporta() throws IOException {
        Path planner = exportacion.resolve("planner.json");
        Files.writeString(planner, Files.readString(planner).replaceFirst("PPQ5", "PPQ6"));
        assertThrows(IllegalArgumentException.class, () -> base.importador.importar(exportacion, false));
        assertEquals(0, base.bd.jdbc().queryForObject("SELECT count(*) FROM ensayo", Integer.class));
    }

    @Test
    void sinLosFicherosObligatoriosNoSeImporta() throws IOException {
        Files.delete(exportacion.resolve("usuarios.json"));
        assertThrows(IllegalArgumentException.class, () -> base.importador.importar(exportacion, false));
    }

    @Test
    void laExportacionNoSobrescribeUnaCarpetaConDatos() {
        DatosLocales datos = new DatosLocales(tmp.resolve("origen2"), PostgresPruebas.escenario(), true);
        var colaboracion = new AlmacenesAuxiliaresLocales.ColaboracionLocal(datos);
        ExportadorDatos exportador = new ExportadorDatos(new AlmacenEnsayosLocal(datos, colaboracion),
                new AlmacenesAuxiliaresLocales.Catalogos(datos, false),
                new AlmacenesAuxiliaresLocales.Usuarios(datos, new AlmacenUsuarios.Identidad("", ""), false),
                new AlmacenesAuxiliaresLocales.Recursos(datos, colaboracion, false), new AlmacenesAuxiliaresLocales.HistoricoKpi(datos),
                new AlmacenesAuxiliaresLocales.Aprobaciones(datos), new AlmacenesAuxiliaresLocales.TiposEquipo(datos), colaboracion,
                new EstadoConexion());
        assertThrows(IllegalArgumentException.class, () -> exportador.exportar(exportacion, "local", "", 10));
    }

    @Test
    void lasColumnasRelacionalesSalenDelDataJson() {
        base.importador.importar(exportacion, false);
        Map<String, Object> fila = base.bd.jdbc().queryForMap(
                "SELECT banco, organizacion, cerrado, approval_id, disponible, fecha_cierre, version FROM ensayo WHERE uid = 'ramz6496'");
        assertEquals("MD3", fila.get("banco"));
        assertEquals("BW", fila.get("organizacion"));
        assertEquals(Boolean.TRUE, fila.get("cerrado"));
        assertEquals("3264", fila.get("approval_id"));
        assertEquals("2026-08-05", fila.get("disponible").toString());
        assertEquals("2026-08-24", fila.get("fecha_cierre").toString());
        assertEquals(1L, ((Number) fila.get("version")).longValue());
        assertFalse(base.bd.jdbc().queryForList("SELECT clave FROM auditoria WHERE operacion = 'IMPORTACION'", String.class).isEmpty());
    }
}
