
package com.borgwarner.tbp.infraestructura.postgresql;

import com.borgwarner.tbp.aplicacion.SinPermiso;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.operaciones.ReglaNoCumplida;
import com.borgwarner.tbp.infraestructura.excel.ExcelTiming;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@ConditionalOnProperty(name = "planner.servidor.activo", havingValue = "true")
public class ImportadorTiming {

    private static final List<DateTimeFormatter> FORMATOS = List.of(DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.BASIC_ISO_DATE, formato("d/M/uuuu"), formato("d MMMM uuuu"));
    private final BaseDatos bd;

    public ImportadorTiming(BaseDatos bd) {
        this.bd = bd;
    }

    public ObjectNode importar(TimingPostgres.Acceso acceso, InputStream archivo, boolean simular) {
        if (!acceso.administra()) {
            throw new SinPermiso("Solo un Admin con acceso a Timing R&T puede importar el histórico.");
        }
        List<Map<String, String>> filas = ExcelTiming.leer(archivo);
        return bd.enTransaccion(() -> cargar(acceso, filas, simular));
    }

    private ObjectNode cargar(TimingPostgres.Acceso acceso, List<Map<String, String>> filas, boolean simular) {
        bd.bloquear("timing-importacion");
        Referencias referencias = new Referencias(bd);
        ObjectNode informe = JsonJs.objetoVacio().put("simulacion", simular).put("filas", filas.size());
        ArrayNode avisos = informe.putArray("avisos");
        int altas = 0;
        int cambios = 0;
        int existentes = 0;
        int conflictos = 0;
        int cerradas = 0;
        Set<Long> ids = new HashSet<>();
        Set<String> codigos = new HashSet<>();
        for (Map<String, String> fila : filas) {
            long id = id(fila);
            TimingPostgres.regla(ids.add(id), "El ID de origen " + id + " está repetido.");
            String codigo = valor(fila, "ID Tarea");
            if (codigo.isEmpty()) codigo = "ORIGEN-" + id;
            if (!codigos.add(codigo)) aviso(avisos, id, "Código de tarea repetido. Se conserva el ID de origen.");
            ObjectNode datos = convertir(fila, referencias, avisos, id);
            if ("cerrada".equals(datos.path("estado").asText())) cerradas++;
            int resultado = guardar(id, codigo, datos, simular);
            switch (resultado) {
                case 0 -> existentes++;
                case 1 -> altas++;
                case 2 -> cambios++;
                default -> {
                    conflictos++;
                    aviso(avisos, id, "Tarea editada en Timing R&T. Se conserva la versión de la aplicación.");
                }
            }
        }
        if (!simular) {
            referencias.guardarEquipos(bd);
            Auditoria.anotar(bd, bd.actor(), "timing_importacion", acceso.email(), Auditoria.ALTA, null,
                    altas + " altas · " + cambios + " cambios · " + conflictos + " conflictos");
        }
        return informe.put("altas", altas).put("cambios", cambios).put("sinCambios", existentes)
                .put("conflictos", conflictos).put("cerradas", cerradas);
    }

    /** La versión importada permite refrescar el Excel sin sobrescribir trabajo posterior hecho en la aplicación. */
    private int guardar(long id, String codigo, ObjectNode datos, boolean simular) {
        String json = datos.toString();
        List<Integer> estados = bd.jdbc().query("SELECT CASE WHEN version <> version_importada THEN 3 "
                        + "WHEN codigo = ? AND datos = ?::jsonb THEN 0 ELSE 2 END "
                        + "FROM timing_tarea WHERE origen_id = ?" + (simular ? "" : " FOR UPDATE"),
                (rs, n) -> rs.getInt(1), codigo, json, id);
        int estado = estados.isEmpty() ? 1 : estados.get(0);
        if (!simular && estado == 1) {
            bd.jdbc().update("INSERT INTO timing_tarea (origen_id, codigo, datos, version_importada) VALUES (?, ?, ?::jsonb, 1)",
                    id, codigo, json);
        } else if (!simular && estado == 2) {
            bd.jdbc().update("UPDATE timing_tarea SET codigo = ?, datos = ?::jsonb, version = version + 1, "
                    + "version_importada = version + 1 WHERE origen_id = ?", codigo, json, id);
        }
        return estado;
    }

