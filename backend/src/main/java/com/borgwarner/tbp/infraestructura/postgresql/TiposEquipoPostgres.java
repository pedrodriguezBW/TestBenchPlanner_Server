package com.borgwarner.tbp.infraestructura.postgresql;

import com.borgwarner.tbp.aplicacion.puertos.AlmacenTiposEquipo;
import com.borgwarner.tbp.dominio.facturacion.Regla;
import com.borgwarner.tbp.dominio.facturacion.TipoEquipo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Equipment Types de facturación (tabla «tipo_equipo»: una fila por tipo y año). */
public class TiposEquipoPostgres implements AlmacenTiposEquipo {

    static final List<String> COLUMNAS = List.of("titulo", "grupo", "anio", "sin_hv", "con_hv", "unidad", "regla");

    private final BaseDatos bd;

    public TiposEquipoPostgres(BaseDatos bd) {
        this.bd = bd;
    }

    @Override
    public List<TipoEquipo> leer() {
        return bd.jdbc().query("SELECT titulo, grupo, anio, sin_hv, con_hv, unidad, regla FROM tipo_equipo ORDER BY id",
                (rs, i) -> new TipoEquipo(rs.getString("titulo"), rs.getString("grupo"), rs.getInt("anio"),
                        ValoresJson.columnaNumero(rs, "sin_hv"), ValoresJson.columnaNumero(rs, "con_hv"),
                        rs.getString("unidad"), Regla.de(rs.getString("regla") == null ? "" : rs.getString("regla"))));
    }

    static List<Map<String, Object>> filas(List<TipoEquipo> tipos) {
        List<Map<String, Object>> filas = new ArrayList<>();
        for (TipoEquipo t : tipos) {
            if (t.titulo().isBlank()) {
                continue;
            }
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("clave", t.clave());
            f.put("titulo", t.titulo());
            f.put("grupo", t.grupo());
            f.put("anio", t.anio());
            f.put("sin_hv", t.sinHv());
            f.put("con_hv", t.conHv());
            f.put("unidad", t.unidad());
            f.put("regla", t.regla().name());
            filas.add(f);
        }
        return filas;
    }

    @Override
    public void guardar(List<TipoEquipo> tipos) {
        List<Map<String, Object>> filas = filas(tipos);
        bd.enTransaccion(() -> {
            bd.bloquear("tipos-equipo");
            return Reconciliador.reconciliar(bd, "tipo_equipo", "clave", COLUMNAS, filas, true, "tipo_equipo", bd.actor(), null);
        });
    }
}
