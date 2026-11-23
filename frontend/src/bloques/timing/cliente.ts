
import { peticion } from '../../api/cliente';
import type { AccionTiming, EquipoTiming, OpcionesTiming, TareaTiming, VistaTiming } from './modelo';

const BASE = '/api/v1/timing';

export interface InformeTiming {
  simulacion: boolean;
  filas: number;
  cerradas: number;
  altas: number;
  cambios: number;
  sinCambios: number;
  conflictos: number;
  avisos: { id: number; mensaje: string }[];
}

export const timingApi = {
  opciones: () => peticion<OpcionesTiming>('GET', BASE + '/opciones'),
  lista: (vista: VistaTiming) => peticion<TareaTiming[]>('GET', BASE + '/tareas?vista=' + vista),
  detalle: (id: number) => peticion<TareaTiming>('GET', BASE + '/tareas/' + id),
  crear: (datos: object) => peticion<TareaTiming>('POST', BASE + '/tareas', datos),
  operar: (tarea: TareaTiming, accion: AccionTiming, datos?: object) =>
    peticion<TareaTiming>('POST', BASE + '/tareas/' + tarea.id + '/' + accion, { ...datos, version: tarea.version }),
  equipo: (equipo: EquipoTiming) => peticion<EquipoTiming>('PUT', BASE + '/equipos', equipo),
  importar: (archivo: File, simular: boolean) => {
    const datos = new FormData();
    datos.append('archivo', archivo);
    return peticion<InformeTiming>('POST', BASE + '/importacion?simular=' + simular, datos);
  },
};
