package com.borgwarner.tbp.infraestructura.postgresql;

import com.borgwarner.tbp.aplicacion.puertos.AlmacenCatalogos;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.listas.ProteccionListas;
import com.borgwarner.tbp.dominio.operaciones.ReglaNoCumplida;
import com.borgwarner.tbp.dominio.planificacion.Areas;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Catálogos (responsables, proyectos, bancos y nombres de área) en sus tablas.
 * Lee y guarda con el MISMO formato y las mismas reglas que el adaptador de
 * SharePoint: orden por Seq (sin Seq al final) y por antigüedad; las columnas
 * de facturación y el área solo se tocan si llegan; responsables solo si llega
 * una lista no vacía; protección D11 de todas las listas antes de escribir.
 *
 * Áreas gestionadas desde la aplicación (D56): la lista de áreas (código y
 * organización fija, en su orden) va en «configuracion_app» con la clave
 * «Areas» (JSON); sus nombres, como siempre, en «nombre_area». Mientras no se
 * gestiona ninguna no hay fila y son las tres de serie (sin migración). Altas,
 * cambios y bajas van con el cerrojo de los catálogos, quedan en «auditoria»
 * (entidad «area») y se rechazan si otra persona ha cambiado el área desde que
 * se abrió para editarla.
 */
public class CatalogosPostgres implements AlmacenCatalogos {

    static final String ORDEN_SEQ = " ORDER BY COALESCE(seq, 1e9), id";
    /** Clave de «configuracion_app» con la lista de áreas (D56). */
    public static final String CLAVE_AREAS = "Areas";
    private static final String CERROJO = "catalogos";

    private final BaseDatos bd;
    private final boolean permitirVacias;
    private final boolean modoServidor;

    public CatalogosPostgres(BaseDatos bd, boolean permitirVacias) {
        this(bd, permitirVacias, false);
    }

    public CatalogosPostgres(BaseDatos bd, boolean permitirVacias, boolean modoServidor) {
        this.bd = bd;
        this.permitirVacias = permitirVacias;
        this.modoServidor = modoServidor;
    }

    @Override
    public ObjectNode leer() {
        List<Areas.Definicion> guardadas = areasGuardadas();
        List<String> codigos = guardadas == null ? Areas.CODIGOS : Areas.codigos(Areas.definiciones(guardadas, Map.of()));
        ObjectNode salida = JsonJs.objetoVacio();
        ArrayNode responsables = salida.putArray("responsibles");
        bd.jdbc().query("SELECT nombre FROM responsable" + ORDEN_SEQ, (java.sql.ResultSet rs) -> {
            while (rs.next()) {
                responsables.add(rs.getString(1));
            }
            return null;
        });
        ArrayNode proyectos = salida.putArray("projects");
        ObjectNode ordenes = salida.putObject("projectOrders");
        ObjectNode numeros = modoServidor ? salida.putObject("projectNumbers") : null;
        bd.jdbc().query("SELECT titulo, orden_facturacion, numero_proyecto FROM proyecto" + ORDEN_SEQ, (java.sql.ResultSet rs) -> {
            while (rs.next()) {
                proyectos.add(rs.getString(1));
                String orden = rs.getString(2) == null ? "" : rs.getString(2).trim();
                if (!orden.isEmpty()) {
                    ordenes.put(rs.getString(1), orden);
                }
                if (numeros != null && rs.getString(3) != null) {
                    numeros.put(rs.getString(1), rs.getString(3));
                }
            }
            return null;
        });
        ArrayNode bancos = salida.putArray("benches");
        bd.jdbc().query("SELECT nombre, group_key, group_name, dot, org, seq, tipo_equipo, area FROM banco" + ORDEN_SEQ,
                (java.sql.ResultSet rs) -> {
                    while (rs.next()) {
                        ObjectNode b = bancos.addObject();
                        b.put("name", rs.getString("nombre"));
                        b.set("groupKey", ValoresJson.nodo(rs.getString("group_key")));
                        b.set("groupName", ValoresJson.nodo(rs.getString("group_name")));
                        b.set("dot", ValoresJson.nodo(rs.getString("dot")));
                        b.set("org", ValoresJson.nodo(rs.getString("org")));
                        b.set("seq", ValoresJson.nodo(ValoresJson.columnaNumero(rs, "seq")));
                        String tipo = rs.getString("tipo_equipo");
                        b.put("equipmentType", tipo == null ? "" : tipo.trim());
                        String area = rs.getString("area") == null ? "" : rs.getString("area").trim();
                        b.put("area", area.isEmpty() ? "" : Areas.normalizar(area, codigos));
                    }
                    return null;
                });
        ObjectNode nombres = salida.putObject("areaNames");
        bd.jdbc().query("SELECT codigo, nombre FROM nombre_area ORDER BY codigo", (java.sql.ResultSet rs) -> {
            while (rs.next()) {
                String nombre = rs.getString(2) == null ? "" : rs.getString(2).trim();
                if (codigos.contains(rs.getString(1)) && !nombre.isEmpty()) {
                    nombres.put(rs.getString(1), nombre);
                }
            }
            return null;
        });
        if (guardadas != null) {
            ArrayNode areas = salida.putArray("areas");
            Areas.definiciones(guardadas, Map.of()).forEach(d -> areas.addObject().put("code", d.codigo()).put("organization", d.organizacion()));
        }
        return salida;
    }

