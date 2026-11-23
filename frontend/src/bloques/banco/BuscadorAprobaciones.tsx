import { useEffect, useState } from 'react';
import { api } from '../../api/cliente';
import type { Aprobacion } from '../../api/tipos';
import { Dialogo } from '../../componentes/Dialogo';
import { usePlanificador } from '../../estado/Planificador';
import { estiloPildoraAprobacion } from '../../utilidades/textos';

/** Estados de la lista «Global status», tal como se escriben. */
const ESTADOS = ['Approved', 'Pending', 'Rejected', 'NA'];

/*
 * Buscador de Approval ID. La búsqueda se rellena con el nombre del usuario,
 * salvo para un Admin, como en la 4.9.4.
 *
 * Con la base de datos del servidor (PostgreSQL) la base de aprobaciones es
 * propia y un Admin la gestiona desde aquí (D54): alta, cambio y baja. Con
 * SharePoint sigue siendo de solo lectura. Desde la tabla de ensayos se abre
 * con «Select»; desde 🔧 Tools, sin él (solo gestión).
 */
export function BuscadorAprobaciones({ abierto, onElegir, onCerrar }: {
  abierto: boolean;
  onElegir?: (id: string) => void;
  onCerrar: () => void;
}) {
  const { sesion, soloLectura, refrescarAprobaciones } = usePlanificador();
  const [lista, setLista] = useState<Aprobacion[] | null>(null);
  const [busqueda, setBusqueda] = useState('');
  const [cargando, setCargando] = useState(false);
  const [editable, setEditable] = useState(false);
  /** null: cerrado · 'nueva': alta · Aprobacion: cambio de esa. */
  const [editando, setEditando] = useState<Aprobacion | 'nueva' | null>(null);

  const cargar = () => {
    setCargando(true);
    api.aprobaciones().then(setLista).catch(() => setLista((l) => l ?? [])).finally(() => setCargando(false));
  };

  useEffect(() => {
    if (!abierto) return;
    setBusqueda(sesion?.esAdmin || !onElegir ? '' : sesion?.responsable ?? '');
    if (!lista) cargar();
    if (sesion?.esAdmin) api.gestionAprobaciones().then((g) => setEditable(!!g.editable)).catch(() => setEditable(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [abierto]);

  if (!abierto) return null;
  const gestionar = editable && !!sesion?.esAdmin && !soloLectura;
  const q = busqueda.toLowerCase().trim();
  const todas = lista ?? [];
  const filtradas = q ? todas.filter((a) => `${a.id} ${a.raw ?? ''} ${a.requester ?? ''} ${a.service ?? ''}`.toLowerCase().includes(q)) : todas;
  const clase = (a: Aprobacion) => (a.status === 'approved' ? 'idok' : a.status === 'pending' ? 'idwait' : 'idbad');

  /** Tras guardar o borrar: lista nueva y estados de los ensayos al día. */
  const cambiado = () => {
    setEditando(null);
    cargar();
    refrescarAprobaciones();
  };

  const borrar = async (a: Aprobacion) => {
    if (!window.confirm(`Delete approval ${a.id}?\n\nTests that use this ID will show it as not created.`)) return;
    try {
      await api.borrarAprobacion(a.id, a);
      cambiado();
    } catch (e) {
      window.alert(`No se pudo borrar: ${e instanceof Error ? e.message : String(e)}`);
      cargar();
    }
  };

  return (<>
    <Dialogo abierto ancho="xwide" onCerrar={onCerrar}>
      <h3 id="id-title">Approval IDs ({filtradas.length}{q ? ` of ${todas.length}` : ''})</h3>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 10 }}>
        <input id="id-search" className="done-search" placeholder="Search by ID or status..." style={{ marginBottom: 0 }}
          value={busqueda} onChange={(e) => setBusqueda(e.target.value)} />
        {gestionar && <button className="btn sm" style={{ whiteSpace: 'nowrap' }} onClick={() => setEditando('nueva')}>&#65291; New approval</button>}
        <button className="btn ghost sm" style={{ whiteSpace: 'nowrap' }} onClick={cargar} disabled={cargando}>
          {cargando ? '\u23F3 Loading\u2026' : '\u{1F504} Reload live'}
        </button>
      </div>
      <div id="id-list" style={{ maxHeight: '60vh', overflow: 'auto' }}>
        {!filtradas.length ? <div className="empty">{cargando ? 'Loading\u2026' : 'No matching IDs.'}</div> : (
          <table className="done">
            <thead><tr><th>ID</th><th>Requester name</th><th>Service details</th><th>Global status</th><th /></tr></thead>
            <tbody>
              {filtradas.map((a) => (
                <tr key={a.id}>
                  <td><b>{a.id}</b></td>
                  <td>{a.requester || '\u2014'}</td>
                  <td>{a.service || '\u2014'}</td>
                  <td><span className="idpill" style={estiloPildoraAprobacion(clase(a))}>{a.raw || '\u2014'}</span></td>
                  <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                    {gestionar && <>
                      <button className="icbtn" title="Edit approval" onClick={() => setEditando(a)}>&#9998;</button>
                      <button className="icbtn del" title="Delete approval" onClick={() => void borrar(a)}>&#128465;</button>{' '}
                    </>}
                    {onElegir && <button className="ru ed" disabled={soloLectura} onClick={() => onElegir(String(a.id))}>Select</button>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
      {gestionar && <div className="hint" style={{ marginTop: 8 }}>Solo Admin: los cambios se aplican a todo el equipo y quedan registrados.</div>}
      <div style={{ textAlign: 'right', marginTop: 14 }}><button className="btn" onClick={onCerrar}>Close</button></div>
    </Dialogo>
    {editando && <EditorAprobacion aprobacion={editando === 'nueva' ? null : editando} onGuardada={cambiado}
      onCerrar={() => setEditando(null)} />}
  </>);
}

/** Alta o cambio de una aprobación (solo Admin, PostgreSQL). */
function EditorAprobacion({ aprobacion, onGuardada, onCerrar }: {
  aprobacion: Aprobacion | null;
  onGuardada: () => void;
  onCerrar: () => void;
}) {
  const [id, setId] = useState('');
  const [raw, setRaw] = useState(aprobacion?.raw && ESTADOS.includes(aprobacion.raw) ? aprobacion.raw : 'Pending');
  const [requester, setRequester] = useState(aprobacion?.requester ?? '');
  const [service, setService] = useState(aprobacion?.service ?? '');
  const [error, setError] = useState('');
  const [guardando, setGuardando] = useState(false);

  const guardar = async () => {
    setGuardando(true);
    setError('');
    const datos = { raw, requester, service: service.trim() ? service : null };
    try {
      if (aprobacion) await api.modificarAprobacion(aprobacion.id, datos, aprobacion);
      else await api.crearAprobacion(id.trim() ? { ...datos, id: id.trim() } : datos);
      onGuardada();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setGuardando(false);
    }
  };

  return (
    <Dialogo abierto onCerrar={onCerrar}>
      <h3>{aprobacion ? `Edit approval ${aprobacion.id}` : 'New approval'}</h3>
      {!aprobacion && (
        <div className="row-inline">
          <label style={{ minWidth: 130 }}>ID:</label>
          <input value={id} placeholder="(next free ID)" inputMode="numeric" onChange={(e) => setId(e.target.value)} />
        </div>
      )}
      <div className="row-inline">
        <label style={{ minWidth: 130 }}>Requester name:</label>
        <input style={{ flex: 1 }} value={requester} maxLength={500} onChange={(e) => setRequester(e.target.value)} />
      </div>
      <div className="row-inline">
        <label style={{ minWidth: 130 }}>Service details:</label>
        <input style={{ flex: 1 }} value={service} maxLength={500} onChange={(e) => setService(e.target.value)} />
      </div>
      <div className="row-inline">
        <label style={{ minWidth: 130 }}>Global status:</label>
        <select value={raw} onChange={(e) => setRaw(e.target.value)}>
          {ESTADOS.map((s) => <option key={s} value={s}>{s}</option>)}
        </select>
      </div>
      {error && <div className="hint" style={{ color: 'var(--danger)' }}>{error}</div>}
      <div style={{ textAlign: 'right', marginTop: 14, display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
        <button className="btn ghost" onClick={onCerrar}>Cancel</button>
        <button className="btn" disabled={guardando || !requester.trim()} onClick={() => void guardar()}>
          {guardando ? '\u23F3 Saving\u2026' : 'Save'}
        </button>
      </div>
    </Dialogo>
  );
}
