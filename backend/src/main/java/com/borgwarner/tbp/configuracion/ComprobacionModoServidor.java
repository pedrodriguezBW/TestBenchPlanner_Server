package com.borgwarner.tbp.configuracion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Comprobaciones del modo servidor (hito S2) ANTES de crear nada: si la
 * configuración no es segura para una aplicación compartida, no arranca y
 * dice por qué. Se ejecuta con la configuración ya leída (application.yml,
 * perfiles, config/…, argumentos), antes de abrir Edge, la base o el puerto.
 *
 * Registrada en META-INF/spring.factories.
 */
public class ComprobacionModoServidor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment entorno, SpringApplication aplicacion) {
        String motivo = motivoParaNoArrancar(
                Boolean.parseBoolean(entorno.getProperty("planner.servidor.activo", "false")),
                entorno.getProperty("planner.almacen", "local"),
                Boolean.parseBoolean(entorno.getProperty("planner.postgresql.reinicio-pruebas", "false")));
        if (motivo != null) {
            throw new IllegalStateException("Modo servidor: " + motivo);
        }
    }

    /** null si se puede arrancar; si no, el motivo (para el log y las pruebas). */
    static String motivoParaNoArrancar(boolean servidor, String almacen, boolean reinicioPruebas) {
        if (!servidor) {
            return null;
        }
        if (!"postgresql".equalsIgnoreCase(almacen == null ? "" : almacen.trim())) {
            return "solo funciona con planner.almacen=postgresql (está en «" + almacen + "»). "
                    + "El servidor no usa SharePoint ni ficheros locales.";
        }
        if (reinicioPruebas) {
            return "planner.postgresql.reinicio-pruebas=true vaciaría la base desde la red. Quítalo de la configuración.";
        }
        return null;
    }
}
