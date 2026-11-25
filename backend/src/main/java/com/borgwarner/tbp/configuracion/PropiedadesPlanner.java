package com.borgwarner.tbp.configuracion;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración de la aplicación (application.yml o argumentos --planner.…).
 * Ningún valor sensible va en el repositorio: el sitio de SharePoint se indica
 * en la configuración de cada instalación.
 */
@ConfigurationProperties(prefix = "planner")
public record PropiedadesPlanner(
        /* local | sharepoint | postgresql */
        String almacen,
        /* Fecha fija (aaaa-mm-dd) para reproducir un día concreto; vacío = hoy. */
        String fechaFija,
        /* Carpeta de los datos de este PC (calendario, perfil…). */
        String carpetaUsuario,
        Local local,
        SharePoint sharepoint,
        PostgreSql postgresql,
        Migracion migracion,
        Servidor servidor) {

    /** Valores de serie para las secciones que no vengan en la configuración. */
    public PropiedadesPlanner {
        postgresql = postgresql == null
                ? new PostgreSql(null, null, null, null, null, null, true, null, null, null, false) : postgresql;
        migracion = migracion == null ? new Migracion(null, null, false, true, null, null, null, null, false) : migracion;
        servidor = servidor == null ? new Servidor(false) : servidor;
    }

    /**
     * Modo servidor (hito S2, perfil «servidor»): una sola instancia en el PC
     * servidor a la que se entra desde el navegador de otros PCs. Exige
     * PostgreSQL, no se apaga al cerrar pestañas y no ofrece /clasico.
     */
    public record Servidor(boolean activo) {
    }

    /** Almacenamiento local en disco (desarrollo y pruebas, sin SharePoint). */
    public record Local(
            String carpetaDatos,
            String escenarioInicial,
            boolean reiniciarAlArrancar,
            String usuarioEmail,
            String usuarioNombre) {
    }

    /** Acceso a las listas de SharePoint a través del Edge del usuario. */
    public record SharePoint(
            String sitio,
            String sitioAprobaciones,
            String listaAprobaciones,
            /* false (por defecto): no se escribe NADA en SharePoint */
            boolean escrituraHabilitada,
            boolean publicarVersion,
            int puertoDepuracion,
            String carpetaPerfilEdge,
            boolean espejoHabilitado,
            /* Conexiones de solo lectura con Edge en paralelo (0 = todo por una sola, como antes). */
            Integer carrilesLectura) {

        public int carrilesLecturaEfectivos() {
            return carrilesLectura == null ? 2 : Math.max(0, Math.min(4, carrilesLectura));
        }
    }

    /**
     * Base de datos PostgreSQL (planner.almacen=postgresql). Dos usuarios: el
     * propietario solo para Flyway (estructura) y el de la aplicación para el
     * día a día (datos, sin DDL). Las claves nunca van en el repositorio.
     */
    public record PostgreSql(
            String url,
            String esquema,
            String usuarioApp,
            String claveApp,
            String usuarioPropietario,
            String clavePropietario,
            /* true: Flyway aplica lo pendiente al arrancar; false: solo valida */
            boolean migrarAlArrancar,
            Integer conexionesMaximas,
            /* Identidad provisional hasta el inicio de sesión (hito S3). */
            String usuarioEmail,
            String usuarioNombre,
            /* Solo pruebas/laboratorio: habilita /api/v1/local/reinicio sobre la base. */
            boolean reinicioPruebas) {

        public String urlEfectiva() {
            return url == null || url.isBlank() ? "jdbc:postgresql://127.0.0.1:5432/tbp" : url.trim();
        }

        public String esquemaEfectivo() {
            return esquema == null || esquema.isBlank() ? "tbp" : esquema.trim();
        }

        public String usuarioAppEfectivo() {
            return usuarioApp == null || usuarioApp.isBlank() ? "tbp_app" : usuarioApp.trim();
        }

        public String usuarioPropietarioEfectivo() {
            return usuarioPropietario == null || usuarioPropietario.isBlank() ? "tbp_owner" : usuarioPropietario.trim();
        }

        public int conexionesMaximasEfectivas() {
            return conexionesMaximas == null ? 10 : Math.max(2, Math.min(50, conexionesMaximas));
        }
    }

    /**
     * Migración de datos en dos pasos: exportar (PC con acceso a SharePoint, solo
     * lectura) e importar (servidor, en PostgreSQL). Vacío = no se hace.
     */
    public record Migracion(
            String exportar,
            String importar,
            /* importar: calcula y verifica, y deshace todo al final */
            boolean simular,
            /* al terminar la exportación/importación se cierra la aplicación */
            boolean salirAlTerminar,
            Integer maxActividad,
            /* importar con fusión de tres vías: exportación con la que se cargó la base (estado común) */
            String base,
            /* fusión de tres vías: conflictos.json editado con las decisiones (opcional) */
            String resoluciones,
            /* fusión de tres vías: carpeta de trabajo (vacío = junto a la de importar, con fecha) */
            String salida,
            /* fusión de tres vías: aplicar aunque no se pueda comprobar que «base» es la exportación con la que se cargó la base (D65) */
            boolean forzarBase) {

        public int maxActividadEfectiva() {
            return maxActividad == null ? 5000 : Math.max(0, maxActividad);
        }
    }
}
