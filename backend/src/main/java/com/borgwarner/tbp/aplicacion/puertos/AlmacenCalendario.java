package com.borgwarner.tbp.aplicacion.puertos;

import com.borgwarner.tbp.dominio.calendario.Calendario;

import java.time.LocalDate;

/**
 * Calendario de festivos. Con los almacenes local y SharePoint es DEL USUARIO
 * (de este PC), como en la 4.9.4, donde se guardaba en el navegador (D6). Con
 * PostgreSQL la aplicación es compartida y el calendario es COMÚN: lo ven
 * todos y solo lo cambia un Admin (D46).
 */
public interface AlmacenCalendario {

    /** Calendario guardado; si no hay ninguno, el de un PC nuevo para el año de `hoy`. */
    Calendario leer(LocalDate hoy);

    void guardar(Calendario calendario);

    /** true: un calendario para todos (solo Admin lo cambia); false: el de este PC. */
    default boolean compartido() {
        return false;
    }
}
