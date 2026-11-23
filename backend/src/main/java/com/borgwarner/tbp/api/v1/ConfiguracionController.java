package com.borgwarner.tbp.api.v1;

import com.borgwarner.tbp.aplicacion.ServicioListas;
import com.borgwarner.tbp.aplicacion.ServicioSesion;
import com.borgwarner.tbp.aplicacion.SinPermiso;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenCalendario;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenCatalogos;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenRecursos;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenUsuarios;
import com.borgwarner.tbp.aplicacion.puertos.Colaboracion;
import com.borgwarner.tbp.aplicacion.puertos.ConsultaAprobaciones;
import com.borgwarner.tbp.aplicacion.puertos.Reloj;
import com.borgwarner.tbp.aplicacion.ServicioPlanificacion;
import com.borgwarner.tbp.dominio.calendario.Calendario;
import com.borgwarner.tbp.dominio.calendario.Festivo;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.planificacion.Areas;
import com.borgwarner.tbp.dominio.planificacion.ConfiguracionBancos;
import com.borgwarner.tbp.dominio.usuarios.Usuario;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * Catálogos, áreas, usuarios, recursos, calendario, aprobaciones y presencia.
 * Los cuerpos usan el formato de intercambio de la 4.9.4 (ver los puertos).
 */
@RestController
@RequestMapping("/api/v1")
public class ConfiguracionController {

    private final AlmacenCatalogos catalogos;
    private final AlmacenUsuarios usuarios;
    private final AlmacenRecursos recursos;
    private final AlmacenCalendario calendario;
    private final ConsultaAprobaciones aprobaciones;
    private final Colaboracion colaboracion;
    private final ServicioSesion sesion;
    private final Reloj reloj;
    private final ServicioListas listas;
    private final ServicioPlanificacion planificacion;

    public ConfiguracionController(AlmacenCatalogos catalogos, AlmacenUsuarios usuarios, AlmacenRecursos recursos,
                                   AlmacenCalendario calendario, ConsultaAprobaciones aprobaciones,
                                   Colaboracion colaboracion, ServicioSesion sesion, Reloj reloj, ServicioListas listas,
                                   ServicioPlanificacion planificacion) {
        this.listas = listas;
        this.planificacion = planificacion;
        this.catalogos = catalogos;
        this.usuarios = usuarios;
        this.recursos = recursos;
        this.calendario = calendario;
        this.aprobaciones = aprobaciones;
        this.colaboracion = colaboracion;
        this.sesion = sesion;
        this.reloj = reloj;
    }

    @GetMapping("/catalogos")
    public ObjectNode leerCatalogos() {
        return listas.leerCatalogos();
    }

    /**
     * Guardar la configuración de bancos y proyectos. Desde D62 solo un Admin o un Lab User
     * (un Editor solo ve los proyectos). La orden contable de los proyectos («projectOrders»,
     * columna BillingOrder) solo la cambia un Admin.
     */
    @PutMapping("/catalogos")
    public ObjectNode guardarCatalogos(@RequestBody JsonNode cuerpo) {
        Usuario u = sesion.usuarioActual();
        sesion.exigirEscritura(u);
        sesion.exigirAdminOLabUser(u);
        JsonNode ordenes = cuerpo.get("projectOrders");
        if (ordenes != null && ordenes.isObject() && !u.esAdmin()) {
            JsonNode actuales = listas.leerCatalogos().path("projectOrders");
            for (JsonNode p : cuerpo.path("projects")) {
                String titulo = p.asText("");
                if (!ordenes.path(titulo).asText("").trim().equals(actuales.path(titulo).asText("").trim())) {
                    throw new SinPermiso("Solo un Admin puede cambiar la orden contable de un proyecto.");
                }
            }
        }
        if (!u.esAdminOLabUser() && cuerpo instanceof com.fasterxml.jackson.databind.node.ObjectNode o) {
            o.remove("areaNames"); // los nombres de las áreas solo los cambia un Admin o un Lab User (D56)
        }
        return listas.guardarCatalogos(cuerpo);
    }

    // ---- áreas de bancos (D56) ------------------------------------------------

