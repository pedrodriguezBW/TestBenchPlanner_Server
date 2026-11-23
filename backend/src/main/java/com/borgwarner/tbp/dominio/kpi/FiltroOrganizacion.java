package com.borgwarner.tbp.dominio.kpi;

import com.borgwarner.tbp.dominio.planificacion.Areas;
import com.borgwarner.tbp.dominio.planificacion.ConfiguracionBancos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

/**
 * Filtro de bancos del Dashboard, «By Responsible» y KPI. Un solo código
 * reúne los dos desplegables (área y, en las áreas que se dividen banco a
 * banco, organización):
 *
 * <ul>
 *   <li>ALL: todos los bancos.</li>
 *   <li>THERMAL: Bancos Térmicos (las dos organizaciones).</li>
 *   <li>BW / CTAG: Bancos Térmicos de esa organización. Son los códigos de la
 *       4.9.4: mientras todos los bancos eran térmicos significaban lo mismo,
 *       así que el histórico guardado con ellos sigue valiendo.</li>
 *   <li>HVCH-CTAG / HVCH-STC (y cualquier otro código de área): los bancos de esa área.</li>
 *   <li>«ÁREA:BW» / «ÁREA:CTAG»: los bancos de esa organización en un área
 *       creada desde la aplicación que se divide banco a banco (D56).</li>
 * </ul>
 */
public final class FiltroOrganizacion {

    /** Los de la 4.9.4 (los que conoce la interfaz clásica). */
    public static final List<String> ORGANIZACIONES = List.of("ALL", "BW", "CTAG");

    /** Todos los filtros con las áreas de serie, en el orden de las fotos semanales. */
    public static final List<String> FILTROS = List.of("ALL", "BW", "CTAG", Areas.TERMICOS, Areas.HVCH_CTAG, Areas.HVCH_STC);

    /** Organizaciones que se eligen banco a banco. */
    public static final List<String> ORGANIZACIONES_BANCO = List.of("BW", "CTAG");

    private FiltroOrganizacion() {
    }

    /**
     * Todos los filtros de una configuración, en el orden de las fotos semanales:
     * ALL, BW, CTAG y, por cada área, su código (y, si es un área nueva que se
     * divide banco a banco, «ÁREA:BW» y «ÁREA:CTAG»). Con las áreas de serie es {@link #FILTROS}.
     */
    public static List<String> filtros(ConfiguracionBancos configuracion) {
        List<String> filtros = new ArrayList<>(ORGANIZACIONES);
        for (Areas.Definicion d : configuracion.areas()) {
            filtros.add(d.codigo());
            if (d.conOrganizacion() && !Areas.TERMICOS.equals(d.codigo())) {
                ORGANIZACIONES_BANCO.forEach(o -> filtros.add(d.codigo() + ":" + o));
            }
        }
        return filtros;
    }

    /** Código de filtro válido (uno desconocido se trata como ALL). */
    public static String normalizar(String filtro) {
        return normalizar(filtro, FILTROS);
    }

    /** Ídem entre los filtros indicados. */
    public static String normalizar(String filtro, Collection<String> validos) {
        if (filtro == null) {
            return "ALL";
        }
        String f = filtro.trim().toUpperCase(java.util.Locale.ROOT);
        return validos.contains(f) ? f : "ALL";
    }

    public static Predicate<String> de(String organizacion, ConfiguracionBancos configuracion) {
        String filtro = normalizar(organizacion, filtros(configuracion));
        int dosPuntos = filtro.indexOf(':');
        if (dosPuntos > 0) {
            String area = filtro.substring(0, dosPuntos);
            String org = filtro.substring(dosPuntos + 1);
            return banco -> area.equals(configuracion.area(banco)) && org.equals(configuracion.organizacion(banco));
        }
        return switch (filtro) {
            case "ALL" -> banco -> true;
            case "BW", "CTAG" -> banco -> Areas.TERMICOS.equals(configuracion.area(banco))
                    && filtro.equals(configuracion.organizacion(banco));
            default -> banco -> filtro.equals(configuracion.area(banco));
        };
    }
}
