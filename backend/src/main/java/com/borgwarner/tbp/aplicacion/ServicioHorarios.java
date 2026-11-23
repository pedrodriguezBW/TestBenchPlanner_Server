package com.borgwarner.tbp.aplicacion;

import com.borgwarner.tbp.aplicacion.puertos.AlmacenHorarios;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenTiposEquipo;
import com.borgwarner.tbp.dominio.calendario.Fechas;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.comun.ValorJs;
import com.borgwarner.tbp.dominio.ensayo.Ensayo;
import com.borgwarner.tbp.dominio.ensayo.EstadosEnsayo;
import com.borgwarner.tbp.dominio.facturacion.HojaMensual;
import com.borgwarner.tbp.dominio.facturacion.HorarioEnsayo;
import com.borgwarner.tbp.dominio.facturacion.HorasTrabajo;
import com.borgwarner.tbp.dominio.facturacion.TipoEquipo;
import com.borgwarner.tbp.dominio.operaciones.ReglaNoCumplida;
import com.borgwarner.tbp.dominio.planificacion.PlanEnsayo;
import com.borgwarner.tbp.dominio.usuarios.Usuario;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Configuration › Test Calendar / Working Hours (D59): horario de trabajo de
 * cada ensayo, vista previa de las horas facturables y avisos para la tabla del
 * banco. Leer lo puede cualquiera; cambiar, un Admin o un Lab User con
 * permiso de escritura y solo con PostgreSQL (en los demás almacenes no hay
 * horarios: 422).
 */
@Service
public class ServicioHorarios {

    /** Días como máximo en la rejilla de medias horas de la vista previa. */
    static final int DIAS_REJILLA = 1000;

    private final ServicioPlanificacion planificacion;
    private final ServicioSesion sesion;
    private final ServicioListas listas;
    private final AlmacenHorarios horarios;
    private final AlmacenTiposEquipo tipos;
    private final EstadoConexion conexion;

    public ServicioHorarios(ServicioPlanificacion planificacion, ServicioSesion sesion, ServicioListas listas, AlmacenHorarios horarios,
                            AlmacenTiposEquipo tipos, EstadoConexion conexion) {
        this.planificacion = planificacion;
        this.sesion = sesion;
        this.listas = listas;
        this.horarios = horarios;
        this.tipos = tipos;
        this.conexion = conexion;
    }

    /** Un ensayo con su banco y sus fechas (inicio y fin, inclusive; null si aún no tiene). */
    record Tarea(String banco, Ensayo ensayo, LocalDate inicio, LocalDate fin) {
    }

    // ---- fechas del ensayo ---------------------------------------------------------------------

    private static LocalDate dia(String texto) {
        return texto == null || texto.length() < 10 ? null : Fechas.dia(texto.substring(0, 10));
    }

    private static String primero(Ensayo e, String... campos) {
        for (String c : campos) {
            String v = e.texto(c);
            if (!v.isEmpty()) {
                return v;
            }
        }
        return "";
    }

    /**
     * Fechas del ensayo para su horario: cerrado, las reales (como la hoja
     * mensual); abierto, el arranque real o el planificado hasta el fin real o el
     * planificado (y como poco hasta hoy si ya ha arrancado).
     */
    static LocalDate[] fechas(Ensayo e, PlanEnsayo plan, LocalDate hoy) {
        LocalDate inicio;
        LocalDate fin;
        if (e.cerrado()) {
            inicio = dia(primero(e, "finalTrueStart", "finalActualStart", "histStart", "actualStart"));
            fin = dia(primero(e, "finalTrueEnd", "histEnd", "trueEnd"));
        } else {
            inicio = dia(EstadosEnsayo.arranqueReal(e));
            if (inicio == null && plan != null) {
                inicio = plan.inicio();
            }
            fin = dia(e.texto("trueEnd"));
            if (fin == null) {
                fin = plan != null ? plan.fin() : null;
                if (EstadosEnsayo.arrancado(e, hoy) && (fin == null || fin.isBefore(hoy))) {
                    fin = hoy;
                }
            }
        }
        if (inicio != null && fin != null && fin.isBefore(inicio)) {
            fin = inicio;
        }
        return new LocalDate[]{inicio, fin};
    }

