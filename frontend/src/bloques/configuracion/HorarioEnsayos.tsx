import { useEffect, useMemo, useRef, useState } from 'react';
import { apiHorarios } from '../../api/horarios';
import type { EnsayoHorario, GestionHorarios, Horario, ParadaHorario, VistaPreviaHorario } from '../../api/tipos';
import { usePlanificador } from '../../estado/Planificador';
import { codigosArea, nombresArea, normalizarArea } from '../../utilidades/areas';
import { fmt } from '../../utilidades/fechas';
import { usePreferencia } from '../../utilidades/preferencias';

/*
 * Configuration › Test Calendar / Working Hours (D59). Horario de trabajo de
 * cada ensayo: jornada (24 h, 8 h 06–14 o 16 h 06–22), hora de inicio el
 * primer día y de fin el último, fines de semana y festivos (como el ensayo o
 * anulados aquí), horas de setup, paradas (por franjas de media hora en la
 * rejilla o por horas, afectando o no a los turnos) y una nota. Todos lo ven;
 * solo un Admin o un Lab User lo cambia, y solo con la base de datos del
 * servidor (PostgreSQL). La vista previa (horas facturables por periodo) la
 * calcula el backend con las mismas reglas que la hoja mensual (D60).
 */

const estiloSelect = { padding: 6, borderRadius: 6, background: 'var(--navy)', color: 'var(--text)', border: '1px solid var(--line)', fontSize: 12 };

/** Ensayos cerrados que se listan: los que acabaron en los últimos 3 meses. */
const DIAS_CERRADOS = 92;

const VENTANA: Record<number, [number, number]> = { 24: [0, 1440], 8: [360, 840], 16: [360, 1320] };

/** Turno de tarde (solo con jornada de 8 h). */
const deTarde = (h: Horario) => h.jornada === 8 && h.turno === 'tarde';

/** Ventana de la jornada [inicio, fin) en minutos, con el turno de tarde 14:00–22:00. */
const ventanaDe = (h: Horario): [number, number] => (deTarde(h) ? [840, 1320] : VENTANA[h.jornada] ?? VENTANA[24]);

/** Valor del desplegable Workday: '24', '8', '8t' (tarde) o '16'. */
const valorJornada = (h: Horario) => (deTarde(h) ? '8t' : String(h.jornada));

const hhmm = (minutos: number) => `${String(Math.floor(minutos / 60)).padStart(2, '0')}:${String(minutos % 60).padStart(2, '0')}`;

/** Medias horas entre dos minutos del día (incluidos los extremos pedidos). */
function horas(desde: number, hasta: number, incluirFin: boolean): string[] {
  const lista: string[] = [];
  for (let m = desde; incluirFin ? m <= hasta : m < hasta; m += 30) lista.push(hhmm(m));
  return lista;
}

/** «aaaa-mm-ddTHH:MM» + minutos (en UTC para no depender del cambio de hora). */
function sumarMinutos(t: string, minutos: number): string {
  return new Date(Date.parse(`${t}:00Z`) + minutos * 60000).toISOString().slice(0, 16);
}

/** Medias horas [inicio, fin) de una franja. */
function franjasDe(p: ParadaHorario): string[] {
  const lista: string[] = [];
  if (!p.fin) return lista;
  for (let t = p.inicio; t < p.fin && lista.length < 200000; t = sumarMinutos(t, 30)) lista.push(t);
  return lista;
}

/** Medias horas marcadas → paradas por franjas (tramos seguidos); conserva el motivo de las que empiezan igual. */
function paradasDeFranjas(marcadas: Set<string>, anteriores: ParadaHorario[] = []): ParadaHorario[] {
  const motivo = (inicio: string) => anteriores.find((p) => p.metodo === 'franjas' && p.inicio === inicio)?.motivo;
  const tramo = (inicio: string, fin: string): ParadaHorario => {
    const m = motivo(inicio);
    return { inicio, fin, afectaTurnos: false, metodo: 'franjas', ...(m ? { motivo: m } : {}) };
  };
  const orden = [...marcadas].sort();
  const paradas: ParadaHorario[] = [];
  let inicio: string | null = null;
  let anterior: string | null = null;
  for (const t of orden) {
    if (inicio && anterior && sumarMinutos(anterior, 30) === t) { anterior = t; continue; }
    if (inicio && anterior) paradas.push(tramo(inicio, sumarMinutos(anterior, 30)));
    inicio = t;
    anterior = t;
  }
  if (inicio && anterior) paradas.push(tramo(inicio, sumarMinutos(anterior, 30)));
  return paradas;
}

const textoFecha = (t: string) => `${fmt(t.slice(0, 10))} ${t.slice(11, 16)}`;

const numero = (v: number) => (Math.round(v * 100) / 100).toLocaleString('en-GB', { maximumFractionDigits: 2 });

/** Horario sin los campos calculados de la respuesta. */
const limpio = (h: Horario): Horario => ({ jornada: h.jornada, turno: deTarde(h) ? 'tarde' : null, horaInicio: h.horaInicio, horaFin: h.horaFin, finDeSemana: h.finDeSemana,
  setupHoras: h.setupHoras, paradas: h.paradas, nota: h.nota });

