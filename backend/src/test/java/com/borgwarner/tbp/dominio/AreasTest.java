package com.borgwarner.tbp.dominio;

import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.kpi.FiltroOrganizacion;
import com.borgwarner.tbp.dominio.kpi.HistoricoKpi;
import com.borgwarner.tbp.dominio.planificacion.Areas;
import com.borgwarner.tbp.dominio.planificacion.BancoConfigurado;
import com.borgwarner.tbp.dominio.planificacion.ConfiguracionBancos;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Áreas de bancos: lectura de la columna Area, filtros y lectura del histórico de KPI. */
class AreasTest {

    private static ConfiguracionBancos configuracion() {
        return ConfiguracionBancos.desdeLista(List.of(
                new BancoConfigurado("MD1", "dur", "Durability", "#111111", "BW", 1.0),
                new BancoConfigurado("2000 CTAG", "dur", "Durability", "#111111", "CTAG", 2.0, ""),
                new BancoConfigurado("H1", "hc", "HVCH climatic", "#222222", "CTAG", 3.0, "HVCH-CTAG"),
                new BancoConfigurado("H2", "hc", "HVCH climatic", "#222222", "CTAG", 4.0, null),
                new BancoConfigurado("S1", "hs", "STC", "#333333", "STC", 5.0, "hvch - stc")), Map.of("HVCH-STC", "STC benches"));
    }

    @Test
    void elAreaEsDeLaCategoriaYVacioEsTermicos() {
        ConfiguracionBancos c = configuracion();
        assertEquals(Areas.TERMICOS, c.area("MD1"));
        assertEquals(Areas.TERMICOS, c.area("2000 CTAG"));
        assertEquals(Areas.HVCH_CTAG, c.area("H1"));
        assertEquals(Areas.HVCH_CTAG, c.area("H2"), "la segunda fila de la categoría hereda el área");
        assertEquals(Areas.HVCH_STC, c.area("S1"), "el valor guardado se normaliza");
        assertEquals(Areas.TERMICOS, Areas.normalizar("cualquier cosa"));
        assertEquals("Thermal Benches", c.nombreArea(Areas.TERMICOS));
        assertEquals("STC benches", c.nombreArea(Areas.HVCH_STC));
        assertEquals(List.of(Areas.TERMICOS, Areas.HVCH_CTAG, Areas.HVCH_STC), List.copyOf(c.nombresAreas().keySet()));
        assertEquals("CTAG", Areas.organizacionFija(Areas.HVCH_CTAG));
        assertEquals("STC", Areas.organizacionFija(Areas.HVCH_STC));
        assertEquals(null, Areas.organizacionFija(Areas.TERMICOS));
    }

    @Test
    void filtrosPorAreaYOrganizacion() {
        ConfiguracionBancos c = configuracion();
        List<String> bancos = c.bancos();
        assertEquals(List.of("MD1", "2000 CTAG", "H1", "H2", "S1"), seleccion(bancos, FiltroOrganizacion.de("ALL", c)));
        assertEquals(List.of("MD1", "2000 CTAG"), seleccion(bancos, FiltroOrganizacion.de("THERMAL", c)));
        assertEquals(List.of("MD1"), seleccion(bancos, FiltroOrganizacion.de("BW", c)));
        assertEquals(List.of("2000 CTAG"), seleccion(bancos, FiltroOrganizacion.de("CTAG", c)),
                "CTAG son solo los térmicos de CTAG, no los de HVCH - CTAG");
        assertEquals(List.of("H1", "H2"), seleccion(bancos, FiltroOrganizacion.de("HVCH-CTAG", c)));
        assertEquals(List.of("S1"), seleccion(bancos, FiltroOrganizacion.de("HVCH-STC", c)));
        assertEquals(6, FiltroOrganizacion.FILTROS.size());
        assertEquals(List.of("ALL", "BW", "CTAG"), FiltroOrganizacion.ORGANIZACIONES);
    }

    @Test
    void sinAreasLosFiltrosDeSiempreNoCambian() {
        ConfiguracionBancos c = ConfiguracionBancos.desdeLista(List.of(
                new BancoConfigurado("A", "g", "G", "#000000", "BW", 1.0),
                new BancoConfigurado("B", "g", "G", "#000000", "CTAG", 2.0),
                new BancoConfigurado("C", "g", "G", "#000000", "", 3.0)));
        assertEquals(List.of("A", "C"), seleccion(c.bancos(), FiltroOrganizacion.de("BW", c)));
        assertEquals(List.of("B"), seleccion(c.bancos(), FiltroOrganizacion.de("CTAG", c)));
        assertEquals(List.of("A", "B", "C"), seleccion(c.bancos(), FiltroOrganizacion.de("THERMAL", c)));
        assertTrue(seleccion(c.bancos(), FiltroOrganizacion.de("HVCH-STC", c)).isEmpty());
    }

