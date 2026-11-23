package com.borgwarner.tbp.aplicacion;

import com.borgwarner.tbp.aplicacion.puertos.AlmacenUsuarios;
import com.borgwarner.tbp.aplicacion.puertos.Colaboracion;
import com.borgwarner.tbp.dominio.usuarios.Usuario;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.stereotype.Service;

/**
 * Quién usa la aplicación y con qué rol (whoami de la 4.9.4).
 *
 * El rol sale de la lista de usuarios buscando por email; quien no esté es de
 * solo lectura. Cuando el backend corre en el PC de cada usuario, la identidad
 * se resuelve una vez y se reutiliza. En el modo servidor (hito S3) cada
 * petición es de la persona que ha iniciado sesión: se resuelve en cada
 * petición (así un cambio de rol se aplica en el acto) y no se guarda nada.
 */
@Service
public class ServicioSesion {

    private final AlmacenUsuarios usuarios;
    private final Colaboracion colaboracion;
    private final RegistroLecturas lecturas;
    private final EstadoConexion conexion;
    private volatile Usuario actual;
    private volatile ArrayNode ultimaLista;

    public ServicioSesion(AlmacenUsuarios usuarios, Colaboracion colaboracion, RegistroLecturas lecturas, EstadoConexion conexion) {
        this.usuarios = usuarios;
        this.colaboracion = colaboracion;
        this.lecturas = lecturas;
        this.conexion = conexion;
    }

    /** Usuario actual; la primera vez lo identifica y anota la conexión en el registro. */
    public Usuario usuarioActual() {
        if (usuarios.identidadPorPeticion()) {
            return resolver(false);
        }
        Usuario usuario = actual;
        if (usuario == null) {
            usuario = identificar();
        }
        return usuario;
    }

    /** Vuelve a leer identidad y roles (whoami). */
    public Usuario identificar() {
        if (usuarios.identidadPorPeticion()) {
            return resolver(false);
        }
        synchronized (this) {
            return resolver(true);
        }
    }

    /** guardar=false: modo servidor (no se recuerda al usuario ni se anota «connect»; lo hace el inicio de sesión). */
    private Usuario resolver(boolean guardar) {
        AlmacenUsuarios.Identidad identidad = usuarios.identidadActual();
        String email = identidad.email() == null ? "" : identidad.email().toLowerCase();
        ArrayNode lista;
        try {
            lista = usuarios.leer();
        } catch (PeticionCancelada cancelada) {
            throw cancelada; // la pestaña se ha cerrado: no cuenta como lectura fallida (D11)
        } catch (RuntimeException e) {
            lecturas.anotar(RegistroLecturas.USUARIOS, false, 0);
            throw e;
        }
        // Una lista vacía (lectura fallida en SharePoint) no habilita guardar usuarios (D11).
        lecturas.anotar(RegistroLecturas.USUARIOS, true, lista.size());
        JsonNode encontrado = null;
        for (JsonNode u : lista) {
            String correo = u.path("email").asText("").trim().toLowerCase();
            if (!correo.isEmpty() && correo.equals(email)) {
                encontrado = u;
                break;
            }
        }
        String rol = encontrado != null ? Usuario.normalizarRol(encontrado.path("role").asText("")) : Usuario.SOLO_LECTURA;
        // Sin conexión (copia de este PC) se entra en solo lectura, como la 4.9.4 (H-04).
        if (conexion.sinConexion()) {
            rol = Usuario.SOLO_LECTURA;
        }
        String responsable = encontrado != null ? encontrado.path("responsibleName").asText("") : "";
        String nombre = identidad.nombre() == null ? "" : identidad.nombre().trim();
        if (nombre.isEmpty()) {
            String titulo = encontrado != null ? encontrado.path("title").asText("") : "";
            nombre = !titulo.isEmpty() ? titulo : (!responsable.isEmpty() ? responsable : email);
        }
        Usuario usuario = new Usuario(email, nombre, responsable, rol);
        if (!guardar) {
            return usuario;
        }
        if (actual == null && !conexion.sinConexion()) {
            colaboracion.anotarActividadSinEsperar(email, nombre, "connect", "role=" + rol);
        }
        actual = usuario;
        ultimaLista = lista;
        return usuario;
    }

    /** Olvida al usuario identificado (reinicio del modo local para pruebas). */
    public synchronized void olvidar() {
        actual = null;
        ultimaLista = null;
    }

    /** Lista de usuarios leída en la última identificación. */
    public ArrayNode usuarios() {
        if (usuarios.identidadPorPeticion()) {
            return usuarios.leer();
        }
        if (ultimaLista == null) {
            identificar();
        }
        return ultimaLista;
    }

    public void exigirEscritura(Usuario usuario) {
        if (usuario.soloLectura()) {
            throw new SinPermiso("Tu usuario es de solo lectura: no se guardan cambios.");
        }
    }

    public void exigirAdmin(Usuario usuario) {
        if (!usuario.esAdmin()) {
            throw new SinPermiso("Solo un Admin puede hacer esta operación.");
        }
    }

    /** Gestión de las áreas de bancos (D56): Admin o Lab User (D55). */
    public void exigirAdminOLabUser(Usuario usuario) {
        if (!usuario.esAdminOLabUser()) {
            throw new SinPermiso("Solo un Admin o un Lab User puede hacer esta operación.");
        }
    }
}
