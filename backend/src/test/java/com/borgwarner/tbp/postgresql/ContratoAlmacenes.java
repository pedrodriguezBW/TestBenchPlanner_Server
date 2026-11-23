package com.borgwarner.tbp.postgresql;

import com.borgwarner.tbp.aplicacion.puertos.AlmacenCatalogos;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenEnsayos;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenHistoricoKpi;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenRecursos;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenTiposEquipo;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenUsuarios;
import com.borgwarner.tbp.aplicacion.puertos.Colaboracion;
import com.borgwarner.tbp.aplicacion.puertos.ConflictoEdicion;
import com.borgwarner.tbp.aplicacion.puertos.ConsultaAprobaciones;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.facturacion.Regla;
import com.borgwarner.tbp.dominio.facturacion.TipoEquipo;
import com.borgwarner.tbp.dominio.listas.ReconciliacionBloqueada;
import com.borgwarner.tbp.dominio.planificacion.CambioFila;
import com.borgwarner.tbp.dominio.planificacion.FilaGuardada;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contrato de los almacenes: las MISMAS pruebas se ejecutan contra el almacén
 * local (ContratoAlmacenesLocalTest) y contra PostgreSQL
 * (ContratoAlmacenesPostgresTest). Cada prueba parte del escenario anonimizado
 * sharepoint-2026-11-05 recién cargado.
 */
abstract class ContratoAlmacenes {

    /** Los almacenes de una implementación, recién cargados con el escenario. */
    record Almacenes(AlmacenEnsayos ensayos, AlmacenCatalogos catalogos, AlmacenUsuarios usuarios, AlmacenRecursos recursos,
                     AlmacenHistoricoKpi kpi, ConsultaAprobaciones aprobaciones, AlmacenTiposEquipo tipos,
                     Colaboracion colaboracion) {
    }

    protected abstract Almacenes preparar();

    static final AlmacenEnsayos.Actor ACTOR = new AlmacenEnsayos.Actor("usuario01@ejemplo.local", "Persona 01");

    Almacenes a;
    ArrayNode escenario;

    @BeforeEach
    void cargar() throws IOException {
        a = preparar();
        escenario = (ArrayNode) JsonJs.mapper().readTree(PostgresPruebas.escenario().resolve("planner.json").toFile());
    }

    private FilaGuardada fila(String uid) {
        return a.ensayos().leer().stream().filter(f -> f.uid().equals(uid)).findFirst().orElse(null);
    }

    private static String conComentario(String texto, String comentario) {
        ObjectNode e = JsonJs.leerObjeto(texto);
        e.put("comments", comentario);
        return JsonJs.escribir(e);
    }

    // ---- ensayos ----------------------------------------------------------------------

    @Test
    void leeTodosLosEnsayosEnElOrdenGuardadoYConElTextoExacto() {
        List<FilaGuardada> filas = a.ensayos().leer();
        assertEquals(escenario.size(), filas.size());
        for (int i = 0; i < filas.size(); i++) {
            assertEquals(escenario.get(i).path("uid").asText(), filas.get(i).uid());
            assertEquals(escenario.get(i).path("data").asText(), filas.get(i).texto());
        }
    }

    @Test
    void guardarConVersionYDetectarElCambioDeOtraPersona() {
        FilaGuardada f = a.ensayos().leer().get(0);
        String mio = conComentario(f.texto(), "mío");
        var r = a.ensayos().guardar(List.of(new CambioFila(f.uid(), mio, f.texto())), true, ACTOR);
        assertEquals(1, r.actualizados());
        assertEquals(mio, fila(f.uid()).texto());
        // Otra persona guarda partiendo del texto antiguo: conflicto con el texto actual.
        ConflictoEdicion c = assertThrows(ConflictoEdicion.class, () -> a.ensayos().guardar(
                List.of(new CambioFila(f.uid(), conComentario(f.texto(), "suyo"), f.texto())), true, ACTOR));
        assertEquals(f.uid(), c.filas().get(0).uid());
        assertEquals(mio, c.filas().get(0).textoActual());
        assertEquals(mio, fila(f.uid()).texto());
    }

