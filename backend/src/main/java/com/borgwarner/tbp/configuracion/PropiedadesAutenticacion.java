package com.borgwarner.tbp.configuracion;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Inicio de sesión en el modo servidor (hito S3): planner.autenticacion.…
 * Solo se usa con planner.servidor.activo=true; fuera del servidor cada PC
 * sigue identificando a su usuario como hasta ahora.
 *
 * @param intentosMaximos     intentos fallidos seguidos antes del bloqueo temporal
 * @param bloqueo             duración del bloqueo temporal
 * @param inactividad         sin tocar la aplicación este tiempo, la sesión se cierra
 * @param longitudMinimaClave longitud mínima de una contraseña nueva
 */
@ConfigurationProperties(prefix = "planner.autenticacion")
public record PropiedadesAutenticacion(
        Integer intentosMaximos,
        Duration bloqueo,
        Duration inactividad,
        Integer longitudMinimaClave) {

    public PropiedadesAutenticacion {
        intentosMaximos = intentosMaximos == null || intentosMaximos < 1 ? 5 : intentosMaximos;
        bloqueo = bloqueo == null || bloqueo.isNegative() || bloqueo.isZero() ? Duration.ofMinutes(15) : bloqueo;
        inactividad = inactividad == null || inactividad.isNegative() || inactividad.isZero() ? Duration.ofMinutes(30) : inactividad;
        longitudMinimaClave = longitudMinimaClave == null || longitudMinimaClave < 8 ? 10 : longitudMinimaClave;
    }

    /** Valores de serie (5 intentos, 15 min de bloqueo, 30 min de inactividad, 10 caracteres). */
    public static PropiedadesAutenticacion deSerie() {
        return new PropiedadesAutenticacion(null, null, null, null);
    }
}