    @Override
    public void guardarNumeroProyecto(String proyecto, String numero, String anterior) {
        if (!numero.isEmpty() && !numero.matches("[0-9]{1,40}")) {
            throw new ReglaNoCumplida("Introduce un número de proyecto de hasta 40 dígitos.");
        }
        bd.enTransaccion(() -> {
            bd.bloquear(CERROJO);
            int cambios = bd.jdbc().update("UPDATE proyecto SET numero_proyecto = NULLIF(?, '') "
                    + "WHERE titulo = ? AND coalesce(numero_proyecto, '') = ?", numero, proyecto, anterior);
            if (cambios == 0) {
                throw new ReglaNoCumplida("El proyecto ha cambiado. Recarga la lista antes de guardar.");
            }
            Auditoria.anotar(bd, bd.actor(), "proyecto", proyecto, Auditoria.MODIFICACION, anterior, numero);
            return null;
        });
    }

    /** Valor de la columna Area: el código del área de serie; vacío para Bancos Térmicos (como en SharePoint). */
    static String valorArea(JsonNode banco) {
        return valorArea(banco, Areas.CODIGOS);
    }

    /** Ídem entre las áreas que existen (D56): un código que no existe es Bancos Térmicos. */
    static String valorArea(JsonNode banco, List<String> codigos) {
        String area = Areas.normalizar(banco.path("area").asText(""), codigos);
        return Areas.TERMICOS.equals(area) ? "" : area;
    }

    @Override
    public ObjectNode guardar(JsonNode catalogos) {
        return bd.enTransaccion(() -> {
            bd.bloquear(CERROJO);
            ObjectNode actual = leer();
            List<String> codigos = codigosAreas();
            JsonNode responsables = catalogos.path("responsibles");
            boolean conResponsables = responsables.isArray() && responsables.size() > 0;
            // D11: se comprueban todas las listas antes de escribir nada.
            if (conResponsables) {
                ProteccionListas.comprobar("TestBenchResponsibles", textos(actual.path("responsibles"), null),
                        textos(responsables, null), permitirVacias);
            }
            ProteccionListas.comprobar("TestBenchProjects", textos(actual.path("projects"), null),
                    textos(catalogos.path("projects"), null), permitirVacias);
            ProteccionListas.comprobar("TestBenchBenches", textos(actual.path("benches"), "name"),
                    textos(catalogos.path("benches"), "name"), permitirVacias);

            var actor = bd.actor();
            ObjectNode resultado = JsonJs.objetoVacio().put("ok", true);
            if (conResponsables) {
                List<Map<String, Object>> filas = new ArrayList<>();
                int i = 0;
                for (JsonNode n : responsables) {
                    i++;
                    if (!n.asText("").isEmpty()) {
                        Map<String, Object> f = new LinkedHashMap<>();
                        f.put("nombre", n.asText());
                        f.put("seq", (double) i);
                        filas.add(f);
                    }
                }
                resultado.set("responsibles", Reconciliador.reconciliar(bd, "responsable", "nombre", List.of("seq"), filas,
                        true, "responsable", actor, null).json());
            } else {
                resultado.putObject("responsibles").put("skipped", "frozen (people come from TestBenchUsers)");
            }

            JsonNode ordenes = catalogos.get("projectOrders");
            boolean conOrdenes = ordenes != null && ordenes.isObject();
            List<Map<String, Object>> proyectos = new ArrayList<>();
            int i = 0;
            for (JsonNode n : catalogos.path("projects")) {
                i++;
                if (n.asText("").isEmpty()) {
                    continue;
                }
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("titulo", n.asText());
                f.put("seq", (double) i);
                if (conOrdenes) {
                    String orden = ordenes.path(n.asText()).asText("").trim();
                    f.put("orden_facturacion", orden.isEmpty() ? null : orden);
                }
                proyectos.add(f);
            }
            resultado.set("projects", Reconciliador.reconciliar(bd, "proyecto", "titulo", List.of("seq", "orden_facturacion"),
                    proyectos, true, "proyecto", actor, null).json());

            List<Map<String, Object>> bancos = new ArrayList<>();
            for (JsonNode b : catalogos.path("benches")) {
                if (!b.hasNonNull("name") || b.get("name").asText().isEmpty()) {
                    continue;
                }
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("nombre", b.get("name").asText());
                f.put("group_key", ValoresJson.texto(b.get("groupKey")));
                f.put("group_name", ValoresJson.texto(b.get("groupName")));
                f.put("dot", ValoresJson.texto(b.get("dot")));
                f.put("org", ValoresJson.texto(b.get("org")));
                f.put("seq", ValoresJson.numero(b.get("seq")));
                if (b.has("equipmentType")) {
                    f.put("tipo_equipo", b.path("equipmentType").asText("").trim());
                }
                if (b.has("area")) {
                    f.put("area", valorArea(b, codigos));
                }
                bancos.add(f);
            }
            resultado.set("benches", Reconciliador.reconciliar(bd, "banco", "nombre",
                    List.of("group_key", "group_name", "dot", "org", "seq", "tipo_equipo", "area"), bancos, true, "banco", actor, null).json());

            if (catalogos.path("areaNames").isObject()) {
                resultado.set("areaNames", JsonJs.objetoVacio().put("updated", guardarNombresAreas(catalogos.get("areaNames"), codigos)));
            }
            return resultado;
        });
    }

