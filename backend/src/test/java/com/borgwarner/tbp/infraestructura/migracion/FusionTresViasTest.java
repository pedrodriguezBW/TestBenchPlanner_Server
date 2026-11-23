package com.borgwarner.tbp.infraestructura.migracion;

import com.borgwarner.tbp.dominio.comun.JsonJs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fusión de tres vías sin base de datos, sobre el escenario anonimizado:
 * BASE = escenario, SHAREPOINT y LOCAL = copias del escenario con cambios.
 */
class FusionTresViasTest {

    private static final List<String> FICHEROS = List.of("planner.json", "configuracion.json", "usuarios.json", "recursos.json",
            "kpis.json", "aprobaciones.json", "version.json");

    private Map<String, Object> base;
    private Map<String, Object> sharepoint;
    private Map<String, Object> local;
    private List<String> uids;

    private static Path escenario() {
        return Path.of(System.getProperty("escenarios.dir", "../datos/escenarios"), "sharepoint-2026-11-05");
    }

    private static Object leerFichero(String f) throws IOException {
        return JsonJs.mapper().readValue(Files.readString(escenario().resolve(f)), Object.class);
    }

    private static Object leerTexto(String t) {
        try {
            return JsonJs.mapper().readValue(t, Object.class);
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static String escribirTexto(Object o) {
        return JsonJs.escribir(JsonJs.mapper().valueToTree(o));
    }

    private static FusionTresVias.Resultado fusionar(Map<String, Object> b, Map<String, Object> s, Map<String, Object> l,
                                                    FusionTresVias.Resoluciones r) {
        return new FusionTresVias(FusionTresViasTest::leerTexto, FusionTresViasTest::escribirTexto, r).fusionar(b, s, l);
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void cargar() throws IOException {
        base = new LinkedHashMap<>();
        sharepoint = new LinkedHashMap<>();
        local = new LinkedHashMap<>();
        for (String f : FICHEROS) {
            base.put(f, leerFichero(f));
            sharepoint.put(f, leerFichero(f));
            local.put(f, leerFichero(f));
        }
        uids = new ArrayList<>();
        for (Object o : (List<Object>) base.get("planner.json")) {
            uids.add((String) ((Map<String, Object>) o).get("uid"));
        }
    }

    // ---- utilidades --------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<Object> planner(Map<String, Object> copia) {
        return (List<Object>) copia.get("planner.json");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fila(List<Object> planner, String uid) {
        for (Object o : planner) {
            Map<String, Object> m = (Map<String, Object>) o;
            if (uid.equals(m.get("uid"))) {
                return m;
            }
        }
        return null;
    }

    private static String texto(List<Object> planner, String uid) {
        Map<String, Object> f = fila(planner, uid);
        return f == null ? null : (String) f.get("data");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(List<Object> planner, String uid) {
        String t = texto(planner, uid);
        return t == null ? null : (Map<String, Object>) leerTexto(t);
    }

    private static void cambiar(Map<String, Object> copia, String uid, String campo, Object valor) {
        Map<String, Object> f = fila(planner(copia), uid);
        Map<String, Object> d = data(planner(copia), uid);
        d.put(campo, valor);
        f.put("data", escribirTexto(d));
    }

    private static void quitar(Map<String, Object> copia, String uid) {
        planner(copia).remove(fila(planner(copia), uid));
    }

    private static void alta(Map<String, Object> copia, String uid) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("uid", uid);
        f.put("data", "{\"id\":\"" + uid + "\"}");
        planner(copia).add(f);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> lista(Object o, String clave) {
        return (List<Object>) ((Map<String, Object>) o).get(clave);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapa(Object o) {
        return (Map<String, Object>) o;
    }

    // ---- pruebas ------------------------------------------------------------------------

    @Test
    void sinCambiosElResultadoEsIgualALaBase() {
        FusionTresVias.Resultado r = fusionar(base, base, base, null);
        assertTrue(r.decisiones().isEmpty());
        for (String f : FICHEROS) {
            assertTrue(FusionTresVias.igual(base.get(f), r.ficheros().get(f)), f);
        }
        assertEquals(texto(planner(base), uids.get(0)), texto(planner(r.ficheros()), uids.get(0)));
    }

    @Test
    void camposDistintosDelMismoEnsayoSeCombinan() {
        cambiar(sharepoint, uids.get(0), "progressActual", 777);
        cambiar(local, uids.get(0), "comments", "comentario del servidor");
        FusionTresVias.Resultado r = fusionar(base, sharepoint, local, null);
        assertTrue(r.pendientes().isEmpty());
        Map<String, Object> d = data(planner(r.ficheros()), uids.get(0));
        assertEquals(777, ((Number) d.get("progressActual")).intValue());
        assertEquals("comentario del servidor", d.get("comments"));
    }

    @Test
    void elMismoCampoCambiadoDistintoEsConflicto() {
        cambiar(sharepoint, uids.get(1), "comments", "SharePoint");
        cambiar(local, uids.get(1), "comments", "servidor");
        FusionTresVias.Resultado r = fusionar(base, sharepoint, local, null);
        assertEquals(1, r.pendientes().size());
        FusionTresVias.Decision d = r.pendientes().get(0);
        assertEquals("planner.json|uid=" + uids.get(1) + "|data/comments", d.id());
        assertEquals("SharePoint", d.sharepoint());
        assertEquals("servidor", d.local());
    }

    @Test
    void elTextoDataJsonSeConservaByteAByte() {
        cambiar(sharepoint, uids.get(2), "durManual", "5");
        FusionTresVias.Resultado r = fusionar(base, sharepoint, local, null);
        assertEquals(texto(planner(sharepoint), uids.get(2)), texto(planner(r.ficheros()), uids.get(2)));
        assertEquals(texto(planner(local), uids.get(3)), texto(planner(r.ficheros()), uids.get(3)));
    }

    @Test
    void altasYBorrados() {
        alta(sharepoint, "zz-sp");
        alta(local, "zz-servidor");
        quitar(sharepoint, uids.get(4));                       // borrado en SharePoint → se conserva
        quitar(local, uids.get(5));                            // borrado en el servidor → sigue borrado
        quitar(sharepoint, uids.get(6));                       // borrado en SharePoint, modificado en el servidor → se conserva
        cambiar(local, uids.get(6), "comments", "tocado en el servidor");
        FusionTresVias.Resultado r = fusionar(base, sharepoint, local, null);
        List<Object> p = planner(r.ficheros());
        assertNotNull(fila(p, "zz-sp"));
        assertNotNull(fila(p, "zz-servidor"));
        assertNotNull(fila(p, uids.get(4)));
        assertNull(fila(p, uids.get(5)));
        assertEquals("tocado en el servidor", data(p, uids.get(6)).get("comments"));
        assertEquals(uids.size() + 1, p.size());
        assertTrue(r.pendientes().isEmpty());
        assertEquals(3, r.decisiones().size());
    }

    @Test
    void catalogosUsuariosKpiYAprobaciones() {
        lista(sharepoint.get("configuracion.json"), "responsibles").add("Responsable nuevo SP");
        lista(local.get("configuracion.json"), "projects").add("99999 - Proyecto del servidor");
        mapa(lista(sharepoint.get("configuracion.json"), "benches").get(0)).put("dot", "#000000");
        mapa(lista(local.get("configuracion.json"), "benches").get(1)).put("area", "Bancos X");
        mapa(((List<Object>) sharepoint.get("usuarios.json")).get(2)).put("role", "Editor");
        Map<String, Object> kpiSp = new LinkedHashMap<>(Map.of("Year", 2026, "CW", 47, "Org", "ALL"));
        Map<String, Object> kpiServidor = new LinkedHashMap<>(Map.of("Year", 2026, "CW", 48, "Org", "ALL"));
        ((List<Object>) sharepoint.get("kpis.json")).add(kpiSp);
        ((List<Object>) local.get("kpis.json")).add(kpiServidor);
        mapa(((List<Object>) sharepoint.get("aprobaciones.json")).get(0)).put("status", "ok");

        FusionTresVias.Resultado r = fusionar(base, sharepoint, local, null);
        assertTrue(r.pendientes().isEmpty());
        Object cfg = r.ficheros().get("configuracion.json");
        assertTrue(lista(cfg, "responsibles").contains("Responsable nuevo SP"));
        assertTrue(lista(cfg, "projects").contains("99999 - Proyecto del servidor"));
        assertEquals("#000000", mapa(lista(cfg, "benches").get(0)).get("dot"));
        assertEquals("Bancos X", mapa(lista(cfg, "benches").get(1)).get("area"));
        assertEquals("Editor", mapa(((List<Object>) r.ficheros().get("usuarios.json")).get(2)).get("role"));
        assertEquals(((List<Object>) base.get("kpis.json")).size() + 2, ((List<Object>) r.ficheros().get("kpis.json")).size());
        assertEquals("ok", mapa(((List<Object>) r.ficheros().get("aprobaciones.json")).get(0)).get("status"));
    }

    @Test
    void actividadSoloSeAnade() {
        Map<String, Object> a = Map.of("when", "2026-11-10", "email", "a", "name", "A", "action", "edit", "detail", "x");
        Map<String, Object> b = Map.of("when", "2026-11-11", "email", "b", "name", "B", "action", "edit", "detail", "y");
        local.put("actividad.json", new ArrayList<>(List.of(a)));
        sharepoint.put("actividad.json", new ArrayList<>(List.of(a, b)));
        FusionTresVias.Resultado r = fusionar(base, sharepoint, local, null);
        assertEquals(2, ((List<Object>) r.ficheros().get("actividad.json")).size());
    }

    @Test
    void ficheroSinBaseMandaElServidorYSeAnadeLoNuevo() {
        local.put("tipos-equipo.json", Map.of("tipos", List.of(Map.of("titulo", "X", "anio", 2026, "sinHv", 1))));
        sharepoint.put("tipos-equipo.json", Map.of("tipos", List.of(Map.of("titulo", "X", "anio", 2026, "sinHv", 2),
                Map.of("titulo", "Y", "anio", 2026, "sinHv", 3))));
        FusionTresVias.Resultado r = fusionar(base, sharepoint, local, null);
        assertTrue(r.pendientes().isEmpty());
        List<Object> tipos = lista(r.ficheros().get("tipos-equipo.json"), "tipos");
        assertEquals(2, tipos.size());
        assertEquals(1, ((Number) mapa(tipos.get(0)).get("sinHv")).intValue());
    }

    @Test
    void lasResolucionesSeAplican() {
        cambiar(sharepoint, uids.get(1), "comments", "SharePoint");
        cambiar(local, uids.get(1), "comments", "servidor");
        quitar(sharepoint, uids.get(4));
        FusionTresVias.Resultado r = fusionar(base, sharepoint, local, null);
        String idConflicto = r.pendientes().get(0).id();
        String idBorrado = "planner.json|uid=" + uids.get(4) + "|borrado-en-sharepoint";

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("porDefecto", "");
        res.put("decisiones", List.of(Map.of("id", idConflicto, "elegir", "SharePoint"), Map.of("id", idBorrado, "elegir", "borrar")));
        FusionTresVias.Resultado resuelto = fusionar(base, sharepoint, local, FusionTresVias.Resoluciones.de(res));
        assertTrue(resuelto.pendientes().isEmpty());
        assertEquals("SharePoint", data(planner(resuelto.ficheros()), uids.get(1)).get("comments"));
        assertNull(fila(planner(resuelto.ficheros()), uids.get(4)));

        res.put("decisiones", List.of());
        res.put("porDefecto", "local");
        FusionTresVias.Resultado porDefecto = fusionar(base, sharepoint, local, FusionTresVias.Resoluciones.de(res));
        assertTrue(porDefecto.pendientes().isEmpty());
        assertEquals("servidor", data(planner(porDefecto.ficheros()), uids.get(1)).get("comments"));
    }

    @Test
    void unaResolucionNoValidaSeRechaza() {
        cambiar(sharepoint, uids.get(1), "comments", "SharePoint");
        cambiar(local, uids.get(1), "comments", "servidor");
        String id = fusionar(base, sharepoint, local, null).pendientes().get(0).id();
        FusionTresVias.Resoluciones mal = FusionTresVias.Resoluciones.de(Map.of("decisiones", List.of(Map.of("id", id, "elegir", "borrar"))));
        assertThrows(IllegalArgumentException.class, () -> fusionar(base, sharepoint, local, mal));
    }
}
