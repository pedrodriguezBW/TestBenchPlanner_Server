package com.borgwarner.tbp.api.acceso;

import com.borgwarner.tbp.aplicacion.puertos.AlmacenUsuarios;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/** La persona que ha iniciado sesión en la petición en curso (modo servidor). */
public final class IdentidadSesion {

    private IdentidadSesion() {
    }

    public static Optional<CuentaAutenticada> cuenta() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.isAuthenticated() && a.getPrincipal() instanceof CuentaAutenticada c) {
            return Optional.of(c);
        }
        return Optional.empty();
    }

    /** Identidad para el almacén de usuarios y la auditoría; vacía si no hay sesión. */
    public static AlmacenUsuarios.Identidad identidad() {
        return cuenta().map(c -> new AlmacenUsuarios.Identidad(c.email(), c.nombre()))
                .orElseGet(() -> new AlmacenUsuarios.Identidad("", ""));
    }
}
