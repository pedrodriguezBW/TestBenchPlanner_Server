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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    // ---- D65 ---------------------------------------------------------------------------

    @Test
    void conOtraCarpetaBaseSoloSeSimula() {
        Path sharepoint = cambiosEnLosDosLados(null);
        // Una exportación de SharePoint posterior, usada por error como base.
        SharePointSimulado otra = new SharePointSimulado(tmp.resolve("sp-otra"));
        cambiar(otra.ensayos, ensayosBase.get(2).uid(), "comments", "otra base");
        Path baseErronea = tmp.resolve("base-erronea");
        otra.exportar(baseErronea);

        ObjectNode aplicar = reconciliacion().ejecutar(baseErronea, sharepoint, null, tmp.resolve("t1"), false, 5000);
        assertEquals(ReconciliacionTresVias.BASE_NO_VERIFICADA, aplicar.path("resultado").asText(), aplicar.toString());
        assertFalse(aplicar.path("comprobacionBase").path("verificada").asBoolean());
        assertEquals(1, aplicar.path("comprobacionBase").path("ensayosDistintos").asInt(), aplicar.toString());
        assertNull(enBase("zz-alta-sharepoint"), "no se toca la base");

        ObjectNode simular = reconciliacion().ejecutar(baseErronea, sharepoint, null, tmp.resolve("t2"), true, 5000);
        assertEquals("SIMULADO", simular.path("resultado").asText(), simular.toString());
        assertTrue(simular.path("avisos").toString().contains("CARPETA BASE SIN VERIFICAR"));

        ObjectNode forzado = reconciliacion().ejecutar(baseErronea, sharepoint, null, tmp.resolve("t3"), false, 5000, true);
        assertEquals("IMPORTADO", forzado.path("resultado").asText(), forzado.toString());
    }

    @Test
    void laBaseCorrectaSeVerificaConLaAuditoria() {
        Path sharepoint = cambiosEnLosDosLados(null);
        ObjectNode informe = reconciliacion().ejecutar(exportacionBase, sharepoint, null, tmp.resolve("t1"), true, 5000);
        assertTrue(informe.path("comprobacionBase").path("verificada").asBoolean(), informe.toString());
        assertEquals(ensayosBase.size(), informe.path("comprobacionBase").path("ensayosIguales").asInt());
    }

    @Test
    void laSiguienteFusionUsaLaExportacionImportada() {
        Path primera = cambiosEnLosDosLados(null);
        ObjectNode aplicada = reconciliacion().ejecutar(exportacionBase, primera, null, tmp.resolve("t1"), false, 5000);
        assertEquals("IMPORTADO", aplicada.path("resultado").asText(), aplicada.toString());

        Path ultima = tmp.resolve("sharepoint-final");
        new SharePointSimulado(tmp.resolve("sp-final")).exportar(ultima);
        ObjectNode conLaVieja = reconciliacion().ejecutar(exportacionBase, ultima, null, tmp.resolve("t2"), false, 5000);
        assertEquals(ReconciliacionTresVias.BASE_NO_VERIFICADA, conLaVieja.path("resultado").asText(), conLaVieja.toString());

        ObjectNode conLaBuena = reconciliacion().ejecutar(primera, ultima, null, tmp.resolve("t3"), true, 5000);
        assertTrue(conLaBuena.path("comprobacionBase").path("verificada").asBoolean(), conLaBuena.toString());
        assertEquals("SIMULADO", conLaBuena.path("resultado").asText(), conLaBuena.toString());
    }

    @Test
    void noBorraUsuariosQueUsaTimingNiCambiaSusTablas() throws IOException {
        List<String> emails = base.bd.jdbc().queryForList("SELECT email FROM usuario ORDER BY id LIMIT 2", String.class);
        String conPerfil = emails.get(0);
        String sinPerfil = emails.get(1);
        base.bd.jdbc().update("INSERT INTO timing_perfil (email, perfil) VALUES (?, 'tecnico')", conPerfil);

        // SharePoint ya no tiene a ninguno de los dos.
        SharePointSimulado sp = new SharePointSimulado(tmp.resolve("sp-sin-usuarios"));
        AlmacenesAuxiliaresLocales.Usuarios usuariosSp = new AlmacenesAuxiliaresLocales.Usuarios(sp.datos,
                new AlmacenUsuarios.Identidad("", ""), true);
        ArrayNode quedan = JsonJs.mapper().createArrayNode();
        usuariosSp.leer().forEach(u -> {
            if (!emails.contains(u.path("email").asText())) {
                quedan.add(u);
            }
        });
        ObjectNode lista = JsonJs.objetoVacio();
        lista.set("users", quedan);
        usuariosSp.guardar(lista);
        Path sharepoint = tmp.resolve("sharepoint-sin-usuarios");
        sp.exportar(sharepoint);

        // Importar tal cual (sin fusión) borraría el perfil de Timing: se deshace todo.
        ObjectNode directa = base.importador.importar(sharepoint, false);
        assertEquals("RECHAZADO", directa.path("resultado").asText(), directa.toString());
        assertTrue(directa.path("verificacion").toString().contains("timing_perfil"), directa.toString());
        assertEquals(2, base.bd.jdbc().queryForObject("SELECT count(*) FROM usuario WHERE email IN (?, ?)", Integer.class,
                conPerfil, sinPerfil));

        // Fusión pidiendo borrar a los dos: solo se borra el que no usa Timing.
        Path resoluciones = tmp.resolve("resoluciones.json");
        ObjectNode raiz = JsonJs.objetoVacio().put("porDefecto", "");
        ArrayNode decisiones = raiz.putArray("decisiones");
        for (String e : emails) {
            decisiones.addObject().put("id", "usuarios.json|email=" + e + "|borrado-en-sharepoint").put("elegir", "borrar");
        }
        JsonJs.mapper().writeValue(resoluciones.toFile(), raiz);
        ObjectNode informe = reconciliacion().ejecutar(exportacionBase, sharepoint, resoluciones, tmp.resolve("trabajo"), false, 5000);
        assertEquals("IMPORTADO", informe.path("resultado").asText(), informe.toString());
        assertEquals(1, base.bd.jdbc().queryForObject("SELECT count(*) FROM usuario WHERE email = ?", Integer.class, conPerfil));
        assertEquals(0, base.bd.jdbc().queryForObject("SELECT count(*) FROM usuario WHERE email = ?", Integer.class, sinPerfil));
        assertEquals(1, base.bd.jdbc().queryForObject("SELECT count(*) FROM timing_perfil WHERE email = ?", Integer.class, conPerfil));
        assertTrue(informe.path("avisos").toString().contains("no se borra"), informe.toString());
    }

    private static ObjectNode fotoKpi(int benches, int delayed) {
        return JsonJs.objetoVacio().put("year", 2030).put("cw", 5).put("org", "THERMAL").put("date", "2030-01-30")
                .put("benches", benches).put("delayed", delayed);
    }

    @Test
    void unaSemanaDeKpiNoSeMezcla() {
        SharePointSimulado sp = new SharePointSimulado(tmp.resolve("sp-kpi"));
        new AlmacenesAuxiliaresLocales.HistoricoKpi(sp.datos).guardarFotos(List.of(fotoKpi(5, 1)));
        Path sharepoint = tmp.resolve("sharepoint-kpi");
        sp.exportar(sharepoint);
        base.almacenes.kpi().guardarFotos(List.of(fotoKpi(7, 0)));

        ObjectNode informe = reconciliacion().ejecutar(exportacionBase, sharepoint, null, tmp.resolve("trabajo"), false, 5000);
        assertEquals("IMPORTADO", informe.path("resultado").asText(), informe.toString());
        JsonNode fila = JsonJs.leerObjeto(base.bd.jdbc().queryForObject(
                "SELECT fila_json FROM kpi_semana WHERE anio = 2030 AND cw = 5 AND org = 'THERMAL'", String.class));
        assertEquals(7, fila.path("Benches").asInt(), "la semana del servidor, entera");
        assertEquals(0, fila.path("DelayedDeclared").asInt(), "sin el retraso de la foto de SharePoint");
        assertTrue(informe.path("avisos").toString().contains("se conserva entera la del servidor"), informe.toString());
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