    /**
     * Áreas y si se gestionan desde la aplicación (PostgreSQL: sí, Admin o Lab
     * User; SharePoint y local: no, son las tres de serie):
     * {editable, areas: [{code, name, organization (null = banco a banco), order, benches}]}.
     */
    @GetMapping("/areas/gestion")
    public ObjectNode gestionAreas() {
        sesion.exigirAdminOLabUser(sesion.usuarioActual()); // D62: Configuration › Areas, solo Admin y Lab User
        ConfiguracionBancos configuracion = planificacion.configuracion();
        ObjectNode salida = JsonJs.objetoVacio().put("editable", catalogos.areasEditables());
        ArrayNode lista = salida.putArray("areas");
        int orden = 0;
        for (Areas.Definicion d : configuracion.areas()) {
            long bancos = configuracion.bancos().stream().filter(b -> d.codigo().equals(configuracion.area(b))).count();
            lista.addObject().put("code", d.codigo()).put("name", d.nombre()).put("organization", d.organizacion())
                    .put("order", orden++).put("benches", bancos);
        }
        return salida;
    }

    /** Alta de un área: {name, organization} (Admin o Lab User, solo PostgreSQL). */
    @PostMapping("/areas")
    public ObjectNode crearArea(@RequestBody JsonNode cuerpo) {
        sesion.exigirAdminOLabUser(sesion.usuarioActual());
        return catalogos.crearArea(cuerpo);
    }

    /** Cambio de nombre (y de posición con «order»): {name, order?, anterior: {name}} (Admin o Lab User). */
    @PutMapping("/areas/{codigo}")
    public ObjectNode modificarArea(@PathVariable("codigo") String codigo, @RequestBody JsonNode cuerpo) {
        sesion.exigirAdminOLabUser(sesion.usuarioActual());
        return catalogos.modificarArea(codigo, cuerpo);
    }

    /** Baja de un área sin bancos: {code, anterior: {name}} (Admin o Lab User). */
    @PostMapping("/areas/borrar")
    public ObjectNode borrarArea(@RequestBody JsonNode cuerpo) {
        sesion.exigirAdminOLabUser(sesion.usuarioActual());
        String codigo = cuerpo.path("code").asText("");
        catalogos.borrarArea(codigo, cuerpo.get("anterior"));
        return JsonJs.objetoVacio().put("ok", true).put("code", codigo);
    }

    @GetMapping("/usuarios")
    public ArrayNode leerUsuarios() {
        return usuarios.leer();
    }

    @PutMapping("/usuarios")
    public ObjectNode guardarUsuarios(@RequestBody JsonNode cuerpo) {
        sesion.exigirAdmin(sesion.usuarioActual());
        ObjectNode r = listas.guardarUsuarios(cuerpo);
        sesion.identificar();
        return r;
    }

    @GetMapping("/recursos")
    public ObjectNode leerRecursos() {
        return listas.leerRecursos();
    }

    @PutMapping("/recursos")
    public ObjectNode guardarRecursos(@RequestBody JsonNode cuerpo) {
        sesion.exigirEscritura(sesion.usuarioActual());
        return listas.guardarRecursos(cuerpo);
    }

    @PostMapping("/recursos/bloqueos")
    public ObjectNode guardarBloqueo(@RequestBody JsonNode cuerpo) {
        Usuario u = sesion.usuarioActual();
        sesion.exigirEscritura(u);
        return recursos.guardarBloqueo(cuerpo, ServicioPlanificacion.actor(u));
    }

    @PostMapping("/recursos/bloqueos/borrar")
    public ObjectNode borrarBloqueo(@RequestBody JsonNode cuerpo) {
        sesion.exigirEscritura(sesion.usuarioActual());
        JsonNode id = cuerpo.get("id");
        return recursos.borrarBloqueo(id == null || id.isNull() ? null : id.asText());
    }

    @GetMapping("/calendario")
    public ObjectNode leerCalendario() {
        Calendario c = calendario.leer(reloj.hoy());
        ObjectNode salida = JsonJs.objetoVacio().put("anio", c.anio()).put("saltarFestivos", c.saltarFestivos());
        ArrayNode lista = salida.putArray("festivos");
        c.festivos().forEach(f -> lista.addObject().put("fecha", f.date()).put("nombre", f.name()));
        // true: calendario común (PostgreSQL), solo lo cambia un Admin (D46); false: el de este PC (D6).
        salida.put("compartido", calendario.compartido());
        return salida;
    }

