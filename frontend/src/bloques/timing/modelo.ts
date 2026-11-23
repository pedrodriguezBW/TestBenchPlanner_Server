
import type { PerfilTiming, Sesion } from '../../api/tipos';

export const CAMARA_CLIMATICA = 'Cámara Climática';
export type VistaTiming = 'solicitudes' | 'cola' | 'planificacion' | 'historico' | 'configuracion';
export type AccionTiming = 'editar' | 'disponibilidad' | 'planificar' | 'iniciar' | 'avance' | 'cerrar' | 'reabrir' | 'cancelar' | 'duplicar';

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
