package com.borgwarner.tbp.api.acceso;

import java.io.Serializable;

/**
 * Quién ha iniciado sesión (modo servidor, hito S3): email de Users/Rights
 * Management y su nombre (Title). El rol NO va aquí: se lee de la lista en cada
 * petición, así un cambio de rol se aplica en el acto.
 */
public record CuentaAutenticada(String email, String nombre) implements Serializable {

    @Override
    public String toString() {
        return email; // lo que Spring Security usa como nombre de la sesión
    }
}
