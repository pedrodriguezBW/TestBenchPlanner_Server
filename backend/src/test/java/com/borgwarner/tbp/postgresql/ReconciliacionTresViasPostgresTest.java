package com.borgwarner.tbp.postgresql;

import com.borgwarner.tbp.aplicacion.EstadoConexion;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenEnsayos;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenUsuarios;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.planificacion.CambioFila;
import com.borgwarner.tbp.dominio.planificacion.FilaGuardada;
import com.borgwarner.tbp.infraestructura.local.AlmacenEnsayosLocal;
import com.borgwarner.tbp.infraestructura.local.AlmacenesAuxiliaresLocales;
import com.borgwarner.tbp.infraestructura.local.DatosLocales;
import com.borgwarner.tbp.infraestructura.migracion.CarpetaExportacion;
import com.borgwarner.tbp.infraestructura.migracion.ExportadorDatos;
import com.borgwarner.tbp.infraestructura.migracion.ReconciliacionTresVias;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fusión de tres vías de punta a punta: BASE (exportación con la que se carga
 * la base) + SHAREPOINT (exportación posterior con cambios) + POSTGRESQL (con
 * otros cambios hechos en la base).
 */
class ReconciliacionTresViasPostgresTest {

    private static BaseDePruebas base;

    @TempDir
    Path tmp;

    Path exportacionBase;
    /** Ensayos del escenario: [0] se toca en los dos lados. */
    List<FilaGuardada> ensayosBase;

    @BeforeAll
    static void abrir() {
        base = new BaseDePruebas();
    }

    @AfterAll
    static void cerrar() {
        base.close();
    }

    /** Exportación de un almacén local sobre el escenario (como el PC con SharePoint). */
    private static final class SharePointSimulado {
        final DatosLocales datos;
        final AlmacenesAuxiliaresLocales.ColaboracionLocal colaboracion;
        final AlmacenEnsayosLocal ensayos;

        SharePointSimulado(Path carpeta) {
            datos = new DatosLocales(carpeta, PostgresPruebas.escenario(), true);
            colaboracion = new AlmacenesAuxiliaresLocales.ColaboracionLocal(datos);
            ensayos = new AlmacenEnsayosLocal(datos, colaboracion);
        }

        void exportar(Path destino) {
            new ExportadorDatos(ensayos, new AlmacenesAuxiliaresLocales.Catalogos(datos, false),
                    new AlmacenesAuxiliaresLocales.Usuarios(datos, new AlmacenUsuarios.Identidad("", ""), false),
                    new AlmacenesAuxiliaresLocales.Recursos(datos, colaboracion, false), new AlmacenesAuxiliaresLocales.HistoricoKpi(datos),
                    new AlmacenesAuxiliaresLocales.Aprobaciones(datos), new AlmacenesAuxiliaresLocales.TiposEquipo(datos), colaboracion,
                    new EstadoConexion()).exportar(destino, "local", "SharePoint simulado", 5000);
        }
    }

    @BeforeEach
    void cargarBase() {
        SharePointSimulado sp = new SharePointSimulado(tmp.resolve("sp-base"));
        exportacionBase = tmp.resolve("base");
        sp.exportar(exportacionBase);
        Path vacio = tmp.resolve("vacio");
        CarpetaExportacion c = new CarpetaExportacion(vacio);
        c.escribir("planner.json", JsonJs.mapper().createArrayNode());
        c.escribir("configuracion.json", JsonJs.objetoVacio());
        c.escribir("usuarios.json", JsonJs.mapper().createArrayNode());
        base.importador.reiniciar(vacio);
        ObjectNode carga = base.importador.importar(exportacionBase, false);
        assertEquals("IMPORTADO", carga.path("resultado").asText(), carga.toString());
        ensayosBase = base.almacenes.ensayos().leer();
    }

    private ReconciliacionTresVias reconciliacion() {
        ExportadorDatos postgres = new ExportadorDatos(base.almacenes.ensayos(), base.almacenes.catalogos(), base.usuarios,
                base.almacenes.recursos(), base.almacenes.kpi(), base.almacenes.aprobaciones(), base.almacenes.tipos(),
                base.colaboracion, new EstadoConexion());
        return new ReconciliacionTresVias(base.importador, postgres);
    }

    /** Cambia un campo del DataJson de un ensayo en un almacén. */
    private static void cambiar(AlmacenEnsayos almacen, String uid, String campo, Object valor) {
        FilaGuardada f = almacen.leer().stream().filter(x -> x.uid().equals(uid)).findFirst().orElseThrow();
        ObjectNode e = JsonJs.leerObjeto(f.texto());
        e.set(campo, JsonJs.mapper().valueToTree(valor));
        almacen.guardar(List.of(new CambioFila(uid, JsonJs.escribir(e), f.texto())), true, ContratoAlmacenes.ACTOR);
    }

    private static void alta(AlmacenEnsayos almacen, String uid) {
        almacen.guardar(List.of(new CambioFila(uid, "{\"id\":\"" + uid + "\"}", null)), true, ContratoAlmacenes.ACTOR);
    }

    private static ObjectNode enBase(String uid) {
        for (FilaGuardada f : base.almacenes.ensayos().leer()) {
            if (f.uid().equals(uid)) {
                return JsonJs.leerObjeto(f.texto());
            }
        }
        return null;
    }

