import { useCallback, useEffect, useState } from 'react';
import { api } from '../../api/cliente';
import type { EstadoCuenta, PerfilTiming, UsuarioLista } from '../../api/tipos';
import { usePlanificador } from '../../estado/Planificador';

/*
 * 👤 Users/Rights Management (renderUsers de la 4.9.4, solo Admin). El acceso
 * se identifica por el email; quien no está en la lista entra como Read only.
 * Los ResponsibleName alimentan el desplegable Responsible.
 *
 * Modo servidor (hito S3): cada persona entra con su email y una contraseña de
 * la aplicación. La columna Account enseña el estado de su cuenta y el Admin
 * genera aquí contraseñas temporales (alta o «la he olvidado») y desbloquea.
 *
 * Rol Lab User (D55): un Editor que además gestiona las áreas, los bancos (D58) y el horario de los ensayos (D59). Solo se ofrece
 * con la base de datos del servidor (PostgreSQL), donde las áreas son editables;
 * se guarda como «LabUser».
 */

const rolNormal = (r: string) => {
  const s = String(r || '').toLowerCase().replace(/\s/g, '');
  if (s === 'labuser' || s === 'lab_user' || s === 'lab-user') return 'labuser';
  return s === 'admin' || s === 'administrator' ? 'admin' : (s === 'editor' || s === 'edit' || s === 'write' ? 'editor' : 'readonly');
};
// Orden de la tabla: Admin, Lab User, Editor, Read only y después roles desconocidos. La 4.9.4
// hacía «[rol] || 3» y el 0 de Admin contaba como falso: los Admin salían al final (D29, corregido).
const ORDEN_ROL: Record<string, number> = { admin: 0, labuser: 1, editor: 2, readonly: 3 };
const orden = (r: string) => ORDEN_ROL[r] ?? 4;
const rolGuardado = (r: string) => (r === 'admin' ? 'Admin' : r === 'labuser' ? 'LabUser' : r === 'editor' ? 'Editor' : 'ReadOnly');
const PERFILES: Record<PerfilTiming, string> = { solicitante: 'Solicitante', tecnico: 'Técnico', coordinador: 'Coordinador' };
const formularioVacio = { title: '', email: '', role: 'Editor', responsibleName: '', timingRoles: [] as PerfilTiming[] };

