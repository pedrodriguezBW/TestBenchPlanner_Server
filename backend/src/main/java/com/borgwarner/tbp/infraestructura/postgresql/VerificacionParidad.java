package com.borgwarner.tbp.infraestructura.postgresql;

import com.borgwarner.tbp.aplicacion.puertos.AlmacenCatalogos;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenEnsayos;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenHistoricoKpi;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenRecursos;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenTiposEquipo;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenUsuarios;
import com.borgwarner.tbp.aplicacion.puertos.Colaboracion;
import com.borgwarner.tbp.aplicacion.puertos.ConsultaAprobaciones;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.facturacion.TipoEquipo;
import com.borgwarner.tbp.dominio.planificacion.Areas;
import com.borgwarner.tbp.dominio.planificacion.FilaGuardada;
import com.borgwarner.tbp.infraestructura.local.AlmacenEnsayosLocal;
import com.borgwarner.tbp.infraestructura.local.AlmacenesAuxiliaresLocales;
import com.borgwarner.tbp.infraestructura.local.DatosLocales;
import com.borgwarner.tbp.infraestructura.migracion.CarpetaExportacion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Paridad exportación ↔ base de datos, en la frontera de los almacenes: lo que
 * devuelven los adaptadores de PostgreSQL tiene que ser lo mismo que devuelve
 * el modo local leyendo la carpeta exportada (= lo que había en SharePoint al
 * exportar). Todo lo que la aplicación hace por encima de los almacenes es el
 * mismo código en los tres modos, así que si los almacenes devuelven lo mismo,
 * la aplicación también.
 *
 * Se comparan formas CANÓNICAS para no dar por diferente lo que es igual:
 * números como número (1 = 1.0), lista sin claves repetidas (la base no las
 * admite: el importador avisa), área vacía o «Thermal» = Bancos Térmicos,
 * título de usuario vacío = su email.
 */
public class VerificacionParidad {

    private static final int MAX_DIFERENCIAS = 30;

    private final AlmacenEnsayos ensayos;
    private final AlmacenCatalogos catalogos;
    private final AlmacenUsuarios usuarios;
    private final AlmacenRecursos recursos;
    private final AlmacenHistoricoKpi kpi;
    private final ConsultaAprobaciones aprobaciones;
    private final AlmacenTiposEquipo tipos;
    private final Colaboracion colaboracion;

    public VerificacionParidad(AlmacenEnsayos ensayos, AlmacenCatalogos catalogos, AlmacenUsuarios usuarios,
                               AlmacenRecursos recursos, AlmacenHistoricoKpi kpi, ConsultaAprobaciones aprobaciones,
                               AlmacenTiposEquipo tipos, Colaboracion colaboracion) {
        this.ensayos = ensayos;
        this.catalogos = catalogos;
        this.usuarios = usuarios;
        this.recursos = recursos;
        this.kpi = kpi;
        this.aprobaciones = aprobaciones;
        this.tipos = tipos;
        this.colaboracion = colaboracion;
    }

