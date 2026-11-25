import { useEffect, useMemo, useState } from 'react';
import { timingApi, type SinVincularTiming, type VinculoTiming } from './cliente';
import type { OpcionesTiming } from './modelo';

const POR_PAGINA = 25;
type Pestana = 'personas' | 'proyectos' | 'manuales';
const normal = (texto: string) => texto.normalize('NFD').replace(/\p{M}/gu, '').toLocaleLowerCase('es-ES');

/**
 * Nombres y proyectos del Excel de Timing R&T que todavía no corresponden a un
 * usuario o a un proyecto de TestBenchPlanner. Se vinculan solos al dar de alta
 * la persona o el proyecto; aquí se vinculan a mano los que se escriben distinto.
 */
export function DatosSinVincular({ opciones, ocupado, guardar }: {
  opciones: OpcionesTiming; ocupado: boolean;
  guardar: (ejecutar: () => Promise<unknown>, exito: string) => Promise<boolean>;
}) {
  const [datos, setDatos] = useState<SinVincularTiming>();
  const [error, setError] = useState('');
  const [revision, setRevision] = useState(0);
  const [pestana, setPestana] = useState<Pestana>('personas');
  const [busca, setBusca] = useState('');
  const [pagina, setPagina] = useState(0);
  const [elegidos, setElegidos] = useState<Record<string, string>>({});
  const [aviso, setAviso] = useState('');

  useEffect(() => {
    let cancelar = false;
    timingApi.sinVincular().then((r) => { if (!cancelar) { setDatos(r); setError(''); } })
      .catch((e: unknown) => { if (!cancelar) setError(e instanceof Error ? e.message : String(e)); });
    return () => { cancelar = true; };
  }, [revision]);

  const personas = useMemo(() => [...opciones.personas].sort((a, b) => a.nombre.localeCompare(b.nombre, 'es')), [opciones]);
  const proyectos = useMemo(() => [...opciones.proyectos].sort((a, b) => a.nombre.localeCompare(b.nombre, 'es', { numeric: true })), [opciones]);
  const filtro = normal(busca.trim());
  const coincide = (...textos: (string | undefined)[]) => !filtro || textos.some((t) => t && normal(t).includes(filtro));
  const filas = !datos ? [] : pestana === 'personas' ? datos.personas.filter((p) => coincide(p.nombre))
    : pestana === 'proyectos' ? datos.proyectos.filter((p) => coincide(p.nombre, p.cuenta))
      : datos.vinculos.filter((v) => coincide(v.nombre, v.cuenta, v.destinoNombre));
  const paginas = Math.max(1, Math.ceil(filas.length / POR_PAGINA));
  const actual = Math.min(pagina, paginas - 1);
  const visibles = filas.slice(actual * POR_PAGINA, (actual + 1) * POR_PAGINA);

  const ejecutar = (accion: () => Promise<{ vinculadas?: number; ok?: boolean }>, exito: (n: number) => string) => {
    let vinculadas = 0;
    void guardar(async () => { vinculadas = (await accion()).vinculadas ?? 0; }, '')
      .then((ok) => { if (ok) { setRevision((v) => v + 1); setAviso(exito(vinculadas)); } });
  };
  const tareas = (n: number) => n === 1 ? '1 tarea vinculada.' : `${n} tareas vinculadas.`;
  const vincular = (tipo: 'persona' | 'proyecto', nombre: string, cuenta: string, clave: string) => {
    const destino = elegidos[clave];
    if (!destino) return;
    setAviso('');
    ejecutar(() => timingApi.guardarVinculo({ tipo, nombre, cuenta, destino }), tareas);
  };
  const quitar = (v: VinculoTiming) => {
    if (!window.confirm(`¿Quitar la correspondencia de «${v.nombre}${v.cuenta ? ' · ' + v.cuenta : ''}»? Las tareas que se vincularon con ella vuelven a quedar sin vincular, salvo que coincidan automáticamente.`)) return;
    setAviso('');
    ejecutar(() => timingApi.borrarVinculo(v), (n) => 'Correspondencia quitada' + (n ? ` (${n === 1 ? '1 tarea desvinculada' : n + ' tareas desvinculadas'}).` : '.'));
  };
  const cambiar = (p: Pestana) => { setPestana(p); setPagina(0); };
  const elegir = (clave: string, valor: string) => setElegidos((e) => ({ ...e, [clave]: valor }));

  return <section className="card timing-sin-vincular">
    <h2>Datos sin vincular</h2>
    <p className="hint">Las tareas importadas del Excel se vinculan solas cuando se da de alta en Users una persona con el mismo
      nombre o correo, o en Projects un proyecto con el mismo nombre y número. Aquí puedes vincular a mano los que se escriben
      distinto; la correspondencia se aplica a todas sus tareas y a las próximas importaciones.</p>
    <div className="timing-barra-lista">
      <div className="timing-segmentos" role="group" aria-label="Tipo de dato">
        <button className="btn ghost" aria-pressed={pestana === 'personas'} onClick={() => cambiar('personas')}>
          Personas ({datos?.personas.length ?? '…'})</button>
        <button className="btn ghost" aria-pressed={pestana === 'proyectos'} onClick={() => cambiar('proyectos')}>
          Proyectos ({datos?.proyectos.length ?? '…'})</button>
        <button className="btn ghost" aria-pressed={pestana === 'manuales'} onClick={() => cambiar('manuales')}>
          Correspondencias manuales ({datos?.vinculos.length ?? '…'})</button>
      </div>
      <input className="f" type="search" aria-label="Buscar en datos sin vincular" placeholder="Buscar" value={busca}
        onChange={(e) => { setBusca(e.target.value); setPagina(0); }} />
      <button className="btn ghost" disabled={ocupado}
        onClick={() => { setAviso(''); ejecutar(() => timingApi.vincular(), (n) => n ? tareas(n) : 'No había nada nuevo que vincular.'); }}>
        Comprobar ahora</button>
    </div>
    {error && <p className="timing-mensaje error" role="alert">{error}</p>}
    {aviso && <p className="timing-mensaje" role="status">{aviso}</p>}
    {!datos && !error && <p className="hint">Cargando…</p>}
    {datos && <div className="timing-tabla"><table className="lst">
      {pestana === 'personas' && <>
        <thead><tr><th>Nombre en el Excel</th><th>Responsable</th><th>Técnico</th><th>Abiertas</th><th>Vincular con</th><th /></tr></thead>
        <tbody>{(visibles as SinVincularTiming['personas']).map((p) => {
          const clave = 'persona:' + p.nombre;
          return <tr key={clave}>
            <td>{p.nombre}{p.coincidencias > 1 && <div className="hint">Coincide con {p.coincidencias} usuarios</div>}</td>
            <td className="timing-numero">{p.responsable}</td><td className="timing-numero">{p.tecnico}</td>
            <td className="timing-numero">{p.abiertas}</td>
            <td><select className="f" aria-label={'Usuario para ' + p.nombre} value={elegidos[clave] ?? ''} onChange={(e) => elegir(clave, e.target.value)}>
              <option value="">Elegir usuario…</option>
              {personas.map((u) => <option key={u.email} value={u.email}>{u.nombre} · {u.email}</option>)}
            </select></td>
            <td><button className="btn sm" disabled={ocupado || !elegidos[clave]} onClick={() => vincular('persona', p.nombre, '', clave)}>Vincular</button></td>
          </tr>;
        })}</tbody>
      </>}
      {pestana === 'proyectos' && <>
        <thead><tr><th>Proyecto en el Excel</th><th>N.º</th><th>Tareas</th><th>Abiertas</th><th>Vincular con</th><th /></tr></thead>
        <tbody>{(visibles as SinVincularTiming['proyectos']).map((p) => {
          const clave = 'proyecto:' + p.nombre + '|' + p.cuenta;
          return <tr key={clave}>
            <td>{p.nombre || '—'}</td><td className="timing-numero">{p.cuenta || '—'}</td>
            <td className="timing-numero">{p.tareas}</td><td className="timing-numero">{p.abiertas}</td>
            <td><select className="f" aria-label={'Proyecto para ' + (p.nombre || p.cuenta)} value={elegidos[clave] ?? ''} onChange={(e) => elegir(clave, e.target.value)}>
              <option value="">Elegir proyecto…</option>
              {proyectos.map((x) => <option key={x.id} value={x.nombre}>{x.etiqueta || x.nombre}</option>)}
            </select></td>
            <td><button className="btn sm" disabled={ocupado || !elegidos[clave]} onClick={() => vincular('proyecto', p.nombre, p.cuenta, clave)}>Vincular</button></td>
          </tr>;
        })}</tbody>
      </>}
      {pestana === 'manuales' && <>
        <thead><tr><th>Tipo</th><th>En el Excel</th><th>Vinculado con</th><th>Por</th><th>Fecha</th><th /></tr></thead>
        <tbody>{(visibles as VinculoTiming[]).map((v) => <tr key={v.tipo + ':' + v.nombre + '|' + v.cuenta}>
          <td>{v.tipo === 'persona' ? 'Persona' : 'Proyecto'}</td>
          <td>{v.nombre}{v.cuenta ? ' · ' + v.cuenta : ''}</td>
          <td>{v.destinoNombre}{!v.vigente && <div className="hint">Ya no existe en TestBenchPlanner</div>}</td>
          <td>{v.autor}</td><td>{new Date(v.fecha).toLocaleDateString('es-ES')}</td>
          <td><button className="btn ghost sm" disabled={ocupado} onClick={() => quitar(v)}>Quitar</button></td>
        </tr>)}</tbody>
      </>}
      {!visibles.length && <tbody><tr><td colSpan={6} className="empty">
        {pestana === 'manuales' ? 'No hay correspondencias manuales.' : 'Todo está vinculado.'}</td></tr></tbody>}
    </table></div>}
    {paginas > 1 && <div className="timing-acciones">
      <button className="btn ghost sm" disabled={actual === 0} onClick={() => setPagina(actual - 1)}>Anterior</button>
      <span>{actual + 1} de {paginas}</span>
      <button className="btn ghost sm" disabled={actual + 1 === paginas} onClick={() => setPagina(actual + 1)}>Siguiente</button>
    </div>}
  </section>;
}
