package com.borgwarner.tbp.dominio.usuarios;

/**
 * Usuario conectado: identidad SSO y rol de TestBenchUsers.
 *
 * @param rol admin, editor, labuser o readonly (cualquiera que no esté dado de alta es readonly)
 */
public record Usuario(String email, String displayName, String responsibleName, String rol) {

    public static final String ADMIN = "admin";
    public static final String EDITOR = "editor";
    public static final String SOLO_LECTURA = "readonly";
    /**
     * Lab User (D55): todo lo de un Editor y, además, la gestión de las áreas de
     * bancos (solo con PostgreSQL). Se guarda como «LabUser».
     */
    public static final String LAB_USER = "labuser";

    public boolean esAdmin() {
        return ADMIN.equals(rol);
    }

    public boolean esLabUser() {
        return LAB_USER.equals(rol);
    }

    /** Admin o Lab User: puede gestionar las áreas de bancos (D56). */
    public boolean esAdminOLabUser() {
        return esAdmin() || esLabUser();
    }

    public boolean soloLectura() {
        return SOLO_LECTURA.equals(rol) || rol == null;
    }

    /** Nombre que firma cierres y correcciones: responsable, nombre visible o correo. */
    public String firma() {
        if (responsibleName != null && !responsibleName.isEmpty()) {
            return responsibleName;
        }
        if (displayName != null && !displayName.isEmpty()) {
            return displayName;
        }
        return email == null ? "" : email;
    }

    /** Responsable que se pone por defecto en un ensayo nuevo (vacío para un Admin). */
    public String responsablePorDefecto() {
        return esAdmin() ? "" : (responsibleName == null ? "" : responsibleName);
    }

    /** Normaliza el texto de rol de la lista (Admin / Editor / LabUser / ReadOnly…). */
    public static String normalizarRol(String valor) {
        String rol = valor == null ? "" : valor.trim().toLowerCase().replace(" ", "");
        if (rol.equals("admin") || rol.equals("administrator")) {
            return ADMIN;
        }
        if (rol.equals("editor") || rol.equals("edit") || rol.equals("write")) {
            return EDITOR;
        }
        if (rol.equals("labuser") || rol.equals("lab_user") || rol.equals("lab-user")) {
            return LAB_USER;
        }
        return SOLO_LECTURA;
    }

    /** Rol tal como se guarda en la lista: Admin, Editor, LabUser o ReadOnly. */
    public static String rolGuardado(String valor) {
        return switch (normalizarRol(valor)) {
            case ADMIN -> "Admin";
            case EDITOR -> "Editor";
            case LAB_USER -> "LabUser";
            default -> "ReadOnly";
        };
    }
}
