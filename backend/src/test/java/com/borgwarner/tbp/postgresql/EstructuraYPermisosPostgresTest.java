package com.borgwarner.tbp.postgresql;

import com.borgwarner.tbp.dominio.facturacion.HojaMensual;
import com.borgwarner.tbp.infraestructura.postgresql.MigracionesBaseDatos;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Usuarios de base de datos y Flyway: tbp_app no puede cambiar la estructura,
 * tbp_consulta solo lee, y una base que no cuadra con las migraciones impide
 * arrancar.
 */
class EstructuraYPermisosPostgresTest {

    private static BaseDePruebas base;

    @BeforeAll
    static void abrir() {
        base = new BaseDePruebas();
        base.importador.reiniciar(PostgresPruebas.escenario());
    }

    @AfterAll
    static void cerrar() {
        base.close();
    }

    private static int contar(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void flywayDejaRegistradaLaMigracionInicial() throws SQLException {
        try (Connection c = PostgresPruebas.conectar(base.base, "tbp_consulta")) {
            assertEquals(1, contar(c, "SELECT count(*) FROM tbp.flyway_schema_history WHERE version = '1' AND success"));
        }
    }

    @Test
    void laMigracionV2DejaElCalendarioComunConSusPermisos() throws SQLException {
        try (Connection c = PostgresPruebas.conectar(base.base, "tbp_consulta")) {
            assertEquals(1, contar(c, "SELECT count(*) FROM tbp.flyway_schema_history WHERE version = '2' AND success"));
        }
        try (Connection c = PostgresPruebas.conectar(base.base, "tbp_app"); Statement s = c.createStatement()) {
            s.execute("INSERT INTO tbp.festivo (fecha, nombre) VALUES (DATE '2030-01-02', 'prueba')");
            assertEquals(1, contar(c, "SELECT count(*) FROM tbp.festivo WHERE fecha = DATE '2030-01-02'"));
            s.execute("DELETE FROM tbp.festivo WHERE fecha = DATE '2030-01-02'");
            assertThrows(SQLException.class, () -> s.execute("INSERT INTO tbp.calendario (id, anio) VALUES (2, 2030)"));
        }
        try (Connection c = PostgresPruebas.conectar(base.base, "tbp_consulta"); Statement s = c.createStatement()) {
            assertEquals(0, contar(c, "SELECT count(*) FROM tbp.festivo WHERE fecha = DATE '2030-01-02'"));
            assertThrows(SQLException.class, () -> s.execute("DELETE FROM tbp.festivo"));
        }
    }

    @Test
    void laMigracionV3GuardaLasCuentasFueraDelAlcanceDeLaConsulta() throws SQLException {
        try (Connection c = PostgresPruebas.conectar(base.base, "tbp_owner")) {
            assertEquals(1, contar(c, "SELECT count(*) FROM tbp.flyway_schema_history WHERE version = '3' AND success"));
        }
        try (Connection c = PostgresPruebas.conectar(base.base, "tbp_app"); Statement s = c.createStatement()) {
            s.execute("INSERT INTO tbp.credencial (email, hash) VALUES ('prueba@ejemplo.local', 'x')");
            assertEquals(1, contar(c, "SELECT count(*) FROM tbp.credencial WHERE email = 'prueba@ejemplo.local'"));
            s.execute("DELETE FROM tbp.credencial WHERE email = 'prueba@ejemplo.local'");
        }
        // pgAdmin / DBeaver (tbp_consulta) no ve ni los hashes.
        try (Connection c = PostgresPruebas.conectar(base.base, "tbp_consulta"); Statement s = c.createStatement()) {
            assertThrows(SQLException.class, () -> s.executeQuery("SELECT count(*) FROM tbp.credencial"));
        }
    }

    @Test
    void laMigracionV4CreaElHorarioDeLosEnsayosYElPrimerMesDeFacturacionPorHoras() throws SQLException {
        try (Connection c = PostgresPruebas.conectar(base.base, "tbp_consulta")) {
            assertEquals(1, contar(c, "SELECT count(*) FROM tbp.flyway_schema_history WHERE version = '4' AND success"));
            assertEquals(0, contar(c, "SELECT count(*) FROM tbp.horario_ensayo"), "tbp_consulta la puede leer");
        }
        try (Connection c = PostgresPruebas.conectar(base.base, "tbp_app"); Statement s = c.createStatement()) {
            s.execute("INSERT INTO tbp.horario_ensayo (ensayo_id, jornada) VALUES ('prueba', 16)");
            assertEquals(1, contar(c, "SELECT count(*) FROM tbp.horario_ensayo WHERE ensayo_id = 'prueba' AND paradas = '[]'::jsonb "
                    + "AND version = 1"));
            assertThrows(SQLException.class, () -> s.execute("INSERT INTO tbp.horario_ensayo (ensayo_id, jornada) VALUES ('otra', 12)"));
            assertThrows(SQLException.class, () -> s.execute("INSERT INTO tbp.horario_ensayo (ensayo_id, fin_de_semana) VALUES ('otra', 'x')"));
            s.execute("DELETE FROM tbp.horario_ensayo WHERE ensayo_id = 'prueba'");
        }
        try (Connection c = PostgresPruebas.conectar(base.base, "tbp_consulta"); Statement s = c.createStatement()) {
            assertThrows(SQLException.class, () -> s.execute("DELETE FROM tbp.horario_ensayo"));
        }
        // En una base nueva la migración deja como primer mes por horas el mes de facturación en curso
        // (el reinicio de pruebas de «base» vacía configuracion_app).
        PostgresPruebas.Base otra = PostgresPruebas.nuevaBase();
        MigracionesBaseDatos.preparar(BaseDePruebas.propiedades(otra, true));
        try (Connection c = PostgresPruebas.conectar(otra, "tbp_owner"); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT valor, current_date FROM tbp.configuracion_app WHERE clave = 'FacturacionHorasDesde'")) {
            assertTrue(rs.next(), "falta FacturacionHorasDesde");
            assertEquals(HojaMensual.periodoDe(rs.getDate(2).toLocalDate()).toString(), rs.getString(1));
        }
    }

    @Test
    void laMigracionV5AnadeElTurnoDeMananaOTarde() throws SQLException {
        try (Connection c = PostgresPruebas.conectar(base.base, "tbp_app"); Statement s = c.createStatement()) {
            assertEquals(1, contar(c, "SELECT count(*) FROM tbp.flyway_schema_history WHERE version = '5' AND success"));
            s.execute("INSERT INTO tbp.horario_ensayo (ensayo_id, jornada, turno) VALUES ('prueba-turno', 8, 'tarde')");
            assertEquals(1, contar(c, "SELECT count(*) FROM tbp.horario_ensayo WHERE ensayo_id = 'prueba-turno' AND turno = 'tarde'"));
            assertThrows(SQLException.class, () -> s.execute("INSERT INTO tbp.horario_ensayo (ensayo_id, jornada, turno) VALUES ('otra', 8, 'noche')"));
            s.execute("DELETE FROM tbp.horario_ensayo WHERE ensayo_id = 'prueba-turno'");
        }
    }

    @Test
    void laAplicacionNoPuedeCambiarLaEstructura() throws SQLException {
        try (Connection c = PostgresPruebas.conectar(base.base, "tbp_app"); Statement s = c.createStatement()) {
            assertTrue(contar(c, "SELECT count(*) FROM tbp.ensayo") > 0);
            assertThrows(SQLException.class, () -> s.execute("CREATE TABLE tbp.intrusa (x int)"));
        }
        try (Connection c = PostgresPruebas.conectar(base.base, "tbp_app"); Statement s = c.createStatement()) {
            assertThrows(SQLException.class, () -> s.execute("ALTER TABLE tbp.ensayo ADD COLUMN intrusa int"));
        }
        try (Connection c = PostgresPruebas.conectar(base.base, "tbp_app"); Statement s = c.createStatement()) {
            assertThrows(SQLException.class, () -> s.execute("DROP TABLE tbp.auditoria"));
        }
    }

    @Test
    void laAplicacionNoPuedeTocarElHistorialDeMigraciones() throws SQLException {
        try (Connection c = PostgresPruebas.conectar(base.base, "tbp_app"); Statement s = c.createStatement()) {
            assertEquals(1, contar(c, "SELECT count(*) FROM tbp.flyway_schema_history WHERE version = '1'"));
            assertThrows(SQLException.class, () -> s.execute("DELETE FROM tbp.flyway_schema_history"));
        }
    }

    @Test
    void elUsuarioDeConsultaSoloLee() throws SQLException {
        try (Connection c = PostgresPruebas.conectar(base.base, "tbp_consulta"); Statement s = c.createStatement()) {
            assertEquals(281, contar(c, "SELECT count(*) FROM tbp.ensayo"));
            assertThrows(SQLException.class, () -> s.execute("DELETE FROM tbp.ensayo"));
        }
        try (Connection c = PostgresPruebas.conectar(base.base, "tbp_app")) {
            assertEquals(281, contar(c, "SELECT count(*) FROM tbp.ensayo"));
        }
    }

    @Test
    void soloValidarFuncionaConElUsuarioDeLaAplicacion() {
        assertDoesNotThrow(() -> MigracionesBaseDatos.preparar(BaseDePruebas.propiedades(base.base, false)));
    }

    @Test
    void unaMigracionRetocadaImpideArrancar() throws SQLException {
        PostgresPruebas.Base otra = PostgresPruebas.nuevaBase();
        MigracionesBaseDatos.preparar(BaseDePruebas.propiedades(otra, true));
        try (Connection c = PostgresPruebas.conectar(otra, "tbp_owner"); Statement s = c.createStatement()) {
            s.execute("UPDATE tbp.flyway_schema_history SET checksum = checksum + 1 WHERE version = '1'");
        }
        assertThrows(IllegalStateException.class, () -> MigracionesBaseDatos.preparar(BaseDePruebas.propiedades(otra, true)));
        assertThrows(IllegalStateException.class, () -> MigracionesBaseDatos.preparar(BaseDePruebas.propiedades(otra, false)));
    }

    @Test
    void sinElEsquemaDeLaInstalacionNoArranca() {
        PostgresPruebas.Base otra = PostgresPruebas.nuevaBase();
        PostgresPruebas.ejecutarComoSuperusuario(otra, "DROP SCHEMA tbp CASCADE");
        assertThrows(IllegalStateException.class, () -> MigracionesBaseDatos.preparar(BaseDePruebas.propiedades(otra, true)));
    }
}
