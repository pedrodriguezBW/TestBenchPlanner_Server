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
        // Servidor: número y nombre por separado para Configuración › Projects (la clave pública sigue siendo la etiqueta).
        ArrayNode fichas = modoServidor ? salida.putArray("projectItems") : null;
        bd.jdbc().query("SELECT titulo, orden_facturacion, numero_proyecto, id FROM proyecto" + ORDEN_SEQ, (java.sql.ResultSet rs) -> {
            while (rs.next()) {
                String nombre = rs.getString(1);
                String numero = rs.getString(3) == null ? "" : rs.getString(3);
                String etiqueta = NombresProyecto.etiqueta(nombre, numero);
                proyectos.add(etiqueta);
                String orden = rs.getString(2) == null ? "" : rs.getString(2).trim();
                if (!orden.isEmpty()) {
                    ordenes.put(etiqueta, orden);
                }
                if (numeros != null && !numero.isEmpty()) {
                    numeros.put(etiqueta, numero);
                }
                if (fichas != null) {
                    fichas.addObject().put("id", rs.getLong(4)).put("label", etiqueta).put("name", nombre).put("number", numero)
                            .put("numberInName", NombresProyecto.nombreConNumero(nombre));
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
        List<Map<String, Object>> filas = bd.jdbc().queryForList("SELECT id, titulo, coalesce(numero_proyecto, '') AS numero FROM proyecto");
        for (Map<String, Object> f : filas) {
            String nombre = (String) f.get("titulo");
            if (NombresProyecto.etiqueta(nombre, (String) f.get("numero")).equals(proyecto)) {
                editarProyecto(((Number) f.get("id")).longValue(), nombre, numero, nombre, anterior);
                return;
            }
        }
        throw new ReglaNoCumplida("El proyecto ha cambiado. Recarga la lista antes de guardar.");
    }

    /**
     * Cambia el nombre y/o el número de un proyecto sin borrarlo (conserva su id,
     * su orden de facturación y su posición). Si cambia la etiqueta «número -
     * nombre», se actualiza en todos los ensayos (abiertos y cerrados) que la
     * usan; en Timing R&T, las tareas de ese proyecto y las correspondencias
     * manuales que apuntan a él. Todo en una transacción y en la auditoría.
     */
    @Override
    public ObjectNode editarProyecto(long id, String nombre, String numero, String anteriorNombre, String anteriorNumero) {
        String nuevoNombre = nombre == null ? "" : nombre.strip();
        String nuevoNumero = NombresProyecto.numeroLargo(numero);
        if (nuevoNombre.isEmpty()) {
            throw new ReglaNoCumplida("El proyecto necesita un nombre.");
        }
        if (!nuevoNumero.isEmpty() && !nuevoNumero.matches("[0-9]{1,40}")) {
            throw new ReglaNoCumplida("Introduce un número de proyecto de hasta 40 dígitos.");
        }
        if (!nuevoNombre.equals(anteriorNombre) && NombresProyecto.tieneFormaNumeroNombre(nuevoNombre)) {
            throw new ReglaNoCumplida("Escribe el número en la columna Project Number, no en el nombre.");
        }
        return bd.enTransaccion(() -> {
            bd.bloquear(CERROJO);
            List<Map<String, Object>> actual = bd.jdbc().queryForList(
                    "SELECT titulo, coalesce(numero_proyecto, '') AS numero FROM proyecto WHERE id = ? FOR UPDATE", id);
            if (actual.isEmpty()) {
                throw new ReglaNoCumplida("El proyecto ya no existe. Recarga la lista.");
            }
            String viejoNombre = (String) actual.get(0).get("titulo");
            String viejoNumero = (String) actual.get(0).get("numero");
            if (!viejoNombre.equals(anteriorNombre) || !viejoNumero.equals(anteriorNumero == null ? "" : anteriorNumero.strip())) {
                throw new ReglaNoCumplida("El proyecto ha cambiado. Recarga la lista antes de guardar.");
            }
            ObjectNode resultado = JsonJs.objetoVacio().put("ok", true);
            String viejaEtiqueta = NombresProyecto.etiqueta(viejoNombre, viejoNumero);
            String nuevaEtiqueta = NombresProyecto.etiqueta(nuevoNombre, nuevoNumero);
            resultado.put("label", nuevaEtiqueta).put("name", nuevoNombre).put("number", nuevoNumero);
            if (viejoNombre.equals(nuevoNombre) && viejoNumero.equals(nuevoNumero)) {
                return resultado.put("tests", 0).put("timingTasks", 0);
            }
            for (Map<String, Object> otro : bd.jdbc().queryForList(
                    "SELECT titulo, coalesce(numero_proyecto, '') AS numero FROM proyecto WHERE id <> ?", id)) {
                String suNombre = (String) otro.get("titulo");
                if (suNombre.equals(nuevoNombre)) {
                    throw new ReglaNoCumplida("Ya hay otro proyecto llamado «" + nuevoNombre + "».");
                }
                if (NombresProyecto.etiqueta(suNombre, (String) otro.get("numero")).equals(nuevaEtiqueta)) {
                    throw new ReglaNoCumplida("Ya hay otro proyecto «" + nuevaEtiqueta + "».");
                }
            }
            bd.jdbc().update("UPDATE proyecto SET titulo = ?, numero_proyecto = NULLIF(?, '') WHERE id = ?",
                    nuevoNombre, nuevoNumero, id);
            var actor = bd.actor();
            Auditoria.anotar(bd, actor, "proyecto", nuevaEtiqueta, Auditoria.MODIFICACION,
                    JsonJs.objetoVacio().put("titulo", viejoNombre).put("numero_proyecto", viejoNumero).toString(),
                    JsonJs.objetoVacio().put("titulo", nuevoNombre).put("numero_proyecto", nuevoNumero).toString());
            int ensayos = viejaEtiqueta.equals(nuevaEtiqueta) ? 0 : renombrarEnEnsayos(viejaEtiqueta, nuevaEtiqueta, actor);
            resultado.put("tests", ensayos);
            if (modoServidor) {
                resultado.put("timingTasks", VinculacionTiming.renombrarProyecto(bd, id, viejoNombre, nuevoNombre, nuevoNumero));
                VinculacionTiming.vincular(bd);
            } else {
                resultado.put("timingTasks", 0);
            }
            return resultado;
        });
    }

    /** Cambia el proyecto de los ensayos que usan la etiqueta anterior. Devuelve cuántos. */
    private int renombrarEnEnsayos(String anterior, String nueva, com.borgwarner.tbp.aplicacion.puertos.AlmacenUsuarios.Identidad actor) {
        List<Map<String, Object>> filas = bd.jdbc().queryForList(
                "SELECT uid, data_json FROM ensayo WHERE proyecto = ? ORDER BY uid FOR UPDATE", anterior.strip());
        int cambiados = 0;
        for (Map<String, Object> fila : filas) {
            String uid = (String) fila.get("uid");
            String texto = (String) fila.get("data_json");
            String nuevo = conProyecto(texto, nueva);
            if (nuevo == null || nuevo.equals(texto)) {
                continue;
            }
            List<Object> valores = new ArrayList<>();
            valores.add(nuevo);
            valores.addAll(java.util.Arrays.asList(ColumnasEnsayo.de(nuevo).valores()));
            valores.add(actor == null ? null : actor.email());
            valores.add(uid);
            bd.jdbc().update("UPDATE ensayo SET data_json = ?, version = version + 1, " + ColumnasEnsayo.ASIGNACIONES
                    + ", actualizado_en = now(), actualizado_por = ? WHERE uid = ?", valores.toArray());
            Auditoria.anotar(bd, actor, "ensayo", uid, Auditoria.MODIFICACION, texto, nuevo);
            cambiados++;
        }
        return cambiados;
    }

    /**
     * El DataJson con otro «project». Se cambia solo ese texto para conservar el
     * resto byte a byte; si no se encuentra una única vez, se reescribe el JSON.
     */
    static String conProyecto(String texto, String proyecto) {
        ObjectNode e = JsonJs.leerObjeto(texto);
        if (e == null || !e.path("project").isTextual()) {
            return null;
        }
        String antes = "\"project\":" + JsonJs.escribir(e.get("project"));
        int primera = texto.indexOf(antes);
        if (primera >= 0 && primera == texto.lastIndexOf(antes)) {
            return texto.substring(0, primera) + "\"project\":" + JsonJs.escribir(com.fasterxml.jackson.databind.node.TextNode.valueOf(proyecto))
                    + texto.substring(primera + antes.length());
        }
        e.put("project", proyecto);
        return JsonJs.escribir(e);
    }

    /**
     * Filas de la tabla proyecto para una lista de etiquetas «número - nombre»:
     * las que ya existen se identifican por su nombre (no se toca su número); las
     * nuevas se separan en número (formato largo) y nombre. Cada fila lleva
     * además «etiqueta» (la recibida), que el llamante debe quitar.
     */
    private List<Map<String, Object>> filasProyectos(JsonNode lista) {
        Map<String, String> nombrePorEtiqueta = new LinkedHashMap<>();
        java.util.Set<String> nombres = new java.util.HashSet<>();
        bd.jdbc().query("SELECT titulo, coalesce(numero_proyecto, '') FROM proyecto", (java.sql.ResultSet rs) -> {
            while (rs.next()) {
                nombrePorEtiqueta.put(NombresProyecto.etiqueta(rs.getString(1), rs.getString(2)), rs.getString(1));
                nombres.add(rs.getString(1));
            }
            return null;
        });
        List<Map<String, Object>> filas = new ArrayList<>();
        java.util.Set<String> usados = new java.util.HashSet<>();
        int i = 0;
        for (JsonNode n : lista) {
            i++;
            String etiqueta = n.asText("");
            if (etiqueta.isEmpty()) {
                continue;
            }
            Map<String, Object> f = new LinkedHashMap<>();
            String existente = nombrePorEtiqueta.get(etiqueta);
            if (existente != null) {
                f.put("titulo", existente);
            } else {
                NombresProyecto.Partes partes = NombresProyecto.separarNuevo(etiqueta);
                boolean libre = !partes.numero().isEmpty() && !nombres.contains(partes.nombre()) && !usados.contains(partes.nombre())
                        && !nombrePorEtiqueta.containsKey(partes.nombre());
                if (libre) {
                    f.put("titulo", partes.nombre());
                    f.put("numero_proyecto", partes.numero());
                } else {
                    // Sin número, o el nombre ya lo tiene otro proyecto: se guarda tal cual.
                    f.put("titulo", etiqueta);
                }
            }
            if (!usados.add((String) f.get("titulo"))) {
                continue;
            }
            f.put("seq", (double) i);
            f.put("etiqueta", etiqueta);
            filas.add(f);
        }
        return filas;
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
            List<Map<String, Object>> proyectos = filasProyectos(catalogos.path("projects"));
            for (Map<String, Object> f : proyectos) {
                String etiqueta = (String) f.remove("etiqueta");
                if (conOrdenes) {
                    String orden = ordenes.path(etiqueta).asText("").trim();
                    f.put("orden_facturacion", orden.isEmpty() ? null : orden);
                }
            }
            resultado.set("projects", Reconciliador.reconciliar(bd, "proyecto", "titulo",
                    List.of("seq", "orden_facturacion", "numero_proyecto"), proyectos, true, "proyecto", actor, null).json());
            if (modoServidor) {
                // Timing R&T: las tareas del Excel con este proyecto (nombre y número) se vinculan solas.
                resultado.put("timingVinculadas", VinculacionTiming.vincular(bd));
            }

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