    private static ObjectNode convertir(Map<String, String> fila, Referencias ref, ArrayNode avisos, long id) {
        String nombreResponsable = valor(fila, "Responsable");
        String nombreTecnico = valor(fila, "Asignado a");
        String responsable = ref.email(nombreResponsable);
        String tecnico = ref.email(nombreTecnico);
        String nombreProyecto = valor(fila, "Proyecto");
        String cuenta = valor(fila, "Cuenta Proyecto");
        Long proyecto = ref.proyecto(nombreProyecto, cuenta);
        String disp = clave(valor(fila, "Disp."));
        String banco = ref.equipo("banco", valor(fila, "Banco Ensayo"));
        String camara = ref.equipo("camara", valor(fila, "Camara_Climatica"));
        boolean cerrada = "terminado".equals(disp);
        boolean enProceso = "en proceso".equals(disp);
        ObjectNode datos = TimingPostgres.inicial(responsable).put("tecnico", tecnico)
                .put("responsableOrigen", nombreResponsable).put("tecnicoOrigen", nombreTecnico)
                .put("proyectoId", proyecto).put("proyecto", nombreProyecto).put("cuenta", cuenta)
                .put("tipo", valor(fila, "EGM/HEATERS")).put("descripcion", valor(fila, "Tarea"))
                .put("ruta", valor(fila, "Ruta")).put("otrosEquipos", valor(fila, "Otros equipos necesarios"))
                .put("piezasCodigo", valor(fila, "Códigos")).put("piezasCantidad", decimal(fila, "Nº Piezas"))
                .put("banco", banco).put("camara", camara).put("prioridad", decimal(fila, "Prio"))
                .put("horasEstimadas", decimal(fila, "Tiempo estimado de tarea (h)"))
                .put("fechaCierre", fecha(fila, "Fecha Fin", id, avisos))
                .put("fechaEnsayo", fecha(fila, "Fecha programada del ensayo", id, avisos));
        ObjectNode legado = datos.putObject("legado");
        fila.forEach(legado::put);
        if (!nombreResponsable.isEmpty() && responsable.isEmpty()) aviso(avisos, id, "Responsable sin correspondencia.");
        if (!nombreTecnico.isEmpty() && tecnico.isEmpty()) aviso(avisos, id, "Técnico sin correspondencia.");
        if (!nombreProyecto.isEmpty() && proyecto == null) aviso(avisos, id, "Proyecto o cuenta sin correspondencia.");
        if (cerrada) {
            datos.put("estado", "cerrada");
        } else {
            List<String> faltan = TimingPostgres.faltantes(datos);
            if (!faltan.isEmpty()) aviso(avisos, id, "Campos pendientes: " + String.join(", ", faltan) + ".");
            datos.put("disponible", "si".equals(disp) || enProceso).put("estado", enProceso ? "en-proceso" : "pendiente");
        }
        return datos;
    }

    private static long id(Map<String, String> fila) {
        try {
            long id = Long.parseLong(valor(fila, "ID"));
            if (id > 0) return id;
        } catch (NumberFormatException e) {
            // El historial se identifica por el ID numérico suministrado en la exportación.
        }
        throw new ReglaNoCumplida("Hay una fila sin un ID de origen numérico válido.");
    }

    private static void aviso(ArrayNode avisos, long id, String mensaje) {
        avisos.addObject().put("id", id).put("mensaje", mensaje);
    }

    private static String valor(Map<String, String> fila, String campo) {
        return fila.getOrDefault(campo, "").strip();
    }

