package com.borgwarner.tbp.postgresql;

import com.borgwarner.tbp.aplicacion.puertos.AlmacenUsuarios;
import com.borgwarner.tbp.infraestructura.local.AlmacenEnsayosLocal;
import com.borgwarner.tbp.infraestructura.local.AlmacenesAuxiliaresLocales;
import com.borgwarner.tbp.infraestructura.local.DatosLocales;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/** El contrato de los almacenes contra el almacén LOCAL (la referencia). */
class ContratoAlmacenesLocalTest extends ContratoAlmacenes {

    @TempDir
    Path carpeta;

    @Override
    protected Almacenes preparar() {
        DatosLocales datos = new DatosLocales(carpeta.resolve("datos"), PostgresPruebas.escenario(), true);
        var colaboracion = new AlmacenesAuxiliaresLocales.ColaboracionLocal(datos);
        return new Almacenes(new AlmacenEnsayosLocal(datos, colaboracion), new AlmacenesAuxiliaresLocales.Catalogos(datos, false),
                new AlmacenesAuxiliaresLocales.Usuarios(datos, new AlmacenUsuarios.Identidad("usuario01@ejemplo.local", ""), false),
                new AlmacenesAuxiliaresLocales.Recursos(datos, colaboracion, false), new AlmacenesAuxiliaresLocales.HistoricoKpi(datos),
                new AlmacenesAuxiliaresLocales.Aprobaciones(datos), new AlmacenesAuxiliaresLocales.TiposEquipo(datos), colaboracion);
    }
}