    /** Diferencias entre la carpeta (leída por el modo local) y la base; vacía = paridad. */
    public List<String> comparar(Path carpeta) {
        Path temporal;
        try {
            temporal = Files.createTempDirectory("tbp-verificacion");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            // El modo local trabaja sobre una copia: la carpeta exportada no se toca.
            DatosLocales datos = new DatosLocales(temporal, carpeta, true);
            AlmacenesAuxiliaresLocales.ColaboracionLocal colLocal = new AlmacenesAuxiliaresLocales.ColaboracionLocal(datos);
            List<String> d = new ArrayList<>();
            comparar(d, "ensayos", ensayosCanonicos(new AlmacenEnsayosLocal(datos, colLocal).leer()), ensayosCanonicos(ensayos.leer()));
            comparar(d, "catálogos", catalogosCanonicos(new AlmacenesAuxiliaresLocales.Catalogos(datos, true).leer()),
                    catalogosCanonicos(catalogos.leer()));
            comparar(d, "usuarios", usuariosCanonicos(new AlmacenesAuxiliaresLocales.Usuarios(datos,
                    new AlmacenUsuarios.Identidad("", ""), true).leer()), usuariosCanonicos(usuarios.leer()));
            comparar(d, "recursos", recursosCanonicos(new AlmacenesAuxiliaresLocales.Recursos(datos, colLocal, true).leer()),
                    recursosCanonicos(recursos.leer()));
            comparar(d, "kpi", canonico(JsonJs.mapper().valueToTree(new AlmacenesAuxiliaresLocales.HistoricoKpi(datos).leer())),
                    canonico(JsonJs.mapper().valueToTree(kpi.leer())));
            comparar(d, "aprobaciones", aprobacionesCanonicas(new AlmacenesAuxiliaresLocales.Aprobaciones(datos).todas()),
                    aprobacionesCanonicas(aprobaciones.todas()));
            comparar(d, "tipos de equipo", tiposCanonicos(new AlmacenesAuxiliaresLocales.TiposEquipo(datos).leer()),
                    tiposCanonicos(tipos.leer()));
            String vLocal = colLocal.versionPublicada();
            String vBase = colaboracion.versionPublicada();
            if (!(vLocal == null ? "" : vLocal.trim()).equals(vBase == null ? "" : vBase.trim())) {
                d.add("versión publicada: exportación «" + vLocal + "», base «" + vBase + "»");
            }
            actividad(d, new CarpetaExportacion(carpeta).leer(CarpetaExportacion.ACTIVIDAD, JsonJs.mapper().createArrayNode()));
            return d.size() > MAX_DIFERENCIAS ? d.subList(0, MAX_DIFERENCIAS) : d;
        } finally {
            borrar(temporal);
        }
    }

