/*
 * Áreas de bancos: nivel por encima de las categorías. De serie son tres
 * (Thermal Benches, HVCH - CTAG, HVCH - STC); con PostgreSQL, un Admin o un
 * Lab User puede crear más, renombrarlas y borrar las que no tengan bancos
 * (D56). La lista, en su orden y con su nombre, viene SIEMPRE del backend
 * (vista.areas); aquí no se fija ninguna salvo como respaldo de un backend anterior.
 *
 * Cada área elige su organización al crearla: banco a banco (BW / CTAG, como
 * Thermal Benches) o una fija para todos sus bancos (HVCH - CTAG: CTAG…).
 *
 * Filtro del Dashboard, «By Responsible» y KPI: dos desplegables (área y, solo
 * en las áreas banco a banco, organización) que se guardan como UN código, el
 * mismo que entiende el backend (FiltroOrganizacion):
 *   ALL · THERMAL · BW / CTAG (Thermal Benches de esa organización, los códigos
 *   de siempre) · el código de cualquier otra área · «ÁREA:BW» / «ÁREA:CTAG»
 *   (organización dentro de un área nueva que se divide banco a banco).
 */
import type { Vista } from '../api/tipos';

export const AREA_TERMICOS = 'THERMAL';
/** Código de un área (los de serie: THERMAL, HVCH-CTAG, HVCH-STC). */
export type CodigoArea = string;

/** Organizaciones que se eligen banco a banco. */
export const ORGANIZACIONES_BANCO = ['BW', 'CTAG'] as const;
export type OrganizacionBanco = (typeof ORGANIZACIONES_BANCO)[number];

/** Organizaciones que se pueden fijar en un área nueva (Areas.ORGANIZACIONES del backend). */
export const ORGANIZACIONES_FIJAS = ['BW', 'CTAG', 'STC'] as const;

export interface DefinicionArea {
  clave: CodigoArea;
  nombre: string;
  /** Organización de todos sus bancos; null = se elige banco a banco. */
  organizacionFija: string | null;
}

/** Las tres de serie (solo si el backend no manda la lista). */
export const AREAS_DE_SERIE: DefinicionArea[] = [
  { clave: 'THERMAL', nombre: 'Thermal Benches', organizacionFija: null },
  { clave: 'HVCH-CTAG', nombre: 'HVCH - CTAG', organizacionFija: 'CTAG' },
  { clave: 'HVCH-STC', nombre: 'HVCH - STC', organizacionFija: 'STC' },
];

/** Filtro de bancos (ver arriba). */
export type FiltroBancos = string;

/** Áreas en su orden, con su nombre y su organización. */
export function areasDe(vista: Vista | null | undefined): DefinicionArea[] {
  const lista = vista?.areas;
  if (!lista || !lista.length) return AREAS_DE_SERIE;
  return lista.map((a) => {
    const serie = AREAS_DE_SERIE.find((s) => s.clave === a.clave);
    const fija = a.organizacionFija !== undefined ? a.organizacionFija : (a.conOrganizacion ? null : serie?.organizacionFija ?? null);
    return { clave: a.clave, nombre: a.nombre || serie?.nombre || a.clave, organizacionFija: fija ?? null };
  });
}

/** Códigos de las áreas, en su orden. */
export function codigosArea(vista: Vista | null | undefined): CodigoArea[] {
  return areasDe(vista).map((a) => a.clave);
}

/** Organización fija de los bancos de un área (null = se elige banco a banco). */
export function organizacionFija(area: string, vista: Vista | null | undefined): string | null {
  return areasDe(vista).find((a) => a.clave === area)?.organizacionFija ?? null;
}

/** Forma de código (mayúsculas y guiones) de un valor guardado. */
const formatear = (valor: unknown) => String(valor ?? '').trim().toUpperCase().replace(/[\s_]+/g, '-').replace(/-+/g, '-');

/** Código de área a partir de lo guardado: vacío o un área que no existe = Thermal Benches. */
export function normalizarArea(valor: unknown, vista?: Vista | null): CodigoArea {
  const v = formatear(valor);
  return codigosArea(vista).includes(v) ? v : AREA_TERMICOS;
}

/** Código de filtro (mayúsculas); vacío = ALL. Uno que ya no existe lo trata el backend como ALL. */
export function normalizarFiltro(valor: unknown): FiltroBancos {
  const v = String(valor ?? '').trim().toUpperCase();
  return v || 'ALL';
}

/** Los dos desplegables que forman un filtro. */
export function partesFiltro(filtro: FiltroBancos): { area: 'ALL' | CodigoArea; org: 'ALL' | OrganizacionBanco } {
  const f = normalizarFiltro(filtro);
  if (f === 'ALL') return { area: 'ALL', org: 'ALL' };
  if (f === 'BW' || f === 'CTAG') return { area: AREA_TERMICOS, org: f };
  const i = f.indexOf(':');
  if (i > 0) {
    const org = f.slice(i + 1);
    return { area: f.slice(0, i), org: org === 'BW' || org === 'CTAG' ? org : 'ALL' };
  }
  return { area: f, org: 'ALL' };
}

export function filtroDe(area: 'ALL' | CodigoArea, org: 'ALL' | OrganizacionBanco): FiltroBancos {
  if (area === 'ALL') return 'ALL';
  if (area === AREA_TERMICOS) return org === 'ALL' ? AREA_TERMICOS : org;
  return org === 'ALL' ? area : `${area}:${org}`;
}

/** ¿El banco entra en el filtro? (banco.area: el código que manda el backend, ya normalizado) */
export function cumpleFiltro(banco: { area?: string; organizacion: string }, filtro: string): boolean {
  const f = normalizarFiltro(filtro);
  if (f === 'ALL') return true;
  const area = banco.area ? formatear(banco.area) : AREA_TERMICOS;
  const { area: a, org } = partesFiltro(f);
  return area === a && (org === 'ALL' || (banco.organizacion || 'BW') === org);
}

/** Nombre de cada área, en su orden. */
export function nombresArea(vista: Vista | null | undefined): Record<CodigoArea, string> {
  const nombres: Record<CodigoArea, string> = {};
  areasDe(vista).forEach((a) => { nombres[a.clave] = a.nombre; });
  return nombres;
}

/** Texto del filtro para títulos («Thermal Benches · BW», «HVCH - STC»…). */
export function etiquetaFiltro(filtro: string, vista: Vista | null | undefined): string {
  const f = normalizarFiltro(filtro);
  if (f === 'ALL') return 'ALL';
  const nombres = nombresArea(vista);
  const { area, org } = partesFiltro(f);
  return area === 'ALL' ? 'ALL' : (nombres[area] ?? area) + (org !== 'ALL' ? ` \u00b7 ${org}` : '');
}
