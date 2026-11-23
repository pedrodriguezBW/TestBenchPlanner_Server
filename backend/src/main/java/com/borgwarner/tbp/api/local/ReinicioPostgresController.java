package com.borgwarner.tbp.api.local;

import com.borgwarner.tbp.aplicacion.RegistroLecturas;
import com.borgwarner.tbp.aplicacion.ServicioSesion;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenUsuarios;
import com.borgwarner.tbp.configuracion.PropiedadesPlanner;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.infraestructura.comun.RelojConfigurable;
import com.borgwarner.tbp.infraestructura.postgresql.ImportadorDatos;
import com.borgwarner.tbp.infraestructura.postgresql.UsuariosPostgres;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * El mismo reinicio que ReinicioLocalController, pero sobre la base de datos:
 * VACÍA todas las tablas y carga un escenario (pruebas de integración y
 * laboratorio con planner.almacen=postgresql).
 *
 * Solo existe con planner.almacen=postgresql Y planner.postgresql.reinicio-pruebas=true
 * (por defecto false): en el servidor no hay forma de llamarlo.
 *
 * Con planner.local.reiniciar-al-arrancar=true, además, carga el escenario
 * inicial al arrancar (como el modo local).
 */
@RestController
@RequestMapping("/api/v1/local")
@ConditionalOnExpression("'${planner.almacen:local}' == 'postgresql' and '${planner.postgresql.reinicio-pruebas:false}' == 'true'")
public class ReinicioPostgresController {

    private final ImportadorDatos importador;
    private final PropiedadesPlanner propiedades;
    private final RelojConfigurable reloj;
    private final UsuariosPostgres usuarios;
    private final ServicioSesion sesion;
    private final RegistroLecturas lecturas;

    public ReinicioPostgresController(ImportadorDatos importador, PropiedadesPlanner propiedades, RelojConfigurable reloj,
                                      UsuariosPostgres usuarios, ServicioSesion sesion, RegistroLecturas lecturas,
                                      @Value("${planner.local.reiniciar-al-arrancar:false}") boolean cargarAlArrancar) {
        this.importador = importador;
        this.propiedades = propiedades;
        this.reloj = reloj;
        this.usuarios = usuarios;
        this.sesion = sesion;
        this.lecturas = lecturas;
        if (cargarAlArrancar) {
            importador.reiniciar(Path.of(propiedades.local().escenarioInicial()));
        }
    }

    @PostMapping("/reinicio")
    public ObjectNode reiniciar(@RequestBody(required = false) JsonNode cuerpo) {
        JsonNode p = cuerpo == null ? JsonJs.objetoVacio() : cuerpo;
        Path escenario = Path.of(propiedades.local().escenarioInicial());
        if (p.hasNonNull("escenario")) {
            String nombre = p.get("escenario").asText();
            if (!nombre.matches("[A-Za-z0-9._-]+")) {
                throw new IllegalArgumentException("Nombre de escenario no válido");
            }
            escenario = escenario.resolveSibling(nombre);
        }
        if (!Files.isDirectory(escenario)) {
            throw new IllegalArgumentException("No existe el escenario: " + escenario.getFileName());
        }
        importador.reiniciar(escenario);
        reloj.fijarFecha(p.path("fecha").asText(propiedades.fechaFija()));
        try {
            Files.deleteIfExists(Path.of(propiedades.carpetaUsuario()).resolve("calendario.json"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (p.hasNonNull("email")) {
            usuarios.cambiarIdentidad(new AlmacenUsuarios.Identidad(p.get("email").asText(), p.path("nombre").asText("")));
        }
        lecturas.olvidarTodo();
        sesion.olvidar();
        return JsonJs.objetoVacio().put("ok", true).put("hoy", reloj.hoy().toString());
    }
}
