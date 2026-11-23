package com.borgwarner.tbp.dominio;

import com.borgwarner.tbp.dominio.usuarios.Usuario;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Roles de TestBenchUsers, con el Lab User (D55). */
class UsuarioTest {

    @Test
    void normalizaYGuardaLosRoles() {
        assertEquals(Usuario.LAB_USER, Usuario.normalizarRol("LabUser"));
        assertEquals(Usuario.LAB_USER, Usuario.normalizarRol(" lab user "));
        assertEquals(Usuario.LAB_USER, Usuario.normalizarRol("Lab_User"));
        assertEquals(Usuario.ADMIN, Usuario.normalizarRol("Administrator"));
        assertEquals(Usuario.EDITOR, Usuario.normalizarRol("write"));
        assertEquals(Usuario.SOLO_LECTURA, Usuario.normalizarRol("laboratorio"));
        assertEquals("LabUser", Usuario.rolGuardado("lab user"));
        assertEquals("Admin", Usuario.rolGuardado("admin"));
        assertEquals("Editor", Usuario.rolGuardado("EDITOR"));
        assertEquals("ReadOnly", Usuario.rolGuardado(""));
    }

    @Test
    void elLabUserEsUnEditorQueGestionaAreas() {
        Usuario lab = new Usuario("lab@ejemplo.local", "Lab", "Responsable Lab", Usuario.LAB_USER);
        assertFalse(lab.soloLectura());
        assertFalse(lab.esAdmin());
        assertTrue(lab.esLabUser());
        assertTrue(lab.esAdminOLabUser());
        assertEquals("Responsable Lab", lab.responsablePorDefecto(), "como un Editor");
        Usuario editor = new Usuario("ed@ejemplo.local", "Ed", "Responsable Ed", Usuario.EDITOR);
        assertFalse(editor.esAdminOLabUser());
        assertTrue(new Usuario("a@ejemplo.local", "A", "", Usuario.ADMIN).esAdminOLabUser());
    }
}
