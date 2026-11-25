
import { useState, type FormEvent, type ReactNode } from 'react';
import type { Sesion } from '../../api/tipos';
import { CAMARA_CLIMATICA, tienePerfil, type AccionTiming, type OpcionesTiming, type TareaTiming } from './modelo';

const ESTADOS = { pendiente: 'Pendiente', 'en-proceso': 'En proceso', cerrada: 'Cerrada', cancelada: 'Cancelada' };
const ACCIONES: Record<string, string> = {
  creada: 'Creada', duplicada: 'Duplicada', editar: 'Datos editados', disponibilidad: 'Disponibilidad actualizada',
  planificar: 'Planificación actualizada', estado: 'Estado actualizado', iniciar: 'Iniciada', avance: 'Avance registrado',
  cerrar: 'Cerrada', reabrir: 'Reabierta', cancelar: 'Cancelada', vinculada: 'Vinculación con TestBenchPlanner',
  'proyecto-renombrado': 'Proyecto cambiado en TestBenchPlanner',
  'orden-diario': 'Orden diario cambiado',
};
const CAMPOS_VINCULO: Record<string, string> = { responsable: 'Responsable', tecnico: 'Técnico', proyecto: 'Proyecto' };
const TITULOS_FORMULARIO = {
  planificar: 'Planificar tarea', avance: 'Registrar avance', cerrar: 'Cerrar tarea', reabrir: 'Reabrir tarea',
};
type FormularioTarea = 'editar' | keyof typeof TITULOS_FORMULARIO;

export function Campo({ nombre, children }: { nombre: string; children: ReactNode }) {
  return <label className="timing-campo"><span>{nombre}</span>{children}</label>;
}

function Dato({ nombre, valor }: { nombre: string; valor?: string | number | null }) {
  if (valor == null || valor === '') return null;
  return <div><dt>{nombre}</dt><dd>{valor}</dd></div>;
}

const texto = (datos: FormData, campo: string) => String(datos.get(campo) ?? '').trim();
const numero = (datos: FormData, campo: string) => texto(datos, campo) === '' ? null : Number(datos.get(campo));

export function EstadoTiming({ tarea }: { tarea: TareaTiming }) {
  const etiqueta = tarea.estado === 'pendiente' ? tarea.disponible ? 'Disponible' : 'No disponible' : ESTADOS[tarea.estado];
  return <span className={'timing-estado ' + (tarea.estado === 'pendiente' && tarea.disponible ? 'disponible' : tarea.estado)}>{etiqueta}</span>;
}