    private Tarea tarea(Vista v, String banco, Ensayo e) {
        LocalDate[] f = fechas(e, v.plan().de(e), v.contexto().hoy());
        return new Tarea(banco, e, f[0], f[1]);
    }

    private Tarea buscar(Vista v, String id) {
        String banco = v.planificacion().bancoDe(id).orElseThrow(() -> new ReglaNoCumplida("El ensayo " + id + " ya no existe."));
        Ensayo e = v.planificacion().buscar(banco, id).orElseThrow(() -> new ReglaNoCumplida("El ensayo " + id + " ya no existe."));
        return tarea(v, banco, e);
    }

    // ---- Equipment Types por piezas ------------------------------------------------------------

    /**
     * Bancos cuyo Equipment Type se factura por piezas (el del año de hoy o, si
     * no está, el del último año configurado). En ellos setupHours son piezas.
     */
    public Set<String> bancosPorPiezas() {
        Set<String> bancos = new TreeSet<>();
        try {
            int anio = planificacion.contexto().hoy().getYear();
            Map<String, Integer> anioDe = new HashMap<>();
            Map<String, String> unidadDe = new HashMap<>();
            for (TipoEquipo t : tipos.leer()) {
                String clave = t.titulo().toLowerCase(Locale.ROOT);
                Integer elegido = anioDe.get(clave);
                boolean mejor = elegido == null || (elegido != anio && (t.anio() == anio || t.anio() > elegido));
                if (mejor) {
                    anioDe.put(clave, t.anio());
                    unidadDe.put(clave, t.unidad());
                }
            }
            for (JsonNode b : listas.leerCatalogos().path("benches")) {
                String et = b.path("equipmentType").asText("").trim().toLowerCase(Locale.ROOT);
                if (TipoEquipo.PIEZAS.equals(unidadDe.get(et))) {
                    bancos.add(b.path("name").asText(""));
                }
            }
        } catch (PeticionCancelada e) {
            throw e;
        } catch (RuntimeException e) {
            // sin la lista de Equipment Types o de bancos: ningún banco por piezas
        }
        return bancos;
    }

    /** Setup de la columna antigua setupHours (null si no hay o si el banco factura por piezas). */
    private static Double setupAntiguo(Ensayo e, boolean porPiezas) {
        if (porPiezas || e.texto("setupHours").isEmpty()) {
            return null;
        }
        double s = ValorJs.numero(e.campo("setupHours"));
        return Double.isNaN(s) || s < 0 ? null : s;
    }

    // ---- lectura ----------------------------------------------------------------------------

    private boolean puedeEditar(Usuario u) {
        return horarios.editable() && !u.soloLectura() && u.esAdminOLabUser() && !conexion.sinConexion();
    }

    /** {editable, puedeEditar, facturacionHorasDesde, jornadas}. */
    public ObjectNode gestion() {
        exigirConsulta();
        ObjectNode salida = JsonJs.objetoVacio();
        salida.put("editable", horarios.editable());
        salida.put("puedeEditar", puedeEditar(sesion.usuarioActual()));
        YearMonth desde = horarios.facturacionHorasDesde();
        if (desde == null) {
            salida.putNull("facturacionHorasDesde");
        } else {
            salida.put("facturacionHorasDesde", desde.toString());
        }
        ArrayNode jornadas = salida.putArray("jornadas");
        HorarioEnsayo.JORNADAS.forEach(jornadas::add);
        return salida;
    }

