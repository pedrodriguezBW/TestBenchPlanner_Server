package com.borgwarner.tbp.dominio.kpi;

import com.borgwarner.tbp.dominio.calendario.Fechas;
import com.borgwarner.tbp.dominio.ensayo.Ensayo;
import com.borgwarner.tbp.dominio.planificacion.Planificacion;
import com.borgwarner.tbp.dominio.planificacion.ResultadoPlan;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Datos de los indicadores para «📤 Export read-only».
 *
 * La foto de solo lectura es la interfaz nueva con los datos incrustados y sin
 * servidor, así que los indicadores que calcula el backend se precalculan aquí,
 * banco a banco y semana a semana. Todos se pueden sumar entre bancos, así que
 * la foto rehace en el navegador cualquier filtro Org y cualquier responsable
 * sin repetir la lógica de cálculo:
 *  - KPI semanales (IndicadoresSemana.bloque): total, bancos con carga,
 *    corriendo, en retraso y sugerencias; uno con todos los ensayos y otro por
 *    responsable (vista «By Responsible»);
 *  - ocupación (PanelDashboard.tabla de un solo banco): trabajando, inicia,
 *    acaba y horas ocupadas. La capacidad es 7 × 24 h por banco y semana.
 */
public final class FotoSoloLectura {

    /** Semanas antes y después de hoy que se incluyen aunque caigan fuera del año del calendario. */
    private static final int MARGEN_SEMANAS = 5;

    /**
     * Una semana de un banco.
     *
     * @param trabajando 1 si algún ensayo activo solapa la semana
     * @param kpi        [bancos con carga, corriendo, en retraso, sugerencias] con todos los ensayos
     * @param porResponsable lo mismo con los ensayos de cada responsable (solo los que no son todo ceros)
     */
    public record Semana(int trabajando, int inicia, int acaba, long busy, int[] kpi, Map<String, int[]> porResponsable) {
    }

    private FotoSoloLectura() {
    }

    /**
     * Lunes que cubre la foto: las semanas del año del calendario, una más (el
     * bloque «Semana que viene» de la última) y unas semanas alrededor de hoy.
     */
    public static List<LocalDate> semanas(int anio, LocalDate hoy) {
        LocalDate desde = PanelDashboard.lunesSemanaUno(anio);
        LocalDate hasta = desde.plusWeeks(PanelDashboard.semanasDelAnio(anio));
        LocalDate lunesHoy = Fechas.lunesDe(hoy);
        if (lunesHoy.minusWeeks(MARGEN_SEMANAS).isBefore(desde)) {
            desde = lunesHoy.minusWeeks(MARGEN_SEMANAS);
        }
        if (lunesHoy.plusWeeks(MARGEN_SEMANAS).isAfter(hasta)) {
            hasta = lunesHoy.plusWeeks(MARGEN_SEMANAS);
        }
        List<LocalDate> lunes = new ArrayList<>();
        for (LocalDate l = desde; !l.isAfter(hasta); l = l.plusWeeks(1)) {
            lunes.add(l);
        }
        return lunes;
    }

    /** Semana a semana (en el orden de {@code lunes}) de cada banco que pasa el filtro. */
    public static Map<String, List<Semana>> porBanco(Planificacion planificacion, ResultadoPlan plan, List<LocalDate> lunes,
                                                     Predicate<String> filtroBancos) {
        Map<String, List<Semana>> salida = new LinkedHashMap<>();
        for (String banco : planificacion.bancos()) {
            if (!filtroBancos.test(banco)) {
                continue;
            }
            Predicate<String> soloEste = banco::equals;
            Set<String> responsables = new TreeSet<>();
            for (Ensayo e : planificacion.activos(banco)) {
                responsables.add(e.texto("resp"));
            }
            List<Semana> semanas = new ArrayList<>();
            for (LocalDate l : lunes) {
                PanelDashboard.TablaOcupacion tabla = PanelDashboard.tabla(planificacion, plan, l, l.plusDays(6), soloEste);
                PanelDashboard.FilaOcupacion fila = tabla.grupos().isEmpty() ? null : tabla.grupos().get(0);
                int[] todos = valores(IndicadoresSemana.bloque(planificacion, plan, l, soloEste));
                Map<String, int[]> porResponsable = new LinkedHashMap<>();
                for (String responsable : responsables) {
                    int[] suyos = valores(IndicadoresSemana.bloque(planificacion, plan, l, soloEste,
                            e -> e.texto("resp").equals(responsable)));
                    if (suyos[0] + suyos[1] + suyos[2] + suyos[3] > 0) {
                        porResponsable.put(responsable, suyos);
                    }
                }
                semanas.add(fila == null
                        ? new Semana(0, 0, 0, 0, todos, porResponsable)
                        : new Semana(fila.trabajando(), fila.inicia(), fila.acaba(), fila.busy(), todos, porResponsable));
            }
            salida.put(banco, semanas);
        }
        return salida;
    }

    private static int[] valores(IndicadoresSemana.Bloque b) {
        return new int[]{b.benches(), b.accr(), b.delayed(), b.sug()};
    }
}