export function EditorTarea({ tarea, opciones, responsableEditable, ocupado, guardar, salir }: {
  tarea?: TareaTiming; opciones: OpcionesTiming; responsableEditable: boolean; ocupado: boolean;
  guardar: (datos: object) => void; salir: () => void;
}) {
  const [banco, setBanco] = useState(tarea?.banco ?? '');
  const [proyecto, setProyecto] = useState(String(tarea?.proyectoId ?? ''));
  const proyectoActual = opciones.proyectos.find((p) => String(p.id) === proyecto);
  const cuenta = proyecto === String(tarea?.proyectoId ?? '') && tarea?.cuenta ? tarea.cuenta : proyectoActual?.cuenta;
  const tipos = tarea?.tipo && !opciones.tipos.includes(tarea.tipo) ? [tarea.tipo, ...opciones.tipos] : opciones.tipos;
  const equipos = (clase: 'banco' | 'camara', anterior?: string) =>
    opciones.equipos.filter((e) => e.clase === clase && (e.activo || e.nombre === anterior));
  const enviar = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const d = new FormData(e.currentTarget);
    guardar({
      ...(responsableEditable ? { responsable: texto(d, 'responsable') } : {}),
      tipo: texto(d, 'tipo'), descripcion: texto(d, 'descripcion'), proyectoId: proyecto ? Number(proyecto) : null,
      limpiarProyecto: !!tarea?.proyectoId && !proyecto,
      banco, camara: texto(d, 'camara'), fechaEnsayo: texto(d, 'fechaEnsayo'),
      piezasCodigo: texto(d, 'piezasCodigo'), piezasCantidad: numero(d, 'piezasCantidad'), ruta: texto(d, 'ruta'),
      otrosEquipos: texto(d, 'otrosEquipos'), horasEstimadas: numero(d, 'horasEstimadas'),
    });
  };
  return <form className="timing-form" onSubmit={enviar}>
    <Campo nombre="Tarea"><textarea className="f" name="descripcion" rows={3} defaultValue={tarea?.descripcion} /></Campo>
    <div className="timing-form-grid">
      {responsableEditable && <Campo nombre="Responsable">
        <select className="f" name="responsable" defaultValue={tarea?.responsable ?? ''}>
          <option value="">{tarea?.responsableOrigen && !tarea.responsable ? tarea.responsableOrigen + ' (sin vincular)' : 'Sin determinar'}</option>
          {tarea?.responsable && !opciones.personas.some((p) => p.email === tarea.responsable && p.responsable) &&
            <option value={tarea.responsable}>{tarea.responsableNombre || tarea.responsableOrigen || tarea.responsable}</option>}
          {opciones.personas.filter((p) => p.responsable).map((p) => <option key={p.email} value={p.email}>{p.nombre}</option>)}
        </select>
      </Campo>}
      <Campo nombre="Tipo">
        <select className="f" name="tipo" defaultValue={tarea?.tipo ?? ''}>
          <option value="">Sin determinar</option>{tipos.map((t) => <option key={t}>{t}</option>)}
        </select>
      </Campo>
      <Campo nombre="Proyecto">
        <select className="f" value={proyecto} onChange={(e) => setProyecto(e.target.value)}>
          <option value="">{tarea?.proyecto && !tarea.proyectoId ? tarea.proyecto + ' (sin conciliar)' : 'Sin determinar'}</option>
          {tarea?.proyectoId && !opciones.proyectos.some((p) => p.id === tarea.proyectoId) &&
            <option value={tarea.proyectoId}>{tarea.proyecto} (archivado)</option>}
          {opciones.proyectos.map((p) => <option key={p.id} value={p.id}>{p.etiqueta || p.nombre}</option>)}
        </select>
      </Campo>
      {(proyecto || cuenta) && <Campo nombre="Cuenta del proyecto"><output>{cuenta || 'Sin cuenta'}</output></Campo>}
      <Campo nombre="Horas estimadas"><input className="f" name="horasEstimadas" type="number" min="0" step="any" defaultValue={tarea?.horasEstimadas ?? ''} /></Campo>
      <Campo nombre="Banco">
        <select className="f" value={banco} onChange={(e) => setBanco(e.target.value)}>
          <option value="">Sin banco</option>
          {banco && !opciones.equipos.some((equipo) => equipo.clase === 'banco' && equipo.nombre === banco) && <option>{banco}</option>}
          {equipos('banco', tarea?.banco).map((e) => <option key={e.nombre} value={e.nombre}>{e.nombre}{e.activo ? '' : ' (inactivo)'}</option>)}
        </select>
      </Campo>
      {banco === CAMARA_CLIMATICA && <Campo nombre="Cámara climática">
        <select className="f" name="camara" defaultValue={banco === tarea?.banco ? tarea.camara ?? '' : ''}>
          <option value="">Sin determinar</option>
          {tarea?.camara && !opciones.equipos.some((equipo) => equipo.clase === 'camara' && equipo.nombre === tarea.camara) && <option>{tarea.camara}</option>}
          {equipos('camara', tarea?.camara).map((e) => <option key={e.nombre} value={e.nombre}>{e.nombre}{e.activo ? '' : ' (inactiva)'}</option>)}
        </select>
      </Campo>}
      <Campo nombre="Fecha programada del ensayo"><input className="f" name="fechaEnsayo" type="date" defaultValue={tarea?.fechaEnsayo ?? ''} /></Campo>
    </div>
    <details className="timing-seccion" open={!!(tarea?.piezasCodigo || tarea?.piezasCantidad != null || tarea?.ruta || tarea?.otrosEquipos) || undefined}>
      <summary>Piezas, equipos y ruta</summary>
      <div className="timing-form-grid">
        <Campo nombre="Código de piezas"><input className="f" name="piezasCodigo" defaultValue={tarea?.piezasCodigo} /></Campo>
        <Campo nombre="Cantidad de piezas"><input className="f" name="piezasCantidad" type="number" min="0" step="any" defaultValue={tarea?.piezasCantidad ?? ''} /></Campo>
        <Campo nombre="Otros equipos"><input className="f" name="otrosEquipos" defaultValue={tarea?.otrosEquipos} /></Campo>
        <Campo nombre="Ruta"><input className="f" name="ruta" defaultValue={tarea?.ruta} /></Campo>
      </div>
    </details>
    <div className="timing-acciones">
      <button className="btn" disabled={ocupado} type="submit">{tarea ? 'Guardar cambios' : 'Crear tarea'}</button>
      <button className="btn ghost" type="button" onClick={salir}>Volver</button>
    </div>
  </form>;
}