    @Test
    void unConflictoNoDejaNadaAMedias() {
        FilaGuardada f0 = a.ensayos().leer().get(0);
        FilaGuardada f1 = a.ensayos().leer().get(1);
        assertThrows(ConflictoEdicion.class, () -> a.ensayos().guardar(List.of(
                new CambioFila(f0.uid(), conComentario(f0.texto(), "bien"), f0.texto()),
                new CambioFila(f1.uid(), conComentario(f1.texto(), "mal"), "texto que ya no está")), true, ACTOR));
        assertEquals(f0.texto(), fila(f0.uid()).texto());
        assertEquals(f1.texto(), fila(f1.uid()).texto());
    }

    @Test
    void altaRepetidaYBaja() {
        String texto = "{\"_bench\":\"MD1\",\"_seq\":99,\"id\":\"zz-prueba\",\"comments\":\"nuevo\"}";
        var r = a.ensayos().guardar(List.of(new CambioFila("zz-prueba", texto, null)), true, ACTOR);
        assertEquals(1, r.creados());
        assertEquals(escenario.size() + 1, a.ensayos().leer().size());
        assertEquals("zz-prueba", a.ensayos().leer().get(escenario.size()).uid(), "las altas van al final");
        // Otra persona da de alta el mismo id sin haberlo visto: conflicto.
        assertThrows(ConflictoEdicion.class, () -> a.ensayos().guardar(
                List.of(new CambioFila("zz-prueba", texto.replace("nuevo", "otro"), null)), true, ACTOR));
        var b = a.ensayos().guardar(List.of(new CambioFila("zz-prueba", null, texto)), true, ACTOR);
        assertEquals(1, b.borrados());
        assertNull(fila("zz-prueba"));
    }

    @Test
    void sinComprobarVersionGanaElUltimo() {
        FilaGuardada f = a.ensayos().leer().get(2);
        a.ensayos().guardar(List.of(new CambioFila(f.uid(), conComentario(f.texto(), "uno"), f.texto())), false, ACTOR);
        String dos = conComentario(f.texto(), "dos");
        a.ensayos().guardar(List.of(new CambioFila(f.uid(), dos, f.texto())), false, ACTOR);
        assertEquals(dos, fila(f.uid()).texto());
    }

    // ---- catálogos --------------------------------------------------------------------

    @Test
    void catalogosIdaYVuelta() {
        ObjectNode cfg = a.catalogos().leer();
        int proyectos = cfg.path("projects").size();
        ArrayNode lista = (ArrayNode) cfg.get("projects");
        String quitado = lista.get(0).asText();
        lista.remove(0);
        lista.add("99999 - Proyecto de prueba");
        cfg.putObject("projectOrders").put("99999 - Proyecto de prueba", "OF-123");
        ObjectNode banco = (ObjectNode) cfg.get("benches").get(0);
        banco.put("equipmentType", "Cámara climática");
        banco.put("area", "HVCH-CTAG");
        a.catalogos().guardar(cfg);

        ObjectNode leido = a.catalogos().leer();
        assertEquals(proyectos, leido.path("projects").size());
        assertFalse(leido.path("projects").toString().contains("\"" + quitado + "\""));
        assertEquals("99999 - Proyecto de prueba", leido.path("projects").get(proyectos - 1).asText());
        assertEquals("OF-123", leido.path("projectOrders").path("99999 - Proyecto de prueba").asText());
        JsonNode b = leido.path("benches").get(0);
        assertEquals(banco.path("name").asText(), b.path("name").asText());
        assertEquals("Cámara climática", b.path("equipmentType").asText());
        assertEquals("HVCH-CTAG", b.path("area").asText());
    }

