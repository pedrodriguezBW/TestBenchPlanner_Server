package com.borgwarner.tbp.configuracion;

import com.borgwarner.tbp.api.acceso.FiltroOrigen;
import com.borgwarner.tbp.api.acceso.FiltroSesion;
import com.borgwarner.tbp.api.acceso.ManejadoresAcceso;
import com.borgwarner.tbp.api.acceso.ProveedorAutenticacion;
import com.borgwarner.tbp.api.v1.SaludController;
import com.borgwarner.tbp.aplicacion.puertos.Colaboracion;
import com.borgwarner.tbp.infraestructura.postgresql.CuentasPostgres;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.logout.LogoutFilter;
import org.springframework.security.web.firewall.StrictHttpFirewall;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

import java.time.Clock;

/**
 * Inicio de sesión (hito S3).
 *
 *   - Fuera del modo servidor (cada PC con su backend, local / SharePoint /
 *     PostgreSQL de pruebas) NO hay inicio de sesión: la cadena de seguridad no
 *     se aplica a ninguna petición y todo funciona como antes.
 *   - En el modo servidor (planner.servidor.activo=true) todo exige sesión
 *     salvo el formulario de entrada y los avisos de pestaña de la interfaz.
 *     Usuario = email de Users/Rights Management; contraseña propia de la
 *     aplicación (BCrypt); el rol se sigue leyendo de la lista en cada petición.
 */
@Configuration
public class ConfiguracionSeguridad {

    /**
     * Spring Security revisa TODAS las rutas antes que nada (también fuera del
     * modo servidor). Los nombres de banco, recurso o proyecto viajan en la ruta
     * (encodeURIComponent): se admiten «%», «/», «;», «\» y «.» codificados, como
     * antes de añadir Spring Security, para no rechazar un nombre con 400.
     */
    @Bean
    public WebSecurityCustomizer cortafuegosDeRutas() {
        StrictHttpFirewall cortafuegos = new StrictHttpFirewall();
        cortafuegos.setAllowUrlEncodedPercent(true);
        cortafuegos.setAllowUrlEncodedSlash(true);
        cortafuegos.setAllowSemicolon(true);
        cortafuegos.setAllowBackSlash(true);
        cortafuegos.setAllowUrlEncodedPeriod(true);
        return web -> web.httpFirewall(cortafuegos);
    }

    /** Sin modo servidor: ninguna petición pasa por la seguridad. */
    @Bean
    @ConditionalOnProperty(prefix = "planner.servidor", name = "activo", havingValue = "false", matchIfMissing = true)
    public SecurityFilterChain sinInicioDeSesion(HttpSecurity http) throws Exception {
        http.securityMatcher(peticion -> false);
        return http.build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "planner.servidor", name = "activo", havingValue = "true")
    public SecurityFilterChain conInicioDeSesion(HttpSecurity http, CuentasPostgres cuentas, Colaboracion colaboracion,
                                                 PropiedadesAutenticacion autenticacion) throws Exception {
        ManejadoresAcceso manejadores = new ManejadoresAcceso(colaboracion);
        http
                // CSRF: cookie SameSite=Lax + FiltroOrigen (la API es JSON y la usa solo la propia interfaz).
                .csrf(c -> c.disable())
                .headers(h -> h.frameOptions(f -> f.sameOrigin()))
                .authenticationProvider(new ProveedorAutenticacion(cuentas))
                .authorizeHttpRequests(a -> a
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers("/login", "/favicon.ico", "/logo.png", "/error").permitAll()
                        // La interfaz los manda también al cerrar la pestaña; en el servidor no apagan nada.
                        .requestMatchers("/api/heartbeat", "/api/shutdown").permitAll()
                        // Salud (hito S4): la consultan los scripts del servidor sin cuenta; no da datos.
                        .requestMatchers(SaludController.RUTA).permitAll()
                        .anyRequest().authenticated())
                .formLogin(f -> f.loginPage("/login").loginProcessingUrl("/login")
                        .usernameParameter("usuario").passwordParameter("clave")
                        .successHandler(manejadores::exito).failureHandler(manejadores::fallo).permitAll())
                .logout(l -> l.logoutRequestMatcher(new AntPathRequestMatcher("/logout", "POST"))
                        .addLogoutHandler(manejadores::salida).logoutSuccessUrl("/login?salida").permitAll())
                .exceptionHandling(e -> e.authenticationEntryPoint(manejadores::sinSesion))
                .requestCache(r -> r.disable())
                .sessionManagement(s -> s.sessionFixation(f -> f.changeSessionId()))
                .addFilterBefore(new FiltroOrigen(), LogoutFilter.class)
                .addFilterAfter(new FiltroSesion(cuentas, autenticacion.inactividad(), Clock.systemUTC()), AuthorizationFilter.class);
        return http.build();
    }
}
