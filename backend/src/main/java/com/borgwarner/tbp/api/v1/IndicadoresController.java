package com.borgwarner.tbp.api.v1;

import com.borgwarner.tbp.aplicacion.ServicioIndicadores;
import com.borgwarner.tbp.aplicacion.ServicioPlanificacion;
import com.borgwarner.tbp.aplicacion.ServicioSesion;
import com.borgwarner.tbp.aplicacion.Vista;
import com.borgwarner.tbp.dominio.calendario.Fechas;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.kpi.Estadisticas;
import com.borgwarner.tbp.dominio.kpi.FiltroOrganizacion;
import com.borgwarner.tbp.dominio.kpi.FotoSoloLectura;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** KPI, ocupación, histórico y estadísticas para el dashboard y KPI History. */
@RestController
@RequestMapping("/api/v1/indicadores")
public class IndicadoresController {

    private final ServicioIndicadores indicadores;
    private final ServicioPlanificacion planificacion;
    private final ServicioSesion sesion;

    public IndicadoresController(ServicioIndicadores indicadores, ServicioPlanificacion planificacion, ServicioSesion sesion) {
        this.indicadores = indicadores;
        this.planificacion = planificacion;
        this.sesion = sesion;
    }

    private static LocalDate fecha(String texto, LocalDate porDefecto) {
        return texto == null || texto.isBlank() ? porDefecto : LocalDate.parse(texto);
    }

    @GetMapping("/bloques")
    public JsonNode bloques(@RequestParam(required = false) String lunes, @RequestParam(defaultValue = "ALL") String org,
                            @RequestParam(required = false) String responsable) {
        Vista vista = planificacion.vista();
        return JsonJs.mapper().valueToTree(indicadores.bloques(vista, fecha(lunes, vista.contexto().hoy()), org, responsable));
    }

    /** Tabla «Ocupación por tipo de banco» (una semana o el acumulado de un rango). */
    @GetMapping("/tabla-ocupacion")
    public JsonNode tablaOcupacion(@RequestParam String desde, @RequestParam String hasta, @RequestParam(defaultValue = "ALL") String org) {
        Vista vista = planificacion.vista();
        return JsonJs.mapper().valueToTree(indicadores.tablaOcupacion(vista, LocalDate.parse(desde), LocalDate.parse(hasta), org));
    }

    /** Gráfica «% Ocupación por CW»: cwDesde/cwHasta = 0 significa «todo el año». */
    @GetMapping("/tendencia")
    public JsonNode tendencia(@RequestParam(defaultValue = "0") int cwDesde, @RequestParam(defaultValue = "0") int cwHasta,
                              @RequestParam(defaultValue = "ALL") String org) {
        Vista vista = planificacion.vista();
        return JsonJs.mapper().valueToTree(indicadores.tendencia(vista, cwDesde, cwHasta, org));
    }

    @GetMapping("/ocupacion")
    public JsonNode ocupacion(@RequestParam String desde, @RequestParam String hasta, @RequestParam(defaultValue = "ALL") String org) {
        Vista vista = planificacion.vista();
        return JsonJs.mapper().valueToTree(indicadores.ocupacion(vista, LocalDate.parse(desde), LocalDate.parse(hasta), org));
    }

    /**
     * Fotos semanales de una organización, ordenadas por año y CW. {@code filas}
     * es el número total de filas leídas de la lista (todas las organizaciones),
     * que la pantalla muestra como «Source: SharePoint (N rows)».
     */
    @GetMapping("/historico")
    public ObjectNode historico(@RequestParam(defaultValue = "ALL") String org) {
        ServicioIndicadores.Historico h = indicadores.historicoCompleto(org);
        ObjectNode salida = JsonJs.objetoVacio();
        salida.put("filas", h.filas());
        salida.set("semanas", JsonJs.mapper().valueToTree(h.semanas()));
        return salida;
    }

    /** Estado de la foto de cada semana del histórico: cerrada, provisional, en curso (D64). */
    @GetMapping("/estado-fotos")
    public JsonNode estadoFotos(@RequestParam(defaultValue = "ALL") String org) {
        return JsonJs.mapper().valueToTree(indicadores.estadoFotos(org));
    }