    /** SharePoint: progreso del ensayo 0 y un alta. Base: comentario del ensayo 0, un alta y un borrado. */
    private Path cambiosEnLosDosLados(String comentarioSharePoint) {
        SharePointSimulado sp = new SharePointSimulado(tmp.resolve("sp-ahora"));
        String uid = ensayosBase.get(0).uid();
        cambiar(sp.ensayos, uid, "progressActual", 4321);
        if (comentarioSharePoint != null) {
            cambiar(sp.ensayos, uid, "comments", comentarioSharePoint);
        }
        alta(sp.ensayos, "zz-alta-sharepoint");
        Path exportacion = tmp.resolve("sharepoint");
        sp.exportar(exportacion);

        cambiar(base.almacenes.ensayos(), uid, "comments", "comentario del servidor");
        alta(base.almacenes.ensayos(), "zz-alta-servidor");
        FilaGuardada borrar = ensayosBase.get(1);
        base.almacenes.ensayos().guardar(List.of(new CambioFila(borrar.uid(), null, borrar.texto())), true, ContratoAlmacenes.ACTOR);
        return exportacion;
    }

    @Test
    void conservaLosCambiosDeLosDosLados() {
        Path sharepoint = cambiosEnLosDosLados(null);
        String uid = ensayosBase.get(0).uid();
        ObjectNode informe = reconciliacion().ejecutar(exportacionBase, sharepoint, null, tmp.resolve("trabajo"), false, 5000);
        assertEquals("IMPORTADO", informe.path("resultado").asText(), informe.toString());
        ObjectNode e = enBase(uid);
        assertEquals(4321, e.path("progressActual").asInt(), "cambio de SharePoint");
        assertEquals("comentario del servidor", e.path("comments").asText(), "cambio del servidor");
        assertNotNull(enBase("zz-alta-sharepoint"));
        assertNotNull(enBase("zz-alta-servidor"));
        assertNull(enBase(ensayosBase.get(1).uid()), "el borrado hecho en el servidor se respeta");
        assertEquals(ensayosBase.size() + 1, base.almacenes.ensayos().leer().size());
        // Carpeta de trabajo completa.
        Path trabajo = tmp.resolve("trabajo");
        for (String f : List.of("informe.json", "conflictos.json", "fusion/exportacion.json", "postgresql-antes/exportacion.json")) {
            assertTrue(trabajo.resolve(f).toFile().isFile(), f);
        }
    }

    @Test
    void conConflictosNoTocaLaBaseHastaResolverlos() throws Exception {
        Path sharepoint = cambiosEnLosDosLados("comentario de SharePoint");
        String uid = ensayosBase.get(0).uid();
        Path trabajo = tmp.resolve("trabajo1");
        ObjectNode informe = reconciliacion().ejecutar(exportacionBase, sharepoint, null, trabajo, false, 5000);
        assertEquals(ReconciliacionTresVias.CONFLICTOS, informe.path("resultado").asText(), informe.toString());
        assertEquals(1, informe.path("conflictosPendientes").asInt());
        assertEquals("comentario del servidor", enBase(uid).path("comments").asText(), "la base no se toca");
        assertNull(enBase("zz-alta-sharepoint"));

        // Se resuelve a favor de SharePoint editando conflictos.json.
        Path conflictos = trabajo.resolve("conflictos.json");
        ObjectNode raiz = (ObjectNode) JsonJs.mapper().readTree(conflictos.toFile());
        List<String> pendientes = new ArrayList<>();
        for (JsonNode d : raiz.path("decisiones")) {
            if ("CAMPO".equals(d.path("tipo").asText()) && d.path("elegir").asText().isEmpty()) {
                ((ObjectNode) d).put("elegir", "sharepoint");
                pendientes.add(d.path("campo").asText());
            }
        }
        assertEquals(List.of("data/comments"), pendientes);
        JsonJs.mapper().writeValue(conflictos.toFile(), raiz);

        ObjectNode resuelto = reconciliacion().ejecutar(exportacionBase, sharepoint, conflictos, tmp.resolve("trabajo2"), false, 5000);
        assertEquals("IMPORTADO", resuelto.path("resultado").asText(), resuelto.toString());
        ObjectNode e = enBase(uid);
        assertEquals("comentario de SharePoint", e.path("comments").asText());
        assertEquals(4321, e.path("progressActual").asInt());
        assertNotNull(enBase("zz-alta-sharepoint"));
    }

    @Test
    void simularNoGuardaNada() {
        Path sharepoint = cambiosEnLosDosLados(null);
        int antes = base.almacenes.ensayos().leer().size();
        ObjectNode informe = reconciliacion().ejecutar(exportacionBase, sharepoint, null, tmp.resolve("trabajo"), true, 5000);
        assertEquals("SIMULADO", informe.path("resultado").asText(), informe.toString());
        assertTrue(informe.path("importacion").path("verificacion").path("ok").asBoolean());
        assertNull(enBase("zz-alta-sharepoint"));
        assertEquals(antes, base.almacenes.ensayos().leer().size());
    }

    @Test
    void sinCambiosEnNingunLadoNoCambiaNada() {
        SharePointSimulado sp = new SharePointSimulado(tmp.resolve("sp-igual"));
        Path sharepoint = tmp.resolve("sharepoint-igual");
        sp.exportar(sharepoint);
        List<Long> versiones = base.bd.jdbc().queryForList("SELECT version FROM ensayo ORDER BY uid", Long.class);
        ObjectNode informe = reconciliacion().ejecutar(exportacionBase, sharepoint, null, tmp.resolve("trabajo"), false, 5000);
        assertEquals("IMPORTADO", informe.path("resultado").asText(), informe.toString());
        assertEquals(0, informe.path("importacion").path("tablas").path("ensayos").path("updated").asInt(), informe.toString());
        assertEquals(versiones, base.bd.jdbc().queryForList("SELECT version FROM ensayo ORDER BY uid", Long.class));
    }
}
