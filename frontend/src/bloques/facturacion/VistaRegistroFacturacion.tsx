import { useEffect, useMemo, useRef, useState } from 'react';
import { apiFacturacion, euros, type LineaRegistro, type RegistroFacturacion } from '../../api/facturacion';
import { usePlanificador } from '../../estado/Planificador';
import { areasDe } from '../../utilidades/areas';
import { fmt } from '../../utilidades/fechas';
import { usePreferencia } from '../../utilidades/preferencias';

/*
 * Billing › Tests by person: el registro del año con el formato de la hoja
 * INPUTS_2026 del Excel (una fila por tarea y mes de facturación). Solo consulta:
 * cada persona se busca (Requester) y ve qué ensayos ha llevado, de qué proyectos,
 * las horas, la orden a la que se imputan y el coste. Los importes son los mismos
 * que los de la hoja mensual (mismo cálculo en el backend).
 */

const MESES = ['Enero', 'Febrero', 'Marzo', 'Abril', 'Mayo', 'Junio', 'Julio', 'Agosto', 'Septiembre', 'Octubre', 'Noviembre', 'Diciembre'];
const estiloSelect = { background: 'var(--navy)', color: 'var(--text)', border: '1px solid var(--line)', borderRadius: 6, padding: '5px 8px', fontSize: 13 };
const horas = (v: number) => (Math.round(v * 100) / 100).toLocaleString('es-ES', { maximumFractionDigits: 2 });
const nombreMes = (mes: string) => `${MESES[Number(mes.slice(5, 7)) - 1]} ${mes.slice(0, 4)}`;

/** Número de proyecto: los dígitos del principio del proyecto («11368 - R&D - …» → 11368). */
const numeroProyecto = (p: string) => /^\s*(\d{3,})/.exec(p)?.[1] ?? '';

const normal = (t: string) => t.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();

type Orden = 'mes' | 'requester' | 'proyecto' | 'coste';

