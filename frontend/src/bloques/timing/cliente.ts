
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

export interface VinculoTiming {
  tipo: 'persona' | 'proyecto';
  nombre: string;
  cuenta: string;
  destino: string;
  destinoNombre: string;
  vigente: boolean;
  autor: string;
  fecha: string;
}

export interface SinVincularTiming {
  personas: { nombre: string; responsable: number; tecnico: number; abiertas: number; coincidencias: number }[];
  proyectos: { nombre: string; cuenta: string; tareas: number; abiertas: number }[];
  vinculos: VinculoTiming[];
}

export const timingApi = {
  opciones: () => peticion<OpcionesTiming>('GET', BASE + '/opciones'),
  lista: (vista: VistaTiming) => peticion<TareaTiming[]>('GET', BASE + '/tareas?vista=' + vista),
  detalle: (id: number) => peticion<TareaTiming>('GET', BASE + '/tareas/' + id),
  crear: (datos: object) => peticion<TareaTiming>('POST', BASE + '/tareas', datos),
  operar: (tarea: TareaTiming, accion: AccionTiming, datos?: object) =>
    peticion<TareaTiming>('POST', BASE + '/tareas/' + tarea.id + '/' + accion, { ...datos, version: tarea.version }),
  ordenDiario: (tecnico: string, tareas: { tarea: TareaTiming; ordenDiario: number }[]) =>
    peticion<{ cambiadas: number }>('POST', BASE + '/orden-diario',
      { tecnico, tareas: tareas.map(({ tarea, ordenDiario }) => ({ id: tarea.id, version: tarea.version, ordenDiario })) }),
  equipo: (equipo: EquipoTiming) => peticion<EquipoTiming>('PUT', BASE + '/equipos', equipo),
  sinVincular: () => peticion<SinVincularTiming>('GET', BASE + '/vinculacion'),
  vincular: () => peticion<{ vinculadas: number }>('POST', BASE + '/vinculacion', {}),
  guardarVinculo: (vinculo: { tipo: 'persona' | 'proyecto'; nombre: string; cuenta: string; destino: string }) =>
    peticion<{ vinculadas: number }>('POST', BASE + '/vinculos', vinculo),
  borrarVinculo: (vinculo: Pick<VinculoTiming, 'tipo' | 'nombre' | 'cuenta'>) =>
    peticion<{ ok: boolean; vinculadas: number }>('POST', BASE + '/vinculos/borrar', { tipo: vinculo.tipo, nombre: vinculo.nombre, cuenta: vinculo.cuenta }),
  importar: (archivo: File, simular: boolean) => {
    const datos = new FormData();
    datos.append('archivo', archivo);
    return peticion<InformeTiming>('POST', BASE + '/importacion?simular=' + simular, datos);
  },
};