    /** El calendario común solo lo cambia un Admin; el de este PC, quien lo usa (como en la 4.9.4). */
    private void exigirPermisoCalendario() {
        if (calendario.compartido()) {
            sesion.exigirAdmin(sesion.usuarioActual());
        }
    }

    /** Guarda el calendario: el de este PC o, con PostgreSQL, el común (solo Admin). */
    @PutMapping("/calendario")
    public ObjectNode guardarCalendario(@RequestBody JsonNode cuerpo) {
        exigirPermisoCalendario();
        List<Festivo> festivos = new ArrayList<>();
        cuerpo.path("festivos").forEach(f -> festivos.add(new Festivo(f.path("fecha").asText(), f.path("nombre").asText(""))));
        calendario.guardar(new Calendario(cuerpo.path("anio").asInt(reloj.hoy().getYear()), cuerpo.path("saltarFestivos").asBoolean(true), festivos));
        return leerCalendario();
    }

    /** Cambiar de año regenera los festivos de Vigo de ese año (como «Year» en Configuration → Calendar). */
    @PostMapping("/calendario/anio")
    public ObjectNode cambiarAnio(@RequestBody JsonNode cuerpo) {
        exigirPermisoCalendario();
        int anio = cuerpo.path("anio").asInt(reloj.hoy().getYear());
        Calendario actual = calendario.leer(reloj.hoy());
        calendario.guardar(new Calendario(anio, actual.saltarFestivos(), Calendario.festivosDeVigo(anio)));
        return leerCalendario();
    }

    @GetMapping("/aprobaciones")
    public ArrayNode todasAprobaciones() {
        return aprobaciones.todas();
    }

    /** ¿Se gestionan desde la aplicación? (PostgreSQL: sí, solo un Admin; SharePoint y local: no). */
    @GetMapping("/aprobaciones/gestion")
    public ObjectNode gestionAprobaciones() {
        return JsonJs.objetoVacio().put("editable", aprobaciones.editable());
    }

    /** Alta de una aprobación (solo Admin, D54). */
    @PostMapping("/aprobaciones")
    public ObjectNode crearAprobacion(@RequestBody JsonNode cuerpo) {
        sesion.exigirAdmin(sesion.usuarioActual());
        return aprobaciones.crear(cuerpo);
    }

    /** Cambio de una aprobación (solo Admin, D54). */
    @PutMapping("/aprobaciones/{id}")
    public ObjectNode modificarAprobacion(@PathVariable("id") int id, @RequestBody JsonNode cuerpo) {
        sesion.exigirAdmin(sesion.usuarioActual());
        return aprobaciones.modificar(id, cuerpo);
    }

    /** Baja de una aprobación (solo Admin, D54). */
    @PostMapping("/aprobaciones/borrar")
    public ObjectNode borrarAprobacion(@RequestBody JsonNode cuerpo) {
        sesion.exigirAdmin(sesion.usuarioActual());
        int id = cuerpo.path("id").asInt(0);
        aprobaciones.borrar(id, cuerpo.get("anterior"));
        return JsonJs.objetoVacio().put("ok", true).put("id", id);
    }

    @PostMapping("/aprobaciones/estados")
    public ObjectNode estados(@RequestBody JsonNode cuerpo) {
        List<JsonNode> ids = new ArrayList<>();
        cuerpo.path("ids").forEach(ids::add);
        return aprobaciones.estados(ids);
    }

    @PostMapping("/presencia")
    public ObjectNode presencia() {
        Usuario u = sesion.usuarioActual();
        return colaboracion.latidoPresencia(new AlmacenUsuarios.Identidad(u.email(), u.displayName()));
    }

    /** Versión de esta aplicación y la publicada en TestBenchAppConfig (aviso de versión antigua). */
    @GetMapping("/version")
    public ObjectNode version() {
        String publicada = colaboracion.versionPublicada();
        return JsonJs.objetoVacio().put("backend", com.borgwarner.tbp.TestBenchPlannerApplication.VERSION)
                .put("published", publicada == null ? com.borgwarner.tbp.TestBenchPlannerApplication.VERSION : publicada);
    }

    @GetMapping("/actividad")
    public ObjectNode actividad() {
        return colaboracion.leerActividad(200);
    }
}