export function VistaRegistroFacturacion() {
  const { vista, sesion } = usePlanificador();
  const anioActual = Number((vista?.hoy ?? new Date().toISOString()).slice(0, 4));
  const [anio, setAnio] = useState(anioActual);
  const [areaElegida, setArea] = usePreferencia<string>('tbp_facturacion_area', 'ALL');
  const areas = areasDe(vista);
  const area = areaElegida !== 'ALL' && !areas.some((a) => a.clave === areaElegida) ? 'ALL' : areaElegida;
  const [datos, setDatos] = useState<RegistroFacturacion | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [cargando, setCargando] = useState(false);
  const [persona, setPersona] = usePreferencia<string>('tbp_registro_persona', '');
  const [buscar, setBuscar] = useState('');
  const [mes, setMes] = useState('ALL');
  const [soloCompletas, setSoloCompletas] = useState(false);
  const [orden, setOrden] = useState<Orden>('mes');

  const peticion = useRef(0);
  const cargar = () => {
    const n = ++peticion.current;
    setCargando(true);
    setError(null);
    apiFacturacion.registro(anio, area)
      .then((d) => { if (n === peticion.current) setDatos(d); })
      .catch((e: Error) => { if (n === peticion.current) { setDatos(null); setError(e.message); } })
      .finally(() => { if (n === peticion.current) setCargando(false); });
  };
  useEffect(cargar, [anio, area]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => setMes('ALL'), [anio]);

  const lineas = datos?.lineas ?? [];
  const personas = useMemo(() => [...new Set(lineas.map((l) => l.requester).filter(Boolean))].sort((a, b) => a.localeCompare(b, 'es')), [lineas]);
  const sinRequester = lineas.some((l) => !l.requester);

  // «Yo»: el Requester que coincide con el usuario de la sesión (parte del email antes de @ o el nombre).
  const yo = useMemo(() => {
    if (!sesion) return '';
    const claves = [sesion.email?.split('@')[0], sesion.nombre].filter(Boolean).map((x) => normal(String(x)));
    return personas.find((p) => claves.includes(normal(p))) ?? '';
  }, [sesion, personas]);

  const filtradas = useMemo(() => {
    const q = normal(buscar.trim());
    const lista = lineas.filter((l) => {
      if (persona === '__vacio__' ? !!l.requester : persona && l.requester !== persona) return false;
      if (mes !== 'ALL' && l.mes !== mes) return false;
      if (soloCompletas && !l.completa) return false;
      if (q) {
        const texto = normal([l.proyecto, l.descripcion ?? '', l.tipoEnsayo, l.requester, l.orden, l.banco, l.equipmentType, l.grupo, l.productLine].join(' '));
        if (!q.split(/\s+/).every((p) => texto.includes(p))) return false;
      }
      return true;
    });
    const cmp: Record<Orden, (a: LineaRegistro, b: LineaRegistro) => number> = {
      mes: (a, b) => a.mes.localeCompare(b.mes) || a.proyecto.localeCompare(b.proyecto) || a.inicio.localeCompare(b.inicio),
      requester: (a, b) => a.requester.localeCompare(b.requester, 'es') || a.mes.localeCompare(b.mes),
      proyecto: (a, b) => a.proyecto.localeCompare(b.proyecto, 'es') || a.mes.localeCompare(b.mes),
      coste: (a, b) => (b.coste ?? -1) - (a.coste ?? -1),
    };
    return [...lista].sort(cmp[orden]);
  }, [lineas, persona, mes, soloCompletas, buscar, orden]);

  const totalHoras = filtradas.reduce((s, l) => s + (l.unidad === 'HORAS' ? l.cantidad : 0), 0);
  const totalPiezas = filtradas.reduce((s, l) => s + (l.unidad === 'PIEZAS' ? l.cantidad : 0), 0);
  const totalCoste = filtradas.reduce((s, l) => s + (l.coste ?? 0), 0);
  const incompletas = filtradas.filter((l) => !l.completa).length;
  const proyectos = new Set(filtradas.map((l) => l.proyecto)).size;
  const anios = Array.from({ length: anioActual - 2023 + 2 }, (_, i) => 2023 + i);

  const th = (texto: string, clave?: Orden, clase = '') => (
    <th className={`${clase}${clave ? ' reg-ordenable' : ''}${clave === orden ? ' reg-orden' : ''}`} onClick={clave ? () => setOrden(clave) : undefined}
      title={clave ? 'Sort by this column' : undefined}>{texto}{clave === orden ? ' \u25BE' : ''}</th>
  );

  return (
    <div className="reg-fac">
      <div className="fac-head">
        <h2>&#128101; Billing &middot; Tests by person</h2>
        <select id="reg-anio" style={estiloSelect} value={anio} onChange={(e) => setAnio(Number(e.target.value))}>
          {anios.map((a) => <option key={a} value={a}>{a}</option>)}
        </select>
        <select id="reg-area" style={estiloSelect} value={area} onChange={(e) => setArea(e.target.value)} title="Only the tasks of the benches of this area">
          <option value="ALL">All areas</option>
          {areas.map((a) => <option key={a.clave} value={a.clave}>{a.nombre}</option>)}
        </select>
        <button className="btn ghost sm" onClick={cargar} disabled={cargando}>&#8635; Reload</button>
        <span className="hint" style={{ margin: 0 }}>Read only &middot; same figures as the Monthly sheet (one row per task and billing month).</span>
      </div>

      <div className="card reg-filtros">
        <label>Requester
          <select id="reg-persona" style={{ ...estiloSelect, minWidth: 180 }} value={persona} onChange={(e) => setPersona(e.target.value)}>
            <option value="">Everyone</option>
            {personas.map((p) => <option key={p} value={p}>{p}{p === yo ? ' (me)' : ''}</option>)}
            {sinRequester && <option value="__vacio__">(no requester)</option>}
          </select>
        </label>
        {yo && persona !== yo && <button className="btn sm" onClick={() => setPersona(yo)}>&#128100; Only my tests</button>}
        <label>Billing month
          <select id="reg-mes" style={estiloSelect} value={mes} onChange={(e) => setMes(e.target.value)}>
            <option value="ALL">Whole year</option>
            {(datos?.meses ?? []).map((m) => <option key={m} value={m}>{nombreMes(m)}</option>)}
          </select>
        </label>
        <input id="reg-buscar" className="f reg-buscar" placeholder="Search project, test, order, bench..." value={buscar} onChange={(e) => setBuscar(e.target.value)} />
        <label className="reg-check"><input type="checkbox" checked={soloCompletas} onChange={(e) => setSoloCompletas(e.target.checked)} /> Only complete rows</label>
        {(persona || buscar || mes !== 'ALL' || soloCompletas) && (
          <button className="btn ghost sm" onClick={() => { setPersona(''); setBuscar(''); setMes('ALL'); setSoloCompletas(false); }}>Clear filters</button>
        )}
      </div>

      {error && <div className="fac-error">{error}</div>}
      {!datos && !error && <div className="empty">Loading&hellip;</div>}
      {datos && (
        <>
          <div className="reg-kpis">
            <div><span>Rows</span><b>{filtradas.length}</b></div>
            <div><span>Projects</span><b>{proyectos}</b></div>
            <div><span>Hours</span><b>{horas(totalHoras)}</b></div>
            {totalPiezas > 0 && <div><span>Pieces</span><b>{horas(totalPiezas)}</b></div>}
            <div className="reg-kpi-coste"><span>Total cost</span><b>{euros(totalCoste)}</b></div>
            {incompletas > 0 && <div className="reg-kpi-aviso"><span>Incomplete</span><b>&#9888; {incompletas}</b></div>}
          </div>
          <div className="card reg-tabla-card">
            <div className="reg-wrap">
              <table className="lst reg-tabla" id="reg-tabla">
                <thead>
                  <tr>
                    {th('Billing month', 'mes')}
                    {th('Project', 'proyecto')}
                    {th('Test / Task')}
                    {th('Requester', 'requester')}
                    {th('Project no.')}
                    {th('Order')}
                    {th('Plant')}
                    {th('Type')}
                    {th('Equipment')}
                    {th('HV')}
                    {th('Start')}
                    {th('End')}
                    {th('Net hours', undefined, 'num')}
                    {th('Set-up h', undefined, 'num')}
                    {th('Total', undefined, 'num')}
                    {th('Cost/hour', undefined, 'num')}
                    {th('Total cost', 'coste', 'num')}
                  </tr>
                </thead>
                <tbody>
                  {!filtradas.length && <tr><td colSpan={17}><div className="empty">No tests match these filters.</div></td></tr>}
                  {filtradas.map((l) => (
                    <tr key={`${l.mes}|${l.ensayoId}|${l.banco}`} className={l.completa ? '' : 'reg-incompleta'}
                      title={l.completa ? undefined : `Incomplete: ${l.faltas.join(', ')}`}>
                      <td className="nw">{nombreMes(l.mes)}</td>
                      <td className="reg-proyecto">{l.proyecto || '\u2014'}</td>
                      <td className="reg-test">
                        {l.descripcion ? <div className="reg-desc">{l.descripcion}</div> : <div className="reg-sin">No description</div>}
                        <div className="reg-sub">{[l.tipoEnsayo, l.banco].filter(Boolean).join(' \u00b7 ')}</div>
                      </td>
                      <td className="nw">{l.requester || <span className="reg-sin">&mdash;</span>}</td>
                      <td>{numeroProyecto(l.proyecto)}</td>
                      <td className="nw">{l.orden || <span className="reg-falta">&#9888; none</span>}</td>
                      <td>{l.productLine}</td>
                      <td>{l.grupo}</td>
                      <td>{l.equipmentType || <span className="reg-falta">&#9888; none</span>}</td>
                      <td>{l.hv === 'yes' ? 'Sí' : l.hv === 'no' ? 'No' : ''}</td>
                      <td className="nw">{fmt(l.inicio)}</td>
                      <td className="nw">{fmt(l.fin)}</td>
                      <td className="num">{l.unidad === 'PIEZAS' ? '\u2014' : horas(l.horasNetas)}</td>
                      <td className="num">{l.unidad === 'PIEZAS' ? '\u2014' : horas(l.horasSetup)}</td>
                      <td className="num">{horas(l.cantidad)}{l.unidad === 'PIEZAS' ? ' pcs' : ' h'}</td>
                      <td className="num">{l.tarifa === null ? '\u2014' : euros(l.tarifa)}</td>
                      <td className="num"><b>{l.coste === null ? '\u2014' : euros(l.coste)}</b></td>
                    </tr>
                  ))}
                </tbody>
                {filtradas.length > 0 && (
                  <tfoot>
                    <tr>
                      <td colSpan={12}><b>Total</b> ({filtradas.length} rows)</td>
                      <td className="num">{horas(filtradas.reduce((s, l) => s + (l.unidad === 'HORAS' ? l.horasNetas : 0), 0))}</td>
                      <td className="num">{horas(filtradas.reduce((s, l) => s + (l.unidad === 'HORAS' ? l.horasSetup : 0), 0))}</td>
                      <td className="num">{horas(totalHoras)} h{totalPiezas > 0 ? ` + ${horas(totalPiezas)} pcs` : ''}</td>
                      <td />
                      <td className="num"><b>{euros(totalCoste)}</b></td>
                    </tr>
                  </tfoot>
                )}
              </table>
            </div>
          </div>
        </>
      )}
    </div>
  );
}