    private static String fecha(Map<String, String> fila, String campo, long id, ArrayNode avisos) {
        String bruto = valor(fila, campo);
        if (bruto.isEmpty()) return null;
        String valor = bruto.toLowerCase(Locale.ROOT).replace(" de ", " ").replaceAll("\\s+", " ");
        for (DateTimeFormatter formato : FORMATOS) {
            try {
                return LocalDate.parse(valor, formato).toString();
            } catch (DateTimeParseException ignored) {
                // Los formatos de las exportaciones antiguas varían de una fila a otra.
            }
        }
        aviso(avisos, id, "Fecha sin interpretar en " + campo + ".");
        return null;
    }

    private static DateTimeFormatter formato(String patron) {
        return DateTimeFormatter.ofPattern(patron, Locale.forLanguageTag("es-ES")).withResolverStyle(ResolverStyle.STRICT);
    }

    private static BigDecimal decimal(Map<String, String> fila, String campo) {
        try {
            return new BigDecimal(valor(fila, campo).replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String clave(String valor) {
        return Normalizer.normalize(valor.strip(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private record Proyecto(long id, String nombre, String cuenta) {
    }

    private static final class Referencias {
        private final Map<String, Set<String>> usuarios = new HashMap<>();
        private final Map<String, List<Proyecto>> porCuenta = new HashMap<>();
        private final Map<String, List<Proyecto>> porTitulo = new HashMap<>();
        private final Map<String, String> equipos = new HashMap<>();
        private final Map<String, Set<String>> nuevos = new HashMap<>();

        private Referencias(BaseDatos bd) {
            bd.jdbc().query("SELECT email, titulo, nombre_responsable FROM usuario", (ResultSet rs) -> {
                String email = rs.getString(1);
                for (String alias : List.of(email, rs.getString(2), rs.getString(3) == null ? "" : rs.getString(3))) {
                    if (!alias.isBlank()) usuarios.computeIfAbsent(clave(alias),
                            k -> new LinkedHashSet<>()).add(email);
                }
            });
            bd.jdbc().query("SELECT id, titulo, coalesce(numero_proyecto, '') FROM proyecto", (ResultSet rs) -> {
                Proyecto p = new Proyecto(rs.getLong(1), rs.getString(2), rs.getString(3));
                porTitulo.computeIfAbsent(clave(p.nombre()), k -> new ArrayList<>()).add(p);
                if (!p.cuenta().isBlank()) porCuenta.computeIfAbsent(p.cuenta(), k -> new ArrayList<>()).add(p);
            });
            bd.jdbc().query("SELECT clase, nombre FROM timing_equipo", (ResultSet rs) -> {
                equipos.put(rs.getString(1) + "/" + clave(rs.getString(2)), rs.getString(2));
            });
        }

        private String email(String nombre) {
            Set<String> coincidencias = usuarios.getOrDefault(clave(nombre), Set.of());
            return coincidencias.size() == 1 ? coincidencias.iterator().next() : "";
        }

        private Long proyecto(String nombre, String cuenta) {
            List<Proyecto> titulos = porTitulo.getOrDefault(clave(nombre), List.of());
            List<Proyecto> exactos = titulos.stream().filter(p -> p.cuenta().equals(cuenta)).toList();
            if (exactos.size() == 1) return exactos.get(0).id();
            List<Proyecto> coincidencias = porCuenta.getOrDefault(cuenta, List.of());
            return titulos.isEmpty() && coincidencias.size() == 1 ? coincidencias.get(0).id() : null;
        }

        private String equipo(String clase, String nombre) {
            if (nombre.isEmpty() || "NA".equalsIgnoreCase(nombre)) return "";
            return equipos.computeIfAbsent(clase + "/" + clave(nombre), k -> {
                nuevos.computeIfAbsent(clase, c -> new LinkedHashSet<>()).add(nombre);
                return nombre;
            });
        }

        private void guardarEquipos(BaseDatos bd) {
            nuevos.forEach((clase, nombres) -> nombres.forEach(nombre -> bd.jdbc().update(
                    "INSERT INTO timing_equipo (clase, nombre) VALUES (?, ?) ON CONFLICT DO NOTHING", clase, nombre)));
        }
    }
}