    /** Solo los nombres que cambian; vacío o el de serie sin nada guardado = no se guarda. */
    private int guardarNombresAreas(JsonNode pedidos, List<String> codigos) {
        Map<String, String> guardados = new LinkedHashMap<>();
        bd.jdbc().query("SELECT codigo, nombre FROM nombre_area", (java.sql.ResultSet rs) -> {
            while (rs.next()) {
                guardados.put(rs.getString(1), rs.getString(2) == null ? "" : rs.getString(2).trim());
            }
            return null;
        });
        int cambiados = 0;
        for (String codigo : codigos) {
            if (!pedidos.has(codigo)) {
                continue;
            }
            String nombre = pedidos.path(codigo).asText("").trim();
            String guardado = guardados.get(codigo);
            if (nombre.equals(guardado == null ? "" : guardado)
                    || (guardado == null && (nombre.isEmpty() || nombre.equals(Areas.NOMBRES_DE_SERIE.get(codigo))))) {
                continue;
            }
            if (nombre.isEmpty()) {
                bd.jdbc().update("DELETE FROM nombre_area WHERE codigo = ?", codigo);
            } else {
                bd.jdbc().update("INSERT INTO nombre_area (codigo, nombre) VALUES (?, ?) "
                        + "ON CONFLICT (codigo) DO UPDATE SET nombre = EXCLUDED.nombre", codigo, nombre);
            }
            Auditoria.anotar(bd, bd.actor(), "nombre_area", codigo, Auditoria.MODIFICACION, guardado, nombre);
            cambiados++;
        }
        return cambiados;
    }

    // ---- áreas gestionadas desde la aplicación (solo PostgreSQL, D56; el rol se exige en la API) ----

    /** Lista de áreas guardada (código y organización, sin nombre), o null si no se ha gestionado ninguna. */
    List<Areas.Definicion> areasGuardadas() {
        List<String> valor = bd.jdbc().queryForList("SELECT valor FROM configuracion_app WHERE clave = ?", String.class, CLAVE_AREAS);
        if (valor.isEmpty() || valor.get(0) == null || valor.get(0).isBlank()) {
            return null;
        }
        List<Areas.Definicion> areas = new ArrayList<>();
        for (JsonNode a : JsonJs.leer(valor.get(0))) {
            String codigo = a.path("code").asText("").trim();
            JsonNode org = a.get("organization");
            String fija = org == null || org.isNull() || org.asText("").trim().isEmpty() ? null : org.asText().trim();
            if (!codigo.isEmpty()) {
                areas.add(new Areas.Definicion(codigo, null, fija));
            }
        }
        return areas.isEmpty() ? null : areas;
    }

