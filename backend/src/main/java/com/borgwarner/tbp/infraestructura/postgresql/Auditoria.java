package com.borgwarner.tbp.infraestructura.postgresql;

import com.borgwarner.tbp.aplicacion.puertos.AlmacenUsuarios;

/**
 * Tabla «auditoria»: antes y después de cada cambio, con quién y cuándo. Es el
 * registro técnico completo (el «Activity log» visible en la app sigue en
 * registro_actividad). Una baja guarda el valor anterior, así que cualquier
 * ensayo borrado se puede recuperar desde aquí.
 */
public final class Auditoria {

    public static final String ALTA = "ALTA";
    public static final String MODIFICACION = "MODIFICACION";
    public static final String BAJA = "BAJA";
    public static final String IMPORTACION = "IMPORTACION";

    private Auditoria() {
    }

    public static void anotar(BaseDatos bd, AlmacenUsuarios.Identidad actor, String entidad, String clave, String operacion,
                              String antes, String despues) {
        bd.jdbc().update("INSERT INTO auditoria (usuario_email, usuario_nombre, entidad, clave, operacion, antes, despues) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                actor == null ? null : actor.email(), actor == null ? null : actor.nombre(), entidad, clave, operacion, antes, despues);
    }
}
