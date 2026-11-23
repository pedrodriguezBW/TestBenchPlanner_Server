package com.borgwarner.tbp.configuracion;

import com.borgwarner.tbp.infraestructura.postgresql.CuentasPostgres;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.io.Console;
import java.util.Arrays;

/**
 * Primer Admin (o rescate si ningún Admin puede entrar), en el PC servidor:
 *
 *   scripts\servidor\crear-admin.cmd nombre.apellido@borgwarner.com
 *   = --planner.almacen=postgresql --planner.cuentas.crear-admin=EMAIL
 *
 * Pide la contraseña dos veces en la consola (no se ve al escribirla ni queda
 * en el historial ni en el log) y deja a esa persona como Admin con esa
 * contraseña. Así no hay ninguna contraseña de serie en el código: el control
 * es tener acceso al servidor y a las claves de la base de datos.
 */
@Component
public class TareaCuentaAdmin implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(TareaCuentaAdmin.class);

    private final ApplicationContext contexto;
    private final ObjectProvider<CuentasPostgres> cuentas;
    private final String email;
    private final String nombre;

    public TareaCuentaAdmin(ApplicationContext contexto, ObjectProvider<CuentasPostgres> cuentas,
                            @Value("${planner.cuentas.crear-admin:}") String email,
                            @Value("${planner.cuentas.nombre:}") String nombre) {
        this.contexto = contexto;
        this.cuentas = cuentas;
        this.email = email == null ? "" : email.trim();
        this.nombre = nombre == null ? "" : nombre.trim();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (email.isEmpty()) {
            return;
        }
        int codigo = crear() ? 0 : 1;
        System.exit(SpringApplication.exit(contexto, () -> codigo));
    }

    private boolean crear() {
        CuentasPostgres c = cuentas.getIfAvailable();
        if (c == null) {
            LOG.error("Las cuentas solo existen con --planner.almacen=postgresql");
            return false;
        }
        Console consola = System.console();
        if (consola == null) {
            LOG.error("No hay consola para pedir la contraseña: ejecuta crear-admin.cmd desde una ventana de PowerShell o cmd.");
            return false;
        }
        for (int intento = 1; intento <= 3; intento++) {
            char[] primera = consola.readPassword("%nContraseña para %s (mínimo %d caracteres, no se ve al escribir): ",
                    email, c.propiedades().longitudMinimaClave());
            char[] segunda = consola.readPassword("Repítela: ");
            try {
                if (primera == null || segunda == null) {
                    return false;
                }
                if (!Arrays.equals(primera, segunda)) {
                    consola.printf("Las dos contraseñas no coinciden.%n");
                    continue;
                }
                String clave = new String(primera);
                String motivo = c.motivoRechazo(email, clave);
                if (motivo != null) {
                    consola.printf("%s%n", motivo);
                    continue;
                }
                String hecho = c.establecerAdmin(email, nombre, clave);
                LOG.warn("CUENTA ADMIN LISTA · {}", hecho);
                consola.printf("%nListo: %s.%nYa puede entrar en TestBenchPlanner con ese email y esa contraseña.%n", hecho);
                return true;
            } catch (CuentasPostgres.ClaveNoValida e) {
                consola.printf("%s%n", e.getMessage());
            } catch (RuntimeException e) {
                LOG.error("No se ha podido crear la cuenta: {}", e.getMessage(), e);
                return false;
            } finally {
                if (primera != null) {
                    Arrays.fill(primera, ' ');
                }
                if (segunda != null) {
                    Arrays.fill(segunda, ' ');
                }
            }
        }
        LOG.error("No se ha creado la cuenta (tres intentos sin una contraseña válida).");
        return false;
    }
}
