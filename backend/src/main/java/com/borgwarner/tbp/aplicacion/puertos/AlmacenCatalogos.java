package com.borgwarner.tbp.aplicacion.puertos;

import com.borgwarner.tbp.dominio.operaciones.ReglaNoCumplida;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Catálogos compartidos: bancos (TestBenchBenches), proyectos (TestBenchProjects)
 * y responsables (TestBenchResponsibles, congelada desde la v3.7).
 *
 * Formato de intercambio: el de /api/load_config de la 4.9.4
 * {responsibles:[…], projects:[…], benches:[{name, groupKey, groupName, dot, org, seq}]}
 * más, para la facturación, projectOrders {«título del proyecto»: «orden(es)»}
 * (columna BillingOrder) y benches[].equipmentType (columna EquipmentType).
 * Si al guardar no llegan (interfaz clásica), se conservan las guardadas.
 *
 * Áreas de bancos (D56): «areaNames» {código: nombre} y, solo con PostgreSQL y
 * cuando se han gestionado desde la aplicación, «areas» [{code, organization}]
 * (código y organización fija o null = banco a banco, en su orden). Sin
 * «areas» son las tres de serie.
 */
public interface AlmacenCatalogos {

    String AREAS_NO_EDITABLES = "Las áreas solo se pueden gestionar con la base de datos del servidor (PostgreSQL).";

    ObjectNode leer();

    /**
     * Reconcilia las listas con lo recibido (lo que no viene se manda a la
     * papelera). Responsables solo se toca si viene una lista no vacía.
     */
    ObjectNode guardar(JsonNode catalogos);

    default void guardarNumeroProyecto(String proyecto, String numero, String anterior) {
        throw new ReglaNoCumplida("Project Number se gestiona con PostgreSQL.");
    }

    /** true si las áreas se gestionan desde la aplicación (PostgreSQL, D56). */
    default boolean areasEditables() {
        return false;
    }

    /**
     * Alta de un área: {name, organization} (organization vacía o null = banco a
     * banco; si no, la organización fija de todos sus bancos). El código se
     * forma con el nombre. Devuelve el área: {code, name, organization, order}.
     */
    default ObjectNode crearArea(JsonNode datos) {
        throw new ReglaNoCumplida(AREAS_NO_EDITABLES);
    }

    /**
     * Cambio de nombre y, si llega «order» (posición desde 0), de posición:
     * {name, order?, anterior: {name}}. Si «anterior» no coincide con lo
     * guardado, otra persona la ha cambiado: se rechaza.
     */
    default ObjectNode modificarArea(String codigo, JsonNode datos) {
        throw new ReglaNoCumplida(AREAS_NO_EDITABLES);
    }

    /** Baja de un área sin bancos (con la misma comprobación de «anterior» que el cambio). */
    default void borrarArea(String codigo, JsonNode anterior) {
        throw new ReglaNoCumplida(AREAS_NO_EDITABLES);
    }
}
