import { peticion } from './cliente';

/*
 * API de facturación (/api/v1/facturacion): Equipment Types por año y hoja
 * mensual. Misma forma que el backend Java (dominio/facturacion) y que la
 * réplica del laboratorio (herramientas/laboratorio/facturacion).
 */

export type Unidad = 'HORAS' | 'PIEZAS';

/** Una fila de TestBenchEquipmentTypes: un Equipment Type en un año. */
export interface TipoEquipo {
  titulo: string;
  grupo: string;
  anio: number;
  sinHv: number | null;
  conHv: number | null;
  unidad: Unidad;
  regla: string;
}

export interface Regla { codigo: string; nombre: string; calculo: string; unidad: Unidad }

export interface ConfiguracionFacturacion {
  tipos: TipoEquipo[];
  reglas: Regla[];
  grupos: string[];
  productLines: string[];
  bancos: { nombre: string; organizacion: string; area?: string; equipmentType: string }[];
  /** La lista de Equipment Types no se ha podido leer (falta la lista o alguna columna). */
  error?: string;
}

/** Campos que se pueden anidar en las filas de la hoja, en su orden fijo (HojaMensual.NIVELES). */
export const NIVELES_HOJA: { id: string; nombre: string }[] = [
  { id: 'orden', nombre: 'Billing Order' }, { id: 'requester', nombre: 'Requester' }, { id: 'planta', nombre: 'Planta' }, { id: 'tipo', nombre: 'Tipo' },
  { id: 'equipo', nombre: 'Equipo' }, { id: 'hv', nombre: 'HV' }, { id: 'inicio', nombre: 'Inicio' },
  { id: 'fin', nombre: 'Fin' }, { id: 'tarifa', nombre: 'Coste/hora' },
];

export interface FilaHoja {
  /** Posición del campo en los niveles elegidos (0 = el primero); niveles.length = «Total general». */
  nivel: number;
  etiqueta: string;
  valores: Record<string, number>;
  total: number;
  incompleta: boolean;
}

export interface TablaHoja { columnas: string[]; niveles: string[]; filas: FilaHoja[] }

export interface LineaHoja {
  banco: string;
  ensayoId: string;
  proyecto: string;
  tipoEnsayo: string;
  requester: string;
  productLine: string;
  orden: string;
  equipmentType: string;
  grupo: string;
  hv: '' | 'yes' | 'no';
  /** Tramo de la tarea dentro del periodo de facturación (lo que se factura). */
  inicio: string;
  fin: string;
  /** Inicio y fin de la tarea completa. */
  inicioTarea: string;
  finTarea: string;
  unidad: Unidad;
  regla: string;
  horasNetas: number;
  /** Setup facturado (cálculo por días); 0 en el cálculo por horas, donde el setup no se factura (D60). */
  horasSetup: number;
  /** 'dias': cálculo de siempre; 'horas': horario de Working Hours (D60). Ausente con un backend anterior. */
  calculo?: 'dias' | 'horas';
  /** Cálculo por horas: fin del setup («aaaa-mm-ddTHH:MM»). */
  inicioFacturable?: string;
  cantidad: number;
  tarifa: number | null;
  coste: number | null;
  completa: boolean;
  faltas: string[];
  avisos: string[];
}

/** Línea del registro del año (Billing › Tests by person): la de la hoja más su mes y la descripción. */
export interface LineaRegistro extends LineaHoja {
  /** Mes de facturación (aaaa-mm). */
  mes: string;
  /** Descripción del ensayo (columna Comments de la tarea). */
  descripcion: string | null;
}

export interface RegistroFacturacion { anio: number; area: string; meses: string[]; lineas: LineaRegistro[] }

export interface HojaMensual {
  mes: string;
  /** Periodo de facturación del mes: del 21 del mes anterior al 20 del mes. */
  periodoInicio: string;
  periodoFin: string;
  vistaPrevia: TablaHoja;
  exportable: TablaHoja;
  lineas: LineaHoja[];
  incompletas: number;
  /** Área por la que se ha filtrado (código) o «ALL» (D57; ausente con un backend anterior). */
  area?: string;
  /** El mes se factura por horas (D60). */
  porHoras?: boolean;
  /** Primer mes de facturación por horas (null = ninguno). */
  facturacionHorasDesde?: string | null;
}

/** Facturación por horas (D60): solo editable con PostgreSQL. */
export interface FacturacionPorHoras { editable: boolean; facturacionHorasDesde: string | null }

export interface Exportacion { ok: boolean; filename: string; b64: string; excluidas: number }

export const apiFacturacion = {
  tiposEquipo: () => peticion<ConfiguracionFacturacion>('GET', '/api/v1/facturacion/tipos-equipo'),
  guardarTiposEquipo: (tipos: TipoEquipo[]) => peticion<ConfiguracionFacturacion>('PUT', '/api/v1/facturacion/tipos-equipo', { tipos }),
  /** Primer mes de facturación por horas (D60). */
  horasDesde: () => peticion<FacturacionPorHoras>('GET', '/api/v1/facturacion/horas-desde'),
  /** mes «aaaa-mm» o null (ninguno: todo por días). Solo Admin y PostgreSQL. */
  guardarHorasDesde: (mes: string | null) => peticion<FacturacionPorHoras>('PUT', '/api/v1/facturacion/horas-desde', { mes }),
  /** area: código de un área (solo sus bancos, D57); 'ALL' o sin indicar = todas. */
  hoja: (mes: string, niveles: string[], area = 'ALL') =>
    peticion<HojaMensual>('GET', `/api/v1/facturacion/hoja?mes=${encodeURIComponent(mes)}&niveles=${parametroNiveles(niveles)}${parametroArea(area)}`),
  registro: (anio: number, area = 'ALL') =>
    peticion<RegistroFacturacion>('GET', `/api/v1/facturacion/registro?anio=${anio}${parametroArea(area)}`),
  exportar: (mes: string, formato: 'excel' | 'html', niveles: string[], area = 'ALL') =>
    peticion<Exportacion>('GET', `/api/v1/facturacion/hoja/exportar?mes=${encodeURIComponent(mes)}&formato=${formato}&niveles=${parametroNiveles(niveles)}${parametroArea(area)}`),
};

/** Parámetro de área para la URL (nada si son todas). */
const parametroArea = (area: string) => (area && area !== 'ALL' ? `&area=${encodeURIComponent(area)}` : '');

/** Ids elegidos para la URL; «-» = ninguno (solo el total). */
const parametroNiveles = (niveles: string[]) => (niveles.length ? encodeURIComponent(niveles.join(',')) : '-');

/** Descarga un archivo recibido en base64. */
export function descargarArchivo(nombre: string, b64: string, tipo: string) {
  const bytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
  const url = URL.createObjectURL(new Blob([bytes], { type: tipo }));
  const a = document.createElement('a');
  a.href = url;
  a.download = nombre;
  document.body.appendChild(a);
  a.click();
  a.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/** 1234.5 → «1.234,50 €» (como el Excel). */
export function euros(v: number | null | undefined): string {
  if (v === null || v === undefined || Number.isNaN(v)) return '';
  const [entero, dec] = Math.abs(v).toFixed(2).split('.');
  return `${v < 0 ? '-' : ''}${entero.replace(/\B(?=(\d{3})+(?!\d))/g, '.')},${dec} \u20ac`;
}