export function ConfigUsuarios() {
  const { soloLectura, recargarListas, sesion } = usePlanificador();
  const [usuarios, setUsuarios] = useState<UsuarioLista[] | null>(null);
  const [form, setForm] = useState(formularioVacio);
  const [estado, setEstado] = useState('');
  const modoServidor = !!sesion?.modoServidor;
  const gestionaCuentas = modoServidor && !!sesion?.esAdmin;
  const [cuentas, setCuentas] = useState<Record<string, EstadoCuenta>>({});
  const [temporal, setTemporal] = useState<{ email: string; clave: string } | null>(null);
  // Lab User solo con PostgreSQL (las áreas son editables): se pregunta al backend.
  const [conLabUser, setConLabUser] = useState(false);
  useEffect(() => { api.gestionAreas().then((g) => setConLabUser(!!g.editable)).catch(() => setConLabUser(false)); }, []);

  const leerCuentas = useCallback(() => {
    if (!gestionaCuentas) return;
    api.cuentas()
      .then((lista) => setCuentas(Object.fromEntries(lista.map((c) => [c.email.toLowerCase(), c]))))
      .catch(() => undefined);
  }, [gestionaCuentas]);
  useEffect(() => { leerCuentas(); }, [leerCuentas]);

  useEffect(() => {
    api.usuarios().then((u) => setUsuarios(u.map((x) => ({ ...x, role: rolNormal(x.role) })))).catch(() => setUsuarios([]));
  }, []);

  const guardar = async (lista: UsuarioLista[], perfilEditado?: string) => {
    setUsuarios(lista);
    setEstado('Guardando...');
    try {
      const r = await api.guardarUsuarios(lista.map((u) => ({ title: u.title, email: u.email, role: rolGuardado(u.role),
        responsibleName: u.responsibleName, timingRoles: gestionaCuentas && u.email === perfilEditado ? u.timingRoles : undefined })));
      const vinculadas = r?.timingVinculadas ?? 0;
      setEstado((modoServidor ? 'Guardado.' : 'Guardado en SharePoint.')
        + (vinculadas ? ` ${vinculadas === 1 ? '1 tarea' : vinculadas + ' tareas'} de Timing R&T vinculada${vinculadas === 1 ? '' : 's'}.` : ''));
      await recargarListas();
      leerCuentas();
    } catch (e) {
      setEstado(e instanceof Error && e.message ? `No se pudo guardar: ${e.message}` : 'No se pudo guardar (backend).');
      api.usuarios().then((u) => setUsuarios(u.map((x) => ({ ...x, role: rolNormal(x.role) })))).catch(() => undefined);
    }
  };

  const busca = (email: string) => (usuarios ?? []).find((u) => (u.email || '').toLowerCase() === String(email || '').toLowerCase());
  const anadir = () => {
    const email = form.email.trim().toLowerCase();
    if (!email) { window.alert('Introduce el email.'); return; }
    const registro = { title: form.title.trim() || email, email, role: rolNormal(form.role), responsibleName: form.responsibleName.trim(),
      timingRoles: gestionaCuentas ? form.timingRoles : undefined };
    const lista = (usuarios ?? []).map((u) => ({ ...u }));
    const i = lista.findIndex((u) => (u.email || '').toLowerCase() === email);
    if (i >= 0) lista[i] = { ...lista[i], ...registro }; else lista.push(registro);
    setForm(formularioVacio);
    void guardar(lista, email);
  };
  const editar = (email: string) => {
    const u = busca(email);
    if (u) setForm({ title: u.title || '', email: u.email || '', role: rolGuardado(rolNormal(u.role)), responsibleName: u.responsibleName || '',
      timingRoles: u.timingRoles ?? [] });
  };
  const quitar = (email: string) => {
    if (!window.confirm(`Quitar a ${email} de la lista de usuarios?`)) return;
    void guardar((usuarios ?? []).filter((u) => (u.email || '').toLowerCase() !== String(email).toLowerCase()));
  };

  const claveTemporal = async (email: string) => {
    const cuenta = cuentas[email.toLowerCase()];
    const aviso = cuenta?.tieneClave
      ? `Generar una contraseña temporal nueva para ${email}?\n\nSu contraseña actual deja de valer y sus sesiones abiertas se cierran.`
      : `Generar la contraseña temporal de ${email}?`;
    if (!window.confirm(aviso)) return;
    try {
      const r = await api.claveTemporal(email);
      setTemporal({ email: r.email, clave: r.clave });
      leerCuentas();
    } catch (e) {
      window.alert(`No se pudo generar: ${e instanceof Error ? e.message : String(e)}`);
    }
  };
  const desbloquear = async (email: string) => {
    try {
      await api.desbloquearCuenta(email);
      leerCuentas();
    } catch (e) {
      window.alert(`No se pudo desbloquear: ${e instanceof Error ? e.message : String(e)}`);
    }
  };
  const hora = (iso: string | null) => (iso ? new Date(iso).toLocaleString('es-ES', { dateStyle: 'short', timeStyle: 'short' }) : '');
  const estadoCuenta = (email: string) => {
    const c = cuentas[String(email || '').toLowerCase()];
    if (!c || !c.tieneClave) return <span className="rl-badge rl-readonly" title="Todavía no puede entrar: genera una contraseña temporal">no password</span>;
    if (c.bloqueadaHasta) return <span className="rl-badge" style={{ background: '#7f1d1d', color: '#fff' }} title={`Bloqueada hasta ${hora(c.bloqueadaHasta)}`}>locked</span>;
    if (c.debeCambiar) return <span className="rl-badge" style={{ background: '#92400e', color: '#fff' }} title="Tiene que cambiar la contraseña temporal al entrar">temporary</span>;
    return <span className="rl-badge rl-editor" title={c.ultimoAcceso ? `Último acceso: ${hora(c.ultimoAcceso)}` : 'Aún no ha entrado'}>active</span>;
  };

  const filas = [...(usuarios ?? [])].sort((a, b) => (orden(a.role) - orden(b.role)) || String(a.title || '').localeCompare(String(b.title || '')));
  return (
    <div className="sec" style={{ maxWidth: 1000 }} id="config-usuarios">
      <h2>&#128100; Users/Rights Management</h2>
      {modoServidor ? (
        <div className="hint" id="u-hint-cuentas">
          Cada persona entra con su <b>email</b> y una <b>contraseña de TestBenchPlanner</b> (no la de Windows). Para dar acceso: añádela aquí con su
          rol y pulsa <b>&#128273;</b> para generar una <b>contraseña temporal</b>; la cambiará al entrar. Si alguien la olvida, genera otra.
          Quitar a alguien de la lista borra su cuenta. Quien no esté en la lista no puede entrar.
        </div>
      ) : (
        <div className="hint">
          El acceso se identifica por el <b>email corporativo</b> (SSO). La app muestra el <b>ResponsibleName</b> en vez del email. Cualquiera que no
          este en esta lista entra como <b>Read only</b>. Estos nombres alimentan tambien el desplegable de Responsible.
        </div>
      )}
      {temporal && (
        <div className="card" id="u-temporal" style={{ margin: '10px 0', padding: 12, border: '1px solid #b45309' }}>
          Contraseña temporal de <b>{temporal.email}</b>: <code style={{ fontSize: 16, padding: '2px 8px', userSelect: 'all' }}>{temporal.clave}</code>{' '}
          <button className="btn ghost sm" onClick={() => void navigator.clipboard?.writeText(temporal.clave)}>Copy</button>{' '}
          <button className="btn ghost sm" onClick={() => setTemporal(null)}>Hide</button>
          <div className="hint" style={{ marginTop: 6 }}>Dásela a esa persona por un canal privado. No se volverá a mostrar; al entrar tendrá que cambiarla.</div>
        </div>
      )}
      <div className="tools" style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center', margin: '12px 0' }}>
        <input className="f" id="u-name" placeholder="Nombre (Title)" style={{ minWidth: 170 }} value={form.title} disabled={soloLectura}
          onChange={(e) => setForm({ ...form, title: e.target.value })} />
        <input className="f" id="u-email" placeholder="email@borgwarner.com" style={{ minWidth: 220 }} value={form.email} disabled={soloLectura}
          onChange={(e) => setForm({ ...form, email: e.target.value })} />
        <select className="f" id="u-role" value={form.role} disabled={soloLectura} onChange={(e) => setForm({ ...form, role: e.target.value })}>
          <option value="Admin">Admin</option>
          {(conLabUser || form.role === 'LabUser') && <option value="LabUser" title="Editor + manage areas">Lab User</option>}
          <option value="Editor">Editor</option><option value="ReadOnly">ReadOnly</option>
        </select>
        <input className="f" id="u-resp" placeholder="ResponsibleName (p. ej. A. Vide)" style={{ minWidth: 190 }} value={form.responsibleName}
          disabled={soloLectura} onChange={(e) => setForm({ ...form, responsibleName: e.target.value })} />
        {gestionaCuentas && <fieldset className="timing-perfiles" disabled={soloLectura}>
          <legend>Timing R&amp;T</legend>
          {(Object.keys(PERFILES) as PerfilTiming[]).map((perfil) => <label key={perfil}>
            <input type="checkbox" checked={form.timingRoles.includes(perfil)} onChange={(e) => setForm({
              ...form, timingRoles: e.target.checked ? [...form.timingRoles, perfil] : form.timingRoles.filter((p) => p !== perfil),
            })} /> {PERFILES[perfil]}
          </label>)}
        </fieldset>}
        <button className="btn sm" disabled={soloLectura} onClick={anadir}>&#65291; Add / Update</button>
        <span className="hint" id="u-status" style={{ marginLeft: 'auto' }}>{estado}</span>
      </div>
      <div className="card" style={{ padding: 0 }}>
        <table className="lst users-tbl">
          <thead><tr><th>Name</th><th>Email</th><th>Role</th><th>ResponsibleName</th>{gestionaCuentas && <><th>Timing R&amp;T</th><th>Account</th></>}<th /></tr></thead>
          <tbody>
            {usuarios !== null && !filas.length && (
              <tr><td colSpan={gestionaCuentas ? 7 : 5} className="empty">No users loaded (backend/list missing). Se sembraran los usuarios por defecto al primer guardado.</td></tr>
            )}
            {filas.map((u) => (
              <tr key={u.email}>
                <td><b>{u.title || ''}</b></td><td>{u.email || ''}</td>
                <td><span className={`rl-badge ${u.role === 'admin' ? 'rl-admin' : u.role === 'labuser' ? 'rl-labuser' : u.role === 'editor' ? 'rl-editor' : 'rl-readonly'}`}>{u.role === 'labuser' ? 'lab user' : u.role || 'readonly'}</span></td>
                <td>{u.responsibleName || ''}</td>
                {gestionaCuentas && <td>{u.timingRoles?.map((p) => PERFILES[p]).join(', ') || '—'}</td>}
                {gestionaCuentas && <td className="u-cuenta">{estadoCuenta(u.email)}</td>}
                <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                  {gestionaCuentas && cuentas[String(u.email || '').toLowerCase()]?.bloqueadaHasta && (
                    <button className="btn ghost sm" title="Quitar el bloqueo temporal" onClick={() => void desbloquear(u.email)}>&#128275;</button>
                  )}
                  {gestionaCuentas && (
                    <button className="btn ghost sm u-clave" title="Generar una contraseña temporal" onClick={() => void claveTemporal(u.email)}>&#128273;</button>
                  )}
                  <button className="btn ghost sm" disabled={soloLectura} onClick={() => editar(u.email)}>&#9998;</button>
                  <button className="icbtn del" disabled={soloLectura} onClick={() => quitar(u.email)}>&#128465;</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="hint" style={{ marginTop: 10 }}>
        Los nombres de esta tabla (<b>ResponsibleName</b>) son los que aparecen en el desplegable <b>Responsible</b> de los ensayos y en <b>By
        Responsible</b>. Ya no hay un catalogo de responsables aparte: esta lista es la unica fuente. Aun asi, en un ensayo puedes escribir
        cualquier nombre libremente.
      </div>
    </div>
  );
}
