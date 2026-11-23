package com.borgwarner.tbp.api.v1;

import com.borgwarner.tbp.aplicacion.ServicioHorarios;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API de Configuration › Test Calendar / Working Hours (D59):
 *   GET  /gestion                    {editable, puedeEditar, facturacionHorasDesde, jornadas}
 *   GET  ?banco=X                    ensayos del banco con su horario (o el de por defecto)
 *   GET  /avisos                     ensayos con horario que anula su Weekend y bancos por piezas (tabla del banco)
 *   POST /{id}/vista-previa          horas facturables con el horario recibido ({horario}) o el guardado (cuerpo vacío)
 *   PUT  /{id}                       guarda {horario, anterior: {version}} (Admin o Lab User; 422 si otra persona lo ha cambiado)
 *   POST /{id}/restablecer           vuelve a los valores por defecto ({anterior: {version}})
 * Leer: cualquiera. Cambiar: Admin o Lab User con PostgreSQL (403 / 422).
 */
@RestController
@RequestMapping("/api/v1/horarios")
public class HorariosController {

    private final ServicioHorarios servicio;

    public HorariosController(ServicioHorarios servicio) {
        this.servicio = servicio;
    }

    @GetMapping("/gestion")
    public ObjectNode gestion() {
        return servicio.gestion();
    }

    @GetMapping
    public ObjectNode banco(@RequestParam("banco") String banco) {
        return servicio.banco(banco);
    }

    @GetMapping("/avisos")
    public ObjectNode avisos() {
        return servicio.avisos();
    }

    @PostMapping("/{id}/vista-previa")
    public ObjectNode vistaPrevia(@PathVariable("id") String id, @RequestBody(required = false) JsonNode cuerpo) {
        return servicio.vistaPrevia(id, cuerpo);
    }

    @PutMapping("/{id}")
    public ObjectNode guardar(@PathVariable("id") String id, @RequestBody JsonNode cuerpo) {
        return servicio.guardar(id, cuerpo);
    }

    @PostMapping("/{id}/restablecer")
    public ObjectNode restablecer(@PathVariable("id") String id, @RequestBody(required = false) JsonNode cuerpo) {
        return servicio.restablecer(id, cuerpo);
    }
}
