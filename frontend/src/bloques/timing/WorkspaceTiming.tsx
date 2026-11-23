
import { Fragment, useEffect, useRef, useState, type FormEvent } from 'react';
import type { Sesion } from '../../api/tipos';
import { timingApi, type InformeTiming } from './cliente';
import { Campo, DetalleTiming, EditorTarea, EstadoTiming } from './DetalleTiming';
import { CAMARA_CLIMATICA, enlaceTiming, ordenarCola, tienePerfil, VISTAS_TIMING, type EquipoTiming, type OpcionesTiming, type TareaTiming, type VistaTiming } from './modelo';

const errorTexto = (error: unknown) => error instanceof Error ? error.message : String(error);
const TAMANO_PAGINA = 35;
const FILTROS_VACIOS = { texto: '', tipo: '', disponibilidad: '', responsable: '', tecnico: '', proyecto: '', cuenta: '', banco: '' };

export function WorkspaceTiming({ vista, id, sesion }: { vista: VistaTiming; id?: number; sesion: Sesion }) {
  const [datos, setDatos] = useState<{ vista: VistaTiming; tareas: TareaTiming[] }>();
  const [opciones, setOpciones] = useState<OpcionesTiming>();
  const [detalle, setDetalle] = useState<TareaTiming>();
  const [errorDetalle, setErrorDetalle] = useState<{ id: number; texto: string }>();
  const [revision, setRevision] = useState(0);
  const [error, setError] = useState('');
  const [aviso, setAviso] = useState('');
  const [ocupado, setOcupado] = useState(false);
  const [nueva, setNueva] = useState(false);
  const [grupo, setGrupo] = useState<'analisis' | 'bancos'>('analisis');
  const [pagina, setPagina] = useState(0);
  const [filtros, setFiltros] = useState(FILTROS_VACIOS);
  const [diaElegido, setDiaElegido] = useState('');
  const workspace = useRef<HTMLElement>(null);
  const puedeCrear = tienePerfil(sesion, 'solicitante') || tienePerfil(sesion, 'coordinador');
  const destinoAlta = tienePerfil(sesion, 'solicitante') ? 'solicitudes' : 'planificacion';
  const navegacion = useRef({ vista, id });
  navegacion.current = { vista, id };

  useEffect(() => {
    let cancelar = false;
    timingApi.opciones().then((r) => { if (!cancelar) setOpciones(r); })
      .catch((e: unknown) => { if (!cancelar) setError(errorTexto(e)); });
    return () => { cancelar = true; };
  }, [revision]);

  useEffect(() => {
    if (vista === 'configuracion') return;
    let cancelar = false;
    timingApi.lista(vista).then((tareas) => { if (!cancelar) setDatos({ vista, tareas }); })
      .catch((e: unknown) => { if (!cancelar) setError(errorTexto(e)); });
    return () => { cancelar = true; };
  }, [vista, revision]);

  useEffect(() => {
    if (!id || vista === 'configuracion') return;
    let cancelar = false;
    setErrorDetalle(undefined);
    timingApi.detalle(id).then((r) => { if (!cancelar) setDetalle(r); })
      .catch((e: unknown) => { if (!cancelar) setErrorDetalle({ id, texto: errorTexto(e) }); });
    return () => { cancelar = true; };
  }, [id, vista, revision]);

  useEffect(() => {
    setNueva(false);
    setPagina(0);
    setError('');
    setFiltros(FILTROS_VACIOS);
  }, [vista]);

  useEffect(() => {
    if (vista === 'planificacion' && detalle && detalle.id === id) setGrupo(detalle.banco ? 'bancos' : 'analisis');
  }, [vista, id, detalle?.id, detalle?.banco]);

  const recargar = () => { setError(''); setRevision((v) => v + 1); };
  const guardar = async (ejecutar: () => Promise<unknown>, exito: string): Promise<boolean> => {
    if (ocupado) return false;
    setOcupado(true);
    setError('');
    setAviso('');
    try {
      await ejecutar();
      setAviso(exito);
      setRevision((v) => v + 1);
      return true;
    } catch (e) {
      setError(errorTexto(e));
      return false;
    } finally {
      setOcupado(false);
    }
  };
  const abrir = (destino: VistaTiming, tarea: number) => { window.location.hash = enlaceTiming(destino, tarea); };
  const alta = (valores: object) => {
    void guardar(async () => {
      const t = await timingApi.crear(valores);
      setNueva(false);
      if (navegacion.current.vista === vista) abrir(destinoAlta, t.id);
    }, 'Tarea creada.');
  };

  const visibles = datos?.vista === vista ? datos.tareas : [];
  const dia = diaElegido || opciones?.hoy || '';
  const busca = filtros.texto.toLocaleLowerCase('es-ES').trim();
  const porTexto = (valor?: string) => valor?.toLocaleLowerCase('es-ES').includes(busca);
  const coincide = (columna: string, valor?: string) => !columna || !!valor?.toLocaleLowerCase('es-ES').includes(columna.toLocaleLowerCase('es-ES'));
  const filtradas = visibles.filter((t) => {
    if (vista === 'planificacion' && (grupo === 'bancos') !== !!t.banco) return false;
    if (busca && !porTexto(t.codigo) && !porTexto(t.descripcion) && !porTexto(String(t.origenId ?? t.id))) return false;
    if (filtros.tipo && t.tipo !== filtros.tipo) return false;
    if (filtros.disponibilidad && (filtros.disponibilidad === 'si') !== t.disponible) return false;
    return coincide(filtros.responsable, t.responsableNombre || t.responsableOrigen || t.responsable)
      && coincide(filtros.tecnico, t.tecnicoNombre || t.tecnicoOrigen || t.tecnico)
      && coincide(filtros.proyecto, t.proyecto) && coincide(filtros.cuenta, t.cuenta)
      && coincide(filtros.banco, t.banco);
  });
  const ordenadas = vista === 'cola' ? ordenarCola(filtradas, dia) : filtradas;
  const paginas = Math.max(1, Math.ceil(ordenadas.length / TAMANO_PAGINA));
  const paginaVisible = Math.min(pagina, paginas - 1);
  const filas = ordenadas.slice(paginaVisible * TAMANO_PAGINA, (paginaVisible + 1) * TAMANO_PAGINA);
  const filtro = (campo: keyof typeof filtros, valor: string) => {
    setFiltros((f) => ({ ...f, [campo]: valor }));
    setPagina(0);
  };
  const seleccion = detalle?.id === id ? detalle : undefined;
  const { texto: _busqueda, ...adicionales } = filtros;
  const activos = Object.values(adicionales).filter(Boolean).length;
  const grupoCola = (t: TareaTiming) => t.diaTrabajo === dia ? 'Trabajo del día' : !t.diaTrabajo ? 'Pendientes sin programar' : 'Otras fechas';

  useEffect(() => {
    if (nueva || seleccion) {
      const panel = workspace.current?.querySelector<HTMLElement>('.timing-detalle');
      panel?.focus({ preventScroll: true });
      panel?.scrollIntoView({ block: 'nearest' });
    }
  }, [nueva, seleccion?.id]);

  return <main id="main" className="timing-workspace" ref={workspace}>
    <div className="timing-titulo">
      <h1>{VISTAS_TIMING[vista]}</h1>
      <div className="timing-acciones">
        <button className="btn ghost timing-refrescar" aria-label="Actualizar" title="Actualizar" onClick={recargar} disabled={ocupado}>↻</button>
        {puedeCrear && (vista === 'solicitudes' || vista === 'planificacion') &&
          <button className="btn" onClick={() => { setNueva(true); setError(''); setAviso(''); }} disabled={ocupado}>＋ Nueva tarea</button>}
      </div>
    </div>
    {error && <div className="timing-mensaje error" role="alert">{error}</div>}
    {aviso && <div className="timing-mensaje" role="status">{aviso}</div>}
    {!opciones && !error && <p className="hint">Cargando Timing R&amp;T…</p>}
    {opciones && vista === 'configuracion' && <ConfigTiming
      opciones={opciones} admin={sesion.esAdmin} ocupado={ocupado} guardar={guardar}
    />}
    {opciones && vista !== 'configuracion' && <div className={'timing-contenido' + (nueva || id ? ' con-detalle' : '')}>
      <section className="timing-lista card" aria-label="Lista de tareas">
        <div className="timing-filtros">
          <div className="timing-barra-lista">
          {vista === 'planificacion' && <div className="timing-segmentos" role="group" aria-label="Área de trabajo">
            <button className="btn ghost" aria-pressed={grupo === 'analisis'} onClick={() => { setGrupo('analisis'); setPagina(0); }}>Análisis</button>
            <button className="btn ghost" aria-pressed={grupo === 'bancos'} onClick={() => { setGrupo('bancos'); setPagina(0); }}>Bancos</button>
          </div>}
          <div className="timing-busqueda">
            <input className="f" type="search" aria-label="Buscar tarea" placeholder="Buscar tarea" value={filtros.texto} onChange={(e) => filtro('texto', e.target.value)} />
            {vista === 'cola' && <input className="f" aria-label="Día de trabajo" type="date" value={dia}
              onChange={(e) => { setDiaElegido(e.target.value); setPagina(0); }} />}
            <details className="timing-filtros-extra">
              <summary>Filtros{activos > 0 ? ` (${activos})` : ''}</summary>
              <div className="timing-filtros-panel">
                <div className="timing-form-grid">
                  <Campo nombre="Tipo"><select className="f" value={filtros.tipo} onChange={(e) => filtro('tipo', e.target.value)}>
                    <option value="">Todos</option>{opciones.tipos.map((t) => <option key={t}>{t}</option>)}
                  </select></Campo>
                  {vista !== 'cola' && vista !== 'historico' && <Campo nombre="Disponibilidad"><select className="f" value={filtros.disponibilidad}
                    onChange={(e) => filtro('disponibilidad', e.target.value)}>
                    <option value="">Todas</option><option value="si">Disponible</option><option value="no">No disponible</option>
                  </select></Campo>}
                  {vista !== 'cola' && ([['proyecto', 'Proyecto'], ['cuenta', 'Cuenta'], ['responsable', 'Responsable'], ['tecnico', 'Técnico'], ['banco', 'Banco']] as const)
                    .map(([campo, etiqueta]) => <Campo key={campo} nombre={etiqueta}><input className="f" value={filtros[campo]}
                      onChange={(e) => filtro(campo, e.target.value)} /></Campo>)}
                </div>
                {activos > 0 && <button className="btn ghost sm" onClick={() => { setFiltros({ ...FILTROS_VACIOS, texto: filtros.texto }); setPagina(0); }}>Limpiar filtros</button>}
              </div>
            </details>
          </div>
          </div>
        </div>
        <div className="timing-tabla">
          <table className="lst timing-tabla-tareas">
            <thead><tr><th scope="col">{vista === 'cola' ? 'Orden' : 'Prioridad'}</th><th scope="col">Tarea</th>
              <th scope="col" className="timing-columna-secundaria">{vista === 'cola' ? 'Día' : 'Técnico'}</th><th scope="col">Estado</th></tr></thead>
            <tbody>
              {filas.map((tarea, i) => <Fragment key={tarea.id}>
                {vista === 'cola' && (i === 0 || grupoCola(tarea) !== grupoCola(filas[i - 1])) &&
                  <tr className="timing-grupo"><th colSpan={4}>{grupoCola(tarea)}</th></tr>}
                <tr className={id === tarea.id && !nueva ? 'seleccionada' : ''}>
                  <td>{(vista === 'cola' ? tarea.ordenDiario : tarea.prioridad) ?? '—'}</td>
                  <td><a href={enlaceTiming(vista, tarea.id)} aria-current={id === tarea.id ? 'true' : undefined} onClick={() => setNueva(false)}>
                      <span className="timing-fila-resumen">{tarea.descripcion || tarea.codigo}</span>
                      <span className="timing-fila-info">{[tarea.descripcion && tarea.codigo, tarea.proyecto, tarea.banco].filter(Boolean).join(' · ')}</span>
                    </a>
                  </td>
                  <td className="timing-columna-secundaria">{(vista === 'cola' ? tarea.diaTrabajo : tarea.tecnicoNombre || tarea.tecnicoOrigen || tarea.tecnico) || '—'}</td>
                  <td><EstadoTiming tarea={tarea} /></td>
                </tr>
              </Fragment>)}
              {!filas.length && <tr><td colSpan={4} className="empty">{datos?.vista === vista ? 'No hay tareas para esta selección.' : 'Cargando tareas…'}</td></tr>}
            </tbody>
          </table>
        </div>
        <div className="timing-paginacion">
          <span>{ordenadas.length} {ordenadas.length === 1 ? 'tarea' : 'tareas'}</span>
          {paginas > 1 && <div className="timing-acciones">
            <button className="btn ghost sm" onClick={() => setPagina(paginaVisible - 1)} disabled={paginaVisible === 0}>Anterior</button>
            <span>{paginaVisible + 1} de {paginas}</span>
            <button className="btn ghost sm" onClick={() => setPagina(paginaVisible + 1)} disabled={paginaVisible + 1 === paginas}>Siguiente</button>
          </div>}
        </div>
      </section>
      {nueva ? <section className="card timing-detalle" tabIndex={-1} aria-label="Nueva tarea">
        <h3>Nueva tarea</h3>
        <EditorTarea opciones={opciones} responsableEditable={vista === 'planificacion'} ocupado={ocupado} guardar={alta} salir={() => setNueva(false)} />
      </section> : errorDetalle && errorDetalle.id === id ? <section className="card timing-detalle">
        <p role="alert">{errorDetalle.texto}</p><a href={enlaceTiming(vista)}>Volver a la lista</a>
      </section> : seleccion ? <DetalleTiming key={seleccion.id + ':' + seleccion.version} tarea={seleccion} sesion={sesion} opciones={opciones} ocupado={ocupado}
        volver={() => { window.location.hash = enlaceTiming(vista); }}
        operar={(accion, payload) => guardar(async () => {
          const resultado = await timingApi.operar(seleccion, accion, payload);
          if (navegacion.current.vista !== vista || navegacion.current.id !== seleccion.id) return;
          if (accion === 'duplicar') abrir(destinoAlta, resultado.id);
          if (accion === 'cerrar' || accion === 'cancelar') abrir('historico', resultado.id);
          if (accion === 'reabrir') abrir('planificacion', resultado.id);
        }, 'Cambios guardados.')} /> : id ? <div className="card timing-detalle">Cargando tarea…</div> : null}
    </div>}
  </main>;
}

