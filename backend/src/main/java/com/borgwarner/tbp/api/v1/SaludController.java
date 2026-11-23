package com.borgwarner.tbp.api.v1;

import com.borgwarner.tbp.TestBenchPlannerApplication;
import com.borgwarner.tbp.configuracion.PropiedadesPlanner;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;

/**
 * Endpoint de salud (hito S4): GET /api/v1/salud, SIN iniciar sesión, para que
 * los scripts de instalación, actualización y comprobación del servidor sepan
 * si la aplicación está en marcha y llega a la base de datos.
 *
 *   200 {"estado":"ok", ...}     todo bien
 *   503 {"estado":"error", ...}  la aplicación responde pero no llega a PostgreSQL
 *
 * No da ningún dato de la planificación ni de las personas: solo versión,
 * almacén, estado de la base y desde cuándo está arrancada.
 */
@RestController
public class SaludController {

    public static final String RUTA = "/api/v1/salud";
    private static final Logger LOG = LoggerFactory.getLogger(SaludController.class);
    private static final int ESPERA_BASE_S = 5;

    private final PropiedadesPlanner propiedades;
    private final ObjectProvider<DataSource> origenes;
    private final Instant arranque = Instant.now();

    public SaludController(PropiedadesPlanner propiedades, ObjectProvider<DataSource> origenes) {
        this.propiedades = propiedades;
        this.origenes = origenes;
    }

    @GetMapping(RUTA)
    public ResponseEntity<ObjectNode> salud() {
        String baseDatos = comprobarBase();
        boolean bien = !"error".equals(baseDatos);
        ObjectNode r = JsonJs.objetoVacio()
                .put("estado", bien ? "ok" : "error")
                .put("version", TestBenchPlannerApplication.VERSION)
                .put("almacen", propiedades.almacen())
                .put("modoServidor", propiedades.servidor().activo())
                .put("baseDatos", baseDatos)
                .put("arrancadaDesde", arranque.toString())
                .put("segundosEnMarcha", Duration.between(arranque, Instant.now()).toSeconds());
        return ResponseEntity.status(bien ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore()).body(r);
    }

    /** «ok», «error» o «no-aplica» (sin PostgreSQL). */
    private String comprobarBase() {
        if (!"postgresql".equalsIgnoreCase(propiedades.almacen())) {
            return "no-aplica";
        }
        DataSource origen = origenes.getIfAvailable();
        if (origen == null) {
            return "error";
        }
        try (Connection c = origen.getConnection(); Statement s = c.createStatement()) {
            s.setQueryTimeout(ESPERA_BASE_S);
            try (ResultSet rs = s.executeQuery("SELECT 1")) {
                return rs.next() ? "ok" : "error";
            }
        } catch (Exception e) {
            LOG.warn("Salud: no se llega a PostgreSQL: {}", e.getMessage());
            return "error";
        }
    }
}
