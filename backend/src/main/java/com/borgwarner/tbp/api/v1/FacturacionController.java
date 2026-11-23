package com.borgwarner.tbp.api.v1;

import com.borgwarner.tbp.aplicacion.facturacion.ServicioFacturacion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API de la facturación (sección «Billing» de la interfaz):
 *   GET  /tipos-equipo            Equipment Types, reglas y Equipment Type de cada banco
 *   PUT  /tipos-equipo            guarda todos los Equipment Types (Admin)
 *   GET  /horas-desde             {editable, facturacionHorasDesde}: primer mes de facturación por horas (D60)
 *   PUT  /horas-desde             {mes: "aaaa-mm" | null}: lo cambia (Admin, solo PostgreSQL)
 *   GET  /hoja?mes=aaaa-mm[&niveles=orden,planta,…]   vista previa de la hoja mensual
 *   GET  /hoja/exportar?mes=…&formato=excel|html[&niveles=…]   archivo (solo líneas completas), en base64
 * niveles: campos de las filas (orden, planta, tipo, equipo, hv, inicio, fin, tarifa),
 * siempre en ese orden; sin indicar = todos; «-» = ninguno (solo el total).
 * area (D57, opcional en /hoja y /hoja/exportar): código de un área; solo
 * entran las tareas de sus bancos. Sin indicar o «ALL» = todas.
 */
@RestController
@RequestMapping("/api/v1/facturacion")
public class FacturacionController {

    private final ServicioFacturacion servicio;

    public FacturacionController(ServicioFacturacion servicio) {
        this.servicio = servicio;
    }

    @GetMapping("/tipos-equipo")
    public ObjectNode tiposEquipo() {
        return servicio.configuracion();
    }

    @PutMapping("/tipos-equipo")
    public ObjectNode guardarTiposEquipo(@RequestBody JsonNode cuerpo) {
        return servicio.guardarTipos(cuerpo);
    }

    @GetMapping("/horas-desde")
    public ObjectNode facturacionHorasDesde() {
        return servicio.facturacionPorHoras();
    }

    @PutMapping("/horas-desde")
    public ObjectNode guardarFacturacionHorasDesde(@RequestBody JsonNode cuerpo) {
        return servicio.guardarFacturacionHorasDesde(cuerpo);
    }

    @GetMapping("/hoja")
    public ObjectNode hoja(@RequestParam("mes") String mes, @RequestParam(value = "niveles", required = false) String niveles,
                           @RequestParam(value = "area", required = false) String area) {
        return servicio.hoja(mes, niveles, area);
    }

    /** Registro del año (Billing › Tests by person): solo consulta, todos. */
    @GetMapping("/registro")
    public ObjectNode registro(@RequestParam("anio") String anio, @RequestParam(value = "area", required = false) String area) {
        return servicio.registro(anio, area);
    }

    @GetMapping("/hoja/exportar")
    public ObjectNode exportar(@RequestParam("mes") String mes, @RequestParam(value = "formato", defaultValue = "excel") String formato,
                               @RequestParam(value = "niveles", required = false) String niveles,
                               @RequestParam(value = "area", required = false) String area) {
        return servicio.exportar(mes, formato, niveles, area);
    }
}
