
package com.borgwarner.tbp.api.v1;

import com.borgwarner.tbp.aplicacion.ServicioSesion;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.operaciones.ReglaNoCumplida;
import com.borgwarner.tbp.infraestructura.postgresql.ImportadorTiming;
import com.borgwarner.tbp.infraestructura.postgresql.TimingPostgres;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/v1/timing")
@ConditionalOnProperty(name = "planner.servidor.activo", havingValue = "true")
public class TimingController {

    private final TimingPostgres timing;
    private final ImportadorTiming importador;
    private final ServicioSesion sesion;

    public TimingController(TimingPostgres timing, ImportadorTiming importador, ServicioSesion sesion) {
        this.timing = timing;
        this.importador = importador;
        this.sesion = sesion;
    }

    @GetMapping("/opciones")
    public ObjectNode opciones() {
        return timing.opciones(acceso());
    }

    @GetMapping("/tareas")
    public ArrayNode lista(@RequestParam("vista") String vista) {
        return timing.lista(acceso(), vista);
    }

    @GetMapping("/tareas/{id}")
    public ObjectNode detalle(@PathVariable("id") long id) {
        return timing.detalle(acceso(), id);
    }

    @PostMapping("/tareas")
    public ObjectNode crear(@RequestBody JsonNode cuerpo) {
        return timing.crear(acceso(), cuerpo);
    }

    @PostMapping("/tareas/{id}/{accion}")
    public ObjectNode operar(@PathVariable("id") long id, @PathVariable("accion") String accion, @RequestBody JsonNode cuerpo) {
        return timing.operar(acceso(), id, accion, cuerpo);
    }

    @PutMapping("/equipos")
    public ObjectNode equipo(@RequestBody JsonNode cuerpo) {
        return timing.guardarEquipo(acceso(), cuerpo);
    }

    @PostMapping(value = "/importacion", consumes = "multipart/form-data")
    public ObjectNode importar(@RequestParam("archivo") MultipartFile archivo,
                               @RequestParam(name = "simular", defaultValue = "true") boolean simular) {
        var acceso = acceso();
        try (var entrada = archivo.getInputStream()) {
            return importador.importar(acceso, entrada, simular);
        } catch (IOException e) {
            throw new ReglaNoCumplida("No se ha podido abrir el archivo de importación.");
        }
    }

    @ExceptionHandler(TimingPostgres.Conflicto.class)
    public ResponseEntity<ObjectNode> conflicto(TimingPostgres.Conflicto e) {
        return ResponseEntity.status(409).body(JsonJs.objetoVacio().put("error", "timing-conflicto").put("mensaje", e.getMessage()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ObjectNode> noEncontrada(NoSuchElementException e) {
        return ResponseEntity.status(404).body(JsonJs.objetoVacio().put("error", "timing-no-encontrada").put("mensaje", e.getMessage()));
    }

    private TimingPostgres.Acceso acceso() {
        return timing.acceso(sesion.usuarioActual());
    }
}
