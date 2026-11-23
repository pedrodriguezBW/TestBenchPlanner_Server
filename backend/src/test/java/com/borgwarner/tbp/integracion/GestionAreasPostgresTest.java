package com.borgwarner.tbp.integracion;

import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.infraestructura.postgresql.BaseDatos;
import com.borgwarner.tbp.infraestructura.postgresql.CuentasPostgres;
import com.borgwarner.tbp.infraestructura.postgresql.ImportadorDatos;
import com.borgwarner.tbp.postgresql.PostgresPruebas;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rol Lab User (D55), áreas gestionadas desde la aplicación (D56) y filtro de
 * área de la hoja mensual (D57) con PostgreSQL en modo servidor: un Lab User
 * edita ensayos como un Editor, gestiona las áreas y configura los bancos (D58)
 * como un Admin; un Editor
 * no gestiona áreas (403) y un Read only no escribe nada.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GestionAreasPostgresTest {

    static final String ADMIN = "usuario01@ejemplo.local";
    static final String EDITOR = "usuario07@ejemplo.local";
    static final String LAB = "usuario08@ejemplo.local";
    static final String LECTOR = "usuario09@ejemplo.local";
    static final String CLAVE = "Clave-de-prueba-1";

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) throws IOException {
        Path carpeta = Files.createTempDirectory("tbp-gestion-areas");
        PostgresPruebas.registrar(registro);
        registro.add("planner.postgresql.reinicio-pruebas", () -> "false");
        registro.add("planner.servidor.activo", () -> "true");
        registro.add("planner.fecha-fija", () -> "2026-11-05");
        registro.add("planner.carpeta-usuario", carpeta::toString);
        registro.add("planner.postgresql.usuario-email", () -> "nadie@ejemplo.local");
    }

    @LocalServerPort
    int puerto;

    @Autowired
    ImportadorDatos importador;

    @Autowired
    CuentasPostgres cuentas;

    @Autowired
    BaseDatos bd;

    @BeforeEach
    void datos() {
        importador.reiniciar(PostgresPruebas.escenario());
        bd.jdbc().update("UPDATE usuario SET rol = 'LabUser' WHERE email = ?", LAB);
        bd.jdbc().update("UPDATE usuario SET rol = 'ReadOnly' WHERE email = ?", LECTOR);
        cuentas.establecerAdmin(ADMIN, "", CLAVE);
        for (String email : List.of(EDITOR, LAB, LECTOR)) {
            String temporal = cuentas.claveTemporal(CuentasPostgres.INSTALACION, email);
            cuentas.cambiarClave(email, temporal, CLAVE);
        }
    }

    private NavegadorPruebas dentro(String email) {
        NavegadorPruebas n = new NavegadorPruebas(puerto);
        assertEquals(302, n.entrar(email, CLAVE).statusCode());
        return n;
    }

    private int auditorias(String codigo, String operacion, String email) {
        Integer n = bd.jdbc().queryForObject("SELECT count(*) FROM auditoria WHERE entidad = 'area' AND clave = ? "
                + "AND operacion = ? AND usuario_email = ?", Integer.class, codigo, operacion, email);
        return n == null ? 0 : n;
    }

    private static ObjectNode area(String nombre, String organizacion) {
        ObjectNode o = JsonJs.objetoVacio().put("name", nombre);
        return organizacion == null ? o.putNull("organization") : o.put("organization", organizacion);
    }

    private static List<String> codigos(JsonNode gestion) {
        List<String> c = new ArrayList<>();
        gestion.path("areas").forEach(a -> c.add(a.path("code").asText()));
        return c;
    }

    /** Edita el comentario del primer ensayo de MD2. */
    private static HttpResponse<String> editarEnsayo(NavegadorPruebas n, String texto) {
        JsonNode fila = n.leer("/api/v1/planificacion").path("bancos").path("MD2").get(0);
        ObjectNode edicion = JsonJs.objetoVacio().put("version", fila.path("version").asText());
        edicion.putArray("cambios").addObject().put("campo", "comments").put("valor", texto);
        return n.json("POST", "/api/v1/bancos/MD2/ensayos/" + fila.path("id").asText() + "/campos", edicion);
    }

    /** Pasa la categoría del banco indicado al área (como Configuration › Test Benches, solo Admin). */
    private static void moverCategoria(NavegadorPruebas admin, String banco, String area) {
        JsonNode catalogos = admin.leer("/api/v1/catalogos");
        String clave = null;
        for (JsonNode b : catalogos.path("benches")) {
            if (banco.equals(b.path("name").asText())) {
                clave = b.path("groupKey").asText();
            }
        }
        ObjectNode cuerpo = JsonJs.objetoVacio();
        cuerpo.set("projects", catalogos.path("projects").deepCopy());
        ArrayNode bancos = cuerpo.putArray("benches");
        for (JsonNode b : catalogos.path("benches")) {
            ObjectNode copia = b.deepCopy();
            if (b.path("groupKey").asText().equals(clave)) {
                copia.put("area", area);
            } else if (!copia.has("area") || copia.path("area").asText().isEmpty()) {
                copia.put("area", "THERMAL");
            }
            bancos.add(copia);
        }
        HttpResponse<String> r = admin.json("PUT", "/api/v1/configuracion/bancos", cuerpo);
        assertEquals(200, r.statusCode(), r.body());
    }

    @Test
    void labUserEditaComoUnEditorYGestionaLasAreas() {
        NavegadorPruebas lab = dentro(LAB);
        JsonNode sesion = lab.leer("/api/v1/sesion");
        assertEquals("labuser", sesion.path("rol").asText());
        assertFalse(sesion.path("esAdmin").asBoolean());
        assertFalse(sesion.path("soloLectura").asBoolean());
        assertEquals(200, editarEnsayo(lab, "Comentario de un Lab User").statusCode());
        assertEquals("Comentario de un Lab User",
                lab.leer("/api/v1/planificacion").path("bancos").path("MD2").get(0).path("datos").path("comments").asText());

        JsonNode gestion = lab.leer("/api/v1/areas/gestion");
        assertTrue(gestion.path("editable").asBoolean(), "con PostgreSQL son editables");
        assertEquals(List.of("THERMAL", "HVCH-CTAG", "HVCH-STC"), codigos(gestion), "de serie, las tres de siempre");
        assertTrue(gestion.path("areas").get(0).path("organization").isNull(), "Thermal: banco a banco");
        assertEquals("CTAG", gestion.path("areas").get(1).path("organization").asText());
        assertEquals("STC", gestion.path("areas").get(2).path("organization").asText());

        HttpResponse<String> alta = lab.json("POST", "/api/v1/areas", area("Lab Benches", "BW"));
        assertEquals(200, alta.statusCode(), alta.body());
        JsonNode nueva = JsonJs.leer(alta.body());
        assertEquals("LAB-BENCHES", nueva.path("code").asText());
        assertEquals("BW", nueva.path("organization").asText());
        assertEquals(1, auditorias("LAB-BENCHES", "ALTA", LAB));
        JsonNode vista = lab.leer("/api/v1/planificacion");
        assertEquals(4, vista.path("areas").size());
        assertEquals("Lab Benches", vista.path("areas").get(3).path("nombre").asText());
        assertEquals("BW", vista.path("areas").get(3).path("organizacionFija").asText());

        // El Lab User configura los bancos (D58): renombra un banco y sus ensayos lo siguen.
        JsonNode catalogos = lab.leer("/api/v1/catalogos");
        int enMd2 = lab.leer("/api/v1/planificacion").path("bancos").path("MD2").size();
        ObjectNode cuerpo = JsonJs.objetoVacio();
        cuerpo.set("projects", catalogos.path("projects").deepCopy());
        ArrayNode bancos = cuerpo.putArray("benches");
        for (JsonNode b : catalogos.path("benches")) {
            ObjectNode copia = b.deepCopy();
            if ("MD2".equals(b.path("name").asText())) {
                copia.put("name", "MD2-LAB");
            }
            bancos.add(copia);
        }
        cuerpo.putObject("renombrados").put("MD2", "MD2-LAB");
        HttpResponse<String> bancosLab = lab.json("PUT", "/api/v1/configuracion/bancos", cuerpo);
        assertEquals(200, bancosLab.statusCode(), bancosLab.body());
        JsonNode trasRenombrar = lab.leer("/api/v1/planificacion");
        assertFalse(trasRenombrar.path("bancos").has("MD2"));
        assertEquals(enMd2, trasRenombrar.path("bancos").path("MD2-LAB").size());
        // Un Lab User sigue sin gestionar usuarios (Admin).
        ObjectNode sinUsuarios = JsonJs.objetoVacio();
        sinUsuarios.putArray("users");
        assertEquals(403, lab.json("PUT", "/api/v1/usuarios", sinUsuarios).statusCode());
    }

    @Test
    void unEditorYUnReadOnlyNoGestionanAreas() {
        NavegadorPruebas editor = dentro(EDITOR);
        assertEquals(403, editor.get("/api/v1/areas/gestion").statusCode(), "D62: Areas solo para Admin y Lab User");
        assertEquals(403, editor.json("POST", "/api/v1/areas", area("Editor Area", null)).statusCode());
        assertEquals(403, editor.json("PUT", "/api/v1/areas/HVCH-STC", JsonJs.objetoVacio().put("name", "X")).statusCode());
        assertEquals(403, editor.json("POST", "/api/v1/areas/borrar", JsonJs.objetoVacio().put("code", "HVCH-STC")).statusCode());
        assertEquals(200, editarEnsayo(editor, "Comentario de un Editor").statusCode(), "un Editor sigue editando ensayos");

        NavegadorPruebas lector = dentro(LECTOR);
        JsonNode sesion = lector.leer("/api/v1/sesion");
        assertEquals("readonly", sesion.path("rol").asText());
        assertTrue(sesion.path("soloLectura").asBoolean());
        assertEquals(403, lector.json("POST", "/api/v1/areas", area("Reader Area", null)).statusCode());
        assertEquals(403, editarEnsayo(lector, "No debe guardarse").statusCode());
        assertEquals(403, lector.get("/api/v1/areas/gestion").statusCode());
        assertEquals(3, dentro(ADMIN).leer("/api/v1/areas/gestion").path("areas").size(), "no se ha creado ninguna");
        // D62: un Editor ve los proyectos pero no los cambia ni añade.
        JsonNode catalogos = editor.leer("/api/v1/catalogos");
        ObjectNode proyectos = JsonJs.objetoVacio();
        proyectos.set("projects", catalogos.path("projects").deepCopy());
        ((com.fasterxml.jackson.databind.node.ArrayNode) proyectos.get("projects")).add("99999 - Proyecto de un Editor");
        proyectos.set("benches", catalogos.path("benches").deepCopy());
        assertEquals(403, editor.json("PUT", "/api/v1/catalogos", proyectos).statusCode());
        assertFalse(editor.leer("/api/v1/catalogos").path("projects").toString().contains("Proyecto de un Editor"));
    }

    @Test
    void altaCambioOrdenYBajaDeUnArea() {
        NavegadorPruebas admin = dentro(ADMIN);
        assertEquals(422, admin.json("POST", "/api/v1/areas", area("  ", null)).statusCode(), "sin nombre");
        assertEquals(422, admin.json("POST", "/api/v1/areas", area("thermal benches", null)).statusCode(), "nombre repetido");
        assertEquals(422, admin.json("POST", "/api/v1/areas", area("Otra", "XYZ")).statusCode(), "organización que no existe");

        HttpResponse<String> alta = admin.json("POST", "/api/v1/areas", area("Endurance", null));
        assertEquals(200, alta.statusCode(), alta.body());
        JsonNode nueva = JsonJs.leer(alta.body());
        String codigo = nueva.path("code").asText();
        assertEquals("ENDURANCE", codigo);
        assertTrue(nueva.path("organization").isNull(), "banco a banco");

        // Renombrar y subirla a la segunda posición.
        ObjectNode cambio = JsonJs.objetoVacio().put("name", "Endurance Benches").put("order", 1);
        cambio.set("anterior", JsonJs.objetoVacio().put("name", "Endurance"));
        HttpResponse<String> r = admin.json("PUT", "/api/v1/areas/" + codigo, cambio);
        assertEquals(200, r.statusCode(), r.body());
        JsonNode gestion = admin.leer("/api/v1/areas/gestion");
        assertEquals(List.of("THERMAL", codigo, "HVCH-CTAG", "HVCH-STC"), codigos(gestion));
        assertEquals("Endurance Benches", gestion.path("areas").get(1).path("name").asText());
        assertEquals(1, auditorias(codigo, "MODIFICACION", ADMIN));

        // Otra persona la había cambiado: se rechaza.
        ObjectNode desfasado = JsonJs.objetoVacio().put("name", "Otro nombre");
        desfasado.set("anterior", JsonJs.objetoVacio().put("name", "Endurance"));
        HttpResponse<String> conflicto = admin.json("PUT", "/api/v1/areas/" + codigo, desfasado);
        assertEquals(422, conflicto.statusCode());
        assertTrue(JsonJs.leer(conflicto.body()).path("mensaje").asText().contains("Otra persona"));

        // Con bancos no se puede borrar; los filtros de KPI la incluyen (con BW y CTAG por ser banco a banco).
        moverCategoria(admin, "MD1", codigo);
        assertTrue(admin.leer("/api/v1/areas/gestion").path("areas").get(1).path("benches").asInt() > 0);
        HttpResponse<String> bloqueada = admin.json("POST", "/api/v1/areas/borrar",
                JsonJs.objetoVacio().put("code", codigo).set("anterior", JsonJs.objetoVacio().put("name", "Endurance Benches")));
        assertEquals(422, bloqueada.statusCode());
        assertTrue(JsonJs.leer(bloqueada.body()).path("mensaje").asText().contains("MD1"));
        int total = admin.leer("/api/v1/indicadores/bloques?org=ALL").path("estaSemana").path("total").asInt();
        int enArea = admin.leer("/api/v1/indicadores/bloques?org=" + codigo).path("estaSemana").path("total").asInt();
        int termicos = admin.leer("/api/v1/indicadores/bloques?org=THERMAL").path("estaSemana").path("total").asInt();
        assertTrue(enArea > 0);
        assertEquals(total, enArea + termicos);
        assertEquals(enArea, admin.leer("/api/v1/indicadores/bloques?org=" + codigo + ":BW").path("estaSemana").path("total").asInt()
                + admin.leer("/api/v1/indicadores/bloques?org=" + codigo + ":CTAG").path("estaSemana").path("total").asInt());
        HttpResponse<String> fotos = admin.json("POST", "/api/v1/indicadores/foto-semanal", JsonJs.objetoVacio());
        assertEquals(200, fotos.statusCode(), fotos.body());
        List<String> orgs = new ArrayList<>();
        JsonJs.leer(fotos.body()).forEach(f -> orgs.add(f.path("org").asText()));
        assertEquals(List.of("ALL", "BW", "CTAG", "THERMAL", codigo, codigo + ":BW", codigo + ":CTAG", "HVCH-CTAG", "HVCH-STC"), orgs);
        assertTrue(admin.leer("/api/v1/indicadores/historico?org=" + codigo).path("semanas").size() >= 1);

        // Sin bancos ya se puede borrar; Bancos Térmicos nunca.
        moverCategoria(admin, "MD1", "THERMAL");
        HttpResponse<String> baja = admin.json("POST", "/api/v1/areas/borrar",
                JsonJs.objetoVacio().put("code", codigo).set("anterior", JsonJs.objetoVacio().put("name", "Endurance Benches")));
        assertEquals(200, baja.statusCode(), baja.body());
        assertEquals(List.of("THERMAL", "HVCH-CTAG", "HVCH-STC"), codigos(admin.leer("/api/v1/areas/gestion")));
        assertEquals(1, auditorias(codigo, "BAJA", ADMIN));
        assertEquals(422, admin.json("POST", "/api/v1/areas/borrar", JsonJs.objetoVacio().put("code", codigo)).statusCode(), "ya no existe");
        assertEquals(422, admin.json("POST", "/api/v1/areas/borrar", JsonJs.objetoVacio().put("code", "THERMAL")).statusCode());
    }

    @Test
    void laHojaMensualSeFiltraPorArea() {
        NavegadorPruebas lab = dentro(LAB);
        String codigo = JsonJs.leer(lab.json("POST", "/api/v1/areas", area("Lab STC", "STC")).body()).path("code").asText();
        JsonNode todas = lab.leer("/api/v1/facturacion/hoja?mes=2026-10");
        assertTrue(todas.path("lineas").size() > 0, "hay tareas en octubre");
        String banco = todas.path("lineas").get(0).path("banco").asText();
        moverCategoria(dentro(ADMIN), banco, codigo);
        List<String> delArea = new ArrayList<>();
        for (JsonNode g : lab.leer("/api/v1/planificacion").path("grupos")) {
            if (codigo.equals(g.path("area").asText())) {
                g.path("bancos").forEach(b -> {
                    delArea.add(b.path("nombre").asText());
                    assertEquals("STC", b.path("organizacion").asText(), "organización fija del área");
                });
            }
        }
        assertTrue(delArea.contains(banco));

        JsonNode soloArea = lab.leer("/api/v1/facturacion/hoja?mes=2026-10&area=" + codigo);
        JsonNode termicos = lab.leer("/api/v1/facturacion/hoja?mes=2026-10&area=THERMAL");
        assertEquals("ALL", todas.path("area").asText());
        assertEquals(codigo, soloArea.path("area").asText());
        assertTrue(soloArea.path("lineas").size() > 0, "el banco movido tiene tareas en octubre");
        soloArea.path("lineas").forEach(l -> assertTrue(delArea.contains(l.path("banco").asText())));
        termicos.path("lineas").forEach(l -> assertFalse(delArea.contains(l.path("banco").asText())));
        assertEquals(todas.path("lineas").size(), soloArea.path("lineas").size() + termicos.path("lineas").size());
        assertEquals(422, lab.get("/api/v1/facturacion/hoja?mes=2026-10&area=NO-EXISTE").statusCode());
        JsonNode excel = lab.leer("/api/v1/facturacion/hoja/exportar?mes=2026-10&formato=excel&area=" + codigo);
        assertEquals("Facturacion_2026-10_OCTUBRE_" + codigo + ".xlsx", excel.path("filename").asText());
    }
}