    /** Códigos de las áreas que existen, en su orden. */
    private List<String> codigosAreas() {
        return Areas.codigos(areasActuales());
    }

    /** Áreas que existen con su nombre (las guardadas o las de serie). */
    private List<Areas.Definicion> areasActuales() {
        Map<String, String> nombres = new LinkedHashMap<>();
        bd.jdbc().query("SELECT codigo, nombre FROM nombre_area", (java.sql.ResultSet rs) -> {
            while (rs.next()) {
                nombres.put(rs.getString(1), rs.getString(2) == null ? "" : rs.getString(2).trim());
            }
            return null;
        });
        return Areas.definiciones(areasGuardadas(), nombres);
    }

    private void guardarAreas(List<Areas.Definicion> areas) {
        ArrayNode lista = JsonJs.mapper().createArrayNode();
        areas.forEach(d -> lista.addObject().put("code", d.codigo()).put("organization", d.organizacion()));
        bd.jdbc().update("INSERT INTO configuracion_app (clave, valor) VALUES (?, ?) ON CONFLICT (clave) DO UPDATE SET valor = EXCLUDED.valor",
                CLAVE_AREAS, JsonJs.escribir(lista));
    }

    private static ObjectNode json(Areas.Definicion d, int orden) {
        return JsonJs.objetoVacio().put("code", d.codigo()).put("name", d.nombre()).put("organization", d.organizacion())
                .put("order", orden);
    }

    /** Nombre pedido: obligatorio, con un largo máximo y sin repetir el de otra área (sin distinguir mayúsculas). */
    private static String nombreValido(JsonNode datos, List<Areas.Definicion> areas, String codigoPropio) {
        String nombre = datos == null ? "" : datos.path("name").asText("").trim();
        if (nombre.isEmpty()) {
            throw new ReglaNoCumplida("Indica el nombre del área.");
        }
        if (nombre.length() > Areas.LARGO_MAXIMO_NOMBRE) {
            throw new ReglaNoCumplida("El nombre del área admite como máximo " + Areas.LARGO_MAXIMO_NOMBRE + " caracteres.");
        }
        for (Areas.Definicion d : areas) {
            if (!d.codigo().equals(codigoPropio) && d.nombre().equalsIgnoreCase(nombre)) {
                throw new ReglaNoCumplida("Ya existe un área llamada «" + d.nombre() + "».");
            }
        }
        return nombre;
    }

    @Override
    public boolean areasEditables() {
        return true;
    }

    @Override
    public ObjectNode crearArea(JsonNode datos) {
        return bd.enTransaccion(() -> {
            bd.bloquear(CERROJO);
            List<Areas.Definicion> areas = new ArrayList<>(areasActuales());
            String nombre = nombreValido(datos, areas, null);
            JsonNode org = datos.get("organization");
            String fija = org == null || org.isNull() ? "" : org.asText("").trim().toUpperCase(java.util.Locale.ROOT);
            if (!fija.isEmpty() && !Areas.ORGANIZACIONES.contains(fija)) {
                throw new ReglaNoCumplida("Organización no válida: elige " + String.join(", ", Areas.ORGANIZACIONES)
                        + " o «bench by bench».");
            }
            String codigo = Areas.codigoNuevo(nombre, Areas.codigos(areas));
            Areas.Definicion nueva = new Areas.Definicion(codigo, nombre, fija.isEmpty() ? null : fija);
            areas.add(nueva);
            guardarAreas(areas);
            bd.jdbc().update("INSERT INTO nombre_area (codigo, nombre) VALUES (?, ?) "
                    + "ON CONFLICT (codigo) DO UPDATE SET nombre = EXCLUDED.nombre", codigo, nombre);
            ObjectNode salida = json(nueva, areas.size() - 1);
            Auditoria.anotar(bd, bd.actor(), "area", codigo, Auditoria.ALTA, null, JsonJs.escribir(salida));
            return salida;
        });
    }

