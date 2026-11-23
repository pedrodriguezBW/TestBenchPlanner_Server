package com.borgwarner.tbp.aplicacion.puertos;

import com.borgwarner.tbp.dominio.facturacion.HorarioEnsayo;
import com.borgwarner.tbp.dominio.operaciones.ReglaNoCumplida;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.YearMonth;
import java.util.Map;

/**
 * Horario de trabajo de los ensayos (Configuration › Test Calendar / Working
 * Hours, D59) y primer mes con facturación por horas (D60).
 *
 * Solo existe con PostgreSQL (tabla «horario_ensayo», migración V4, y la clave
 * «FacturacionHorasDesde» de «configuracion_app»). Con SharePoint y en local no
 * hay horarios: las lecturas salen vacías, no hay facturación por horas y los
 * cambios se rechazan (422).
 */
public interface AlmacenHorarios {

    String NO_EDITABLE = "El horario de los ensayos (Working Hours) solo se puede usar con la base de datos del servidor (PostgreSQL).";
    /** Clave de «configuracion_app» con el primer mes de facturación por horas («aaaa-mm»). */
    String CLAVE_FACTURACION_HORAS = "FacturacionHorasDesde";

    /**
     * Un horario guardado. version: concurrencia optimista (0 = sin registro);
     * modificadoPor / modificadoEn: email y fecha ISO del último cambio.
     */
    record Guardado(String ensayoId, HorarioEnsayo horario, long version, String modificadoPor, String modificadoEn) {
    }

    /** true con PostgreSQL: los horarios se guardan y se usan en la facturación. */
    default boolean editable() {
        return false;
    }

    /** Todos los horarios guardados, por id de ensayo. */
    default Map<String, Guardado> todos() {
        return Map.of();
    }

    /** El horario guardado del ensayo o null si no tiene. */
    default Guardado leer(String ensayoId) {
        return null;
    }

    /**
     * Guarda el horario del ensayo. anterior: {version} que se tenía en pantalla
     * (0 o ausente = no tenía registro); si no coincide con lo guardado, otra
     * persona lo ha cambiado: se rechaza (422). Sin «anterior» no se comprueba.
     */
    default Guardado guardar(String ensayoId, HorarioEnsayo horario, JsonNode anterior) {
        throw new ReglaNoCumplida(NO_EDITABLE);
    }

    /** Vuelve a los valores por defecto (borra el registro), con la misma comprobación de «anterior». */
    default void restablecer(String ensayoId, JsonNode anterior) {
        throw new ReglaNoCumplida(NO_EDITABLE);
    }

    /** Primer mes de facturación por horas; null = ninguno (siempre el cálculo por días). */
    default YearMonth facturacionHorasDesde() {
        return null;
    }

    /** Cambia el primer mes de facturación por horas (solo Admin; lo comprueba el servicio). */
    default YearMonth cambiarFacturacionHorasDesde(YearMonth mes) {
        throw new ReglaNoCumplida(NO_EDITABLE);
    }

    /**
     * Sustituye todos los horarios por los de una copia de seguridad
     * ({id: horario}); devuelve cuántos quedan guardados.
     */
    default int restaurar(Map<String, HorarioEnsayo> horarios) {
        throw new ReglaNoCumplida(NO_EDITABLE);
    }

    /** Almacén sin horarios (SharePoint y local). */
    final class SinHorarios implements AlmacenHorarios {
    }
}