    /** Un ensayo con su horario (guardado o por defecto con el setup antiguo como propuesta). */
    private ObjectNode json(Tarea t, AlmacenHorarios.Guardado g, boolean porPiezas) {
        Ensayo e = t.ensayo();
        ObjectNode o = JsonJs.objetoVacio();
        o.put("id", e.id());
        o.put("banco", t.banco());
        o.put("proyecto", e.texto("project"));
        o.put("tipo", e.tipo());
        o.put("responsable", e.texto("resp"));
        o.put("descripcion", e.texto("comments")); // descripción del ensayo (columna Comments de la tarea)
        o.put("cerrado", e.cerrado());
        o.put("especial", e.esTipoEspecial());
        o.put("inicio", t.inicio() == null ? null : Fechas.iso(t.inicio()));
        o.put("fin", t.fin() == null ? null : Fechas.iso(t.fin()));
        o.put("finDeSemanaEnsayo", e.finDeSemana());
        o.put("porPiezas", porPiezas);
        double parada = ValorJs.numeroOCero(e.campo("downtimeDays"));
        o.put("paradaDias", Double.isNaN(parada) || parada < 0 ? 0 : parada);
        Double antiguo = setupAntiguo(e, porPiezas);
        if (antiguo == null) {
            o.putNull("setupAntiguo");
        } else {
            o.put("setupAntiguo", antiguo);
        }
        HorarioEnsayo h = g != null ? g.horario() : HorarioEnsayo.POR_DEFECTO.conSetup(antiguo);
        ObjectNode horario = h.json();
        // Horas efectivas para la pantalla (las null son «por defecto»).
        horario.put("horaInicioEfectiva", HorarioEnsayo.hora(h.minutoInicio()));
        horario.put("horaFinEfectiva", HorarioEnsayo.hora(h.minutoFin()));
        o.set("horario", horario);
        o.put("guardado", g != null);
        o.put("version", g == null ? 0 : g.version());
        o.put("modificadoPor", g == null ? null : g.modificadoPor());
        o.put("modificadoEn", g == null ? null : g.modificadoEn());
        o.put("anulaFinDeSemana", g != null && g.horario().anulaFinDeSemana(e.finDeSemana()));
        return o;
    }

    /** Ensayos del banco con su horario: {banco, editable, ensayos: [...]}, abiertos primero. */
    public ObjectNode banco(String banco) {
        exigirConsulta();
        Vista v = planificacion.vista();
        if (banco == null || !v.planificacion().existeBanco(banco)) {
            throw new ReglaNoCumplida("El banco " + banco + " no existe.");
        }
        Map<String, AlmacenHorarios.Guardado> guardados = horarios.todos();
        boolean porPiezas = bancosPorPiezas().contains(banco);
        ObjectNode salida = JsonJs.objetoVacio().put("banco", banco).put("editable", horarios.editable()).put("porPiezas", porPiezas);
        ArrayNode lista = salida.putArray("ensayos");
        for (Ensayo e : v.planificacion().activos(banco)) {
            lista.add(json(tarea(v, banco, e), guardados.get(e.id()), porPiezas));
        }
        for (Ensayo e : v.planificacion().cerrados(banco)) {
            lista.add(json(tarea(v, banco, e), guardados.get(e.id()), porPiezas));
        }
        return salida;
    }

    /**
     * Avisos para la tabla de los bancos: {editable, bancosPorPiezas: [...],
     * ensayos: {id: {jornada, anulaFinDeSemana, trabajaFinesDeSemana}}} (solo los
     * ensayos con horario guardado).
     */
    public ObjectNode avisos() {
        ObjectNode salida = JsonJs.objetoVacio().put("editable", horarios.editable());
        ArrayNode piezas = salida.putArray("bancosPorPiezas");
        bancosPorPiezas().forEach(piezas::add);
        ObjectNode ensayos = salida.putObject("ensayos");
        Map<String, AlmacenHorarios.Guardado> guardados = horarios.todos();
        if (guardados.isEmpty()) {
            return salida;
        }
        Vista v = planificacion.vista();
        for (String banco : v.planificacion().bancos()) {
            for (Ensayo e : v.planificacion().ensayos(banco)) {
                AlmacenHorarios.Guardado g = guardados.get(e.id());
                if (g != null) {
                    ensayos.putObject(e.id()).put("jornada", g.horario().jornada())
                            .put("anulaFinDeSemana", g.horario().anulaFinDeSemana(e.finDeSemana()))
                            .put("trabajaFinesDeSemana", g.horario().trabajaFinesDeSemana(e.finDeSemana()));
                }
            }
        }
        return salida;
    }