    @Test
    void laInterfazClasicaNoBorraLasColumnasQueNoConoce() {
        ObjectNode cfg = a.catalogos().leer();
        ((ObjectNode) cfg.get("benches").get(1)).put("equipmentType", "Banco térmico");
        a.catalogos().guardar(cfg);
        ObjectNode clasica = a.catalogos().leer();
        clasica.remove("projectOrders");
        clasica.remove("areaNames");
        clasica.get("benches").forEach(b -> {
            ((ObjectNode) b).remove("equipmentType");
            ((ObjectNode) b).remove("area");
        });
        a.catalogos().guardar(clasica);
        assertEquals("Banco térmico", a.catalogos().leer().path("benches").get(1).path("equipmentType").asText());
    }

    @Test
    void noSeVaciaUnaListaCompartida() {
        ObjectNode cfg = a.catalogos().leer();
        int bancos = cfg.path("benches").size();
        cfg.putArray("projects");
        assertThrows(ReconciliacionBloqueada.class, () -> a.catalogos().guardar(cfg));
        ObjectNode leido = a.catalogos().leer();
        assertTrue(leido.path("projects").size() > 0);
        assertEquals(bancos, leido.path("benches").size());
    }

    // ---- usuarios ----------------------------------------------------------------------

    @Test
    void usuariosIdaYVueltaYProteccion() {
        ArrayNode usuarios = a.usuarios().leer();
        int n = usuarios.size();
        ObjectNode nuevo = usuarios.addObject();
        nuevo.put("title", "Persona Nueva").put("email", "Nueva@Ejemplo.local").put("role", "editor").put("responsibleName", "");
        ObjectNode cuerpo = JsonJs.objetoVacio();
        cuerpo.set("users", usuarios);
        a.usuarios().guardar(cuerpo);
        ArrayNode leidos = a.usuarios().leer();
        assertEquals(n + 1, leidos.size());
        JsonNode ultimo = leidos.get(n);
        assertEquals("nueva@ejemplo.local", ultimo.path("email").asText());
        assertEquals("editor", ultimo.path("role").asText());
        ObjectNode vacio = JsonJs.objetoVacio();
        vacio.putArray("users");
        assertThrows(ReconciliacionBloqueada.class, () -> a.usuarios().guardar(vacio));
        assertEquals(n + 1, a.usuarios().leer().size());
    }

    // ---- recursos ------------------------------------------------------------------------

    @Test
    void recursosYBloqueos() {
        ObjectNode r = a.recursos().leer();
        int n = r.path("resources").size();
        ObjectNode bloqueo = JsonJs.objetoVacio().put("resource", r.path("resources").get(0).path("name").asText())
                .put("start", "2026-11-10").put("end", "2026-11-12").put("reason", "Mantenimiento").put("note", "prueba");
        ObjectNode creado = a.recursos().guardarBloqueo(bloqueo, ACTOR);
        assertTrue(creado.path("ok").asBoolean());
        long id = creado.path("id").asLong();
        JsonNode leido = a.recursos().leer().path("blocks").get(0);
        assertEquals(id, leido.path("id").asLong());
        assertEquals("2026-11-10", leido.path("start").asText());
        assertEquals("2026-11-12", leido.path("end").asText());
        assertEquals("Persona 01", leido.path("author").asText());
        bloqueo.put("id", id).put("end", "2026-11-20");
        assertTrue(a.recursos().guardarBloqueo(bloqueo, ACTOR).path("ok").asBoolean());
        assertEquals("2026-11-20", a.recursos().leer().path("blocks").get(0).path("end").asText());
        assertEquals(1, a.recursos().leer().path("blocks").size());
        assertTrue(a.recursos().borrarBloqueo(id).path("ok").asBoolean());
        assertFalse(a.recursos().borrarBloqueo(id).path("ok").asBoolean());
        assertEquals(n, a.recursos().leer().path("resources").size());

        ObjectNode catalogo = a.recursos().leer();
        ((ArrayNode) catalogo.get("resources")).remove(0);
        a.recursos().guardarCatalogo(catalogo);
        assertEquals(n - 1, a.recursos().leer().path("resources").size());
    }

    // ---- KPI, tipos de equipo, aprobaciones y colaboración ----------------------------------

