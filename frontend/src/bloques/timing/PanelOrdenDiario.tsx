import { useEffect, useMemo, useState, type DragEvent } from 'react';
import { timingApi } from './cliente';
import { EstadoTiming } from './DetalleTiming';
import {
  enlaceTiming, grupoOrden, GRUPOS_ORDEN, nuevoOrdenDiario, ORDEN_FINAL, ORDEN_POR_DEFECTO, ordenarCola, tecnicoTiming,
  type GrupoOrden, type OpcionesTiming, type TareaTiming,
} from './modelo';

/*
 * Panel «Orden diario» (coordinador): la cola de un técnico, en el orden en que
 * la verá en «Mi cola». Se reordena arrastrando o con las flechas y se pasa una
 * tarea entre «Orden del día», «En cola» y «Genéricas y ausencias». Nada se
 * guarda hasta pulsar «Guardar orden»: el día se numera 1, 2, 3…, la cola queda
 * en 9 y las genéricas en 99 (como en el Excel de Timing R&T).
 */

const ORDEN_GRUPOS: GrupoOrden[] = ['dia', 'cola', 'final'];
type Grupos = Record<GrupoOrden, TareaTiming[]>;
const errorTexto = (e: unknown) => (e instanceof Error ? e.message : String(e));

function agrupar(tareas: TareaTiming[]): Grupos {
  const g: Grupos = { dia: [], cola: [], final: [] };
  ordenarCola(tareas).forEach((t) => g[grupoOrden(t)].push(t));
  return g;
}