    // ---- vista previa -----------------------------------------------------------------------

    /**
     * Horas del ensayo con un horario (el recibido sin guardar o, si el cuerpo no
     * trae «horario», el guardado): inicio facturable, horas por periodo de
     * facturación, horas de setup y de parada, paradas resueltas y la rejilla de
     * medias horas de cada día.
     */
    public ObjectNode vistaPrevia(String id, JsonNode cuerpo) {
        exigirConsulta();
        Vista v = planificacion.vista();
        Tarea t = buscar(v, id);
        if (t.inicio() == null || t.fin() == null) {
            throw new ReglaNoCumplida("El ensayo aún no tiene fechas: no se pueden calcular sus horas.");
        }
        boolean porPiezas = bancosPorPiezas().contains(t.banco());
        Double antiguo = setupAntiguo(t.ensayo(), porPiezas);
        HorarioEnsayo h;
        JsonNode recibido = cuerpo == null ? null : cuerpo.get("horario");
        if (recibido != null && recibido.isObject()) {
            h = HorarioEnsayo.desdeJson(recibido).validar(t.inicio(), t.fin());
        } else {
            AlmacenHorarios.Guardado g = horarios.leer(id);
            h = g != null ? g.horario() : HorarioEnsayo.POR_DEFECTO;
        }
        HorarioEnsayo efectivo = h.conSetup(h.setupEfectivo(antiguo));
        boolean finDeSemana = t.ensayo().finDeSemana();
        HorasTrabajo ht = new HorasTrabajo(efectivo, t.inicio(), t.fin(), finDeSemana, v.contexto().calendario()::esLaborable);

        ObjectNode salida = JsonJs.objetoVacio();
        salida.put("id", id);
        salida.put("inicio", HorarioEnsayo.fechaHora(ht.inicio()));
        salida.put("fin", HorarioEnsayo.fechaHora(ht.fin()));
        salida.put("inicioFacturable", HorarioEnsayo.fechaHora(ht.inicioFacturable()));
        salida.put("horasTurno", ht.horasDeTurno());
        salida.put("horasFacturables", ht.horasFacturables());
        salida.put("horasSetup", ht.horasSetup());
        salida.put("horasParadas", ht.horasParadas());
        salida.put("trabajaFinesDeSemana", efectivo.trabajaFinesDeSemana(finDeSemana));
        salida.put("anulaFinDeSemana", efectivo.anulaFinDeSemana(finDeSemana));
        salida.put("porPiezas", porPiezas);

        // Parada (d) antigua: solo cuenta si no hay paradas aquí (D60).
        double paradaDias = ValorJs.numeroOCero(t.ensayo().campo("downtimeDays"));
        paradaDias = Double.isNaN(paradaDias) || paradaDias < 0 ? 0 : paradaDias;
        double total = ht.horasFacturables();
        double descuentoTotal = !ht.tieneParadas() && total > 0 ? paradaDias * efectivo.jornada() : 0;
        salida.put("paradaDias", paradaDias);
        salida.put("paradaDiasIgnorada", ht.tieneParadas() && paradaDias > 0);
        ArrayNode avisos = salida.putArray("avisos");
        if (ht.tieneParadas() && paradaDias > 0) {
            avisos.add("Parada (d) ignorada: hay paradas en Working Hours");
        }
        if (porPiezas) {
            avisos.add("El banco factura por piezas: las horas no se usan en la facturación.");
        }
        if (h.setupHoras() != null && antiguo != null && !h.setupHoras().equals(antiguo)) {
            avisos.add("La columna antigua Setup del ensayo (" + ValorJs.numeroComoTexto(antiguo) + " h) ya no cuenta: manda el setup de aquí.");
        }

        ArrayNode periodos = salida.putArray("periodos");
        for (Map.Entry<YearMonth, Double> p : ht.porPeriodo().entrySet()) {
            // horas: tiempo de trabajo según el horario (sin descontar nada); horasNetas: lo que se
            // factura (sin setup ni paradas y, si no hay paradas aquí, menos la Parada (d) antigua).
            double facturables = p.getValue();
            double horas = ht.horasDeTurno(HorasTrabajo.inicioPeriodo(p.getKey()), HorasTrabajo.finPeriodo(p.getKey()));
            double netas = Math.max(0, facturables - (total > 0 ? descuentoTotal * facturables / total : 0));
            periodos.addObject().put("mes", p.getKey().toString())
                    .put("desde", Fechas.iso(HojaMensual.inicioPeriodo(p.getKey())))
                    .put("hasta", Fechas.iso(HojaMensual.finPeriodo(p.getKey())))
                    .put("horas", horas)
                    .put("horasFacturables", facturables)
                    .put("horasNetas", Math.round(netas * 100) / 100.0);
        }
        ArrayNode paradas = salida.putArray("paradas");
        for (HorasTrabajo.Tramo tramo : ht.paradas()) {
            paradas.addObject().put("inicio", HorarioEnsayo.fechaHora(tramo.inicio())).put("fin", HorarioEnsayo.fechaHora(tramo.fin()))
                    .put("horas", tramo.horas());
        }
        ArrayNode dias = salida.putArray("dias");
        long numero = ChronoUnit.DAYS.between(t.inicio(), t.fin()) + 1;
        salida.put("rejillaRecortada", numero > DIAS_REJILLA);
        for (int d = 0; d < Math.min(numero, DIAS_REJILLA); d++) {
            LocalDate dia = t.inicio().plusDays(d);
            dias.addObject().put("fecha", Fechas.iso(dia)).put("franjas", ht.franjasDelDia(dia))
                    .put("laborable", v.contexto().calendario().esLaborable(dia));
        }
        return salida;
    }