    @GetMapping("/estadisticas")
    public ArrayNode estadisticas(@RequestParam(required = false) String desde, @RequestParam(required = false) String hasta,
                                  @RequestParam(defaultValue = "ALL") String org, @RequestParam(required = false) String responsable) {
        Vista vista = planificacion.vista();
        int anio = vista.contexto().calendario().anio();
        ArrayNode salida = JsonJs.mapper().createArrayNode();
        for (Estadisticas.Fila f : indicadores.estadisticas(vista, fecha(desde, LocalDate.of(anio, 1, 1)),
                fecha(hasta, LocalDate.of(anio, 12, 31)), org, responsable)) {
            anadirEstadistica(salida, f);
        }
        return salida;
    }

    private static void anadirEstadistica(ArrayNode salida, Estadisticas.Fila f) {
        ObjectNode fila = salida.addObject();
        fila.put("banco", f.banco());
        fila.put("id", f.ensayo().id());
        fila.put("fecha", Fechas.iso(f.fecha()));
        fila.put("estadoFinal", f.estadoFinal());
        fila.put("motivo", f.motivo());
        fila.put("confirmado", f.confirmado());
        fila.set("datos", f.ensayo().datos());
    }

    /**
     * 📤 Export read-only: los indicadores de un área precalculados banco a banco
     * y semana a semana (FotoSoloLectura), el histórico de KPI de sus filtros y
     * todas sus estadísticas de ensayos cerrados, sin límite de fechas. La
     * interfaz los incrusta en la foto y rehace en el navegador cada filtro.
     * De momento solo se exporta Thermal Benches (sus filtros: THERMAL, BW y CTAG).
     */
    @GetMapping("/foto-solo-lectura")
    public ObjectNode fotoSoloLectura(@RequestParam(defaultValue = "THERMAL") String area) {
        Vista vista = planificacion.vista();
        var filtro = FiltroOrganizacion.de(area, vista.planificacion().configuracion());
        List<LocalDate> lunes = FotoSoloLectura.semanas(vista.contexto().calendario().anio(), vista.contexto().hoy());
        ObjectNode salida = JsonJs.objetoVacio();
        salida.put("area", area);
        ArrayNode listaLunes = salida.putArray("lunes");
        lunes.forEach(l -> listaLunes.add(Fechas.iso(l)));
        ObjectNode bancos = salida.putObject("bancos");
        for (Map.Entry<String, List<FotoSoloLectura.Semana>> banco
                : FotoSoloLectura.porBanco(vista.planificacion(), vista.plan(), lunes, filtro).entrySet()) {
            ArrayNode semanas = bancos.putArray(banco.getKey());
            for (FotoSoloLectura.Semana s : banco.getValue()) {
                ObjectNode semana = semanas.addObject();
                semana.set("o", JsonJs.mapper().valueToTree(new long[]{s.trabajando(), s.inicia(), s.acaba(), s.busy()}));
                semana.set("k", JsonJs.mapper().valueToTree(s.kpi()));
                if (!s.porResponsable().isEmpty()) {
                    semana.set("r", JsonJs.mapper().valueToTree(s.porResponsable()));
                }
            }
        }
        ObjectNode historicos = salida.putObject("historico");
        for (String org : List.of("THERMAL", "BW", "CTAG")) {
            historicos.set(org, historico(org));
        }
        ObjectNode estados = salida.putObject("estadoFotos");
        for (String org : List.of("THERMAL", "BW", "CTAG")) {
            estados.set(org, estadoFotos(org));
        }
        ArrayNode estadisticas = salida.putArray("estadisticas");
        for (Estadisticas.Fila f : indicadores.estadisticas(vista, LocalDate.of(1900, 1, 1), LocalDate.of(2999, 12, 31), area, null)) {
            anadirEstadistica(estadisticas, f);
        }
        return salida;
    }

    /**
     * 📊 Update Yearly Status. origen=export cuando la pide el Export read-only
     * (D64); la tarea automática de cada noche no pasa por aquí.
     */
    @PostMapping("/foto-semanal")
    public JsonNode fotoSemanal(@RequestParam(defaultValue = ServicioIndicadores.MANUAL) String origen) {
        sesion.exigirEscritura(sesion.usuarioActual());
        String o = ServicioIndicadores.EXPORTACION.equals(origen) ? origen : ServicioIndicadores.MANUAL;
        return JsonJs.mapper().valueToTree(indicadores.guardarFotoSemanal(o));
    }
}