    @Test
    void fotoSemanalSeCreaYDespuesSeSustituye() {
        int antes = a.kpi().leer().size();
        ObjectNode foto = JsonJs.objetoVacio().put("year", 2031).put("cw", 7).put("org", "ALL").put("date", "2031-02-14")
                .put("benches", 3).put("total", 10).put("occPct", 45.5);
        assertEquals(1, a.kpi().guardarFotos(List.of(foto.deepCopy())).path("created").asInt());
        foto.put("benches", 4);
        assertEquals(1, a.kpi().guardarFotos(List.of(foto)).path("updated").asInt());
        List<JsonNode> filas = a.kpi().leer();
        assertEquals(antes + 1, filas.size());
        JsonNode ultima = filas.get(filas.size() - 1);
        assertEquals(2031, ultima.path("Year").asInt());
        assertEquals(4, ultima.path("Benches").asInt());
        assertEquals(45.5, ultima.path("OccPct").asDouble());
    }

    @Test
    void tiposDeEquipoSeSustituyen() {
        a.tipos().guardar(List.of(new TipoEquipo("Cámara climática", "Climatic", 2026, 3.72, 4.5, "HORAS", Regla.LINEAR),
                new TipoEquipo("RACS", "Racs", 2026, null, 120.0, "PIEZAS", Regla.PER_PIECE)));
        List<TipoEquipo> leidos = a.tipos().leer();
        assertEquals(2, leidos.size());
        assertEquals(3.72, leidos.get(0).sinHv());
        assertNull(leidos.get(1).sinHv());
        assertEquals("PIEZAS", leidos.get(1).unidad());
        a.tipos().guardar(List.of(leidos.get(1)));
        assertEquals(List.of(leidos.get(1)), a.tipos().leer());
    }

    @Test
    void aprobacionesDeLaMasRecienteALaMasAntigua() {
        ArrayNode todas = a.aprobaciones().todas();
        assertTrue(todas.size() > 100);
        assertTrue(todas.get(0).path("id").asInt() > todas.get(1).path("id").asInt());
        int id = todas.get(0).path("id").asInt();
        List<JsonNode> pedidos = new ArrayList<>(List.of(TextNode.valueOf("ID " + id + " / x"), TextNode.valueOf("sin número"),
                TextNode.valueOf("99999999")));
        ObjectNode estados = a.aprobaciones().estados(pedidos);
        assertEquals(id, estados.path("ID " + id + " / x").path("resolvedId").asInt());
        assertEquals(todas.get(0).path("status").asText(), estados.path("ID " + id + " / x").path("status").asText());
        assertEquals("not_created", estados.path("sin número").path("status").asText());
        assertTrue(estados.path("sin número").path("resolvedId").isNull());
        assertEquals("not_created", estados.path("99999999").path("status").asText());
    }

    @Test
    void registroDeActividadYVersion() {
        assertTrue(a.colaboracion().anotarActividad("usuario01@ejemplo.local", "Persona 01", "edit", "created 1"));
        JsonNode fila = a.colaboracion().leerActividad(10).path("rows").get(0);
        assertEquals("edit", fila.path("action").asText());
        assertEquals("created 1", fila.path("detail").asText());
        assertEquals("Persona 01", fila.path("name").asText());
        assertTrue(fila.path("when").asText().endsWith("Z"));

        assertEquals("4.9.4", a.colaboracion().versionPublicada());
        a.colaboracion().publicarVersion("5.0.0");
        assertEquals("5.0.0", a.colaboracion().versionPublicada());
        a.colaboracion().publicarVersion("4.10");
        assertEquals("5.0.0", a.colaboracion().versionPublicada(), "nunca se rebaja");
    }

    @Test
    void presenciaIncluyeAQuienEnviaElLatido() {
        JsonNode p = a.colaboracion().latidoPresencia(new AlmacenUsuarios.Identidad("usuario02@ejemplo.local", "Persona 02"));
        assertEquals("usuario02@ejemplo.local", p.path("me").asText());
        JsonNode yo = p.path("online").get(0);
        assertEquals("usuario02@ejemplo.local", yo.path("email").asText());
        assertTrue(yo.path("me").asBoolean());
        assertNotEquals("", yo.path("initials").asText());
    }
}
