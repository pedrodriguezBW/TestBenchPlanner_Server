package com.borgwarner.tbp.infraestructura.postgresql;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Número y nombre de los proyectos del catálogo (servidor).
 *
 * En la base de datos el proyecto guarda el nombre (titulo, sin número) y el
 * número (numero_proyecto) por separado. Hacia fuera se sigue usando la
 * etiqueta «número - nombre», que es lo que guardan los ensayos, la que sale en
 * el desplegable Project y de la que la facturación saca el número. Así, los
 * proyectos que venían como «80001755 - Rimac …» conservan exactamente su texto
 * y ningún ensayo cambia al separarlos.
 *
 * Los números de la serie 8 se guardan en formato largo de 8 cifras
 * (80834 → 80000834, 801786 → 80001786). Otros números (10766, 00877…) no se tocan.
 */
public final class NombresProyecto {

    /** Formato exacto «número - nombre»: solo este se separa sin cambiar el texto. */
    private static final Pattern EXACTO = Pattern.compile("^([0-9]+) - (\\S(?:.*\\S)?)$");
    /** Un nombre que todavía empieza por un número de proyecto (5 cifras o más; sin separar o con otro formato). */
    private static final Pattern CON_NUMERO = Pattern.compile("^\\s*[0-9]{5,}(?:\\s|-).*", Pattern.DOTALL);

    private NombresProyecto() {
    }

    /** Número y nombre de un proyecto. numero = "" si no tiene. */
    public record Partes(String numero, String nombre) {
    }

    /**
     * «80001755 - Nombre» → (80001755, Nombre). Cualquier otro texto se queda
     * entero como nombre y sin número (null), para no cambiar la etiqueta.
     */
    public static Partes separarExacto(String texto) {
        Matcher m = EXACTO.matcher(texto == null ? "" : texto);
        // Solo si la etiqueta vuelve a dar exactamente el mismo texto (p. ej. no «80001755 - 80001755 Rimac»).
        return m.matches() && etiqueta(m.group(2), m.group(1)).equals(texto) ? new Partes(m.group(1), m.group(2)) : null;
    }

    /**
     * Proyecto nuevo escrito en la aplicación («número - nombre» o solo nombre):
     * el número pasa a formato largo.
     */
    public static Partes separarNuevo(String texto) {
        String limpio = texto == null ? "" : texto.strip();
        Partes p = separarExacto(limpio);
        if (p == null) return new Partes("", limpio);
        String largo = numeroLargo(p.numero());
        return etiqueta(p.nombre(), largo).equals(largo + " - " + p.nombre()) ? new Partes(largo, p.nombre()) : new Partes("", limpio);
    }

    /** Etiqueta pública del proyecto: «número - nombre», o el nombre si ya empieza por un número o no tiene número. */
    public static String etiqueta(String nombre, String numero) {
        String n = nombre == null ? "" : nombre;
        String c = numero == null ? "" : numero.strip();
        boolean yaLoLleva = !c.isEmpty() && n.strip().startsWith(c)
                && (n.strip().length() == c.length() || !Character.isDigit(n.strip().charAt(c.length())));
        return c.isEmpty() || yaLoLleva || nombreConNumero(n) ? n : c + " - " + n;
    }

    /** true si el texto tiene la forma «número - nombre» (el número debe ir en su columna). */
    public static boolean tieneFormaNumeroNombre(String texto) {
        return texto != null && Pattern.compile("^\\s*[0-9]+\\s*-\\s*\\S.*", Pattern.DOTALL).matcher(texto).matches();
    }

    /** true si el nombre todavía lleva el número delante (hay que corregirlo a mano). */
    public static boolean nombreConNumero(String nombre) {
        return nombre != null && CON_NUMERO.matcher(nombre).matches();
    }

    /**
     * Formato largo de los números de la serie 8 (8 cifras): 80834 → 80000834,
     * 81886 → 80001886, 801786 → 80001786, 8000475 → 80000475, 800001786 → 80001786.
     * Lo que no es de esa serie (o no son solo cifras) se devuelve sin espacios y sin cambios.
     */
    public static String numeroLargo(String numero) {
        String n = numero == null ? "" : numero.strip();
        if (!n.matches("8[0-9]{4,8}")) {
            return n;
        }
        String resto = n.substring(1).replaceFirst("^0+", "");
        if (resto.length() > 7) {
            return n;
        }
        return "8" + "0".repeat(7 - resto.length()) + resto;
    }
}