    @Test
    void areasCreadasConPostgresql() {
        List<Areas.Definicion> areas = Areas.definiciones(List.of(
                new Areas.Definicion("HVCH-CTAG", null, "CTAG"),
                new Areas.Definicion("LAB", null, null),
                new Areas.Definicion("FIX", null, "BW"),
                new Areas.Definicion("LAB", null, "STC")), Map.of("LAB", "Lab Benches"));
        assertEquals(List.of("THERMAL", "HVCH-CTAG", "LAB", "FIX"), Areas.codigos(areas),
                "Bancos Térmicos siempre está; un código repetido vale la primera vez");
        assertEquals("Thermal Benches", areas.get(0).nombre());
        assertEquals("Lab Benches", areas.get(2).nombre());
        assertEquals("FIX", areas.get(3).nombre(), "sin nombre guardado: el código");
        ConfiguracionBancos c = ConfiguracionBancos.desdeLista(List.of(
                new BancoConfigurado("A", "t", "T", "#000000", "BW", 1.0, ""),
                new BancoConfigurado("L1", "l", "L", "#000000", "BW", 2.0, "lab"),
                new BancoConfigurado("L2", "l", "L", "#000000", "CTAG", 3.0, null),
                new BancoConfigurado("F1", "f", "F", "#000000", "BW", 4.0, "FIX"),
                new BancoConfigurado("S1", "s", "S", "#000000", "STC", 5.0, "HVCH-STC")), areas);
        assertEquals("LAB", c.area("L2"));
        assertEquals(Areas.TERMICOS, c.area("S1"), "un área que no existe cuenta como Bancos Térmicos");
        assertEquals(null, c.organizacionFija("LAB"));
        assertEquals("BW", c.organizacionFija("FIX"));
        assertEquals(List.of("ALL", "BW", "CTAG", "THERMAL", "HVCH-CTAG", "LAB", "LAB:BW", "LAB:CTAG", "FIX"),
                FiltroOrganizacion.filtros(c));
        assertEquals(List.of("L1", "L2"), seleccion(c.bancos(), FiltroOrganizacion.de("LAB", c)));
        assertEquals(List.of("L2"), seleccion(c.bancos(), FiltroOrganizacion.de("lab:ctag", c)));
        assertEquals(List.of("A"), seleccion(c.bancos(), FiltroOrganizacion.de("BW", c)),
                "BW sigue siendo Bancos Térmicos de BW");
        assertEquals(List.of("F1"), seleccion(c.bancos(), FiltroOrganizacion.de("FIX", c)));
        assertEquals(5, seleccion(c.bancos(), FiltroOrganizacion.de("FIX:BW", c)).size(), "filtro que no existe = ALL");
        assertEquals(FiltroOrganizacion.FILTROS, FiltroOrganizacion.filtros(configuracion()), "con las áreas de serie, los de siempre");
        assertEquals("LAB-BENCHES-2", Areas.codigoNuevo("Lab Bénches", List.of("LAB-BENCHES")));
        assertEquals("ALL-2", Areas.codigoNuevo("all", List.of()));
    }

    @Test
    void historicoDeUnAreaCreadaConPostgresql() {
        List<JsonNode> filas = List.of(fila(2026, 45, "ALL", 10), fila(2026, 45, "LAB", 4), fila(2026, 45, "LAB:BW", 3));
        assertEquals(4, HistoricoKpi.semanas(filas, "lab").get(0).benches());
        assertEquals(3, HistoricoKpi.semanas(filas, "LAB:BW").get(0).benches());
        assertEquals(10, HistoricoKpi.semanas(filas, "OTRA").get(0).benches(), "desconocido = ALL");
    }

    @Test
    void historicoTermicosUsaAllEnLasSemanasAntiguas() {
        List<JsonNode> filas = List.of(
                fila(2026, 40, "ALL", 10), fila(2026, 40, "BW", 7), fila(2026, 40, "CTAG", 3),
                fila(2026, 41, "ALL", 12), fila(2026, 41, "THERMAL", 9), fila(2026, 41, "HVCH-STC", 3));
        List<HistoricoKpi.Semana> termicos = HistoricoKpi.semanas(filas, "THERMAL");
        assertEquals(2, termicos.size());
        assertEquals(10, termicos.get(0).benches(), "semana sin fila THERMAL: se usa ALL");
        assertEquals("THERMAL", termicos.get(0).org());
        assertEquals(9, termicos.get(1).benches(), "semana con fila THERMAL: la suya");
        assertEquals(2, HistoricoKpi.semanas(filas, "ALL").size());
        assertEquals(1, HistoricoKpi.semanas(filas, "BW").size());
        assertEquals(1, HistoricoKpi.semanas(filas, "HVCH-STC").size());
        assertFalse(HistoricoKpi.semanas(filas, "HVCH-CTAG").iterator().hasNext());
    }

    private static JsonNode fila(int anio, int cw, String org, int bancos) {
        return JsonJs.objetoVacio().put("Year", anio).put("CW", cw).put("Org", org).put("Benches", bancos);
    }

    private static List<String> seleccion(List<String> bancos, Predicate<String> filtro) {
        return bancos.stream().filter(filtro).toList();
    }
}
