package com.borgwarner.tbp.aplicacion.puertos;

import com.borgwarner.tbp.dominio.operaciones.ReglaNoCumplida;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Base de aprobaciones de servicios. Con SharePoint (y en el modo local) es de
 * SOLO LECTURA (otro sitio de SharePoint). Con PostgreSQL es una tabla propia
 * que un Admin gestiona desde la aplicación (plan del servidor §9.2, D54).
 */
public interface ConsultaAprobaciones {

    String NO_EDITABLE = "La base de aprobaciones solo se puede editar con la base de datos del servidor (PostgreSQL).";

    /** Todas: [{id, status, color, raw, requester, service}] de la más reciente a la más antigua. */
    ArrayNode todas();

    /** Estado de cada Approval ID pedido: {id: {input, resolvedId, status, color, raw…}}. */
    ObjectNode estados(List<JsonNode> ids);

    /** true si las aprobaciones se gestionan desde la aplicación (PostgreSQL). */
    default boolean editable() {
        return false;
    }

    /**
     * Alta: {id?, raw, requester, service}. Sin id se usa el siguiente libre.
     * Devuelve la aprobación guardada con el formato de {@link #todas()}.
     */
    default ObjectNode crear(JsonNode datos) {
        throw new ReglaNoCumplida(NO_EDITABLE);
    }

    /**
     * Cambio: {raw, requester, service, anterior: {raw, requester, service}}. Si
     * «anterior» no coincide con lo guardado, otra persona la ha cambiado: se rechaza.
     */
    default ObjectNode modificar(int id, JsonNode datos) {
        throw new ReglaNoCumplida(NO_EDITABLE);
    }

    /** Baja (con la misma comprobación de «anterior» que el cambio). */
    default void borrar(int id, JsonNode anterior) {
        throw new ReglaNoCumplida(NO_EDITABLE);
    }
}
