package com.borgwarner.tbp.postgresql;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PostgreSQL para las pruebas.
 *
 * Por defecto arranca UNA vez por ejecución un PostgreSQL temporal (binarios
 * de io.zonky.test, sin instalar nada ni Docker). Con
 * -Dtbp.pruebas.postgresql.url=jdbc:postgresql://host:puerto/postgres (y
 * -Dtbp.pruebas.postgresql.usuario / .clave de un superusuario) usa ese
 * servidor; mejor uno de desarrollo, nunca el del servidor de producción.
 *
 * Cada base de prueba se prepara EXACTAMENTE como en la instalación real: los
 * tres usuarios (tbp_owner, tbp_app, tbp_consulta), una base propiedad de
 * tbp_owner y el script db/instalacion/02-esquema-y-permisos.sql. La estructura
 * la crea Flyway al arrancar la aplicación, como en el servidor.
 */
public final class PostgresPruebas {

    /** Clave de los tres usuarios de base de datos en las pruebas. */
    public static final String CLAVE = "tbp_prueba";

    private static final AtomicInteger CONTADOR = new AtomicInteger();
    private static EmbeddedPostgres temporal;
    private static String urlServidor;      // jdbc:postgresql://host:puerto/
    private static String superusuario;
    private static String claveSuperusuario;

    private PostgresPruebas() {
    }

    /** Base nueva y vacía (solo el esquema y los permisos). */
    public record Base(String nombre, String url) {
    }

    private static synchronized void arrancar() {
        if (urlServidor != null) {
            return;
        }
        String url = System.getProperty("tbp.pruebas.postgresql.url", "").trim();
        try {
            if (url.isEmpty()) {
                temporal = EmbeddedPostgres.builder().start();
                urlServidor = "jdbc:postgresql://localhost:" + temporal.getPort() + "/";
                superusuario = "postgres";
                claveSuperusuario = "";
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        temporal.close();
                    } catch (IOException e) {
                        // al salir: no importa
                    }
                }));
            } else {
                urlServidor = url.substring(0, url.lastIndexOf('/') + 1);
                superusuario = System.getProperty("tbp.pruebas.postgresql.usuario", "postgres");
                claveSuperusuario = System.getProperty("tbp.pruebas.postgresql.clave", "");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("No se ha podido arrancar el PostgreSQL temporal de las pruebas", e);
        }
        ejecutar("postgres", superusuario, claveSuperusuario, List.of(
                crearRol("tbp_owner"), crearRol("tbp_app"), crearRol("tbp_consulta")));
    }

    private static String crearRol(String rol) {
        return "DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '" + rol + "') THEN "
                + "CREATE ROLE " + rol + " LOGIN PASSWORD '" + CLAVE + "'; END IF; END $$";
    }

    /** Crea una base nueva propiedad de tbp_owner con el esquema tbp y sus permisos. */
    public static synchronized Base nuevaBase() {
        arrancar();
        String nombre = "tbp_prueba_" + ProcessHandle.current().pid() + "_" + CONTADOR.incrementAndGet();
        ejecutar("postgres", superusuario, claveSuperusuario, List.of(
                "CREATE DATABASE " + nombre + " OWNER tbp_owner ENCODING 'UTF8' TEMPLATE template0",
                "REVOKE ALL ON DATABASE " + nombre + " FROM PUBLIC",
                "GRANT CONNECT ON DATABASE " + nombre + " TO tbp_app, tbp_consulta"));
        ejecutar(nombre, superusuario, claveSuperusuario, sentencias(leerRecurso("db/instalacion/02-esquema-y-permisos.sql")));
        return new Base(nombre, urlServidor + nombre);
    }

    /** Propiedades para arrancar la aplicación con planner.almacen=postgresql sobre una base nueva. */
    public static Base registrar(DynamicPropertyRegistry registro) {
        Base base = nuevaBase();
        registro.add("planner.almacen", () -> "postgresql");
        registro.add("planner.postgresql.url", base::url);
        registro.add("planner.postgresql.clave-app", () -> CLAVE);
        registro.add("planner.postgresql.clave-propietario", () -> CLAVE);
        registro.add("planner.postgresql.reinicio-pruebas", () -> "true");
        return base;
    }

    public static Connection conectar(Base base, String usuario) throws SQLException {
        return DriverManager.getConnection(base.url(), usuario, usuario.equals(superusuario) ? claveSuperusuario : CLAVE);
    }

    /** Una sentencia con el superusuario en una base de prueba. */
    public static void ejecutarComoSuperusuario(Base base, String sql) {
        ejecutar(base.nombre(), superusuario, claveSuperusuario, List.of(sql));
    }

    public static String superusuario() {
        arrancar();
        return superusuario;
    }

    static void ejecutar(String baseDatos, String usuario, String clave, List<String> sentencias) {
        try (Connection c = DriverManager.getConnection(urlServidor + baseDatos, usuario, clave);
             Statement s = c.createStatement()) {
            for (String sql : sentencias) {
                s.execute(sql);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se ha podido preparar la base de pruebas: " + e.getMessage(), e);
        }
    }

    static String leerRecurso(String ruta) {
        try (InputStream in = PostgresPruebas.class.getClassLoader().getResourceAsStream(ruta)) {
            if (in == null) {
                throw new IllegalStateException("No está " + ruta);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Sentencias de un script sencillo (sin funciones): quita comentarios «--» y parte por «;» al final de línea. */
    static List<String> sentencias(String script) {
        List<String> salida = new ArrayList<>();
        StringBuilder actual = new StringBuilder();
        for (String linea : script.split("\\R")) {
            String l = linea.strip();
            if (l.isEmpty() || l.startsWith("--") || l.startsWith("\\")) {
                continue;
            }
            actual.append(linea).append('\n');
            if (l.endsWith(";")) {
                String sql = actual.toString().strip();
                salida.add(sql.substring(0, sql.length() - 1));
                actual.setLength(0);
            }
        }
        if (!actual.toString().isBlank()) {
            salida.add(actual.toString().strip());
        }
        return salida;
    }

    /** Escenario anonimizado de las pruebas. */
    public static Path escenario() {
        return Path.of(System.getProperty("escenarios.dir", "../datos/escenarios"), "sharepoint-2026-11-05");
    }
}