export function HorarioEnsayos() {
  const { vista, soloLectura, mostrarMensaje } = usePlanificador();
  const [gestion, setGestion] = useState<GestionHorarios | null>(null);
  const [sinServicio, setSinServicio] = useState(false);
  const bancosOrdenados = useMemo(() => {
    if (!vista) return [] as { area: string; categoria: string; banco: string }[];
    const codigos = codigosArea(vista);
    return codigos.flatMap((c) => vista.grupos.filter((g) => normalizarArea(g.area, vista) === c)
      .flatMap((g) => g.bancos.map((b) => ({ area: c, categoria: g.nombre, banco: b.nombre }))));
  }, [vista]);
  const [banco, setBanco] = usePreferencia<string>('tbp_horario_banco', '');
  const bancoElegido = bancosOrdenados.some((b) => b.banco === banco) ? banco : (bancosOrdenados[0]?.banco ?? '');
  const [ensayos, setEnsayos] = useState<EnsayoHorario[] | null>(null);
  const [errorBanco, setErrorBanco] = useState<string | null>(null);
  const [elegido, setElegido] = useState<string | null>(null);
  const [borrador, setBorrador] = useState<Horario | null>(null);
  const [cambiado, setCambiado] = useState(false);
  const [ocupado, setOcupado] = useState(false);
  const [previa, setPrevia] = useState<VistaPreviaHorario | null>(null);
  const [errorPrevia, setErrorPrevia] = useState<string | null>(null);
  const [clavePrevia, setClavePrevia] = useState(''); // horario con el que se calculó la vista previa
  const [verCerrados, setVerCerrados] = useState(true);

  useEffect(() => {
    apiHorarios.gestion().then((g) => { setGestion(g); setSinServicio(false); }).catch(() => setSinServicio(true));
  }, []);

  const cargarBanco = (mantener?: string | null) => {
    if (!bancoElegido) return;
    setErrorBanco(null);
    apiHorarios.banco(bancoElegido)
      .then((d) => {
        setEnsayos(d.ensayos);
        const sigue = mantener ? d.ensayos.find((e) => e.id === mantener) : undefined;
        if (sigue) {
          setBorrador(limpio(sigue.horario));
          setCambiado(false);
        } else {
          setElegido(null);
          setBorrador(null);
        }
      })
      .catch((e: Error) => { setEnsayos([]); setErrorBanco(e.message); });
  };
  useEffect(() => { setElegido(null); setBorrador(null); setPrevia(null); cargarBanco(null); }, [bancoElegido]); // eslint-disable-line react-hooks/exhaustive-deps

  const puedeEditar = !!gestion?.editable && !!gestion?.puedeEditar && !soloLectura;
  const actual = ensayos?.find((e) => e.id === elegido) ?? null;

  // Vista previa (backend) del horario que se está editando, medio segundo después del último cambio.
  const claveBorrador = borrador ? JSON.stringify(borrador) : '';
  useEffect(() => {
    if (!elegido || !borrador || !actual?.inicio || !actual?.fin) { setPrevia(null); setErrorPrevia(null); return; }
    let vigente = true;
    const clave = claveBorrador;
    const t = window.setTimeout(() => {
      apiHorarios.vistaPrevia(elegido, borrador)
        .then((p) => { if (vigente) { setPrevia(p); setErrorPrevia(null); setClavePrevia(clave); } })
        .catch((e: Error) => { if (vigente) setErrorPrevia(e.message); });
    }, 450);
    return () => { vigente = false; window.clearTimeout(t); };
  }, [elegido, claveBorrador, actual?.inicio, actual?.fin]); // eslint-disable-line react-hooks/exhaustive-deps

  if (!vista) return null;

  if (sinServicio || (gestion && !gestion.editable)) {
    return (
      <div className="sec" style={{ maxWidth: 1100 }} id="horario-ensayos" data-solo-nueva="">
        <h2>&#128338; Test Calendar / Working Hours</h2>
        <div className="hint">
          Working hours per test (workday, start and end times, weekends and holidays, setup hours and stops) are only available with the
          server database (PostgreSQL). Here billing keeps the whole-day calculation.
        </div>
      </div>
    );
  }
  if (!gestion) return <div className="empty">Loading&hellip;</div>;

  const nombres = nombresArea(vista);
  const hoy = vista.hoy;
  const limiteCerrados = new Date(Date.parse(`${hoy}T00:00:00Z`) - DIAS_CERRADOS * 86400000).toISOString().slice(0, 10);
  const lista = (ensayos ?? []).filter((e) => !e.especial && (!e.cerrado || (verCerrados && (e.fin ?? '') >= limiteCerrados)));

  const elegir = (e: EnsayoHorario) => {
    if (e.id === elegido) return;
    if (cambiado && !window.confirm('Discard the unsaved changes of the current test?')) return;
    setElegido(e.id);
    setBorrador(limpio(e.horario));
    setCambiado(false);
    setPrevia(null);
  };
  const cambiar = (c: Partial<Horario>) => {
    if (!borrador) return;
    setBorrador({ ...borrador, ...c });
    setCambiado(true);
  };

  const aplicar = async (accion: () => Promise<EnsayoHorario>) => {
    setOcupado(true);
    try {
      const nuevo = await accion();
      setEnsayos((l) => (l ?? []).map((x) => (x.id === nuevo.id ? nuevo : x)));
      setBorrador(limpio(nuevo.horario));
      setCambiado(false);
    } catch (e) {
      // Conflicto (otra persona lo ha cambiado) u otra regla: se avisa y se vuelve a leer lo guardado.
      mostrarMensaje(e instanceof Error ? e.message : String(e));
      cargarBanco(elegido);
    } finally {
      setOcupado(false);
    }
  };
  const guardar = () => { if (actual && borrador) void aplicar(() => apiHorarios.guardar(actual.id, borrador, actual.version)); };
  const restablecer = () => {
    if (!actual) return;
    if (!window.confirm('Reset this test to the default working hours (24 h, weekends as the test, no stops)?')) return;
    void aplicar(() => apiHorarios.restablecer(actual.id, actual.version));
  };
  const descartar = () => { if (actual) { setBorrador(limpio(actual.horario)); setCambiado(false); } };

  // Qué partes del horario tienen cambios sin guardar (para la barra de acciones).
  const guardadoLimpio = actual ? limpio(actual.horario) : null;
  const partes: string[] = [];
  if (cambiado && borrador && guardadoLimpio) {
    const ajustes = (h: Horario) => JSON.stringify([h.jornada, deTarde(h), h.horaInicio, h.horaFin, h.finDeSemana, h.setupHoras, h.nota]);
    if (ajustes(borrador) !== ajustes(guardadoLimpio)) partes.push('settings');
    const conFranjas = (h: Horario) => h.paradas.some((p) => p.metodo === 'franjas');
    const sinFranjas = (h: Horario) => JSON.stringify(h.paradas.filter((p) => p.metodo !== 'franjas'));
    const franjas = (h: Horario) => JSON.stringify(h.paradas.filter((p) => p.metodo === 'franjas'));
    if (sinFranjas(borrador) !== sinFranjas(guardadoLimpio)) partes.push('stops');
    if (franjas(borrador) !== franjas(guardadoLimpio) && (conFranjas(borrador) || conFranjas(guardadoLimpio))) partes.push('half-hour grid');
  }
  const hayCambios = cambiado && partes.length > 0;

  return (
    <div className="sec" style={{ maxWidth: 'none' }} id="horario-ensayos" data-solo-nueva="">
      <h2>&#128338; Test Calendar / Working Hours</h2>
      <div className="hint">
        By default a test works 24 h a day, with weekends and holidays as its <b>Weekend</b> option. Here you can set, per test, an 8 h
        shift (morning 06:00&ndash;14:00 or afternoon 14:00&ndash;22:00) or 16 h (06:00&ndash;22:00) workday, the start and end times, the setup hours and the stops. Setup is not billed: billing
        starts when it ends. The Gantt and the bench occupancy do not change.
        {puedeEditar ? '' : ' Only an Admin or a Lab User can change the working hours.'}
        {gestion.facturacionHorasDesde ? <> Billing uses these hours from <b>{gestion.facturacionHorasDesde}</b>.</> : ' Hour-based billing is not enabled yet (Billing \u203a Configuration).'}
      </div>
      <div className="tools" style={{ marginBottom: 10 }}>
        <span>Bench</span>
        <select id="horario-banco" style={estiloSelect} value={bancoElegido}
          onChange={(e) => { if (!cambiado || window.confirm('Discard the unsaved changes?')) { setCambiado(false); setBanco(e.target.value); } }}>
          {codigosArea(vista).map((c) => {
            const delArea = bancosOrdenados.filter((b) => b.area === c);
            return delArea.length ? (
              <optgroup key={c} label={nombres[c] ?? c}>
                {delArea.map((b) => <option key={b.banco} value={b.banco}>{b.banco} &middot; {b.categoria}</option>)}
              </optgroup>
            ) : null;
          })}
        </select>
        <label style={{ fontSize: 12 }}><input type="checkbox" checked={verCerrados} onChange={(e) => setVerCerrados(e.target.checked)} /> Closed tests of the last 3 months</label>
        <button className="btn ghost sm" onClick={() => cargarBanco(elegido)}>&#8635; Reload</button>
      </div>
      {errorBanco && <div className="fac-error">{errorBanco}</div>}
      {actual && borrador && (
        <BarraAcciones ensayo={actual} editable={puedeEditar && !ocupado} ocupado={ocupado} partes={hayCambios ? partes : []}
          bloqueado={!!errorPrevia} onGuardar={guardar} onDescartar={descartar} onRestablecer={restablecer} />
      )}
      <div className="horario-cuerpo">
        <div className="horario-lista card">
          <table className="lst" id="horario-ensayos-lista">
            <thead><tr><th>Test</th><th>Dates &amp; schedule</th></tr></thead>
            <tbody>
              {!lista.length && <tr><td colSpan={2}><div className="empty">No tests.</div></td></tr>}
              {lista.map((e) => (
                <tr key={e.id} data-id={e.id} className={e.id === elegido ? 'sel' : ''} style={{ cursor: 'pointer' }} onClick={() => elegir(e)}>
                  <td className="hl-test">
                    <div className="hl-proyecto">{e.proyecto || e.tipo || e.id}{e.cerrado && <span className="hl-cerrado">closed</span>}</div>
                    {e.descripcion
                      ? <div className="hl-desc" title={e.descripcion}>{e.descripcion}</div>
                      : <div className="hl-desc hl-sin-desc">No description</div>}
                    <div className="hl-meta">{e.tipo}{e.responsable ? ` \u00b7 ${e.responsable}` : ''}</div>
                  </td>
                  <td className="hl-fechas">
                    <div>{e.inicio ? fmt(e.inicio) : '\u2014'} &rarr; {e.fin ? fmt(e.fin) : '\u2014'}</div>
                    <div className="hl-meta">
                      Weekend: {e.finDeSemanaEnsayo ? 'Yes' : 'No'}
                      {e.anulaFinDeSemana && <span className="np-warn" title={`Working Hours: ${e.horario.finDeSemana === 'si' ? 'works' : 'does not work'} weekends and holidays`}> &#9888;</span>}
                    </div>
                    <div className="hl-meta">
                      {e.guardado ? `${e.horario.jornada} h${deTarde(e.horario) ? ' (afternoon)' : ''}` : '24 h (default)'}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="horario-editor">
          {!actual || !borrador
            ? <div className="card"><div className="empty">Choose a test.</div></div>
            : <>
                <Editor ensayo={actual} horario={borrador} previa={previa} errorPrevia={errorPrevia} editable={puedeEditar && !ocupado}
                  cambiadoAjustes={partes.includes('settings')} cambiadoParadas={partes.includes('stops')} onCambio={cambiar} />
              </>}
        </div>
      </div>
      {actual && borrador && previa && (
        <Rejilla ensayo={actual} previa={previa} horario={borrador} editable={puedeEditar && !ocupado}
          cambiada={partes.includes('half-hour grid')} desactualizada={clavePrevia !== claveBorrador} onCambio={cambiar} />
      )}
    </div>
  );
}

/**
 * Barra fija arriba del editor: Save / Discard / Reset afectan a TODO el horario
 * del ensayo (ajustes, paradas y rejilla), y dice qué partes tienen cambios.
 */
function BarraAcciones({ ensayo, editable, ocupado, partes, bloqueado, onGuardar, onDescartar, onRestablecer }: {
  ensayo: EnsayoHorario;
  editable: boolean;
  ocupado: boolean;
  /** Partes con cambios sin guardar (vacío = todo guardado). */
  partes: string[];
  /** La vista previa da error: no se puede guardar. */
  bloqueado: boolean;
  onGuardar: () => void;
  onDescartar: () => void;
  onRestablecer: () => void;
}) {
  const pendiente = partes.length > 0;
  return (
    <div className={`horario-acciones${pendiente ? ' pendiente' : ''}`} id="horario-acciones">
      <div className="ha-texto">
        <div className="ha-titulo">Working hours of <b>{ensayo.proyecto || ensayo.tipo || ensayo.id}</b>
          {ensayo.descripcion && <span className="ha-desc"> &mdash; {ensayo.descripcion}</span>}</div>
        <div className="ha-estado">
          {ocupado ? 'Saving…'
            : pendiente ? <>&#9679; Unsaved changes in: <b>{partes.join(', ')}</b></>
            : ensayo.guardado ? '✔ All changes saved' : 'Default working hours (nothing saved for this test)'}
        </div>
      </div>
      {editable || pendiente ? (
        <div className="ha-botones">
          <button className="btn" id="horario-guardar" disabled={!editable || !pendiente || bloqueado} onClick={onGuardar}
            title="Saves the settings, the stops and the half-hour grid of this test">&#128190; Save all changes</button>
          <button className="btn ghost" disabled={!pendiente} onClick={onDescartar}
            title="Undo every unsaved change of this test (settings, stops and grid)">Discard all changes</button>
          <button className="btn ghost" id="horario-restablecer" disabled={!editable || !ensayo.guardado} onClick={onRestablecer}
            title="Delete the saved working hours of this test (settings, stops and grid) and go back to the defaults">&#8634; Reset test to defaults</button>
        </div>
      ) : null}
    </div>
  );
}

function Editor({ ensayo, horario, previa, errorPrevia, editable, cambiadoAjustes, cambiadoParadas, onCambio }: {
  ensayo: EnsayoHorario;
  horario: Horario;
  previa: VistaPreviaHorario | null;
  errorPrevia: string | null;
  editable: boolean;
  cambiadoAjustes: boolean;
  cambiadoParadas: boolean;
  onCambio: (c: Partial<Horario>) => void;
}) {
  const [ini, fin] = ventanaDe(horario);
  const anula = horario.finDeSemana !== null && (horario.finDeSemana === 'si') !== ensayo.finDeSemanaEnsayo;
  const sinFechas = !ensayo.inicio || !ensayo.fin;
  return (
    <>
      <div className={`card${cambiadoAjustes ? ' horario-cambiada' : ''}`} id="horario-editor" data-id={ensayo.id}>
        <h3 style={{ marginTop: 0 }}>Settings{cambiadoAjustes && <span className="horario-marca">unsaved</span>}
          <span className="hint" style={{ margin: '0 0 0 8px' }}>{ensayo.banco} &middot; {ensayo.inicio ? fmt(ensayo.inicio) : '\u2014'} &rarr; {ensayo.fin ? fmt(ensayo.fin) : '\u2014'}</span></h3>
        {ensayo.porPiezas && <div className="fac-aviso">This bench is billed per piece: the hours below are not used for billing.</div>}
        <div className="horario-campos">
          <label>Workday</label>
          <select id="horario-jornada" style={estiloSelect} value={valorJornada(horario)} disabled={!editable}
            onChange={(e) => {
              const v = e.target.value;
              onCambio({ jornada: (v === '8t' ? 8 : Number(v)) as Horario['jornada'], turno: v === '8t' ? 'tarde' : null, horaInicio: null, horaFin: null });
            }}>
            <option value="24">24 h (00:00&ndash;24:00)</option>
            <option value="8">8 h, one shift &middot; morning (06:00&ndash;14:00)</option>
            <option value="8t">8 h, one shift &middot; afternoon (14:00&ndash;22:00)</option>
            <option value="16">16 h, two shifts (06:00&ndash;22:00)</option>
          </select>
          <label>Start time (first day)</label>
          <select id="horario-inicio" style={estiloSelect} value={horario.horaInicio ?? ''} disabled={!editable}
            onChange={(e) => onCambio({ horaInicio: e.target.value || null })}>
            <option value="">Start of workday ({hhmm(ini)})</option>
            {horas(ini, fin, false).map((h) => <option key={h} value={h}>{h}</option>)}
          </select>
          <label>End time (last day)</label>
          <select id="horario-fin" style={estiloSelect} value={horario.horaFin ?? ''} disabled={!editable}
            onChange={(e) => onCambio({ horaFin: e.target.value || null })}>
            <option value="">End of workday ({hhmm(fin)})</option>
            {horas(ini + 30, fin, true).map((h) => <option key={h} value={h}>{h}</option>)}
          </select>
          <label>Weekends &amp; holidays</label>
          <span>
            <select id="horario-finde" style={estiloSelect} value={horario.finDeSemana ?? ''} disabled={!editable}
              onChange={(e) => onCambio({ finDeSemana: (e.target.value || null) as Horario['finDeSemana'] })}>
              <option value="">As task (Weekend: {ensayo.finDeSemanaEnsayo ? 'Yes' : 'No'})</option>
              <option value="si">Work weekends &amp; holidays</option>
              <option value="no">No weekends &amp; holidays</option>
            </select>
            {anula && <span className="np-warn" title="Differs from the test's Weekend option: the bench table shows a warning"> &#9888; overrides the test&rsquo;s Weekend option</span>}
          </span>
          <label>Setup hours</label>
          <span>
            <input type="number" min="0" step="0.5" className="f" style={{ width: 90 }} id="horario-setup" disabled={!editable}
              value={horario.setupHoras ?? ''} placeholder="0"
              onChange={(e) => onCambio({ setupHoras: e.target.value === '' ? null : Number(e.target.value) })} />
            <span className="hint" style={{ margin: '0 0 0 8px' }}>In-shift time from the start; not billed.
              {ensayo.setupAntiguo !== null && !ensayo.guardado ? ` Pre-filled with the old Setup column (${ensayo.setupAntiguo} h).` : ''}</span>
          </span>
          <label>Note</label>
          <textarea className="f" rows={2} style={{ width: '100%' }} maxLength={2000} disabled={!editable} value={horario.nota ?? ''}
            onChange={(e) => onCambio({ nota: e.target.value || null })} />
        </div>
        {ensayo.guardado && ensayo.modificadoPor && (
          <div className="hint">Last change: {ensayo.modificadoPor}{ensayo.modificadoEn ? ` \u00b7 ${ensayo.modificadoEn.slice(0, 16).replace('T', ' ')} UTC` : ''}</div>
        )}
      </div>

      <PrevisionHoras previa={previa} error={errorPrevia} sinFechas={sinFechas} />
      <Paradas ensayo={ensayo} horario={horario} editable={editable && !sinFechas} cambiada={cambiadoParadas} onCambio={onCambio} />
    </>
  );
}

function PrevisionHoras({ previa, error, sinFechas }: { previa: VistaPreviaHorario | null; error: string | null; sinFechas: boolean }) {
  return (
    <div className="card" id="horario-previa">
      <h3 style={{ marginTop: 0 }}>Billing preview</h3>
      {sinFechas && <div className="hint">This test has no dates yet.</div>}
      {error && <div className="fac-error">{error}</div>}
      {!sinFechas && !previa && !error && <div className="hint">Calculating&hellip;</div>}
      {previa && (
        <>
          <div className="horario-resumen">
            <span>Test: <b>{textoFecha(previa.inicio)}</b> &rarr; <b>{textoFecha(previa.fin)}</b></span>
            <span>Billable from: <b>{textoFecha(previa.inicioFacturable)}</b></span>
            {previa.horasTurno !== undefined && <span>Working hours: <b>{numero(previa.horasTurno)}</b></span>}
            <span>Billable hours: <b>{numero(previa.horasFacturables)}</b></span>
            <span>Setup: <b>{numero(previa.horasSetup)} h</b></span>
            <span>Stopped (in shift): <b>{numero(previa.horasParadas)} h</b></span>
          </div>
          <table className="lst" style={{ marginTop: 8 }}>
            <thead><tr>
              <th>Billing month</th><th>Period</th>
              <th title="Hours the test works according to its schedule (workday, weekends, start and end), before deducting setup and stops">Hours</th>
              <th title="Setup and stops (in shift) deducted in this period">Deducted</th>
              <th title="Hours billed: Hours minus setup and stops">Net hours</th>
            </tr></thead>
            <tbody>{previa.periodos.map((p) => (
              <tr key={p.mes}><td>{p.mes}</td><td>{fmt(p.desde)} &ndash; {fmt(p.hasta)}</td><td>{numero(p.horas)}</td>
                <td style={{ color: 'var(--muted)' }}>{p.horas - p.horasNetas > 0 ? `\u2212${numero(p.horas - p.horasNetas)}` : '0'}</td>
                <td><b>{numero(p.horasNetas)}</b></td></tr>
            ))}</tbody>
          </table>
          {previa.paradaDias > 0 && !previa.paradaDiasIgnorada && (
            <div className="hint">Net hours deduct the old <b>Parada (d)</b> ({previa.paradaDias} d &times; workday hours), shared out by billable hours.</div>
          )}
          {previa.avisos.map((a) => <div key={a} className="fac-aviso" style={{ marginTop: 6 }}>{a}</div>)}
        </>
      )}
    </div>
  );
}

function Paradas({ ensayo, horario, editable, cambiada, onCambio }: {
  ensayo: EnsayoHorario;
  horario: Horario;
  editable: boolean;
  cambiada: boolean;
  onCambio: (c: Partial<Horario>) => void;
}) {
  const [fecha, setFecha] = useState(ensayo.inicio ?? '');
  const [hora, setHora] = useState('06:00');
  const [duracion, setDuracion] = useState('1');
  const [fechaFin, setFechaFin] = useState(ensayo.inicio ?? '');
  const [horaFin, setHoraFin] = useState('07:00');
  const [motivo, setMotivo] = useState('');
  const [afecta, setAfecta] = useState(false);
  /** Parada que se está modificando (índice en horario.paradas) o null si se añade una nueva. */
  const [editando, setEditando] = useState<number | null>(null);
  const reiniciar = () => { setEditando(null); setMotivo(''); };
  useEffect(() => { setFecha(ensayo.inicio ?? ''); setFechaFin(ensayo.inicio ?? ''); reiniciar(); }, [ensayo.id, ensayo.inicio]);
  // Si la parada que se modificaba desaparece (Discard, Reset, rejilla...), se vuelve a «añadir».
  useEffect(() => { if (editando !== null && editando >= horario.paradas.length) reiniciar(); }, [horario.paradas.length, editando]);

  const enEdicion = editando !== null ? horario.paradas[editando] : undefined;
  const porFranjas = enEdicion?.metodo === 'franjas';

  const empezarEdicion = (i: number) => {
    const p = horario.paradas[i];
    setEditando(i);
    setFecha(p.inicio.slice(0, 10));
    setHora(p.inicio.slice(11, 16));
    setMotivo(p.motivo ?? '');
    setAfecta(p.afectaTurnos);
    if (p.metodo === 'franjas' && p.fin) {
      // 00:00 del día siguiente se enseña como 24:00 del día anterior.
      if (p.fin.slice(11, 16) === '00:00') {
        setFechaFin(sumarMinutos(p.fin, -1440).slice(0, 10));
        setHoraFin('24:00');
      } else {
        setFechaFin(p.fin.slice(0, 10));
        setHoraFin(p.fin.slice(11, 16));
      }
    } else {
      setDuracion(String(p.horas ?? 1));
    }
  };

  const aceptar = () => {
    if (!fecha) { window.alert('Enter the start date.'); return; }
    const extra = motivo.trim() ? { motivo: motivo.trim() } : {};
    let nueva: ParadaHorario;
    if (porFranjas) {
      const inicio = `${fecha}T${hora}`;
      const fin = horaFin === '24:00' ? sumarMinutos(`${fechaFin}T00:00`, 1440) : `${fechaFin}T${horaFin}`;
      if (!fechaFin || fin <= inicio) { window.alert('The end must be after the start.'); return; }
      nueva = { inicio, fin, afectaTurnos: false, metodo: 'franjas', ...extra };
    } else {
      const h = Number(duracion.replace(',', '.'));
      if (!(h > 0) || Math.round(h * 2) !== h * 2) {
        window.alert('Enter a duration in half hours (0.5, 1, 1.5...).');
        return;
      }
      nueva = { inicio: `${fecha}T${hora}`, horas: h, afectaTurnos: afecta, metodo: 'horas', ...extra };
    }
    onCambio({ paradas: editando !== null ? horario.paradas.map((p, j) => (j === editando ? nueva : p)) : [...horario.paradas, nueva] });
    reiniciar();
  };
  const quitar = (i: number) => {
    if (editando !== null) reiniciar();
    onCambio({ paradas: horario.paradas.filter((_, j) => j !== i) });
  };

  return (
    <div className={`card${cambiada ? ' horario-cambiada' : ''}`} id="horario-paradas">
      <h3 style={{ marginTop: 0 }}>Stops{cambiada && <span className="horario-marca">unsaved</span>}</h3>
      <table className="lst">
        <thead><tr><th>Start</th><th>End / duration</th><th>Method</th><th>Affects shifts</th><th>Reason</th>{editable && <th />}</tr></thead>
        <tbody>
          {!horario.paradas.length && <tr><td colSpan={editable ? 6 : 5}><div className="empty">No stops.</div></td></tr>}
          {horario.paradas.map((p, i) => (
            <tr key={`${p.inicio}|${i}`} className={i === editando ? 'sel' : ''}>
              <td>{textoFecha(p.inicio)}</td>
              <td>{p.metodo === 'franjas' && p.fin ? textoFecha(p.fin) : `${p.horas ?? 0} h`}</td>
              <td>{p.metodo === 'franjas' ? 'Slots' : 'Hours'}</td>
              <td>{p.metodo === 'horas' ? (p.afectaTurnos ? 'Yes' : 'No') : '\u2014'}</td>
              <td>{p.motivo ?? ''}</td>
              {editable && (
                <td style={{ whiteSpace: 'nowrap' }}>
                  <button className="icbtn" title="Edit stop" onClick={() => empezarEdicion(i)}>&#9998;&#65039;</button>
                  <button className="icbtn del" title="Delete stop" onClick={() => quitar(i)}>&#128465;</button>
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>
      {editable && (
        <div className={`tools horario-form${editando !== null ? ' editando' : ''}`} style={{ marginTop: 8, flexWrap: 'wrap' }}>
          <span><b>{editando !== null ? 'Edit stop:' : 'Add stop:'}</b></span>
          <input type="date" className="f" value={fecha} min={ensayo.inicio ?? undefined} max={ensayo.fin ?? undefined} onChange={(e) => setFecha(e.target.value)} />
          <select style={estiloSelect} value={hora} onChange={(e) => setHora(e.target.value)}>
            {horas(0, 1410, true).map((h) => <option key={h} value={h}>{h}</option>)}
          </select>
          {porFranjas ? (
            <>
              <span>to</span>
              <input type="date" className="f" value={fechaFin} min={ensayo.inicio ?? undefined} max={ensayo.fin ?? undefined} onChange={(e) => setFechaFin(e.target.value)} />
              <select style={estiloSelect} value={horaFin} onChange={(e) => setHoraFin(e.target.value)}>
                {horas(30, 1440, true).map((h) => <option key={h} value={h}>{h}</option>)}
              </select>
            </>
          ) : (
            <>
              <input type="number" className="f" min="0.5" step="0.5" style={{ width: 80 }} value={duracion} onChange={(e) => setDuracion(e.target.value)} title="Duration in hours" />
              <span>h</span>
            </>
          )}
          <input className="f" placeholder="Reason (optional)" maxLength={200} value={motivo} onChange={(e) => setMotivo(e.target.value)} />
          {!porFranjas && (
            <label style={{ fontSize: 12 }} title="Only time with the plant open (06:00-22:00, working days) counts: the night stays stopped even on a 24 h workday">
              <input type="checkbox" checked={afecta} onChange={(e) => setAfecta(e.target.checked)} /> Affects shifts</label>
          )}
          <button className="btn sm" onClick={aceptar}>{editando !== null ? <>&#10004; Update stop</> : <>&#65291; Add</>}</button>
          {editando !== null && <button className="btn ghost sm" onClick={reiniciar}>Cancel</button>}
        </div>
      )}
      <div className="hint" style={{ marginTop: 6 }}>
        Not affecting shifts: the stop lasts that many clock hours. Affecting shifts: only time with the plant open (06:00&ndash;22:00) counts,
        also on a 24 h workday, so the night stays stopped (e.g. 8 h from 20:00 &rarr; 2 h until 22:00 and 6 h from 06:00: work resumes at 12:00).
        Stops can also be drawn on the half-hour grid below. Changes here are kept until you press <b>Save all changes</b>.
      </div>
    </div>
  );
}

const CLASE_FRANJA: Record<string, string> = { '-': 'hh-fuera', n: 'hh-sin', s: 'hh-setup', p: 'hh-parada', w: 'hh-trabajo' };

/** Rejilla de medias horas: un día por fila; clic y arrastrar marca o quita franjas de parada. */
function Rejilla({ ensayo, previa, horario, editable, cambiada, desactualizada, onCambio }: {
  ensayo: EnsayoHorario;
  previa: VistaPreviaHorario;
  horario: Horario;
  editable: boolean;
  cambiada: boolean;
  desactualizada: boolean;
  onCambio: (c: Partial<Horario>) => void;
}) {
  const franjas = useMemo(() => new Set(horario.paradas.filter((p) => p.metodo === 'franjas').flatMap(franjasDe)), [horario.paradas]);
  // Durante el arrastre: si se marca o se quita y las medias horas que quedarán marcadas (se guardan al soltar).
  const arrastre = useRef<{ poner: boolean; marcadas: Set<string> } | null>(null);
  const [marcadas, setMarcadas] = useState<Set<string> | null>(null);
  const vigentes = marcadas ?? franjas;

  useEffect(() => {
    const soltar = () => {
      const a = arrastre.current;
      if (!a) return;
      arrastre.current = null;
      setMarcadas(null);
      const otras = horario.paradas.filter((p) => p.metodo !== 'franjas');
      onCambio({ paradas: [...otras, ...paradasDeFranjas(a.marcadas, horario.paradas)] });
    };
    document.addEventListener('pointerup', soltar);
    document.addEventListener('pointercancel', soltar);
    return () => { document.removeEventListener('pointerup', soltar); document.removeEventListener('pointercancel', soltar); };
  }, [horario.paradas, onCambio]);

  const tocar = (t: string, empezar: boolean) => {
    if (!editable) return;
    let a = arrastre.current;
    if (empezar) {
      a = { poner: !vigentes.has(t), marcadas: new Set(vigentes) };
      arrastre.current = a;
    }
    if (!a) return;
    if (a.poner) a.marcadas.add(t); else a.marcadas.delete(t);
    setMarcadas(new Set(a.marcadas));
  };

  return (
    <div className={`card${cambiada ? ' horario-cambiada' : ''}`} id="horario-rejilla">
      <h3 style={{ marginTop: 0 }}>Half-hour grid{cambiada && <span className="horario-marca">unsaved</span>}
        <span className="hint" style={{ margin: '0 0 0 8px' }}>{ensayo.proyecto || ensayo.tipo || ensayo.id}{ensayo.descripcion ? ` \u00b7 ${ensayo.descripcion}` : ''} &middot; {ensayo.banco}</span></h3>
      <div className="hint">
        <span className="hh-muestra hh-trabajo" /> billable <span className="hh-muestra hh-setup" /> setup <span className="hh-muestra hh-parada" /> stopped
        <span className="hh-muestra hh-sin" /> not worked (out of shift, weekend or holiday)
        <span className="hh-muestra hh-planta-muestra" /> plant open 06:00&ndash;22:00.
        {editable ? ' Click or drag over the cells to mark or clear stopped half hours.' : ''}
        {previa.rejillaRecortada ? ' Only the first 1000 days are shown.' : ''}
      </div>
      <div className="hh-wrap">
        <table className="hh-rejilla" onDragStart={(e) => e.preventDefault()}>
          <colgroup><col className="hh-col-dia" />{Array.from({ length: 48 }, (_, i) => <col key={i} />)}</colgroup>
          <thead>
            <tr><th className="hh-dia" />{Array.from({ length: 24 }, (_, h) => (
              <th key={h} colSpan={2} className={h >= 6 && h < 22 ? 'hh-h-planta' : ''}>{String(h).padStart(2, '0')}:00</th>
            ))}</tr>
          </thead>
          <tbody>
            {previa.dias.map((d) => (
              <tr key={d.fecha} className={d.laborable ? '' : 'hh-festivo'}>
                <th className="hh-dia">{fmt(d.fecha)}</th>
                {Array.from(d.franjas).map((c, i) => {
                  const t = `${d.fecha}T${hhmm(i * 30)}`;
                  const marcada = vigentes.has(t);
                  // Media hora que se acaba de quitar: hasta la nueva vista previa no se sabe cómo queda.
                  const pendiente = !marcada && c === 'p' && desactualizada;
                  const clase = marcada ? 'hh-parada' : pendiente ? 'hh-pend' : CLASE_FRANJA[c] ?? 'hh-fuera';
                  const dentro = c !== '-';
                  const borde = i === 12 || i === 44 ? ' hh-borde-planta' : i % 2 === 0 ? ' hh-hora' : '';
                  return (
                    <td key={i} className={`${clase}${borde}${editable && dentro ? ' hh-ed' : ''}`} title={`${fmt(d.fecha)} ${hhmm(i * 30)}\u2013${hhmm(i * 30 + 30)}`}
                      onPointerDown={dentro ? (e) => { e.preventDefault(); (e.target as Element).releasePointerCapture?.(e.pointerId); tocar(t, true); } : undefined}
                      onPointerEnter={dentro ? () => tocar(t, false) : undefined} />
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
