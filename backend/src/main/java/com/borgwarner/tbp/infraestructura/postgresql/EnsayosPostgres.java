package com.borgwarner.tbp.infraestructura.postgresql;

import com.borgwarner.tbp.aplicacion.puertos.AlmacenEnsayos;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenUsuarios;
import com.borgwarner.tbp.aplicacion.puertos.Colaboracion;
import com.borgwarner.tbp.aplicacion.puertos.ConflictoEdicion;
import com.borgwarner.tbp.dominio.planificacion.CambioFila;
import com.borgwarner.tbp.dominio.planificacion.FilaGuardada;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ensayos en la tabla «ensayo»: uid + DataJson íntegro + columnas relacionales
 * + version (concurrencia optimista).
 *
 * Guardado en UNA transacción:
 *  1. Se bloquean (SELECT … FOR UPDATE) todas las filas afectadas, en orden de
 *     uid para que dos guardados a la vez no se esperen mutuamente.
 *  2. Con comprobación de versión (interfaz nueva), si el DataJson actual de
 *     alguna fila no es el que vio el usuario, se lanza ConflictoEdicion y no se
 *     escribe NADA (mismo aviso que con SharePoint: 409). Dos altas simultáneas
 *     del mismo uid también se detectan (INSERT … ON CONFLICT DO NOTHING).
 *  3. Cada escritura suma 1 a version y deja antes/después en la auditoría.
 * Sin comprobación (interfaz clásica): «el último que guarda gana», como la 4.9.4.
 */
public class EnsayosPostgres implements AlmacenEnsayos {

    private final BaseDatos bd;
    private final Colaboracion colaboracion;

    public EnsayosPostgres(BaseDatos bd, Colaboracion colaboracion) {
        this.bd = bd;
        this.colaboracion = colaboracion;
    }

    @Override
    public List<FilaGuardada> leer() {
        return bd.jdbc().query("SELECT uid, data_json FROM ensayo ORDER BY orden, uid",
                (rs, i) -> new FilaGuardada(rs.getString(1), rs.getString(2)));
    }

    /** Fila actual: existe y su DataJson. */
    private record Actual(String texto) {
    }

    @Override
    public ResumenGuardado guardar(List<CambioFila> cambios, boolean comprobarVersion, Actor actor) {
        AlmacenUsuarios.Identidad quien = new AlmacenUsuarios.Identidad(actor == null ? null : actor.email(),
                actor == null ? null : actor.nombre());
        ResumenGuardado resumen = bd.enTransaccion(() -> {
            Map<String, Actual> actuales = new HashMap<>();
            for (String uid : cambios.stream().map(CambioFila::uid).distinct().sorted().toList()) {
                List<String> texto = bd.jdbc().query("SELECT data_json FROM ensayo WHERE uid = ? FOR UPDATE",
                        (rs, i) -> rs.getString(1), uid);
                if (!texto.isEmpty()) {
                    actuales.put(uid, new Actual(texto.get(0)));
                }
            }
            if (comprobarVersion) {
                List<ConflictoEdicion.FilaEnConflicto> conflictos = new ArrayList<>();
                for (CambioFila cambio : cambios) {
                    Actual actual = actuales.get(cambio.uid());
                    String texto = actual == null ? null : actual.texto();
                    boolean igual = cambio.textoAnterior() == null ? actual == null : cambio.textoAnterior().equals(texto);
                    if (!igual) {
                        conflictos.add(new ConflictoEdicion.FilaEnConflicto(cambio.uid(), texto));
                    }
                }
                if (!conflictos.isEmpty()) {
                    throw new ConflictoEdicion(conflictos);
                }
            }
            int creados = 0, actualizados = 0, borrados = 0;
            for (CambioFila cambio : cambios) {
                Actual actual = actuales.get(cambio.uid());
                if (cambio.esBorrado()) {
                    if (actual != null) {
                        bd.jdbc().update("DELETE FROM ensayo WHERE uid = ?", cambio.uid());
                        Auditoria.anotar(bd, quien, "ensayo", cambio.uid(), Auditoria.BAJA, actual.texto(), null);
                        actuales.remove(cambio.uid());
                        borrados++;
                    }
                } else if (actual != null) {
                    actualizar(cambio.uid(), actual.texto(), cambio.textoNuevo(), quien);
                    actuales.put(cambio.uid(), new Actual(cambio.textoNuevo()));
                    actualizados++;
                } else {
                    if (insertar(cambio.uid(), cambio.textoNuevo(), quien)) {
                        creados++;
                    } else {
                        // Otra persona acaba de crear el mismo ensayo.
                        String suyo = bd.jdbc().queryForObject("SELECT data_json FROM ensayo WHERE uid = ? FOR UPDATE",
                                String.class, cambio.uid());
                        if (comprobarVersion) {
                            throw new ConflictoEdicion(List.of(new ConflictoEdicion.FilaEnConflicto(cambio.uid(), suyo)));
                        }
                        actualizar(cambio.uid(), suyo, cambio.textoNuevo(), quien);
                        actualizados++;
                    }
                    actuales.put(cambio.uid(), new Actual(cambio.textoNuevo()));
                }
            }
            return new ResumenGuardado(creados, actualizados, borrados);
        });
        if (resumen.creados() + resumen.actualizados() + resumen.borrados() > 0) {
            colaboracion.anotarActividad(quien.email(), quien.nombre(), "edit", "created " + resumen.creados()
                    + ", updated " + resumen.actualizados() + ", deleted " + resumen.borrados());
        }
        return resumen;
    }

    private void actualizar(String uid, String antes, String texto, AlmacenUsuarios.Identidad quien) {
        if (texto != null && texto.equals(antes)) {
            return; // mismo contenido: no se toca la versión
        }
        List<Object> valores = new ArrayList<>();
        valores.add(texto);
        valores.addAll(java.util.Arrays.asList(ColumnasEnsayo.de(texto).valores()));
        valores.add(quien.email());
        valores.add(uid);
        bd.jdbc().update("UPDATE ensayo SET data_json = ?, version = version + 1, " + ColumnasEnsayo.ASIGNACIONES
                + ", actualizado_en = now(), actualizado_por = ? WHERE uid = ?", valores.toArray());
        Auditoria.anotar(bd, quien, "ensayo", uid, Auditoria.MODIFICACION, antes, texto);
    }

    /** false si ya existía (alta simultánea de otra persona). */
    private boolean insertar(String uid, String texto, AlmacenUsuarios.Identidad quien) {
        List<Object> valores = new ArrayList<>();
        valores.add(uid);
        valores.add(texto);
        valores.addAll(java.util.Arrays.asList(ColumnasEnsayo.de(texto).valores()));
        valores.add(quien.email());
        int filas = bd.jdbc().update("INSERT INTO ensayo (uid, data_json, " + ColumnasEnsayo.LISTA + ", actualizado_por) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (uid) DO NOTHING", valores.toArray());
        if (filas == 1) {
            Auditoria.anotar(bd, quien, "ensayo", uid, Auditoria.ALTA, null, texto);
            return true;
        }
        return false;
    }
}
