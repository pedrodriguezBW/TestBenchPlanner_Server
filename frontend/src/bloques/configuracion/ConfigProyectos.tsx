import { useCallback, useEffect, useRef, useState } from 'react';
import { api, type FichaProyecto } from '../../api/cliente';
import { colaConfiguracion } from '../../estado/colaConfiguracion';
import { usePlanificador } from '../../estado/Planificador';
import { useAlVaciarConfiguracion } from '../../estado/useColaConfiguracion';
import { etiquetaProyecto, nombreConNumero, numeroLargo, separarNuevo, tieneFormaNumeroNombre } from './nombresProyecto';

/*
 * 📁 Projects (renderProjects de la 4.9.4, Admin y Editor): lista compartida
 * de proyectos que alimenta el desplegable Project. Alta manual, importar un
 * .csv/.txt o pegar una columna; borrar uno o todos. La búsqueda es solo de
 * consulta y funciona también en solo lectura.
 *
 * Facturación: columna «Billing order» (BillingOrder en TestBenchProjects) con
 * la orden contable del proyecto; varias, separadas por «;». Solo la cambia un
 * Admin. Requester y línea de producto no van aquí: son de cada ensayo.
 *
 * Servidor (PostgreSQL): columnas # · Project Number · Name · Billing order. El
 * nombre ya no lleva el número; los dos se editan en la fila (Admin y Lab User)
 * y el cambio se aplica a todos los ensayos y tareas de Timing R&T que usan el
 * proyecto. La etiqueta «número - nombre» sigue siendo lo que ve el resto de la
 * aplicación. Los proyectos que aún llevan el número en el nombre se marcan.
 */

/** parseLines: primera columna de cada línea, sin cabeceras típicas. */
export function leerLineas(texto: string): string[] {
  const salida: string[] = [];
  (texto || '').split(/\r?\n/).forEach((linea) => {
    if (!linea.trim()) return;
    const primera = linea.split(/[\t;,]/)[0].trim().replace(/^"|"$/g, '').trim();
    if (!primera) return;
    if (['project', 'projects', 'responsible', 'responsibles', 'name', 'id'].includes(primera.toLowerCase())) return;
    salida.push(primera);
  });
  return salida;
}

/** Líneas «proyecto<TAB>orden» o «proyecto;orden» (la orden es opcional). */
export function leerLineasConOrden(texto: string): { proyecto: string; orden: string }[] {
  const salida: { proyecto: string; orden: string }[] = [];
  (texto || '').split(/\r?\n/).forEach((linea) => {
    if (!linea.trim()) return;
    const sep = linea.includes('\t') ? '\t' : linea.includes(';') ? ';' : null;
    const partes = sep ? linea.split(sep) : [linea.split(',')[0]];
    const proyecto = (partes[0] ?? '').trim().replace(/^"|"$/g, '').trim();
    if (!proyecto || ['project', 'projects', 'responsible', 'responsibles', 'name', 'id'].includes(proyecto.toLowerCase())) return;
    // Solo se toma como orden algo con forma de orden (letras y números, 6 o más); varias separadas por «;».
    const resto = sep === '\t' ? (partes[1] ?? '') : partes.slice(1).join(';');
    const ordenes = resto.split(';').map((x) => x.trim().replace(/^"|"$/g, '').trim()).filter((x) => /^[A-Za-z0-9]{6,}$/.test(x));
    salida.push({ proyecto, orden: ordenes.join('; ') });
  });
  return salida;
}

