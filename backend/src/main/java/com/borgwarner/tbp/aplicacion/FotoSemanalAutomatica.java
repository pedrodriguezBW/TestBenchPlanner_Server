package com.borgwarner.tbp.aplicacion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Foto semanal automática (D64): cada noche el servidor vuelve a guardar la
 * foto de la semana en curso, como si alguien pulsara 📊 Update Yearly Status.
 * La del domingo por la noche es la última de la semana ISO y la deja cerrada,
 * aunque alguien pulsara el botón días antes. El botón y el Export read-only
 * siguen funcionando igual (actualizan la foto al momento).
 *
 * Solo con PostgreSQL (servidor o PC con su base): con SharePoint cada PC
 * escribiría su propia foto. Hora en planner.kpi.foto-automatica (cron de
 * Spring, hora de Madrid); «-» la desactiva.
 */
@Component
@ConditionalOnProperty(name = "planner.almacen", havingValue = "postgresql")
public class FotoSemanalAutomatica {

    private static final Logger LOG = LoggerFactory.getLogger(FotoSemanalAutomatica.class);

    private final ServicioIndicadores indicadores;

    public FotoSemanalAutomatica(ServicioIndicadores indicadores) {
        this.indicadores = indicadores;
    }

    @Scheduled(cron = "${planner.kpi.foto-automatica:0 55 23 * * *}", zone = "Europe/Madrid")
    public void tomar() {
        try {
            List<?> fotos = indicadores.guardarFotoSemanal(ServicioIndicadores.AUTOMATICA);
            LOG.info("Foto semanal automática guardada ({} filas del histórico de KPI)", fotos.size());
        } catch (RuntimeException e) {
            // Sin relanzar: la siguiente noche se vuelve a intentar; la semana queda «provisional».
            LOG.error("No se pudo guardar la foto semanal automática", e);
        }
    }
}
