package com.borgwarner.tbp.infraestructura.postgresql;

import com.borgwarner.tbp.aplicacion.puertos.AlmacenUsuarios;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Deja una tabla con exactamente las filas recibidas, identificadas por una
 * columna clave (equivale a la reconciliación de las listas de SharePoint):
 * crea las que faltan, actualiza SOLO los campos que cambian y borra las que
 * sobran. Cada cambio queda en la auditoría.
 *
 * Cada fila es un mapa columna → valor; una columna que no viene en la fila no
 * se toca (así se conservan, p. ej., las columnas de facturación cuando guarda
 * la interfaz clásica, que no las conoce). Los nombres de tabla y columna son
 * siempre constantes del código, nunca datos de la petición.
 *
 * Las filas existentes conservan su id (y con él su posición en las lecturas
 * que ordenan por id, como el Id de SharePoint); las nuevas van al final.
 */
public final class Reconciliador {

    private Reconciliador() {
    }

    /** Altas, modificaciones y bajas hechas. */
    public record Recuento(int creados, int actualizados, int borrados) {
        public static final Recuento NINGUNO = new Recuento(0, 0, 0);

        public int total() {
            return creados + actualizados + borrados;
        }

        public Recuento mas(Recuento otro) {
            return new Recuento(creados + otro.creados, actualizados + otro.actualizados, borrados + otro.borrados);
        }

        public ObjectNode json() {
            return JsonJs.objetoVacio().put("created", creados).put("updated", actualizados).put("deleted", borrados);
        }
    }

    /**
     * @param tabla           tabla
     * @param clave           columna clave (única)
     * @param columnasLectura columnas que se comparan y se auditan
     * @param filas           filas deseadas, en orden (cada una con la clave)
     * @param borrarSobrantes si es true, se borran las filas cuya clave no llega
     * @param operacionAlta   operación de auditoría (ALTA/IMPORTACION…); null = se elige sola
     */
    public static Recuento reconciliar(BaseDatos bd, String tabla, String clave, List<String> columnasLectura,
                                       List<Map<String, Object>> filas, boolean borrarSobrantes, String entidad,
                                       AlmacenUsuarios.Identidad actor, String operacionForzada) {
        String seleccion = "SELECT " + clave + (columnasLectura.isEmpty() ? "" : ", " + String.join(", ", columnasLectura))
                + " FROM " + tabla + " ORDER BY id";
        Map<String, Map<String, Object>> actuales = new LinkedHashMap<>();
        for (Map<String, Object> fila : bd.jdbc().queryForList(seleccion)) {
            actuales.putIfAbsent(textoClave(fila.get(clave)), fila);
        }
        int creados = 0, actualizados = 0, borrados = 0;
        java.util.Set<String> vistas = new java.util.HashSet<>();
        for (Map<String, Object> fila : filas) {
            Object valorClave = fila.get(clave);
            String k = textoClave(valorClave);
            if (valorClave == null || !vistas.add(k)) {
                continue; // sin clave o repetida: vale la primera
            }
            Map<String, Object> actual = actuales.remove(k);
            if (actual == null) {
                List<String> columnas = new ArrayList<>();
                List<Object> valores = new ArrayList<>();
                columnas.add(clave);
                valores.add(valorClave);
                for (Map.Entry<String, Object> c : fila.entrySet()) {
                    if (!c.getKey().equals(clave)) {
                        columnas.add(c.getKey());
                        valores.add(c.getValue());
                    }
                }
                bd.jdbc().update("INSERT INTO " + tabla + " (" + String.join(", ", columnas) + ") VALUES ("
                        + String.join(", ", java.util.Collections.nCopies(columnas.size(), "?")) + ")", valores.toArray());
                Auditoria.anotar(bd, actor, entidad, k, operacionForzada != null ? operacionForzada : Auditoria.ALTA,
                        null, comoJson(fila));
                creados++;
                continue;
            }
            List<String> cambiadas = new ArrayList<>();
            List<Object> valores = new ArrayList<>();
            for (Map.Entry<String, Object> c : fila.entrySet()) {
                if (c.getKey().equals(clave)) {
                    continue;
                }
                if (!mismoValor(c.getValue(), actual.get(c.getKey()))) {
                    cambiadas.add(c.getKey() + " = ?");
                    valores.add(c.getValue());
                }
            }
            if (!cambiadas.isEmpty()) {
                valores.add(valorClave);
                bd.jdbc().update("UPDATE " + tabla + " SET " + String.join(", ", cambiadas) + " WHERE " + clave + " = ?",
                        valores.toArray());
                Map<String, Object> despues = new LinkedHashMap<>(actual);
                despues.putAll(fila);
                Auditoria.anotar(bd, actor, entidad, k, operacionForzada != null ? operacionForzada : Auditoria.MODIFICACION,
                        comoJson(actual), comoJson(despues));
                actualizados++;
            }
        }
        if (borrarSobrantes) {
            for (Map.Entry<String, Map<String, Object>> sobra : actuales.entrySet()) {
                Object valorClave = sobra.getValue().get(clave);
                bd.jdbc().update("DELETE FROM " + tabla + " WHERE " + clave + " = ?", valorClave);
                Auditoria.anotar(bd, actor, entidad, sobra.getKey(), operacionForzada != null ? operacionForzada : Auditoria.BAJA,
                        comoJson(sobra.getValue()), null);
                borrados++;
            }
        }
        return new Recuento(creados, actualizados, borrados);
    }

    static String textoClave(Object valor) {
        if (valor instanceof Number n) {
            double d = n.doubleValue();
            return d == Math.rint(d) && !Double.isInfinite(d) ? String.valueOf((long) d) : String.valueOf(d);
        }
        return valor == null ? "" : valor.toString();
    }

    /** Igualdad de valores de base de datos: números como número y fechas como fecha. */
    static boolean mismoValor(Object nuevo, Object actual) {
        if (nuevo == null || actual == null) {
            return nuevo == null && actual == null;
        }
        if (nuevo instanceof Number a && actual instanceof Number b) {
            return Double.compare(a.doubleValue(), b.doubleValue()) == 0;
        }
        Object x = nuevo instanceof java.sql.Date d ? d.toLocalDate() : nuevo;
        Object y = actual instanceof java.sql.Date d ? d.toLocalDate() : actual;
        if (x instanceof LocalDate || y instanceof LocalDate) {
            return x.toString().equals(y.toString());
        }
        return Objects.equals(x, y);
    }

    /** Una fila en JSON para la auditoría (Jackson convierte fechas a texto). */
    static String comoJson(Map<String, Object> fila) {
        ObjectNode o = JsonJs.objetoVacio();
        fila.forEach((k, v) -> {
            if (v == null) {
                o.putNull(k);
            } else if (v instanceof Number n) {
                o.put(k, n.doubleValue());
            } else if (v instanceof Boolean b) {
                o.put(k, b);
            } else {
                o.put(k, v instanceof java.sql.Date d ? d.toLocalDate().toString() : v.toString());
            }
        });
        return JsonJs.escribir(o);
    }
}
