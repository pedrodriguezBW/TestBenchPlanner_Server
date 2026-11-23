package com.borgwarner.tbp.api.acceso;

import com.borgwarner.tbp.aplicacion.ServicioSesion;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.infraestructura.postgresql.CuentasPostgres;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cuentas de Users/Rights Management (solo Admin, modo servidor):
 *   GET  /api/v1/cuentas                  estado de la cuenta de cada persona de la lista
 *   POST /api/v1/cuentas/clave-temporal   {email} → {ok, email, clave} (se enseña una sola vez)
 *   POST /api/v1/cuentas/desbloquear      {email}
 * El alta y la baja de personas y su rol siguen en PUT /api/v1/usuarios.
 */
@RestController
@RequestMapping("/api/v1/cuentas")
@ConditionalOnProperty(prefix = "planner.servidor", name = "activo", havingValue = "true")
public class CuentasController {

    private final CuentasPostgres cuentas;
    private final ServicioSesion sesion;

    public CuentasController(CuentasPostgres cuentas, ServicioSesion sesion) {
        this.cuentas = cuentas;
        this.sesion = sesion;
    }

    @GetMapping
    public ArrayNode estados() {
        sesion.exigirAdmin(sesion.usuarioActual());
        return cuentas.estados();
    }

    @PostMapping("/clave-temporal")
    public ObjectNode claveTemporal(@RequestBody JsonNode cuerpo) {
        sesion.exigirAdmin(sesion.usuarioActual());
        String email = email(cuerpo);
        String clave = cuentas.claveTemporal(IdentidadSesion.identidad(), email);
        return JsonJs.objetoVacio().put("ok", true).put("email", email).put("clave", clave);
    }

    @PostMapping("/desbloquear")
    public ObjectNode desbloquear(@RequestBody JsonNode cuerpo) {
        sesion.exigirAdmin(sesion.usuarioActual());
        String email = email(cuerpo);
        if (!cuentas.desbloquear(IdentidadSesion.identidad(), email)) {
            throw new IllegalArgumentException(email + " no tiene cuenta todavía.");
        }
        return JsonJs.objetoVacio().put("ok", true).put("email", email);
    }

    private static String email(JsonNode cuerpo) {
        String email = cuerpo == null ? "" : cuerpo.path("email").asText("").trim().toLowerCase();
        if (email.isEmpty()) {
            throw new IllegalArgumentException("Falta el email.");
        }
        return email;
    }
}