export function PanelOrdenDiario({ opciones, revision, ocupado, guardar }: {
  opciones: OpcionesTiming;
  revision: number;
  ocupado: boolean;
  guardar: (ejecutar: () => Promise<unknown>, exito: string) => Promise<boolean>;
}) {
  const [tareas, setTareas] = useState<TareaTiming[]>();
  const [error, setError] = useState('');
  const [tecnico, setTecnico] = useState(() => sessionStorage.getItem('tbp-timing-orden-tecnico') ?? '');
  const [grupos, setGrupos] = useState<Grupos>();
  const [arrastrada, setArrastrada] = useState<number>();
  const [destino, setDestino] = useState<string>();

  useEffect(() => {
    let cancelar = false;
    timingApi.lista('planificacion').then((t) => { if (!cancelar) { setTareas(t); setError(''); } })
      .catch((e: unknown) => { if (!cancelar) setError(errorTexto(e)); });
    return () => { cancelar = true; };
  }, [revision]);

  // Técnicos habilitados y los que tienen tareas abiertas (aunque ya no tengan el perfil).
  const tecnicos = useMemo(() => {
    const cuenta = new Map<string, { nombre: string; total: number; dia: number }>();
    opciones.personas.filter((p) => p.tecnico).forEach((p) => cuenta.set(p.email, { nombre: p.nombre, total: 0, dia: 0 }));
    (tareas ?? []).forEach((t) => {
      if (!t.tecnico) return;
      const c = cuenta.get(t.tecnico) ?? { nombre: tecnicoTiming(t), total: 0, dia: 0 };
      c.total++;
      if (grupoOrden(t) === 'dia') c.dia++;
      cuenta.set(t.tecnico, c);
    });
    return [...cuenta.entries()].map(([email, c]) => ({ email, ...c }))
      .sort((a, b) => a.nombre.localeCompare(b.nombre, 'es'));
  }, [opciones, tareas]);
  const sinTecnico = (tareas ?? []).filter((t) => !t.tecnico).length;
  const elegido = tecnicos.some((t) => t.email === tecnico) ? tecnico : tecnicos[0]?.email ?? '';

  const original = useMemo(() => agrupar((tareas ?? []).filter((t) => t.tecnico === elegido)), [tareas, elegido]);
  useEffect(() => { setGrupos(original); }, [original]);
  const actual = grupos ?? original;
  const cambios = nuevoOrdenDiario(actual);
  const movidas = new Set(cambios.map((c) => c.tarea.id));

  const elegir = (email: string) => {
    if (email === elegido) return;
    if (cambios.length && !window.confirm('Hay cambios de orden sin guardar. ¿Descartarlos?')) return;
    sessionStorage.setItem('tbp-timing-orden-tecnico', email);
    setTecnico(email);
  };
  /** Mueve una tarea al grupo indicado, delante de «antes» (o al final del grupo). */
  const mover = (id: number, grupo: GrupoOrden, antes?: number) => {
    setGrupos((g0) => {
      const g = g0 ?? original;
      const tarea = ORDEN_GRUPOS.flatMap((k) => g[k]).find((t) => t.id === id);
      if (!tarea || id === antes) return g;
      const nuevo: Grupos = { dia: g.dia.filter((t) => t.id !== id), cola: g.cola.filter((t) => t.id !== id), final: g.final.filter((t) => t.id !== id) };
      const lista = nuevo[grupo];
      if (grupo !== 'dia') {
        // En la cola (9) y en genéricas (99) el orden lo da la prioridad: no se colocan a mano.
        lista.push(tarea);
        const valor = (t: TareaTiming) => grupoOrden(t) === grupo ? t.ordenDiario ?? ORDEN_POR_DEFECTO : grupo === 'cola' ? ORDEN_POR_DEFECTO : ORDEN_FINAL;
        lista.sort((a, b) => valor(a) - valor(b) || (a.prioridad ?? Infinity) - (b.prioridad ?? Infinity) || a.id - b.id);
        return nuevo;
      }
      const i = antes === undefined ? -1 : lista.findIndex((t) => t.id === antes);
      lista.splice(i < 0 ? lista.length : i, 0, tarea);
      return nuevo;
    });
  };
  const desplazar = (grupo: GrupoOrden, i: number, paso: -1 | 1) => {
    const lista = actual[grupo];
    const j = i + paso;
    if (grupo !== 'dia' || j < 0 || j >= lista.length) {
      // Al salir por arriba o por abajo pasa al grupo vecino.
      const k = ORDEN_GRUPOS.indexOf(grupo) + paso;
      if (k < 0 || k >= ORDEN_GRUPOS.length) return;
      const vecino = ORDEN_GRUPOS[k];
      mover(lista[i].id, vecino, paso === 1 ? actual[vecino][0]?.id : undefined);
      return;
    }
    mover(lista[i].id, grupo, paso === -1 ? lista[j].id : lista[j + 1]?.id);
  };
  const soltar = (e: DragEvent, grupo: GrupoOrden, antes?: number) => {
    e.preventDefault();
    e.stopPropagation();
    const id = Number(e.dataTransfer.getData('text/plain')) || arrastrada;
    setArrastrada(undefined);
    setDestino(undefined);
    if (id) mover(id, grupo, antes);
  };
  const sobre = (e: DragEvent, clave: string) => {
    if (arrastrada === undefined) return;
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';
    if (destino !== clave) setDestino(clave);
  };
  const guardarOrden = () => {
    if (!cambios.length) return;
    void guardar(() => timingApi.ordenDiario(elegido, cambios), `Orden diario guardado (${cambios.length} ${cambios.length === 1 ? 'tarea' : 'tareas'}).`);
  };

  if (error) return <div className="timing-mensaje error" role="alert">{error}</div>;
  if (!tareas) return <p className="hint">Cargando tareas…</p>;
  const nombre = tecnicos.find((t) => t.email === elegido)?.nombre ?? '';

  return <div className="timing-orden">
    <section className="card timing-orden-tecnicos" aria-label="Técnicos">
      <label className="timing-campo"><span>Técnico</span>
        <select className="f" value={elegido} onChange={(e) => elegir(e.target.value)}>
          {tecnicos.map((t) => <option key={t.email} value={t.email}>{t.nombre} ({t.total})</option>)}
        </select>
      </label>
      <div className="timing-orden-chips" role="group" aria-label="Cambiar de técnico">
        {tecnicos.map((t) => <button key={t.email} type="button" className="btn ghost sm" aria-pressed={t.email === elegido}
          onClick={() => elegir(t.email)} title={`${t.total} tareas abiertas · ${t.dia} en el orden del día`}>
          {t.nombre} <span className="timing-orden-cuenta">{t.dia}/{t.total}</span>
        </button>)}
      </div>
      {sinTecnico > 0 && <p className="hint">{sinTecnico} tareas abiertas sin técnico: se asignan desde Planificación.</p>}
    </section>

    {!elegido ? <p className="hint">No hay técnicos con perfil de Timing R&amp;T.</p> : <>
      <div className={'timing-orden-barra' + (cambios.length ? ' con-cambios' : '')} role="status">
        <span>{cambios.length ? `${cambios.length} ${cambios.length === 1 ? 'cambio' : 'cambios'} sin guardar en la cola de ${nombre}`
          : `Cola de ${nombre}: arrastra las tareas o usa las flechas para cambiar el orden.`}</span>
        <button className="btn ghost sm" disabled={!cambios.length || ocupado} onClick={() => setGrupos(original)}>Descartar</button>
        <button className="btn sm" disabled={!cambios.length || ocupado} onClick={guardarOrden}>Guardar orden</button>
      </div>
      {ORDEN_GRUPOS.map((grupo) => <section key={grupo} className={'card timing-orden-grupo grupo-' + grupo + (destino === grupo ? ' destino' : '')}
        aria-label={GRUPOS_ORDEN[grupo]} onDragOver={(e) => sobre(e, grupo)} onDrop={(e) => soltar(e, grupo)}>
        <h3>{GRUPOS_ORDEN[grupo]} <span className="timing-orden-cuenta">{actual[grupo].length}</span>
          <small>{grupo === 'dia' ? 'Se numeran 1, 2, 3… en este orden' : grupo === 'cola' ? 'Orden 9 · por prioridad' : 'Orden 99'}</small></h3>
        <ol className="timing-orden-lista">
          {actual[grupo].map((t, i) => <li key={t.id} draggable={!ocupado}
            className={(arrastrada === t.id ? 'arrastrando ' : '') + (destino === 'antes-' + t.id ? 'destino-antes ' : '') + (movidas.has(t.id) ? 'movida' : '')}
            onDragStart={(e) => { e.dataTransfer.setData('text/plain', String(t.id)); e.dataTransfer.effectAllowed = 'move'; setArrastrada(t.id); }}
            onDragEnd={() => { setArrastrada(undefined); setDestino(undefined); }}
            onDragOver={(e) => { e.stopPropagation(); sobre(e, 'antes-' + t.id); }}
            onDrop={(e) => soltar(e, grupo, t.id)}>
            <span className="timing-orden-asa" aria-hidden="true">⋮⋮</span>
            <span className="timing-orden-posicion" title="Orden diario">{grupo === 'dia' ? i + 1 : grupo === 'cola' ? (t.ordenDiario ?? 9) : (t.ordenDiario ?? 99)}</span>
            <a className="timing-orden-tarea" href={enlaceTiming('planificacion', t.id)}>
              <b>{t.descripcion || t.codigo}</b>
              <span>{[t.codigo, t.cuenta, t.proyecto, t.banco].filter(Boolean).join(' · ')}</span>
            </a>
            <span className="timing-orden-dato" title="Prioridad">P {t.prioridad ?? '—'}</span>
            <span className="timing-orden-dato" title="Horas estimadas">{t.horasEstimadas != null ? t.horasEstimadas + ' h' : '—'}</span>
            <EstadoTiming tarea={t} />
            <span className="timing-orden-botones">
              <button type="button" className="icbtn" title={grupo === 'dia' ? 'Subir' : grupo === 'cola' ? 'Pasar al orden del día' : 'Pasar a la cola'} aria-label={'Subir ' + t.codigo} disabled={ocupado || (grupo === 'dia' && i === 0)}
                onClick={() => desplazar(grupo, i, -1)}>↑</button>
              <button type="button" className="icbtn" title={grupo === 'dia' ? 'Bajar' : 'Pasar a genéricas y ausencias'} aria-label={'Bajar ' + t.codigo} disabled={ocupado || grupo === 'final'}
                onClick={() => desplazar(grupo, i, 1)}>↓</button>
              {grupo !== 'dia' && <button type="button" className="btn ghost sm" disabled={ocupado} onClick={() => mover(t.id, 'dia')}>Al día</button>}
              {grupo === 'dia' && <button type="button" className="btn ghost sm" disabled={ocupado} onClick={() => mover(t.id, 'cola', actual.cola[0]?.id)}>A la cola</button>}
            </span>
          </li>)}
          {!actual[grupo].length && <li className="timing-orden-vacio">{grupo === 'dia' ? 'Arrastra aquí las tareas que van primero.' : 'Sin tareas.'}</li>}
        </ol>
      </section>)}
    </>}
  </div>;
}
