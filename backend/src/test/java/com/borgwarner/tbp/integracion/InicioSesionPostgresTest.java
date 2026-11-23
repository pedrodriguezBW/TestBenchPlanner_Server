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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hito S3 · inicio de sesión del modo servidor: sin sesión no se ve nada,
 * cada persona entra con su cuenta y su rol, bloqueo tras intentos fallidos,
 * contraseña temporal con cambio obligatorio, sesiones que se cierran al
 * cambiar la contraseña o al salir de la lista, auditoría con la persona real
 * y peticiones desde otra web rechazadas.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InicioSesionPostgresTest {

    static final String ADMIN = "usuario01@ejemplo.local";
    static final String EDITOR = "usuario07@ejemplo.local";
    static final String LECTOR = "lector@ejemplo.local";
    static final String CLAVE = "Clave-de-prueba-1";

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) throws IOException {
        Path carpeta = Files.createTempDirectory("tbp-sesion");
        PostgresPruebas.registrar(registro);
        registro.add("planner.postgresql.reinicio-pruebas", () -> "false");
        registro.add("planner.servidor.activo", () -> "true");
        registro.add("planner.fecha-fija", () -> "2026-11-05");
        registro.add("planner.carpeta-usuario", carpeta::toString);
        registro.add("planner.postgresql.usuario-email", () -> "nadie@ejemplo.local"); // no se usa en el servidor
        registro.add("planner.autenticacion.intentos-maximos", () -> "3");
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
        importador.reiniciar(PostgresPruebas.escenario()); // también borra las cuentas
        bd.jdbc().update("INSERT INTO usuario (email, titulo, rol) VALUES (?, 'Lector', 'ReadOnly')", LECTOR);
        cuentas.establecerAdmin(ADMIN, "", CLAVE);
        conClave(EDITOR);
        conClave(LECTOR);
    }

    /** Cuenta lista para entrar (contraseña temporal ya cambiada por CLAVE). */
    private void conClave(String email) {
        String temporal = cuentas.claveTemporal(CuentasPostgres.INSTALACION, email);
        cuentas.cambiarClave(email, temporal, CLAVE);
    }

    private NavegadorPruebas dentro(String email) {
        NavegadorPruebas n = new NavegadorPruebas(puerto);
        HttpResponse<String> r = n.entrar(email, CLAVE);
        assertEquals(302, r.statusCode());
        assertEquals("/", NavegadorPruebas.destino(r), "entra en la aplicación");
        return n;
    }

    @Test
    void sinSesionNoSeVeNada() {
        NavegadorPruebas n = new NavegadorPruebas(puerto);
        HttpResponse<String> api = n.get("/api/v1/planificacion");
        assertEquals(401, api.statusCode());
        assertEquals("sin-sesion", JsonJs.leer(api.body()).path("error").asText());
        HttpResponse<String> raiz = n.get("/");
        assertEquals(302, raiz.statusCode());
        assertEquals("/login", NavegadorPruebas.destino(raiz));
        HttpResponse<String> formulario = n.get("/login");
        assertEquals(200, formulario.statusCode());
        assertTrue(formulario.body().contains("name=\"clave\""));
        // Los avisos de pestaña de la interfaz no necesitan sesión (no apagan nada en el servidor).
        assertEquals(200, n.json("POST", "/api/heartbeat", JsonJs.objetoVacio().put("pestana", "p1")).statusCode());
    }

    @Test
    void entrarYSalir() {
        NavegadorPruebas n = dentro(ADMIN);
        JsonNode s = n.leer("/api/v1/sesion");
        assertEquals(ADMIN, s.path("email").asText());
        assertEquals("admin", s.path("rol").asText());
        assertTrue(s.path("modoServidor").asBoolean());
        assertEquals(1, bd.jdbc().queryForObject(
                "SELECT count(*) FROM registro_actividad WHERE email = ? AND accion = 'login'", Integer.class, ADMIN));

        HttpResponse<String> salida = n.salir();
        assertEquals(302, salida.statusCode());
        assertEquals("/login?salida", NavegadorPruebas.destino(salida));
        assertEquals(401, n.get("/api/v1/sesion").statusCode());
    }

    @Test
    void cadaPersonaConSuRol() {
        NavegadorPruebas admin = dentro(ADMIN);
        NavegadorPruebas editor = dentro(EDITOR);
        NavegadorPruebas lector = dentro(LECTOR);
        assertEquals("editor", editor.leer("/api/v1/sesion").path("rol").asText());
        assertEquals("readonly", lector.leer("/api/v1/sesion").path("rol").asText());

        // Solo un Admin: calendario común, usuarios y cuentas.
        JsonNode cal = admin.leer("/api/v1/calendario");
        assertEquals(200, admin.json("PUT", "/api/v1/calendario", cal).statusCode());
        assertEquals(403, editor.json("PUT", "/api/v1/calendario", cal).statusCode());
        assertEquals(403, editor.get("/api/v1/cuentas").statusCode());
        assertEquals(200, admin.get("/api/v1/cuentas").statusCode());
        JsonNode lista = admin.leer("/api/v1/usuarios");
        assertEquals(403, editor.json("PUT", "/api/v1/usuarios", JsonJs.objetoVacio().set("users", lista)).statusCode());

        // Solo lectura: no guarda nada.
        JsonNode recursos = lector.leer("/api/v1/recursos");
        HttpResponse<String> r = lector.json("PUT", "/api/v1/recursos", recursos);
        assertEquals(403, r.statusCode());
        assertTrue(r.body().contains("solo lectura"));
    }

    @Test
    void laAuditoriaGuardaQuienHaceCadaCambio() {
        NavegadorPruebas editor = dentro(EDITOR);
        JsonNode md2 = editor.leer("/api/v1/planificacion").path("bancos").path("MD2");
        String id = md2.get(1).path("id").asText();
        ObjectNode cambio = JsonJs.objetoVacio().put("version", md2.get(1).path("version").asText());
        cambio.putArray("cambios").addObject().put("campo", "comments").put("valor", "Cambio del editor");
        assertEquals(200, editor.json("POST", "/api/v1/bancos/MD2/ensayos/" + id + "/campos", cambio).statusCode());
        assertEquals(EDITOR, bd.jdbc().queryForObject("SELECT usuario_email FROM auditoria WHERE entidad = 'ensayo' AND clave = ? "
                + "ORDER BY id DESC LIMIT 1", String.class, id));

        NavegadorPruebas admin = dentro(ADMIN);
        JsonNode cal = admin.leer("/api/v1/calendario");
        ((ArrayNode) cal.get("festivos")).addObject().put("fecha", "2026-11-06").put("nombre", "Puente");
        assertEquals(200, admin.json("PUT", "/api/v1/calendario", cal).statusCode());
        assertEquals(ADMIN, bd.jdbc().queryForObject("SELECT usuario_email FROM auditoria WHERE entidad = 'calendario' "
                + "ORDER BY id DESC LIMIT 1", String.class));
    }

    @Test
    void bloqueoTrasVariosIntentosFallidos() {
        NavegadorPruebas n = new NavegadorPruebas(puerto);
        assertEquals("/login?error", NavegadorPruebas.destino(n.entrar(EDITOR, "mal-1")));
        assertEquals("/login?error", NavegadorPruebas.destino(n.entrar(EDITOR, "mal-2")));
        assertEquals("/login?bloqueada", NavegadorPruebas.destino(n.entrar(EDITOR, "mal-3")));
        // Bloqueada: ni con la contraseña buena.
        assertEquals("/login?bloqueada", NavegadorPruebas.destino(n.entrar(EDITOR, CLAVE)));
        assertEquals(401, n.get("/api/v1/sesion").statusCode());
        assertEquals(1, bd.jdbc().queryForObject("SELECT count(*) FROM auditoria WHERE entidad = 'cuenta' AND clave = ? "
                + "AND operacion = 'BLOQUEO'", Integer.class, EDITOR));

        NavegadorPruebas admin = dentro(ADMIN);
        JsonNode estado = estadoDe(admin, EDITOR);
        assertFalse(estado.path("bloqueadaHasta").isNull());
        assertEquals(200, admin.json("POST", "/api/v1/cuentas/desbloquear", JsonJs.objetoVacio().put("email", EDITOR)).statusCode());
        assertTrue(estadoDe(admin, EDITOR).path("bloqueadaHasta").isNull());
        dentro(EDITOR);
    }

    @Test
    void sinCuentaOFueraDeLaListaNoSeEntra() {
        NavegadorPruebas n = new NavegadorPruebas(puerto);
        assertEquals("/login?error", NavegadorPruebas.destino(n.entrar("desconocido@ejemplo.local", CLAVE)));
        // En la lista pero sin contraseña todavía.
        assertEquals("/login?error", NavegadorPruebas.destino(n.entrar("usuario08@ejemplo.local", CLAVE)));
        assertEquals("/login?error", NavegadorPruebas.destino(n.entrar("", "")));
    }

    @Test
    void elBloqueoNoDelataQueEmailsTienenCuenta() {
        NavegadorPruebas n = new NavegadorPruebas(puerto);
        String nadie = "no-existe@ejemplo.local";
        assertEquals("/login?error", NavegadorPruebas.destino(n.entrar(nadie, "mal-1")));
        assertEquals("/login?error", NavegadorPruebas.destino(n.entrar(nadie, "mal-2")));
        assertEquals("/login?bloqueada", NavegadorPruebas.destino(n.entrar(nadie, "mal-3")), "igual que una cuenta real");
        assertEquals("/login?bloqueada", NavegadorPruebas.destino(n.entrar(nadie, "mal-4")));
    }

    @Test
    void laClaveTemporalObligaACambiarla() {
        NavegadorPruebas admin = dentro(ADMIN);
        HttpResponse<String> r = admin.json("POST", "/api/v1/cuentas/clave-temporal", JsonJs.objetoVacio().put("email", "usuario08@ejemplo.local"));
        assertEquals(200, r.statusCode());
        String temporal = JsonJs.leer(r.body()).path("clave").asText();
        assertTrue(temporal.matches("[A-Za-z2-9]{4}-[A-Za-z2-9]{4}-[A-Za-z2-9]{4}"), temporal);
        assertTrue(estadoDe(admin, "usuario08@ejemplo.local").path("debeCambiar").asBoolean());
        assertFalse(bd.jdbc().queryForObject("SELECT hash FROM credencial WHERE email = 'usuario08@ejemplo.local'", String.class)
                .contains(temporal), "solo el hash");

        NavegadorPruebas nueva = new NavegadorPruebas(puerto);
        assertEquals("/cambiar-clave", NavegadorPruebas.destino(nueva.entrar("usuario08@ejemplo.local", temporal)));
        HttpResponse<String> api = nueva.get("/api/v1/planificacion");
        assertEquals(403, api.statusCode());
        assertEquals("cambiar-clave", JsonJs.leer(api.body()).path("error").asText());
        assertEquals("/cambiar-clave", NavegadorPruebas.destino(nueva.get("/")));
        assertEquals(200, nueva.get("/cambiar-clave").statusCode());

        String nuevaClave = "Mi-clave-nueva-8";
        assertTrue(nueva.formulario("/cambiar-clave", Map.of("actual", temporal, "nueva", nuevaClave, "repetida", "otra-cosa-99"))
                .body().contains("no coinciden"));
        assertTrue(nueva.formulario("/cambiar-clave", Map.of("actual", temporal, "nueva", "corta1", "repetida", "corta1"))
                .body().contains("al menos"));
        HttpResponse<String> hecho = nueva.formulario("/cambiar-clave", Map.of("actual", temporal, "nueva", nuevaClave, "repetida", nuevaClave));
        assertEquals(302, hecho.statusCode());
        assertEquals("/", NavegadorPruebas.destino(hecho));
        assertEquals(200, nueva.get("/api/v1/planificacion").statusCode());

        assertEquals("/login?error", NavegadorPruebas.destino(new NavegadorPruebas(puerto).entrar("usuario08@ejemplo.local", temporal)));
        assertEquals("/", NavegadorPruebas.destino(new NavegadorPruebas(puerto).entrar("usuario08@ejemplo.local", nuevaClave)));
        assertEquals(1, bd.jdbc().queryForObject("SELECT count(*) FROM auditoria WHERE entidad = 'cuenta' AND clave = ? "
                + "AND operacion = 'CLAVE_TEMPORAL' AND usuario_email = ?", Integer.class, "usuario08@ejemplo.local", ADMIN));
    }

    @Test
    void cambiarLaClaveCierraLasOtrasSesiones() {
        NavegadorPruebas aqui = dentro(EDITOR);
        NavegadorPruebas alli = dentro(EDITOR);
        String nueva = "Otra-clave-del-editor-2";
        HttpResponse<String> r = aqui.formulario("/cambiar-clave", Map.of("actual", CLAVE, "nueva", nueva, "repetida", nueva));
        assertEquals("/", NavegadorPruebas.destino(r));
        assertEquals(200, aqui.get("/api/v1/sesion").statusCode(), "la sesión que la cambia sigue");
        HttpResponse<String> otra = alli.get("/api/v1/sesion");
        assertEquals(401, otra.statusCode(), "la otra sesión se cierra");
        assertEquals("sin-sesion", JsonJs.leer(otra.body()).path("error").asText());
    }

    @Test
    void salirDeLaListaQuitaLaCuentaYLaSesion() {
        NavegadorPruebas editor = dentro(EDITOR);
        NavegadorPruebas admin = dentro(ADMIN);
        ArrayNode sinEditor = JsonJs.mapper().createArrayNode();
        admin.leer("/api/v1/usuarios").forEach(u -> {
            if (!EDITOR.equals(u.path("email").asText())) {
                sinEditor.add(u);
            }
        });
        assertEquals(200, admin.json("PUT", "/api/v1/usuarios", JsonJs.objetoVacio().set("users", sinEditor)).statusCode());
        assertEquals(401, editor.get("/api/v1/sesion").statusCode());
        assertEquals(0, bd.jdbc().queryForObject("SELECT count(*) FROM credencial WHERE email = ?", Integer.class, EDITOR));
        assertNull(cuentas.sello(EDITOR));
    }

    @Test
    void peticionesDesdeOtraWebRechazadas() {
        NavegadorPruebas admin = dentro(ADMIN);
        HttpResponse<String> ajena = admin.json("POST", "/api/v1/presencia", JsonJs.objetoVacio(), "Origin", "http://malo.example");
        assertEquals(403, ajena.statusCode());
        assertEquals("origen", JsonJs.leer(ajena.body()).path("error").asText());
        assertEquals(200, admin.json("POST", "/api/v1/presencia", JsonJs.objetoVacio()).statusCode());
    }

    @Test
    void elAvisoDelServidorSabeSiHayAdmin() {
        assertTrue(cuentas.hayAdminConClave());
        bd.jdbc().update("DELETE FROM credencial WHERE email = ?", ADMIN);
        assertFalse(cuentas.hayAdminConClave());
    }

    private static JsonNode estadoDe(NavegadorPruebas admin, String email) {
        for (JsonNode c : admin.leer("/api/v1/cuentas")) {
            if (email.equals(c.path("email").asText())) {
                return c;
            }
        }
        throw new AssertionError("sin estado de cuenta para " + email);
    }
}
