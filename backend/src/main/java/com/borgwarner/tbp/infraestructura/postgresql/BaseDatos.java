package com.borgwarner.tbp.infraestructura.postgresql;

import com.borgwarner.tbp.aplicacion.puertos.AlmacenUsuarios;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import java.util.zip.CRC32;

/**
 * Acceso a la base de datos PostgreSQL compartido por todos los adaptadores:
 * consultas (JdbcTemplate), transacciones y cerrojos.
 *
 * Todo lo que lee-comprueba-escribe va en UNA transacción: o se guarda todo o
 * no se guarda nada (SharePoint no lo permitía). Las listas que se guardan por
 * reconciliación se serializan con un cerrojo de transacción (advisory lock)
 * por lista, de modo que dos guardados a la vez no se mezclan.
 */
public class BaseDatos {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transacciones;
    /** Quién hace el cambio (hasta el hito S3, la identidad configurada). */
    private volatile Supplier<AlmacenUsuarios.Identidad> identidad = () -> new AlmacenUsuarios.Identidad("", "");

    public BaseDatos(DataSource origen, PlatformTransactionManager gestor) {
        this.jdbc = new JdbcTemplate(origen);
        this.transacciones = new TransactionTemplate(gestor);
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    /** Ejecuta el bloque en una transacción (se une a la que haya en curso). */
    public <T> T enTransaccion(Supplier<T> bloque) {
        return transacciones.execute(estado -> bloque.get());
    }

    /** Cerrojo exclusivo hasta el final de la transacción en curso para un recurso con nombre. */
    public void bloquear(String recurso) {
        CRC32 crc = new CRC32();
        crc.update(("tbp:" + recurso).getBytes(StandardCharsets.UTF_8));
        jdbc.query("SELECT pg_advisory_xact_lock(?)", (RowCallbackHandler) rs -> {
        }, crc.getValue());
    }

    public void identidad(Supplier<AlmacenUsuarios.Identidad> proveedor) {
        this.identidad = proveedor;
    }

    /** Identidad de quien hace el cambio (para la auditoría). Nunca null. */
    public AlmacenUsuarios.Identidad actor() {
        try {
            AlmacenUsuarios.Identidad i = identidad.get();
            return i == null ? new AlmacenUsuarios.Identidad("", "") : i;
        } catch (RuntimeException e) {
            return new AlmacenUsuarios.Identidad("", "");
        }
    }
}
