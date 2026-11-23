
import type { PerfilTiming, Sesion } from '../../api/tipos';

export const CAMARA_CLIMATICA = 'Cámara Climática';
export type VistaTiming = 'solicitudes' | 'cola' | 'planificacion' | 'historico' | 'configuracion';
export type AccionTiming = 'editar' | 'disponibilidad' | 'estado' | 'planificar' | 'iniciar' | 'avance' | 'cerrar' | 'reabrir' | 'cancelar' | 'duplicar';
export type ColumnaTiming = 'prioridad' | 'ordenDiario' | 'cuenta' | 'tecnico' | 'responsable' | 'estado';
export type OrdenTiming = { columna: ColumnaTiming; descendente: boolean };

export const VISTAS_TIMING: Record<VistaTiming, string> = {
  solicitudes: 'Mis solicitudes', cola: 'Mi cola', planificacion: 'Planificación', historico: 'Histórico', configuracion: 'Configuración',
};

export interface TareaTiming {
  id: number;
  version: number;
  origenId?: number | null;
  codigo: string;
  estado: 'pendiente' | 'en-proceso' | 'cerrada' | 'cancelada';
  disponible: boolean;
  tipo?: string;
  proyectoId?: number | null;
  proyecto?: string;
  cuenta?: string;
  responsable?: string;
  responsableNombre?: string;
  responsableOrigen?: string;
  tecnico?: string;
  tecnicoNombre?: string;
  tecnicoOrigen?: string;
  descripcion?: string;
  banco?: string;
  camara?: string;
  fechaEnsayo?: string;
  piezasCodigo?: string;
  piezasCantidad?: number | null;
  ruta?: string;
  otrosEquipos?: string;
  horasEstimadas?: number | null;
  prioridad?: number | null;
  diaTrabajo?: string;
  ordenDiario?: number | null;
  horasPendientes?: number | null;
  horasReales?: number | null;
  fechaCierre?: string;
  comentarioCierre?: string;
  motivoAjuste?: string;
  horasRegistradas?: number;
  avances?: { autor: string; fecha: string; horas: number; pendientes: number | null; comentario: string }[];
  historial?: { autor: string; fecha: string; accion: string; datos: Record<string, unknown> }[];
  legado?: Record<string, string>;
}

export interface EquipoTiming {
  clase: 'banco' | 'camara';
  nombre: string;
  activo: boolean;
  version: number;
}

export interface OpcionesTiming {
  hoy: string;
  tipos: string[];
  proyectos: { id: number; nombre: string; cuenta: string | null }[];
  personas: { email: string; nombre: string; tecnico: boolean; responsable: boolean }[];
  equipos: EquipoTiming[];
}

export function tienePerfil(sesion: Sesion, perfil: PerfilTiming): boolean {
  return !!sesion.timingRoles?.includes(perfil);
}

export function vistasTiming(sesion: Sesion): VistaTiming[] {
  if (!sesion.modoServidor || !sesion.timingRoles?.length) return [];
  const gestiona = tienePerfil(sesion, 'coordinador') || sesion.esAdmin;
  const vistas: VistaTiming[] = [];
  if (tienePerfil(sesion, 'solicitante')) vistas.push('solicitudes');
  if (tienePerfil(sesion, 'tecnico')) vistas.push('cola');
  if (gestiona) vistas.push('planificacion');
  vistas.push('historico');
  if (gestiona) vistas.push('configuracion');
  return vistas;
}

export function vistaInicialTiming(vistas: VistaTiming[]): VistaTiming {
  return (['planificacion', 'cola', 'solicitudes'] as const).find((vista) => vistas.includes(vista)) ?? 'historico';
}

export function enlaceTiming(vista?: VistaTiming, id?: number): string {
  return '#/timing/' + (vista ?? '') + (id ? '/tarea/' + id : '');
}

export function leerRutaTiming(hash: string): { vista?: VistaTiming; id?: number; invalida?: boolean } | null {
  if (!hash.startsWith('#/timing/')) return null;
  const [, , vista, segmento, id, extra] = hash.split('/');
  if (!vista && segmento === undefined) return {};
  if (!Object.hasOwn(VISTAS_TIMING, vista) || extra !== undefined) return { invalida: true };
  if (segmento === undefined) return { vista: vista as VistaTiming };
  const numero = Number(id);
  if (segmento !== 'tarea' || !/^[1-9]\d*$/.test(id ?? '') || !Number.isSafeInteger(numero) || vista === 'configuracion') return { invalida: true };
  return { vista: vista as VistaTiming, id: numero };
}

export function ordenarCola(tareas: TareaTiming[], dia: string): TareaTiming[] {
  const grupo = (t: TareaTiming) => t.diaTrabajo === dia ? 0 : !t.diaTrabajo ? 1 : 2;
  return [...tareas].sort((a, b) => grupo(a) - grupo(b)
    || (a.diaTrabajo ?? '').localeCompare(b.diaTrabajo ?? '')
    || (a.ordenDiario ?? Infinity) - (b.ordenDiario ?? Infinity)
    || (a.prioridad ?? Infinity) - (b.prioridad ?? Infinity)
    || a.id - b.id);
}

const compararTexto = new Intl.Collator('es', { numeric: true, sensitivity: 'base' });

export const responsableTiming = (t: TareaTiming) => t.responsableNombre || t.responsableOrigen || t.responsable || '';
export const tecnicoTiming = (t: TareaTiming) => t.tecnicoNombre || t.tecnicoOrigen || t.tecnico || '';

export function ordenarTareas(tareas: TareaTiming[], { columna, descendente }: OrdenTiming): TareaTiming[] {
  const valor = (t: TareaTiming) => {
    if (columna === 'responsable') return responsableTiming(t);
    if (columna === 'tecnico') return tecnicoTiming(t);
    if (columna === 'estado') {
      const estados = { pendiente: t.disponible ? 1 : 0, 'en-proceso': 2, cerrada: 3, cancelada: 4 };
      return estados[t.estado];
    }
    return t[columna];
  };
  return [...tareas].sort((a, b) => {
    const x = valor(a), y = valor(b);
    if (x == null || x === '') return y == null || y === '' ? a.id - b.id : 1;
    if (y == null || y === '') return -1;
    const comparacion = typeof x === 'number' && typeof y === 'number' ? x - y : compararTexto.compare(String(x), String(y));
    return comparacion * (descendente ? -1 : 1) || a.id - b.id;
  });
}

export function sugerenciasTiming(tareas: TareaTiming[], opciones?: OpcionesTiming): Record<string, string[]> {
  const unicos = (valores: (string | null | undefined)[]) => [...new Set(valores.filter((v): v is string => !!v?.trim()))].sort(compararTexto.compare);
  return {
    proyecto: unicos([...tareas.map((t) => t.proyecto), ...(opciones?.proyectos.map((p) => p.nombre) ?? [])]),
    cuenta: unicos([...tareas.map((t) => t.cuenta), ...(opciones?.proyectos.map((p) => p.cuenta) ?? [])]),
    responsable: unicos([...tareas.map(responsableTiming), ...(opciones?.personas.filter((p) => p.responsable).map((p) => p.nombre) ?? [])]),
    tecnico: unicos([...tareas.map(tecnicoTiming), ...(opciones?.personas.filter((p) => p.tecnico).map((p) => p.nombre) ?? [])]),
    banco: unicos([...tareas.map((t) => t.banco), ...(opciones?.equipos.filter((e) => e.clase === 'banco' && e.activo).map((e) => e.nombre) ?? [])]),
  };
}