export function ConfigProyectos() {
  const { soloLectura, mostrarMensaje, sesion } = usePlanificador();
  const esAdmin = !!sesion?.esAdmin;
  const [proyectos, setProyectos] = useState<string[] | null>(null);
  const [ordenes, setOrdenes] = useState<Record<string, string>>({});
  const [fichas, setFichas] = useState<FichaProyecto[] | undefined>();
  const [nuevo, setNuevo] = useState('');
  const [nuevoNumero, setNuevoNumero] = useState('');
  const [soloConNumero, setSoloConNumero] = useState(false);
  const [revision, setRevision] = useState(0); // vuelve a pintar los campos tras cancelar un cambio
  const [busqueda, setBusqueda] = useState('');
  const [pegado, setPegado] = useState('');
  const fichero = useRef<HTMLInputElement>(null);

  const leer = async () => {
    try {
      const c = await api.catalogos();
      setProyectos(c.projects ?? []);
      setOrdenes(c.projectOrders ?? {});
      setFichas(c.projectItems);
    } catch (e) {
      setProyectos((p) => p ?? []);
      mostrarMensaje(e instanceof Error ? e.message : String(e));
    }
  };
  useEffect(() => { void leer(); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // Guardado terminado: se relee la lista (si falló, vuelve a lo que de verdad está guardado).
  const alVaciar = useCallback(() => { void leer(); }, []); // eslint-disable-line react-hooks/exhaustive-deps
  useAlVaciarConfiguracion(alVaciar);

  /**
   * La lista cambia en el acto y se guarda en segundo plano (cola de configuración).
   * Los bancos se envían como estén en ese momento y sin su categoría de facturación
   * (así se conserva la guardada). Las órdenes solo las envía un Admin; si no se
   * envían, el backend conserva las guardadas.
   */
  const guardar = (lista: string[], aviso?: string, nuevasOrdenes?: Record<string, string>) => {
    const base = nuevasOrdenes ?? ordenes;
    const filtradas = Object.fromEntries(lista.filter((p) => (base[p] ?? '').trim()).map((p) => [p, base[p].trim()]));
    setProyectos(lista);
    if (esAdmin) setOrdenes(filtradas);
    // Id de cada proyecto que ve esta pantalla: si otra persona lo ha renombrado mientras tanto,
    // se envía con su nombre actual (si no, el guardado lo borraría y lo crearía de nuevo con el antiguo).
    const idPorEtiqueta = new Map((fichas ?? []).map((f) => [f.label, f.id] as const));
    colaConfiguracion.encolar({
      clave: 'proyectos',
      fusionable: true,
      ejecutar: async () => {
        const c = await api.catalogos();
        const bancos = (c.benches ?? []).map(({ equipmentType: _sinCategoria, ...b }) => b);
        const actual = new Map((c.projectItems ?? []).map((f) => [f.id, f.label] as const));
        const vigente = (p: string) => { const id = idPorEtiqueta.get(p); return (id && actual.get(id)) || p; };
        const enviar = lista.map(vigente);
        const ordenesVigentes = Object.fromEntries(Object.entries(filtradas).map(([p, o]) => [vigente(p), o]));
        await api.guardarCatalogos(enviar, bancos, esAdmin ? ordenesVigentes : undefined);
      },
    });
    if (aviso) window.alert(aviso);
  };
  const anadirVarios = (nuevos: { proyecto: string; orden: string }[], aviso: (n: number) => string) => {
    const lista = [...(proyectos ?? [])];
    const conOrdenes = { ...ordenes };
    let n = 0;
    nuevos.forEach(({ proyecto: v, orden }) => {
      if (!lista.includes(v)) { lista.push(v); n++; }
      if (esAdmin && orden) conOrdenes[v] = orden;
    });
    void guardar(lista, aviso(n), conOrdenes);
  };
  const cambiarOrden = (p: string, valor: string) => {
    const v = valor.split(/[;\n]+/).map((x) => x.trim()).filter(Boolean).join('; ');
    if (v === (ordenes[p] ?? '')) return;
    void guardar(proyectos ?? [], undefined, { ...ordenes, [p]: v });
  };
  /** Alta en el servidor: número (formato largo) y nombre por separado. */
  const anadirServidor = () => {
    const nombre = nuevo.trim();
    const numero = numeroLargo(nuevoNumero);
    if (!nombre) return;
    if (tieneFormaNumeroNombre(nombre) || nombreConNumero(nombre)) { window.alert('Write the number in Project Number, not in the name.'); return; }
    if (numero && !/^[0-9]{1,40}$/.test(numero)) { window.alert('The project number can only contain digits.'); return; }
    if ((fichas ?? []).some((f) => f.name === nombre)) { window.alert(`There is already a project called \u00ab${nombre}\u00bb.`); return; }
    const etiqueta = etiquetaProyecto(nombre, numero);
    setNuevo('');
    setNuevoNumero('');
    if (!(proyectos ?? []).includes(etiqueta)) void guardar([etiqueta, ...(proyectos ?? [])]);
  };
  /** Cambio de nombre y/o número en la fila: el backend lo aplica también a ensayos y tareas de Timing R&T. */
  const editar = (f: FichaProyecto, cambio: { name?: string; number?: string }) => {
    const nombre = (cambio.name ?? f.name).trim();
    const numero = numeroLargo(cambio.number ?? f.number);
    if (nombre === f.name && numero === f.number) { setRevision((r) => r + 1); return; }
    const rechazar = (texto: string) => { window.alert(texto); setRevision((r) => r + 1); };
    const otros = (fichas ?? []).filter((o) => o.id !== f.id);
    if (!nombre) return rechazar('The project needs a name.');
    if (nombre !== f.name && tieneFormaNumeroNombre(nombre)) return rechazar('Write the number in Project Number, not in the name.');
    if (numero && !/^[0-9]{1,40}$/.test(numero)) return rechazar('The project number can only contain digits.');
    if (otros.some((o) => o.name === nombre)) return rechazar(`There is already a project called \u00ab${nombre}\u00bb.`);
    const etiqueta = etiquetaProyecto(nombre, numero);
    if (etiqueta !== f.label && otros.some((o) => o.label === etiqueta)) return rechazar(`There is already a project \u00ab${etiqueta}\u00bb.`);
    if (etiqueta !== f.label && !window.confirm(`Change \u00ab${f.label}\u00bb to \u00ab${etiqueta}\u00bb?\n\n`
      + 'Every test (open and closed) and every Timing R&T task that uses this project will be updated.')) {
      setRevision((r) => r + 1);
      return;
    }
    const anterior = { nombre: f.name, numero: f.number };
    setFichas((l) => l?.map((o) => (o.id === f.id ? { ...o, name: nombre, number: numero, label: etiqueta, numberInName: nombreConNumero(nombre) } : o)));
    if (etiqueta !== f.label) {
      setProyectos((l) => l?.map((p) => (p === f.label ? etiqueta : p)) ?? l);
      setOrdenes((o) => {
        if (o[f.label] === undefined) return o;
        const n = { ...o, [etiqueta]: o[f.label] };
        delete n[f.label];
        return n;
      });
    }
    colaConfiguracion.encolar({
      clave: 'proyecto-' + f.id,
      fusionable: false,
      ejecutar: async () => {
        const r = await api.editarProyecto(f.id, nombre, numero, anterior.nombre, anterior.numero);
        if (r.tests || r.timingTasks) {
          mostrarMensaje(`Project updated to \u00ab${r.label}\u00bb: ${r.tests} test(s) and ${r.timingTasks} Timing R&T task(s) updated.`);
        }
      },
    });
  };
  if (proyectos === null) return <div className="sec"><h2>&#128193; Projects</h2><div className="hint">Loading&hellip;</div></div>;
  const q = busqueda.toLowerCase();
  const porEtiqueta = new Map((fichas ?? []).map((f) => [f.label, f] as const));
  const conNumeroEnNombre = fichas ? proyectos.filter((p) => nombreConNumero(porEtiqueta.get(p)?.name ?? p)).length : 0;
  const visibles = proyectos.filter((p) => (!q || p.toLowerCase().includes(q))
    && (!soloConNumero || nombreConNumero(porEtiqueta.get(p)?.name ?? p)));
  // D62: solo un Admin o un Lab User cambia la lista; un Editor solo la ve.
  const puedeEditar = (esAdmin || sesion?.rol === 'labuser') && !soloLectura;
  const bloqueado = !puedeEditar;

  const celdaOrden = (p: string) => (
    <td data-orden={p} data-solo-nueva="">
      {esAdmin && !soloLectura ? (
        <input className="f" key={`${p}|${ordenes[p] ?? ''}`} defaultValue={ordenes[p] ?? ''} placeholder="463B…" disabled={bloqueado}
          style={{ minWidth: 220 }} onBlur={(e) => cambiarOrden(p, e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') (e.target as HTMLInputElement).blur(); }} />
      ) : <span style={{ color: ordenes[p] ? undefined : 'var(--muted)' }}>{ordenes[p] || '\u2014'}</span>}
    </td>
  );
  // Servidor: # · Project Number · Name · Billing order. Una fila recién añadida (aún sin id) no se edita hasta guardarse.
  const tablaServidor = () => (
    <div className="card" style={{ maxHeight: '52vh', overflow: 'auto', padding: 0 }}>
      <table className="lst" id="proj-tabla">
        <thead><tr><th>#</th><th>Project Number</th><th>Name</th><th>Billing order</th><th /></tr></thead>
        <tbody>
          {visibles.length ? visibles.map((p, i) => {
            const f = porEtiqueta.get(p) ?? { id: 0, label: p, ...(() => { const x = separarNuevo(p); return { name: x.nombre, number: x.numero }; })() };
            const editable = puedeEditar && f.id > 0;
            const aviso = nombreConNumero(f.name);
            return (
              <tr key={p} data-proyecto={f.id || undefined}>
                <td style={{ color: 'var(--muted)' }}>{i + 1}</td>
                <td>{editable ? <input className="f" aria-label={'Project Number: ' + p} style={{ width: 130 }} inputMode="numeric"
                  key={`n|${f.id}|${f.number}|${revision}`} defaultValue={f.number}
                  onBlur={(e) => editar(f, { number: e.target.value })}
                  onKeyDown={(e) => { if (e.key === 'Enter') e.currentTarget.blur(); }} /> : (f.number || '\u2014')}</td>
                <td><div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>{editable ? <input className="f" aria-label={'Name: ' + p} style={{ minWidth: 320, flex: 1 }}
                  key={`t|${f.id}|${f.name}|${revision}`} defaultValue={f.name}
                  title={aviso ? 'The name still includes the number: move it to Project Number.' : undefined}
                  onBlur={(e) => editar(f, { name: e.target.value })}
                  onKeyDown={(e) => { if (e.key === 'Enter') e.currentTarget.blur(); }} /> : f.name}
                  {aviso && <span title="The name still includes the number: move it to Project Number.">&#9888;&#65039;</span>}
                </div></td>
                {celdaOrden(p)}
                <td style={{ textAlign: 'right' }}>
                  {puedeEditar && <button className="icbtn del" title="Delete" onClick={() => void guardar(proyectos.filter((x) => x !== p))}>&#128465;</button>}
                </td>
              </tr>
            );
          }) : <tr><td colSpan={5} className="empty">No projects.</td></tr>}
        </tbody>
      </table>
    </div>
  );

  return (
    <div className="sec" id="config-proyectos">
      <h2>&#128193; Projects</h2>
      <div className="hint">Shared project list feeding the Project dropdown in every bench. Add manually, import a .csv/.txt or paste a column.</div>
      {/* data-solo-nueva: lo que la 4.9.4 no tiene (facturación); el laboratorio de paridad lo deja fuera (D38). */}
      <div className="hint" data-solo-nueva=""><b>Billing order</b>: accounting order used by Billing (several separated by ;). Only an Admin can
        change it; you can paste &laquo;project&raquo; and &laquo;order&raquo; as two columns (tab or ;).</div>
      {!puedeEditar && (
        <div className="hint" id="proj-solo-ver">&#128065; View only: only an Admin or a Lab User can add, import or delete projects.</div>
      )}
      {puedeEditar ? <><div className="tools">
        {fichas && <input className="f" id="proj-new-number" placeholder="Project Number" style={{ width: 150 }} value={nuevoNumero}
          inputMode="numeric" disabled={bloqueado} data-solo-nueva="" onChange={(e) => setNuevoNumero(e.target.value)} />}
        <input className="f" id="proj-new" placeholder={fichas ? 'Name (without the number)...' : 'New project...'} style={{ minWidth: 300 }}
          value={nuevo} disabled={bloqueado} onChange={(e) => setNuevo(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter' && fichas) anadirServidor(); }} />
        <button className="btn sm" disabled={bloqueado} onClick={() => {
          if (fichas) { anadirServidor(); return; }
          const v = nuevo.trim();
          if (!v) return;
          setNuevo('');
          if (!proyectos.includes(v)) void guardar([v, ...proyectos]);
        }}>&#65291; Add</button>
        <button className="btn ghost sm" disabled={bloqueado} onClick={() => fichero.current?.click()}>&#11014; Import CSV/TXT</button>
        <input ref={fichero} id="proj-file" type="file" accept=".csv,.txt" style={{ display: 'none' }} onChange={(e) => {
          const f = e.target.files?.[0];
          e.target.value = '';
          if (!f) return;
          if (/\.xlsx?$/i.test(f.name)) { window.alert('Please save the Excel as .csv first, then import it here.'); return; }
          void f.text().then((t) => anadirVarios(leerLineasConOrden(t), (n) => `${n} items imported.`));
        }} />
        <input className="f" id="proj-search" placeholder="Search..." value={busqueda} onChange={(e) => setBusqueda(e.target.value)}
          style={{ marginLeft: 'auto', minWidth: 200 }} />
        <button className="btn ghost sm" disabled={bloqueado} onClick={() => { if (window.confirm('Clear the whole list?')) void guardar([]); }}>&#128465; Clear all</button>
      </div>
      <textarea id="proj-paste" placeholder="Paste a table/column here (one project per line) then press Add pasted" value={pegado}
        disabled={bloqueado} onChange={(e) => setPegado(e.target.value)} />
      <div className="tools">
        <button className="btn sm" disabled={bloqueado} onClick={() => { const t = pegado; setPegado(''); anadirVarios(leerLineasConOrden(t), (n) => `${n} added.`); }}>&#65291; Add pasted</button>
        <span className="hint">{proyectos.length} projects{q ? ` \u00b7 ${visibles.length} shown` : ''}</span>
      </div></> : (
        <div className="tools">
          <input className="f" id="proj-search" placeholder="Search..." value={busqueda} onChange={(e) => setBusqueda(e.target.value)} style={{ minWidth: 260 }} />
          <span className="hint">{proyectos.length} projects{q ? ` \u00b7 ${visibles.length} shown` : ''}</span>
        </div>
      )}
      {fichas && conNumeroEnNombre > 0 && (
        <div className="hint" id="proj-con-numero" data-solo-nueva="">&#9888;&#65039; {conNumeroEnNombre} project(s) still have the number in
          the name. Move it to <b>Project Number</b> and leave only the name: tests and Timing R&amp;T tasks are updated automatically.{' '}
          <button className="btn ghost sm" onClick={() => setSoloConNumero((v) => !v)}>{soloConNumero ? 'Show all' : 'Show them'}</button>
        </div>
      )}
      {fichas ? tablaServidor() : <div className="card" style={{ maxHeight: '52vh', overflow: 'auto', padding: 0 }}>
        <table className="lst">
          <thead><tr><th>#</th><th>Project</th><th data-solo-nueva="">Billing order</th><th /></tr></thead>
          <tbody>
            {visibles.length ? visibles.map((p, i) => (
              <tr key={p}>
                <td style={{ color: 'var(--muted)' }}>{i + 1}</td><td>{p}</td>
                {celdaOrden(p)}
                <td style={{ textAlign: 'right' }}>
                  {puedeEditar && <button className="icbtn del" title="Delete" onClick={() => void guardar(proyectos.filter((x) => x !== p))}>&#128465;</button>}
                </td>
              </tr>
            )) : <tr><td colSpan={4} className="empty">No projects.</td></tr>}
          </tbody>
        </table>
      </div>}
    </div>
  );
}
