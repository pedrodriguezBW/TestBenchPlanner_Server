import { peticion } from './cliente';
import type { AvisosHorario, EnsayoHorario, GestionHorarios, Horario, VistaPreviaHorario } from './tipos';

/*
 * API de Configuration › Test Calendar / Working Hours (/api/v1/horarios, D59).
 * Solo PostgreSQL guarda horarios; en los demás almacenes «editable» es false
 * y los cambios responden 422.
 */

const enc = encodeURIComponent;

/** Lo que se envía al guardar (sin los campos calculados de la respuesta). */
export function horarioParaGuardar(h: Horario): Horario {
  return {
    jornada: h.jornada, turno: h.jornada === 8 && h.turno === 'tarde' ? 'tarde' : null, horaInicio: h.horaInicio, horaFin: h.horaFin, finDeSemana: h.finDeSemana,
    setupHoras: h.setupHoras, paradas: h.paradas, nota: h.nota,
  };
}

export const apiHorarios = {
  gestion: () => peticion<GestionHorarios>('GET', '/api/v1/horarios/gestion'),
  banco: (banco: string) => peticion<{ banco: string; editable: boolean; porPiezas: boolean; ensayos: EnsayoHorario[] }>(
    'GET', `/api/v1/horarios?banco=${enc(banco)}`),
  avisos: () => peticion<AvisosHorario>('GET', '/api/v1/horarios/avisos'),
  /** horario: el que se está editando (sin guardar); sin indicar, el guardado. */
  vistaPrevia: (id: string, horario?: Horario) =>
    peticion<VistaPreviaHorario>('POST', `/api/v1/horarios/${enc(id)}/vista-previa`, horario ? { horario: horarioParaGuardar(horario) } : {}),
  /** version: la que se tenía en pantalla (0 = sin registro); si otra persona lo ha cambiado, 422. */
  guardar: (id: string, horario: Horario, version: number) =>
    peticion<EnsayoHorario>('PUT', `/api/v1/horarios/${enc(id)}`, { horario: horarioParaGuardar(horario), anterior: { version } }),
  restablecer: (id: string, version: number) =>
    peticion<EnsayoHorario>('POST', `/api/v1/horarios/${enc(id)}/restablecer`, { anterior: { version } }),
};
