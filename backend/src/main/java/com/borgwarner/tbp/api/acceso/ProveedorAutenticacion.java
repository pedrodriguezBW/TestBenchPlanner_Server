package com.borgwarner.tbp.api.acceso;

import com.borgwarner.tbp.infraestructura.postgresql.CuentasPostgres;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;

import java.io.Serializable;
import java.util.List;

/** Email y contraseña contra la tabla «credencial» (con bloqueo temporal). */
public class ProveedorAutenticacion implements AuthenticationProvider {

    /** Lo que el inicio de sesión necesita saber además de quién es. */
    public record DatosAcceso(boolean debeCambiar, int sello) implements Serializable {
    }

    private final CuentasPostgres cuentas;

    public ProveedorAutenticacion(CuentasPostgres cuentas) {
        this.cuentas = cuentas;
    }

    @Override
    public Authentication authenticate(Authentication peticion) throws AuthenticationException {
        String email = peticion.getName();
        String clave = peticion.getCredentials() == null ? "" : peticion.getCredentials().toString();
        CuentasPostgres.Acceso r = cuentas.autenticar(email, clave);
        switch (r.resultado()) {
            case BLOQUEADA -> throw new LockedException("Cuenta bloqueada temporalmente");
            case INCORRECTO -> throw new BadCredentialsException("Usuario o contraseña incorrectos");
            default -> {
                // sigue
            }
        }
        UsernamePasswordAuthenticationToken t = UsernamePasswordAuthenticationToken.authenticated(
                new CuentaAutenticada(r.email(), r.nombre()), null, List.of());
        t.setDetails(new DatosAcceso(r.debeCambiar(), r.sello()));
        return t;
    }

    @Override
    public boolean supports(Class<?> tipo) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(tipo);
    }
}