    @Override
    public ObjectNode modificarArea(String codigo, JsonNode datos) {
        return bd.enTransaccion(() -> {
            bd.bloquear(CERROJO);
            List<Areas.Definicion> areas = new ArrayList<>(areasActuales());
            int posicion = existente(areas, codigo, datos == null ? null : datos.get("anterior"));
            Areas.Definicion actual = areas.get(posicion);
            String nombre = nombreValido(datos, areas, actual.codigo());
            int destino = posicion;
            JsonNode orden = datos.get("order");
            if (orden != null && !orden.isNull() && !orden.asText("").isBlank()) {
                if (!orden.canConvertToInt() && !orden.asText("").trim().matches("-?\\d{1,9}")) {
                    throw new ReglaNoCumplida("Posición del área no válida.");
                }
                destino = Math.max(0, Math.min(areas.size() - 1, orden.canConvertToInt() ? orden.asInt() : Integer.parseInt(orden.asText().trim())));
            }
            ObjectNode antes = json(actual, posicion);
            Areas.Definicion nueva = actual.conNombre(nombre);
            areas.remove(posicion);
            areas.add(destino, nueva);
            if (destino != posicion) {
                guardarAreas(areas);
            }
            if (!nombre.equals(actual.nombre())) {
                bd.jdbc().update("INSERT INTO nombre_area (codigo, nombre) VALUES (?, ?) "
                        + "ON CONFLICT (codigo) DO UPDATE SET nombre = EXCLUDED.nombre", actual.codigo(), nombre);
            }
            ObjectNode despues = json(nueva, destino);
            if (!despues.equals(antes)) {
                Auditoria.anotar(bd, bd.actor(), "area", actual.codigo(), Auditoria.MODIFICACION,
                        JsonJs.escribir(antes), JsonJs.escribir(despues));
            }
            return despues;
        });
    }

    @Override
    public void borrarArea(String codigo, JsonNode anterior) {
        bd.enTransaccion(() -> {
            bd.bloquear(CERROJO);
            List<Areas.Definicion> areas = new ArrayList<>(areasActuales());
            int posicion = existente(areas, codigo, anterior);
            Areas.Definicion actual = areas.get(posicion);
            if (Areas.TERMICOS.equals(actual.codigo())) {
                throw new ReglaNoCumplida("«" + actual.nombre() + "» no se puede borrar: es el área de los bancos sin área.");
            }
            List<String> codigos = Areas.codigos(areas);
            List<String> usados = bd.jdbc().query("SELECT nombre, area FROM banco ORDER BY nombre", (rs, i) -> {
                String area = rs.getString("area") == null ? "" : rs.getString("area").trim();
                return !area.isEmpty() && Areas.normalizar(area, codigos).equals(actual.codigo()) ? rs.getString("nombre") : null;
            }).stream().filter(Objects::nonNull).toList();
            if (!usados.isEmpty()) {
                throw new ReglaNoCumplida("El área «" + actual.nombre() + "» tiene " + usados.size() + " banco(s) ("
                        + String.join(", ", usados.subList(0, Math.min(5, usados.size()))) + (usados.size() > 5 ? "…" : "")
                        + "): muévelos a otra área en Test Benches antes de borrarla.");
            }
            ObjectNode antes = json(actual, posicion);
            areas.remove(posicion);
            guardarAreas(areas);
            bd.jdbc().update("DELETE FROM nombre_area WHERE codigo = ?", actual.codigo());
            Auditoria.anotar(bd, bd.actor(), "area", actual.codigo(), Auditoria.BAJA, JsonJs.escribir(antes), null);
            return null;
        });
    }

    /** Posición del área; rechaza si no existe o si otra persona la ha cambiado desde que se abrió. */
    private static int existente(List<Areas.Definicion> areas, String codigo, JsonNode anterior) {
        String c = codigo == null ? "" : codigo.trim().toUpperCase(java.util.Locale.ROOT);
        for (int i = 0; i < areas.size(); i++) {
            Areas.Definicion d = areas.get(i);
            if (d.codigo().equals(c)) {
                if (anterior != null && anterior.isObject() && anterior.has("name")
                        && !anterior.path("name").asText("").trim().equals(d.nombre())) {
                    throw new ReglaNoCumplida("Otra persona ha cambiado el área «" + d.nombre()
                            + "» mientras la editabas. Vuelve a cargar la lista para ver el valor actual.");
                }
                return i;
            }
        }
        throw new ReglaNoCumplida("El área " + codigo + " ya no existe (otra persona la ha borrado). Vuelve a cargar la lista.");
    }

    /** Claves no vacías de una lista: el texto de cada elemento o el de su campo. */
    static List<String> textos(JsonNode lista, String campo) {
        List<String> claves = new ArrayList<>();
        for (JsonNode n : lista) {
            String clave = campo == null ? n.asText("") : n.path(campo).asText("");
            if (!clave.isEmpty()) {
                claves.add(clave);
            }
        }
        return claves;
    }
}