    private static void borrar(Path carpeta) {
        try (Stream<Path> rutas = Files.walk(carpeta)) {
            rutas.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    // temporal: no importa
                }
            });
        } catch (IOException e) {
            // temporal: no importa
        }
    }

    // ---- comparación ----------------------------------------------------------------

    private static void comparar(List<String> diferencias, String que, JsonNode exportacion, JsonNode base) {
        if (exportacion.equals(base)) {
            return;
        }
        if (exportacion.isArray() && base.isArray()) {
            if (exportacion.size() != base.size()) {
                diferencias.add(que + ": " + exportacion.size() + " en la exportación y " + base.size() + " en la base");
            }
            for (int i = 0; i < Math.min(exportacion.size(), base.size()); i++) {
                if (!exportacion.get(i).equals(base.get(i))) {
                    diferencias.add(que + " [" + (i + 1) + "]: exportación " + recortar(exportacion.get(i)) + " · base " + recortar(base.get(i)));
                    return;
                }
            }
            return;
        }
        Iterator<String> campos = exportacion.fieldNames();
        Set<String> todos = new LinkedHashSet<>();
        campos.forEachRemaining(todos::add);
        base.fieldNames().forEachRemaining(todos::add);
        for (String campo : todos) {
            if (!exportacion.path(campo).equals(base.path(campo))) {
                diferencias.add(que + "." + campo + ": exportación " + recortar(exportacion.path(campo)) + " · base " + recortar(base.path(campo)));
            }
        }
    }

    private static String recortar(JsonNode n) {
        String t = JsonJs.escribir(n);
        return t.length() > 300 ? t.substring(0, 300) + "…" : t;
    }

    /** Copia con todos los números como DoubleNode (1 = 1.0) para comparar. */
    public static JsonNode canonico(JsonNode n) {
        if (n == null || n.isMissingNode()) {
            return NullNode.getInstance();
        }
        if (n.isNumber()) {
            return DoubleNode.valueOf(n.doubleValue());
        }
        if (n.isArray()) {
            ArrayNode a = JsonJs.mapper().createArrayNode();
            n.forEach(x -> a.add(canonico(x)));
            return a;
        }
        if (n.isObject()) {
            ObjectNode o = JsonJs.objetoVacio();
            n.fields().forEachRemaining(e -> o.set(e.getKey(), canonico(e.getValue())));
            return o;
        }
        return n;
    }

    private static JsonNode texto(JsonNode v) {
        return v == null || v.isNull() || v.isMissingNode() ? NullNode.getInstance() : TextNode.valueOf(ValoresJson.texto(v));
    }

    private static JsonNode numero(JsonNode v) {
        Double d = ValoresJson.numero(v);
        return d == null ? NullNode.getInstance() : DoubleNode.valueOf(d);
    }

    // ---- formas canónicas -----------------------------------------------------------------

    static ArrayNode ensayosCanonicos(List<FilaGuardada> filas) {
        ArrayNode a = JsonJs.mapper().createArrayNode();
        Set<String> vistos = new HashSet<>();
        for (FilaGuardada f : filas) {
            if (f.uid() != null && !f.uid().isEmpty() && vistos.add(f.uid())) {
                a.addObject().put("uid", f.uid()).put("data", f.texto());
            }
        }
        return a;
    }

    static ObjectNode catalogosCanonicos(ObjectNode c) {
        ObjectNode o = JsonJs.objetoVacio();
        o.set("responsibles", titulosSinRepetir(c.path("responsibles")));
        ArrayNode proyectos = titulosSinRepetir(c.path("projects"));
        o.set("projects", proyectos);
        ObjectNode ordenes = o.putObject("projectOrders");
        for (JsonNode p : proyectos) {
            String orden = c.path("projectOrders").path(p.asText()).asText("").trim();
            if (!orden.isEmpty()) {
                ordenes.put(p.asText(), orden);
            }
        }
        ArrayNode bancos = o.putArray("benches");
        Set<String> vistos = new HashSet<>();
        for (JsonNode b : c.path("benches")) {
            String nombre = b.path("name").asText("");
            if (nombre.isEmpty() || !vistos.add(nombre)) {
                continue;
            }
            ObjectNode x = bancos.addObject();
            x.put("name", nombre);
            x.set("groupKey", texto(b.get("groupKey")));
            x.set("groupName", texto(b.get("groupName")));
            x.set("dot", texto(b.get("dot")));
            x.set("org", texto(b.get("org")));
            x.set("seq", numero(b.get("seq")));
            x.put("equipmentType", b.path("equipmentType").asText("").trim());
            String area = b.path("area").asText("").trim();
            x.put("area", area.isEmpty() ? "" : CatalogosPostgres.valorArea(b));
        }
        ObjectNode nombres = o.putObject("areaNames");
        for (String codigo : Areas.CODIGOS) {
            String n = c.path("areaNames").path(codigo).asText("").trim();
            if (!n.isEmpty()) {
                nombres.put(codigo, n);
            }
        }
        return o;
    }

    private static ArrayNode titulosSinRepetir(JsonNode lista) {
        ArrayNode a = JsonJs.mapper().createArrayNode();
        Set<String> vistos = new HashSet<>();
        for (JsonNode n : lista) {
            String t = n.asText("");
            if (!t.isEmpty() && vistos.add(t)) {
                a.add(t);
            }
        }
        return a;
    }

    static ArrayNode usuariosCanonicos(ArrayNode lista) {
        ArrayNode a = JsonJs.mapper().createArrayNode();
        Set<String> vistos = new HashSet<>();
        for (JsonNode u : lista) {
            String email = u.path("email").asText("").trim().toLowerCase();
            if (email.isEmpty() || !vistos.add(email)) {
                continue;
            }
            String titulo = u.path("title").asText("");
            a.addObject().put("title", titulo.isEmpty() ? email : titulo).put("email", email)
                    .put("role", u.path("role").asText("")).put("responsibleName", u.path("responsibleName").asText(""));
        }
        return a;
    }

    static ObjectNode recursosCanonicos(ObjectNode r) {
        ObjectNode o = JsonJs.objetoVacio();
        ArrayNode lista = o.putArray("resources");
        Set<String> vistos = new HashSet<>();
        int i = 0;
        for (JsonNode x : r.path("resources")) {
            i++;
            String nombre = x.path("name").asText("");
            if (nombre.isEmpty() || !vistos.add(nombre)) {
                continue;
            }
            ObjectNode y = lista.addObject();
            y.put("name", nombre);
            y.set("groupKey", texto(x.get("groupKey")));
            y.set("groupName", texto(x.get("groupName")));
            y.set("dot", texto(x.get("dot")));
            y.set("code", texto(x.get("code")));
            y.set("org", texto(x.get("org")));
            y.set("seq", numero(x.get("seq")));
        }
        ArrayNode bloqueos = o.putArray("blocks");
        Set<Long> ids = new HashSet<>();
        for (JsonNode b : r.path("blocks")) {
            long id = b.path("id").asLong(0);
            if (id <= 0 || !ids.add(id)) {
                continue;
            }
            ObjectNode y = bloqueos.addObject();
            y.put("id", (double) id);
            for (String campo : List.of("title", "resource", "reason", "note", "author")) {
                y.set(campo, texto(b.get(campo)));
            }
            for (String campo : List.of("start", "end")) {
                String f = b.path(campo).asText("");
                y.put(campo, f.length() > 10 ? f.substring(0, 10) : f);
            }
        }
        return o;
    }

    static ArrayNode aprobacionesCanonicas(ArrayNode lista) {
        ArrayNode a = JsonJs.mapper().createArrayNode();
        Set<Integer> vistos = new HashSet<>();
        for (JsonNode x : lista) {
            if (!x.path("id").canConvertToInt() || x.path("id").asInt(0) <= 0 || !vistos.add(x.path("id").asInt())) {
                continue;
            }
            ObjectNode y = a.addObject();
            y.put("id", x.path("id").asInt());
            for (String campo : List.of("status", "color", "raw", "requester", "service")) {
                y.set(campo, texto(x.get(campo)));
            }
        }
        // La base las devuelve de la más reciente a la más antigua (Id descendente), como SharePoint.
        List<JsonNode> orden = new ArrayList<>();
        a.forEach(orden::add);
        orden.sort(Comparator.comparingInt((JsonNode x) -> x.path("id").asInt()).reversed());
        ArrayNode salida = JsonJs.mapper().createArrayNode();
        orden.forEach(salida::add);
        return salida;
    }

    static ArrayNode tiposCanonicos(List<TipoEquipo> lista) {
        ArrayNode a = JsonJs.mapper().createArrayNode();
        Set<String> vistos = new HashSet<>();
        for (TipoEquipo t : lista) {
            if (!t.titulo().isBlank() && vistos.add(t.clave())) {
                a.add(canonico(t.json()));
            }
        }
        return a;
    }

    /** Cada fila del registro exportado tiene que estar en la base (la base puede tener más). */
    private void actividad(List<String> d, JsonNode exportadas) {
        if (exportadas.size() == 0) {
            return;
        }
        Map<String, Integer> enBase = new java.util.HashMap<>();
        for (JsonNode f : colaboracion.leerActividad(Integer.MAX_VALUE).path("rows")) {
            enBase.merge(claveActividad(f), 1, Integer::sum);
        }
        int faltan = 0;
        for (JsonNode f : exportadas) {
            String k = claveActividad(f);
            Integer n = enBase.get(k);
            if (n == null || n == 0) {
                faltan++;
            } else {
                enBase.put(k, n - 1);
            }
        }
        if (faltan > 0) {
            d.add("registro de actividad: faltan " + faltan + " de " + exportadas.size() + " filas exportadas");
        }
    }

    private static String claveActividad(JsonNode f) {
        return String.join("\u001f", f.path("when").asText(""), f.path("email").asText(""), f.path("name").asText(""),
                f.path("action").asText(""), f.path("detail").asText(""));
    }
}
