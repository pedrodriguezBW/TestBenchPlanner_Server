package com.borgwarner.tbp.infraestructura.migracion;

import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Carpeta de una exportación: los mismos ficheros que un escenario del modo
 * local (planner.json, configuracion.json…) más actividad.json y el manifiesto
 * exportacion.json (fecha, origen, recuentos y huella SHA-256 de cada fichero).
 *
 * Al ser el formato de un escenario, la carpeta también sirve para arrancar el
 * modo local con los datos exportados (y así compararlos con la base de datos).
 */
public final class CarpetaExportacion {

    public static final String MANIFIESTO = "exportacion.json";
    public static final String ACTIVIDAD = "actividad.json";
    /** Ficheros de datos en el orden en que se escriben. */
    public static final List<String> FICHEROS = List.of("planner.json", "configuracion.json", "usuarios.json", "recursos.json",
            "kpis.json", "aprobaciones.json", "tipos-equipo.json", "version.json", ACTIVIDAD);
    /** Sin estos no se importa nada. */
    public static final List<String> OBLIGATORIOS = List.of("planner.json", "configuracion.json", "usuarios.json");

    private static final ObjectWriter ESCRITOR = JsonJs.mapper().writer().with(SerializationFeature.INDENT_OUTPUT);

    private final Path carpeta;

    public CarpetaExportacion(Path carpeta) {
        this.carpeta = carpeta;
    }

    public Path carpeta() {
        return carpeta;
    }

    public boolean existe(String fichero) {
        return Files.isRegularFile(carpeta.resolve(fichero));
    }

    public JsonNode leer(String fichero, JsonNode siNoExiste) {
        Path ruta = carpeta.resolve(fichero);
        if (!Files.isRegularFile(ruta)) {
            return siNoExiste;
        }
        try {
            return JsonJs.mapper().readTree(ruta.toFile());
        } catch (IOException e) {
            throw new IllegalArgumentException("No se puede leer " + ruta + ": " + e.getMessage(), e);
        }
    }

    public void escribir(String fichero, JsonNode contenido) {
        try {
            Files.createDirectories(carpeta);
            ESCRITOR.writeValue(carpeta.resolve(fichero).toFile(), contenido);
        } catch (IOException e) {
            throw new UncheckedIOException("No se puede escribir " + carpeta.resolve(fichero), e);
        }
    }

    /** SHA-256 en hexadecimal del contenido de un fichero. */
    public String huella(String fichero) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(Files.readAllBytes(carpeta.resolve(fichero))));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Comprueba que la carpeta se puede importar: existen los ficheros
     * obligatorios y, si hay manifiesto, cada fichero conserva su huella (no se
     * ha editado ni se ha copiado a medias).
     *
     * @return el manifiesto, o null si la carpeta no tiene (p. ej. un escenario de pruebas)
     */
    public ObjectNode comprobar() {
        if (!Files.isDirectory(carpeta)) {
            throw new IllegalArgumentException("No existe la carpeta de la exportación: " + carpeta);
        }
        for (String f : OBLIGATORIOS) {
            if (!existe(f)) {
                throw new IllegalArgumentException("A la exportación le falta " + f + " (" + carpeta + ")");
            }
        }
        if (!existe(MANIFIESTO)) {
            return null;
        }
        JsonNode m = leer(MANIFIESTO, null);
        if (!(m instanceof ObjectNode manifiesto)) {
            throw new IllegalArgumentException("El manifiesto " + MANIFIESTO + " no es válido");
        }
        Iterator<Map.Entry<String, JsonNode>> it = manifiesto.path("ficheros").fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (!FICHEROS.contains(e.getKey())) {
                continue;
            }
            if (!existe(e.getKey())) {
                throw new IllegalArgumentException("Falta " + e.getKey() + ", que figura en el manifiesto de la exportación");
            }
            if (!huella(e.getKey()).equalsIgnoreCase(e.getValue().asText())) {
                throw new IllegalArgumentException("El fichero " + e.getKey() + " no es el que se exportó (su huella SHA-256 "
                        + "no coincide con el manifiesto): ¿se ha editado o copiado a medias? Vuelve a exportar.");
            }
        }
        return manifiesto;
    }
}
