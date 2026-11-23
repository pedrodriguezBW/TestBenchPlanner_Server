import { useEffect, useState } from 'react';
import { api } from '../../api/cliente';
import type { AreaGestion } from '../../api/tipos';
import { usePlanificador } from '../../estado/Planificador';
import { AREA_TERMICOS, areasDe, ORGANIZACIONES_FIJAS } from '../../utilidades/areas';

/*
 * Gestor de áreas de bancos (D56). Sustituye al bloque fijo de las tres áreas:
 * lista de áreas con su organización y sus bancos y, con la base de datos del
 * servidor (PostgreSQL), alta, cambio de nombre, orden y baja para un Admin o
 * un Lab User (D55). La organización se elige al crear el área y no cambia:
 * «bench by bench» (BW / CTAG en cada banco, como Thermal Benches) o una fija
 * para todos sus bancos. Solo se borra un área sin bancos (el backend lo
 * comprueba y responde 422); Thermal Benches no se borra nunca.
 * Con SharePoint y en local la lista es de solo lectura (las tres de serie).
 */

const estiloSelect = { padding: 6, borderRadius: 6, background: 'var(--navy)', color: 'var(--text)', border: '1px solid var(--line)', fontSize: 12 };

export function GestorAreas() {
  const { vista, sesion, soloLectura, recargar, mostrarMensaje } = usePlanificador();
  const [areas, setAreas] = useState<AreaGestion[] | null>(null);
  const [editable, setEditable] = useState(false);
  const [ocupado, setOcupado] = useState(false);
  const [nombre, setNombre] = useState('');
  const [organizacion, setOrganizacion] = useState(''); // '' = bench by bench
  const [version, setVersion] = useState(0); // vuelve a pintar las cajas si un cambio no se aplica

  const cargar = () => {
    api.gestionAreas()
      .then((g) => { setAreas(g.areas); setEditable(!!g.editable); })
      .catch(() => {
        // Backend anterior o sin conexión: las de la vista, de solo lectura.
        setEditable(false);
        setAreas(areasDe(vista).map((a, i) => ({ code: a.clave, name: a.nombre, organization: a.organizacionFija, order: i, benches: 0 })));
      });
  };
  useEffect(cargar, [vista?.areas?.map((a) => a.clave + a.nombre).join('|')]); // eslint-disable-line react-hooks/exhaustive-deps

  const puedeGestionar = editable && !soloLectura && (!!sesion?.esAdmin || sesion?.rol === 'labuser');

  /** Ejecuta un cambio y relee la lista y la vista (nombres, menú y filtros). */
  const aplicar = async (cambio: () => Promise<unknown>) => {
    setOcupado(true);
    try {
      await cambio();
      await recargar();
    } catch (e) {
      mostrarMensaje(e instanceof Error ? e.message : String(e));
    } finally {
      setOcupado(false);
      setVersion((v) => v + 1);
      cargar();
    }
  };

  const crear = () => {
    const v = nombre.trim();
    if (!v) return;
    const org = organizacion || null;
    const texto = org ? `organisation ${org} for all its benches` : 'organisation chosen bench by bench (BW / CTAG)';
    if (!window.confirm(`Create area "${v}" with ${texto}? The organisation cannot be changed later.`)) return;
    setNombre('');
    void aplicar(() => api.crearArea(v, org));
  };
  const renombrar = (a: AreaGestion, v: string) => {
    v = v.trim();
    if (!v || v === a.name) { setVersion((n) => n + 1); return; }
    void aplicar(() => api.modificarArea(a.code, v, a));
  };
  const mover = (a: AreaGestion, d: number) => void aplicar(() => api.modificarArea(a.code, a.name, a, a.order + d));
  const borrar = (a: AreaGestion) => {
    if (a.benches > 0) {
      mostrarMensaje(`Area "${a.name}" has ${a.benches} bench(es): move its categories to another area in Test Benches before deleting it.`);
      return;
    }
    if (!window.confirm(`Delete area "${a.name}"?`)) return;
    void aplicar(() => api.borrarArea(a));
  };

  const lista = areas ?? [];
  return (
    <div className="cfg-areas" id="gestor-areas" data-solo-nueva="">
      <h3 style={{ margin: '6px 0' }}>&#128450;&#65039; Areas</h3>
      <div className="hint">
        Every bench category belongs to an area. An area either splits its benches by organisation <b>bench by bench</b> (BW / CTAG, like
        Thermal Benches) or applies one <b>fixed organisation</b> to all its benches; this is chosen when the area is created.
        {puedeGestionar
          ? ' Click a name to rename it. An area can only be deleted when it has no benches.'
          : editable
            ? ' Only an Admin or a Lab User can manage areas.'
            : ' Areas can only be managed with the server database (PostgreSQL).'}
      </div>
      <table className="lst">
        <thead><tr><th>#</th><th>Area</th><th>Organisation</th><th>Benches</th>{puedeGestionar && <th />}</tr></thead>
        <tbody>
          {lista.map((a, i) => (
            <tr key={a.code + version} data-area={a.code}>
              <td style={{ color: 'var(--muted)' }}>{i + 1}</td>
              <td>
                {puedeGestionar ? (
                  <input className="f" defaultValue={a.name} style={{ minWidth: 240, fontWeight: 700 }} disabled={ocupado}
                    title={`Area name (code ${a.code})`} data-nombre-area={a.code}
                    onBlur={(e) => renombrar(a, e.target.value)}
                    onKeyDown={(e) => { if (e.key === 'Enter') (e.target as HTMLInputElement).blur(); }} />
                ) : <b title={`Code ${a.code}`}>{a.name}</b>}
              </td>
              <td>{a.organization ? `Fixed: ${a.organization}` : 'Bench by bench (BW / CTAG)'}</td>
              <td>{a.benches}</td>
              {puedeGestionar && (
                <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                  <button className="btn ghost sm" disabled={ocupado || i === 0} onClick={() => mover(a, -1)}>&#9650;</button>
                  <button className="btn ghost sm" disabled={ocupado || i === lista.length - 1} onClick={() => mover(a, 1)}>&#9660;</button>
                  <button className="icbtn del" disabled={ocupado || a.code === AREA_TERMICOS}
                    title={a.code === AREA_TERMICOS ? 'Thermal Benches cannot be deleted' : a.benches ? 'Move its benches to another area first' : 'Delete area'}
                    onClick={() => borrar(a)}>&#128465;</button>
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>
      {puedeGestionar && (
        <div className="tools" style={{ marginTop: 8 }}>
          <input className="f" id="new-area" placeholder="New area name..." style={{ minWidth: 260 }} value={nombre} disabled={ocupado}
            maxLength={60} onChange={(e) => setNombre(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') crear(); }} />
          <select id="new-area-org" style={estiloSelect} value={organizacion} disabled={ocupado} title="Organisation of the benches of the new area"
            onChange={(e) => setOrganizacion(e.target.value)}>
            <option value="">Bench by bench (BW / CTAG)</option>
            {ORGANIZACIONES_FIJAS.map((o) => <option key={o} value={o}>Fixed: {o}</option>)}
          </select>
          <button className="btn sm" disabled={ocupado || !nombre.trim()} onClick={crear}>&#65291; New area</button>
        </div>
      )}
    </div>
  );
}
