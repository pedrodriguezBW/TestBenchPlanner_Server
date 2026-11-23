
package com.borgwarner.tbp.infraestructura.excel;

import com.borgwarner.tbp.dominio.operaciones.ReglaNoCumplida;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

/** Lee los valores guardados de la hoja query. No refresca conexiones ni ejecuta fórmulas. */
public final class ExcelTiming {

    private static final int LIMITE_XML = 24 * 1024 * 1024;
    private static final String REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    private ExcelTiming() {
    }

    public static List<Map<String, String>> leer(InputStream archivo) {
        try {
            Map<String, byte[]> partes = partes(archivo);
            Document libro = xml(partes, "xl/workbook.xml");
            NodeList hojas = elementos(libro, "sheet");
            String relacion = "";
            for (int n = 0; n < hojas.getLength(); n++) {
                Element hoja = (Element) hojas.item(n);
                if ("query".equalsIgnoreCase(hoja.getAttribute("name"))) {
                    relacion = hoja.getAttributeNS(REL, "id");
                    break;
                }
            }
            String ruta = rutaHoja(partes, relacion);
            List<String> compartidos = new ArrayList<>();
            if (partes.containsKey("xl/sharedStrings.xml")) {
                NodeList cadenas = elementos(xml(partes, "xl/sharedStrings.xml"), "si");
                for (int n = 0; n < cadenas.getLength(); n++) {
                    compartidos.add(texto((Element) cadenas.item(n), "t"));
                }
            }
            return filas(xml(partes, ruta), compartidos);
        } catch (IOException | SAXException | ParserConfigurationException | IndexOutOfBoundsException | NumberFormatException e) {
            throw new ReglaNoCumplida("No se ha podido leer el Excel de Timing R&T. Comprueba el formato .xlsx y la hoja query.");
        }
    }

    private static Map<String, byte[]> partes(InputStream archivo) throws IOException {
        Map<String, byte[]> partes = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(archivo)) {
            byte[] bloque = new byte[8192];
            int total = 0;
            int entradas = 0;
            for (var entrada = zip.getNextEntry(); entrada != null; entrada = zip.getNextEntry()) {
                if (++entradas > 256) throw new IOException("Demasiadas entradas");
                String nombre = entrada.getName();
                boolean guardar = nombre.matches("xl/(workbook\\.xml|_rels/workbook\\.xml\\.rels|sharedStrings\\.xml|worksheets/[\\w.-]+\\.xml)");
                var bytes = guardar ? new java.io.ByteArrayOutputStream() : null;
                for (int n = zip.read(bloque); n != -1; n = zip.read(bloque)) {
                    total += n;
                    if (total > LIMITE_XML) throw new IOException("Excel demasiado grande");
                    if (guardar) bytes.write(bloque, 0, n);
                }
                if (guardar && partes.putIfAbsent(nombre, bytes.toByteArray()) != null) throw new IOException("Entrada duplicada");
            }
        }
        return partes;
    }

    private static String rutaHoja(Map<String, byte[]> partes, String relacion)
            throws IOException, SAXException, ParserConfigurationException {
        NodeList relaciones = elementos(xml(partes, "xl/_rels/workbook.xml.rels"), "Relationship");
        for (int n = 0; n < relaciones.getLength(); n++) {
            Element r = (Element) relaciones.item(n);
            if (relacion.equals(r.getAttribute("Id")) && !"External".equals(r.getAttribute("TargetMode"))) {
                String destino = r.getAttribute("Target");
                String ruta = destino.startsWith("/") ? destino.substring(1) : "xl/" + destino;
                if (ruta.matches("xl/worksheets/[\\w.-]+\\.xml")) return ruta;
            }
        }
        throw new ReglaNoCumplida("El Excel debe contener una hoja llamada query.");
    }

    private static List<Map<String, String>> filas(Document hoja, List<String> compartidos) {
        NodeList filas = elementos(hoja, "row");
        if (filas.getLength() > 20001) throw new ReglaNoCumplida("El límite es de 20.000 filas por carga.");
        Map<String, String> cabecera = new HashMap<>();
        List<Map<String, String>> salida = new ArrayList<>();
        for (int n = 0; n < filas.getLength(); n++) {
            NodeList celdas = elementos((Element) filas.item(n), "c");
            Map<String, String> fila = new LinkedHashMap<>();
            for (int c = 0; c < celdas.getLength(); c++) {
                Element celda = (Element) celdas.item(c);
                String columna = celda.getAttribute("r").replaceFirst("[0-9]+$", "");
                String valor = texto(celda, "v");
                switch (celda.getAttribute("t")) {
                    case "s" -> valor = compartidos.get(Integer.parseInt(valor));
                    case "inlineStr" -> valor = texto(celda, "t");
                    default -> { }
                }
                if (valor.isBlank()) continue;
                if (n == 0) {
                    cabecera.put(columna, valor.strip());
                } else if (cabecera.containsKey(columna)) {
                    fila.put(cabecera.get(columna), valor);
                }
            }
            if (!fila.isEmpty()) salida.add(fila);
        }
        if (!cabecera.values().containsAll(List.of("ID", "ID Tarea", "Disp.", "Proyecto", "Cuenta Proyecto"))) {
            throw new ReglaNoCumplida("Faltan columnas de la exportación de Timing R&T.");
        }
        return salida;
    }

    private static Document xml(Map<String, byte[]> partes, String ruta)
            throws IOException, SAXException, ParserConfigurationException {
        byte[] bytes = partes.get(ruta);
        if (bytes == null) throw new IOException("Falta " + ruta);
        DocumentBuilderFactory fabrica = DocumentBuilderFactory.newInstance();
        fabrica.setNamespaceAware(true);
        fabrica.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        fabrica.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        fabrica.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        fabrica.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return fabrica.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    private static NodeList elementos(Document documento, String nombre) {
        return documento.getElementsByTagNameNS("*", nombre);
    }

    private static NodeList elementos(Element elemento, String nombre) {
        return elemento.getElementsByTagNameNS("*", nombre);
    }

    private static String texto(Element elemento, String nombre) {
        NodeList partes = elementos(elemento, nombre);
        StringBuilder texto = new StringBuilder();
        for (int n = 0; n < partes.getLength(); n++) texto.append(partes.item(n).getTextContent());
        return texto.toString();
    }
}
