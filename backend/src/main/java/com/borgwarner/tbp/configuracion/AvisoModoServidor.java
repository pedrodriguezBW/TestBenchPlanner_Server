package com.borgwarner.tbp.configuracion;

import com.borgwarner.tbp.infraestructura.postgresql.CuentasPostgres;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Modo servidor: al arrancar, dice en el log desde qué direcciones se entra
 * (nombre del PC e IPs de la red, con https si el perfil https está activo,
 * hito S4), avisa si NO hay HTTPS (las contraseñas viajarían sin cifrar) y si
 * todavía ningún Admin tiene contraseña (hito S3: sin él nadie puede dar cuentas).
 */
@Component
public class AvisoModoServidor implements ApplicationListener<WebServerInitializedEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(AvisoModoServidor.class);

    private final PropiedadesPlanner propiedades;
    private final ObjectProvider<CuentasPostgres> cuentas;
    private final Environment entorno;

    public AvisoModoServidor(PropiedadesPlanner propiedades, ObjectProvider<CuentasPostgres> cuentas, Environment entorno) {
        this.propiedades = propiedades;
        this.cuentas = cuentas;
        this.entorno = entorno;
    }

    @Override
    public void onApplicationEvent(WebServerInitializedEvent evento) {
        if (!propiedades.servidor().activo()) {
            return;
        }
        int puerto = evento.getWebServer().getPort();
        boolean https = entorno.getProperty("server.ssl.enabled", Boolean.class, false);
        String esquema = https ? "https://" : "http://";
        List<String> direcciones = new ArrayList<>();
        try {
            direcciones.add(esquema + InetAddress.getLocalHost().getHostName() + ":" + puerto);
        } catch (Exception e) {
            // sin nombre: solo las IP
        }
        try {
            for (NetworkInterface red : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!red.isUp() || red.isLoopback() || red.isVirtual()) {
                    continue;
                }
                for (InetAddress ip : Collections.list(red.getInetAddresses())) {
                    if (ip instanceof Inet4Address) {
                        direcciones.add(esquema + ip.getHostAddress() + ":" + puerto);
                    }
                }
            }
        } catch (Exception e) {
            // sin interfaces de red legibles
        }
        LOG.warn("MODO SERVIDOR · se entra desde otros PCs con: {}", direcciones.isEmpty() ? "(no se han podido leer)" : String.join("  ", direcciones));
        if (!https) {
            LOG.warn("MODO SERVIDOR · SIN HTTPS: las contraseñas viajan sin cifrar por la red. "
                    + "Solo para pruebas; el servidor definitivo se instala como servicio con HTTPS (docs/13-despliegue-servidor.md).");
        }
        CuentasPostgres c = cuentas.getIfAvailable();
        try {
            if (c != null && !c.hayAdminConClave()) {
                LOG.warn("MODO SERVIDOR · ningún Admin tiene contraseña todavía: nadie podrá dar cuentas. "
                        + "Crea la primera con scripts\\servidor\\crear-admin.cmd TU_EMAIL (docs/12-inicio-de-sesion.md).");
            } else {
                LOG.info("MODO SERVIDOR · inicio de sesión activo (cuentas de Users/Rights Management).");
            }
        } catch (RuntimeException e) {
            LOG.warn("MODO SERVIDOR · no se ha podido comprobar si hay algún Admin con contraseña: {}", e.getMessage());
        }
    }
}