function ConfigTiming({ opciones, admin, ocupado, guardar }: {
  opciones: OpcionesTiming; admin: boolean; ocupado: boolean;
  guardar: (ejecutar: () => Promise<unknown>, exito: string) => Promise<boolean>;
}) {
  const [archivo, setArchivo] = useState<File>();
  const [informe, setInforme] = useState<InformeTiming>();
  const [pag, setPag] = useState(0);
  const guardarEquipo = (equipo: EquipoTiming) => guardar(() => timingApi.equipo(equipo), 'Catálogo actualizado.');
  const alta = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const form = e.currentTarget;
    const d = new FormData(form);
    const clase = d.get('clase') === 'camara' ? 'camara' : 'banco';
    void guardarEquipo({ clase, nombre: String(d.get('nombre') ?? '').trim(), activo: true, version: 0 })
      .then((ok) => { if (ok) form.reset(); });
  };
  const importar = (simular: boolean) => {
    if (!archivo) return;
    void guardar(async () => {
      setPag(0);
      setInforme(await timingApi.importar(archivo, simular));
    }, simular ? 'Vista previa preparada.' : 'Importación completada.');
  };
  return <div className="timing-configuracion">
    <section className="card">
      <h2>Bancos y cámaras</h2>
      <form className="timing-form-grid" onSubmit={alta}>
        <Campo nombre="Tipo de equipo"><select className="f" name="clase"><option value="banco">Banco</option><option value="camara">Cámara</option></select></Campo>
        <Campo nombre="Nombre"><input className="f" required name="nombre" maxLength={140} /></Campo>
        <button className="btn timing-alta-equipo" disabled={ocupado}>Añadir equipo</button>
      </form>
      <div className="timing-tabla"><table className="lst">
        <thead><tr><th>Equipo</th><th>Tipo</th><th>Estado</th><th>Acción</th></tr></thead>
        <tbody>{opciones.equipos.map((equipo) => <tr key={equipo.clase + '/' + equipo.nombre}>
          <td>{equipo.nombre}</td><td>{equipo.clase === 'banco' ? 'Banco' : 'Cámara'}</td><td>{equipo.activo ? 'Activo' : 'Inactivo'}</td>
          <td>{!(equipo.clase === 'banco' && equipo.nombre === CAMARA_CLIMATICA) &&
            <button className="btn ghost sm" disabled={ocupado} onClick={() => void guardarEquipo({ ...equipo, activo: !equipo.activo })}>
              {equipo.activo ? 'Desactivar' : 'Activar'}
            </button>}</td>
        </tr>)}</tbody>
      </table></div>
    </section>
    {admin && <section className="card">
      <h2>Importar tareas</h2>
      <Campo nombre="Archivo Excel (.xlsx)"><input className="f" type="file" accept=".xlsx" disabled={ocupado} onChange={(e) => {
        setArchivo(e.target.files?.[0]); setInforme(undefined);
      }} /></Campo>
      <div className="timing-acciones">
        <button className="btn ghost" disabled={!archivo || ocupado} onClick={() => importar(true)}>Vista previa</button>
        {informe?.simulacion && <button className="btn" disabled={ocupado}
          onClick={() => { if (window.confirm('¿Aplicar esta importación a la base de datos?')) importar(false); }}>Aplicar importación</button>}
      </div>
      {informe && <div className="timing-informe">
        <p>{informe.filas} filas · {informe.cerradas} cerradas · {informe.altas} altas · {informe.cambios} cambios · {informe.sinCambios} sin cambios · {informe.conflictos} conflictos</p>
        {!!informe.avisos.length && <details open>
          <summary>{informe.avisos.length} avisos</summary>
          <ul>{informe.avisos.slice(pag * 25, (pag + 1) * 25).map((aviso, i) => <li key={i}><b>ID {aviso.id}</b> · {aviso.mensaje}</li>)}</ul>
          {informe.avisos.length > 25 && <div className="timing-acciones">
            <button className="btn ghost sm" disabled={pag === 0} onClick={() => setPag(pag - 1)}>Anterior</button>
            <span>{pag + 1} de {Math.ceil(informe.avisos.length / 25)}</span>
            <button className="btn ghost sm" disabled={(pag + 1) * 25 >= informe.avisos.length} onClick={() => setPag(pag + 1)}>Siguiente</button>
          </div>}
        </details>}
      </div>}
    </section>}
  </div>;
}