    // ---- cambios ------------------------------------------------------------------------------

    /** La pestaña Working Hours solo la ven un Admin o un Lab User (D62); los avisos de la tabla del banco, todos. */
    private void exigirConsulta() {
        sesion.exigirAdminOLabUser(sesion.usuarioActual());
    }

    private Usuario exigirGestion() {
        Usuario u = sesion.usuarioActual();
        sesion.exigirEscritura(u);
        sesion.exigirAdminOLabUser(u);
        if (conexion.sinConexion()) {
            throw new SinPermiso("Modo sin conexión: el horario de los ensayos solo se puede consultar.");
        }
        if (!horarios.editable()) {
            throw new ReglaNoCumplida(AlmacenHorarios.NO_EDITABLE);
        }
        return u;
    }

    /** Guarda el horario: {horario: {...}, anterior: {version}} (Admin o Lab User, D59). */
    public ObjectNode guardar(String id, JsonNode cuerpo) {
        exigirGestion();
        Vista v = planificacion.vista();
        Tarea t = buscar(v, id);
        JsonNode datos = cuerpo == null ? null : cuerpo.get("horario");
        if (datos == null || !datos.isObject()) {
            throw new ReglaNoCumplida("Falta el horario.");
        }
        HorarioEnsayo h = HorarioEnsayo.desdeJson(datos).validar(t.inicio(), t.fin());
        AlmacenHorarios.Guardado g = horarios.guardar(id, h, cuerpo.get("anterior"));
        return json(t, g, bancosPorPiezas().contains(t.banco()));
    }

    /** Vuelve a los valores por defecto: {anterior: {version}} (Admin o Lab User). */
    public ObjectNode restablecer(String id, JsonNode cuerpo) {
        exigirGestion();
        Vista v = planificacion.vista();
        Tarea t = buscar(v, id);
        horarios.restablecer(id, cuerpo == null ? null : cuerpo.get("anterior"));
        return json(t, null, bancosPorPiezas().contains(t.banco()));
    }
}