export function DetalleTiming({ tarea, sesion, opciones, ocupado, operar, volver }: {
  tarea: TareaTiming; sesion: Sesion; opciones: OpcionesTiming; ocupado: boolean;
  operar: (accion: AccionTiming, datos?: object) => Promise<boolean>; volver: () => void;
}) {
  const [formulario, setFormulario] = useState<FormularioTarea | null>(null);
  const [horasCierre, setHorasCierre] = useState(String(tarea.horasRegistradas ?? 0));
  const [descripcionCompleta, setDescripcionCompleta] = useState(false);
  const coordina = tienePerfil(sesion, 'coordinador');
  const gestiona = coordina || sesion.esAdmin;
  const propia = tienePerfil(sesion, 'solicitante') && sesion.email === tarea.responsable;
  const asignada = tienePerfil(sesion, 'tecnico') && sesion.email === tarea.tecnico;
  const pendiente = tarea.estado === 'pendiente';
  const abierta = pendiente || tarea.estado === 'en-proceso';
  const editar = abierta && (gestiona || propia);
  const puedeCancelar = abierta && (coordina || (propia && pendiente && !tarea.disponible));
  const puedeIniciar = asignada && pendiente && tarea.disponible;
  const puedeRegistrar = asignada && tarea.estado === 'en-proceso' && tarea.disponible;
  const puedePreparar = editar && pendiente && !tarea.disponible && !gestiona;
  const descripcion = tarea.descripcion ?? '';
  const resumenNecesario = descripcion.length > 155 || descripcion.split(/\r?\n/).length > 3;
  const ejecutar = (accion: AccionTiming, datos?: object) => { void operar(accion, datos).then((ok) => { if (ok) setFormulario(null); }); };
  const enviar = (e: FormEvent<HTMLFormElement>, accion: AccionTiming) => {
    e.preventDefault();
    const d = new FormData(e.currentTarget);
    switch (accion) {
      case 'planificar':
        ejecutar(accion, { tecnico: texto(d, 'tecnico'), prioridad: numero(d, 'prioridad'), ordenDiario: numero(d, 'ordenDiario') });
        break;
      case 'avance':
        ejecutar(accion, { fecha: texto(d, 'fecha'), horas: numero(d, 'horas'), pendientes: numero(d, 'pendientes'), comentario: texto(d, 'comentario') });
        break;
      case 'cerrar':
        ejecutar(accion, { horasReales: numero(d, 'horasReales'), motivo: texto(d, 'motivo'), comentario: texto(d, 'comentario') });
        break;
      case 'reabrir':
        ejecutar(accion, { motivo: texto(d, 'motivo') });
        break;
    }
  };
  return <section className="timing-detalle card" tabIndex={-1} aria-label={'Detalle ' + tarea.codigo}>
    <header className="timing-detalle-cabecera">
      <div className="timing-identidad"><h3>{tarea.codigo}</h3><EstadoTiming tarea={tarea} /></div>
      <button className="btn ghost timing-cerrar" onClick={volver} aria-label="Cerrar detalle" title="Cerrar detalle">×</button>
    </header>
    {formulario === 'editar' ? <EditorTarea tarea={tarea} opciones={opciones} responsableEditable={gestiona} ocupado={ocupado}
      guardar={(datos) => ejecutar('editar', datos)} salir={() => setFormulario(null)} /> : <>
      {descripcion && <div className="timing-texto-tarea">
        <p className={'timing-descripcion' + (resumenNecesario && !descripcionCompleta ? ' resumida' : '')}>{descripcion}</p>
        {resumenNecesario && <button type="button" className="timing-enlace" aria-expanded={descripcionCompleta}
          onClick={() => setDescripcionCompleta(!descripcionCompleta)}>{descripcionCompleta ? 'Ver menos' : 'Ver texto completo'}</button>}
      </div>}
      {!formulario && <div className="timing-acciones timing-acciones-tarea">
        {gestiona && abierta && <Campo nombre="Estado">
          <select className="f" disabled={ocupado}
            value={tarea.estado === 'en-proceso' ? 'en-proceso' : tarea.disponible ? 'disponible' : 'no-disponible'}
            onChange={(e) => ejecutar('estado', { estado: e.target.value === 'en-proceso' ? 'en-proceso' : 'pendiente',
              disponible: e.target.value !== 'no-disponible' })}>
            <option value="no-disponible">No disponible</option>
            <option value="disponible">Disponible</option>
            <option value="en-proceso">En proceso</option>
          </select>
        </Campo>}
        {puedeIniciar && <button className="btn" disabled={ocupado} onClick={() => ejecutar('iniciar')}>Iniciar tarea</button>}
        {puedeRegistrar && <>
          <button className="btn" disabled={ocupado} onClick={() => setFormulario('avance')}>Registrar avance</button>
        </>}
        {(puedeRegistrar || (gestiona && tarea.estado === 'en-proceso' && tarea.disponible)) &&
          <button className="btn ghost" disabled={ocupado} onClick={() => setFormulario('cerrar')}>Cerrar tarea</button>}
        {abierta && coordina && <button className={puedeIniciar || puedeRegistrar ? 'btn ghost' : 'btn'} disabled={ocupado} onClick={() => setFormulario('planificar')}>
          {tarea.tecnico ? 'Planificar' : 'Asignar'}
        </button>}
        {puedePreparar && <button className="btn" disabled={ocupado} onClick={() => ejecutar('disponibilidad', { disponible: true })}>Marcar disponible</button>}
        {editar && <button className="btn ghost" disabled={ocupado} onClick={() => setFormulario('editar')}>Editar</button>}
        {gestiona && tarea.estado === 'cerrada' && <button className="btn ghost" disabled={ocupado} onClick={() => setFormulario('reabrir')}>Reabrir</button>}
        {((editar && pendiente && !gestiona && !puedePreparar) || propia || puedeCancelar) && <details className="timing-mas">
          <summary className="btn ghost" aria-label="Más acciones" title="Más acciones">···</summary>
          <div className="timing-mas-menu">
            {editar && pendiente && !gestiona && !puedePreparar && <button disabled={ocupado} onClick={() => ejecutar('disponibilidad', { disponible: !tarea.disponible })}>
              {tarea.disponible ? 'Marcar no disponible' : 'Marcar disponible'}
            </button>}
            {propia && <button disabled={ocupado} onClick={() => ejecutar('duplicar')}>Duplicar</button>}
            {puedeCancelar && <button className="timing-peligro" disabled={ocupado}
              onClick={() => { if (window.confirm('¿Cancelar ' + tarea.codigo + '?')) ejecutar('cancelar'); }}>Cancelar tarea</button>}
          </div>
        </details>}
      </div>}
      {formulario && <form className="timing-form timing-subform" onSubmit={(e) => enviar(e, formulario)}>
        <h4>{TITULOS_FORMULARIO[formulario]}</h4>
        {formulario === 'planificar' && <div className="timing-form-grid">
          <Campo nombre="Técnico">
            <select className="f" name="tecnico" defaultValue={tarea.tecnico ?? ''}>
              <option value="">Sin asignar</option>
              {tarea.tecnico && !opciones.personas.some((p) => p.email === tarea.tecnico && p.tecnico) &&
                <option value={tarea.tecnico}>{tarea.tecnicoNombre || tarea.tecnicoOrigen || tarea.tecnico}</option>}
              {opciones.personas.filter((p) => p.tecnico).map((p) => <option key={p.email} value={p.email}>{p.nombre}</option>)}
            </select>
          </Campo>
          <Campo nombre="Prioridad general"><input className="f" name="prioridad" type="number" step="any" defaultValue={tarea.prioridad ?? ''} /></Campo>
          <Campo nombre="Orden diario (0-8 primero · 9 cola · 99 al final)"><input className="f" name="ordenDiario" type="number" step="any" defaultValue={tarea.ordenDiario ?? ''} /></Campo>
        </div>}
        {formulario === 'avance' && <div className="timing-form-grid">
          <Campo nombre="Fecha"><input className="f" name="fecha" required type="date" max={opciones.hoy} defaultValue={opciones.hoy} /></Campo>
          <Campo nombre="Horas trabajadas"><input className="f" name="horas" required type="number" min="0.000001" step="any" /></Campo>
          <Campo nombre="Horas pendientes"><input className="f" name="pendientes" type="number" min="0" step="any" defaultValue={tarea.horasPendientes ?? ''} /></Campo>
        </div>}
        {formulario === 'cerrar' && <Campo nombre="Horas reales">
          <input className="f" name="horasReales" type="number" required step="any" min="0" value={horasCierre}
            onChange={(e) => setHorasCierre(e.target.value)} />
        </Campo>}
        {(formulario === 'reabrir' || (formulario === 'cerrar' && Number(horasCierre) !== (tarea.horasRegistradas ?? 0))) &&
          <Campo nombre={formulario === 'reabrir' ? 'Motivo de reapertura' : 'Motivo del ajuste'}><textarea className="f" name="motivo" required rows={2} /></Campo>}
        {(formulario === 'avance' || formulario === 'cerrar') && <Campo nombre="Comentario"><textarea className="f" name="comentario" rows={3} /></Campo>}
        <div className="timing-acciones"><button className="btn" type="submit" disabled={ocupado}>
          {formulario === 'cerrar' ? 'Confirmar cierre' : formulario === 'reabrir' ? 'Confirmar reapertura' : 'Guardar'}
        </button><button className="btn ghost" type="button" onClick={() => setFormulario(null)}>Volver</button></div>
      </form>}
      {!formulario && <>
        <dl className="timing-datos timing-contexto">
          <Dato nombre="Proyecto" valor={tarea.proyecto} />
          <Dato nombre="Técnico" valor={tarea.tecnicoNombre || tarea.tecnicoOrigen || tarea.tecnico || (coordina && abierta ? 'Sin asignar' : '')} />
          <Dato nombre="Banco" valor={[tarea.banco, tarea.camara].filter(Boolean).join(' · ')} />
          <Dato nombre={tarea.estado === 'cerrada' ? 'Horas reales' : 'Horas estimadas'} valor={tarea.estado === 'cerrada' ? tarea.horasReales : tarea.horasEstimadas} />
          {tarea.estado !== 'cerrada' && <Dato nombre="Horas registradas" valor={tarea.horasRegistradas || null} />}
        </dl>
        <details className="timing-seccion">
          <summary>Datos de la tarea</summary>
          <dl className="timing-datos">
            <Dato nombre="Responsable" valor={tarea.responsableNombre || tarea.responsableOrigen || tarea.responsable} />
            <Dato nombre="Tipo" valor={tarea.tipo} />
            <Dato nombre="Cuenta del proyecto" valor={tarea.cuenta} />
            <Dato nombre="Fecha de ensayo" valor={tarea.fechaEnsayo} />
            <Dato nombre="Prioridad" valor={tarea.prioridad} />
            <Dato nombre="Orden diario" valor={tarea.ordenDiario} />
            <Dato nombre="Horas pendientes" valor={tarea.horasPendientes} />
            {tarea.estado === 'cerrada' && <Dato nombre="Horas estimadas" valor={tarea.horasEstimadas} />}
            {tarea.estado === 'cerrada' && <Dato nombre="Horas registradas" valor={tarea.horasRegistradas || null} />}
            <Dato nombre="Fecha de cierre" valor={tarea.fechaCierre} />
            <Dato nombre="Piezas" valor={[tarea.piezasCodigo, tarea.piezasCantidad].filter((v) => v != null && v !== '').join(' · ')} />
            <Dato nombre="Otros equipos" valor={tarea.otrosEquipos} />
            <Dato nombre="Motivo del ajuste" valor={tarea.motivoAjuste} />
          </dl>
          {tarea.ruta && <p className="timing-ruta"><b>Ruta </b>{tarea.ruta} {navigator.clipboard && <button className="btn ghost sm" onClick={() => { void navigator.clipboard.writeText(tarea.ruta!).catch(() => undefined); }}>Copiar</button>}</p>}
          {tarea.comentarioCierre && <p className="timing-ruta"><b>Comentario de cierre </b>{tarea.comentarioCierre}</p>}
        </details>
      </>}
    </>}
    {!formulario && !!tarea.avances?.length && <details className="timing-seccion">
      <summary>Avances ({tarea.avances.length})</summary>
      <ul className="timing-eventos">{tarea.avances.map((a, i) => <li key={i}><b>{a.fecha} · {a.autor}</b>
        <span>{a.horas} h trabajadas{a.pendientes == null ? '' : ' · ' + a.pendientes + ' h pendientes'}</span>
        {a.comentario && <p>{a.comentario}</p>}
      </li>)}</ul>
    </details>}
    {!formulario && !!tarea.historial?.length && <details className="timing-seccion">
      <summary>Historial de cambios</summary>
      <ul className="timing-eventos">{tarea.historial.map((h, i) => <li key={i}>
        <b>{ACCIONES[h.accion] || h.accion} · {h.autor}</b><span>{new Date(h.fecha).toLocaleString('es-ES')}</span>
        {Array.isArray(h.datos.vinculos) && (h.datos.vinculos as { campo: string; origen: string; destino: string; manual?: boolean }[])
          .map((v, j) => <p key={j}>{CAMPOS_VINCULO[v.campo] ?? v.campo}: {v.origen} → {v.destino ? opciones.personas.find((p) => p.email === v.destino)?.nombre ?? v.destino : 'sin vincular'}
            {v.manual ? ' (manual)' : ''}</p>)}
        {h.accion === 'orden-diario' && <p>{String(h.datos.ordenAnterior ?? '—')} → {String(h.datos.ordenDiario ?? '—')}</p>}
        {typeof h.datos.horasReales === 'number' && <p>{h.datos.horasReales} h reales</p>}
        {typeof h.datos.motivo === 'string' && h.datos.motivo && <p>{h.datos.motivo}</p>}
        {typeof h.datos.comentario === 'string' && h.datos.comentario && <p>{h.datos.comentario}</p>}
      </li>)}</ul>
    </details>}
    {!formulario && tarea.legado && <details className="timing-seccion">
      <summary>Datos originales del Excel · {tarea.origenId}</summary>
      <dl className="timing-datos legado">{Object.entries(tarea.legado).map(([k, v]) => <div key={k}><dt>{k}</dt><dd>{v}</dd></div>)}</dl>
    </details>}
  </section>;
}
