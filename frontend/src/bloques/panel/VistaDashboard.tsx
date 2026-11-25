import { api } from '../../api/cliente';
import { usePanel } from '../../estado/Panel';
import { usePlanificador } from '../../estado/Planificador';
import { esFoto } from '../../api/foto';
import { fmt, semanaIso, sumarDias } from '../../utilidades/fechas';
import { colorEstado } from '../../utilidades/textos';
import { BloquesKpi } from './BloquesKpi';
import { SelectorOrganizacion, TarjetaRedimensionable } from './comunes';
import { GanttGeneral } from './GanttGeneral';
import { GraficaOcupacion } from './GraficaOcupacion';
import { Chip, NavegacionSemana } from './NavegacionSemana';
import { TablaOcupacionCard } from './TablaOcupacion';
import { etiquetaFiltro } from '../../utilidades/areas';

/*
 * Dashboard (renderDashboard de la 4.9.4): cabecera, navegación por semanas,
 * filtro Org, 📊 Update Yearly Status, bloques KPI, plan anual por banco,
 * gráfica de ocupación por CW y tabla de ocupación por tipo de banco.
 */
export function VistaDashboard() {
  const { vista, mostrarMensaje, soloLectura, sesion } = usePlanificador();
  // D64: en el servidor (PostgreSQL) la foto también se actualiza sola cada noche.
  const automatica = !!sesion?.modoServidor;
  const { semana, zoom, setZoom, organizacion, setOrganizacion } = usePanel();
  if (!vista) return null;

  const fotoSemanal = async () => {
    try {
      const fotos = await api.fotoSemanal();
      const f = fotos[0];
      mostrarMensaje(`Yearly status updated: KPIs for CW${f?.cw ?? ''} (${f?.year ?? ''}) saved to the annual history (ALL / BW / CTAG and by area). `
        + (automatica ? 'The server refreshes it again every night and closes the week on Sunday night.' : 'Syncing to SharePoint\u2026'));
    } catch (e) {
      mostrarMensaje(e instanceof Error ? e.message : String(e));
    }
  };

  return (
    <div id="vista-dashboard">
      <div className="hero">
        <img src="/ciudad.jpg" alt="" />
        <div className="ov" /><div className="ov2" />
        <div className="cap"><div className="s">Weekly Test Bench Planning</div><h2>BorgWarner STC Testing Department</h2></div>
        <div className="accent" />
      </div>
      <div className="view-head">
        <NavegacionSemana activarZoom>
          <SelectorOrganizacion valor={organizacion} onCambiar={setOrganizacion} />
          {!esFoto() && <button className="btn ghost sm" disabled={soloLectura} onClick={() => void fotoSemanal()} title={automatica ? 'Save this week\u2019s KPIs now (ALL / BW / CTAG and by area). The server also refreshes them every night and closes the week on Sunday night.' : 'Freeze this week\u2019s KPIs (ALL / BW / CTAG and by area) into the annual history'}>
            &#128202; Update Yearly Status
          </button>}
        </NavegacionSemana>
      </div>
      <BloquesKpi />
      <TarjetaRedimensionable clave="dash-annual" ajustable={false}>
        <div style={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 8 }}>
          <h3 style={{ margin: '0 8px 0 0' }}>Annual plan by bench</h3>
          <div className="dash-toolbar" style={{ marginLeft: 'auto' }}>
            <Chip color={colorEstado('queued')} texto="Queued" />
            <Chip color={colorEstado('confirmed')} texto="Confirmed" />
            <Chip color={colorEstado('running')} texto="Running" />
            <Chip color={colorEstado('delayed')} texto="En retraso" />
            <button className="btn ghost sm" id="dash-zoom" onClick={() => setZoom(!zoom)}>{zoom ? '\u{1F5D3} Full year' : '\u{1F50D} Zoom to week'}</button>
          </div>
        </div>
        <div className="hint">
          {zoom
            ? `Daily resolution for CW${semanaIso(semana)} (${fmt(semana)} \u2192 ${fmt(sumarDias(semana, 6))}). Use Prev/Next above to move; press \u201CFull year\u201D to zoom out.`
            : `Weekly resolution across ${vista.calendario.anio}. Use Prev/Next above (or \u201CZoom to week\u201D) to see a natural week at daily resolution.`}
          {organizacion !== 'ALL' && <>{' \u00b7 Filtered by '}<b>{etiquetaFiltro(organizacion, vista)}</b>.</>}
        </div>
        <GanttGeneral responsable={null} />
      </TarjetaRedimensionable>
      <GraficaOcupacion />
      <TablaOcupacionCard />
    </div>
  );
}
