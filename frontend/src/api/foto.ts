/*
 * 📤 Export read-only: la foto de solo lectura que se envía al proveedor.
 *
 * Es esta misma interfaz en un único .html, con los datos incrustados
 * (window.__TBP_FOTO__) y sin servidor. Mientras está activa, el cliente de la
 * API no sale a la red: responde aquí con lo que se guardó al exportar, y todo
 * lo que escribe falla con «solo lectura». Solo lleva Thermal Benches.
 *
 * Los indicadores que calcula el backend (KPI, ocupación, By Responsible) se
 * exportan banco a banco y semana a semana (FotoSoloLectura en el backend) y
 * aquí se suman con el filtro Org y el responsable elegidos, así que la foto
 * deja navegar por todas las semanas del año sin servidor.
 */
import type {
  BloqueKpi, BloquesKpi, EstadoFotoSemana, FilaEstadistica, FilaOcupacion, Historico, Sesion, SerieTendencia, TablaOcupacion, Tendencia, Vista,
} from './tipos';
import { dia, iso, lunesDe, semanaIso, sumarDias } from '../utilidades/fechas';
import { AREA_TERMICOS, cumpleFiltro, normalizarArea } from '../utilidades/areas';

/** Una semana de un banco: o = [trabajando, inicia, acaba, horas], k = [con carga, corriendo, en retraso, sugerencias]. */
export interface SemanaFoto {
  o: [number, number, number, number];
  k: [number, number, number, number];
  /** Lo mismo que k con solo los ensayos de cada responsable (si no aparece, todo ceros). */
  r?: Record<string, [number, number, number, number]>;
}

/** GET /api/v1/indicadores/foto-solo-lectura */
export interface IndicadoresFoto {
  area: string;
  lunes: string[];
  bancos: Record<string, SemanaFoto[]>;
  historico: Record<string, Historico>;
  /** Estado de la foto de cada semana (D64); no está en las fotos exportadas antes. */
  estadoFotos?: Record<string, EstadoFotoSemana[]>;
  estadisticas: FilaEstadistica[];
}

export interface FotoSoloLectura {
  formato: 1;
  /** Fecha y hora (local, ISO) en que se exportó. */
  generada: string;
  version: string;
  vista: Vista;
  catalogos: { projects: string[]; responsibles: string[] };
  estadosAprobacion: Record<string, { status: string }>;
  indicadores: IndicadoresFoto;
}

declare global {
  interface Window { __TBP_FOTO__?: FotoSoloLectura }
}

/** La foto incrustada, o null en la aplicación normal. */
export function fotoActiva(): FotoSoloLectura | null {
  return typeof window !== 'undefined' && window.__TBP_FOTO__ ? window.__TBP_FOTO__ : null;
}

export const esFoto = () => fotoActiva() !== null;

/** Error de la foto con el mismo formato que los de la API (estado, código, mensaje). */
export class ErrorFoto extends Error {
  constructor(public readonly estado: number, public readonly codigo: string, mensaje: string) {
    super(mensaje);
  }
}

const NO_INCLUIDO = 'Not included in this read-only snapshot.';
const SOLO_LECTURA = 'Read-only snapshot: nothing can be changed or saved.';

/** Sesión de la foto: solo lectura, sin cuenta ni Timing R&T. */
export function sesionFoto(): Sesion {
  return { email: '', nombre: '', responsable: '', rol: 'readonly', esAdmin: false, soloLectura: true, modoServidor: true, timingRoles: [] };
}

/** Filtro Org de la foto: solo Thermal Benches (ALL o cualquier otra área = Thermal Benches). */
export function filtroFoto(valor: string | null | undefined): string {
  const f = String(valor ?? '').trim().toUpperCase();
  return f === 'BW' || f === 'CTAG' ? f : AREA_TERMICOS;
}

/** Bancos de la foto que entran en el filtro, con su categoría. */
function bancosDelFiltro(foto: FotoSoloLectura, filtro: string) {
  const f = filtroFoto(filtro);
  return foto.vista.grupos.flatMap((g) => g.bancos
    .map((b) => ({ grupo: g, nombre: b.nombre, area: normalizarArea(b.area ?? g.area, foto.vista), organizacion: b.organizacion }))
    .filter((b) => cumpleFiltro(b, f)));
}

function semanaDe(foto: FotoSoloLectura, lunes: string): number {
  const i = foto.indicadores.lunes.indexOf(lunes);
  if (i < 0) throw new ErrorFoto(404, 'foto', `${NO_INCLUIDO} (week of ${lunes})`);
  return i;
}

function bloque(foto: FotoSoloLectura, i: number, filtro: string, responsable: string | null): BloqueKpi {
  const b: BloqueKpi = { total: 0, benches: 0, accr: 0, delayed: 0, sug: 0 };
  bancosDelFiltro(foto, filtro).forEach(({ nombre }) => {
    b.total++;
    const s = foto.indicadores.bancos[nombre]?.[i];
    const v = !s ? undefined : responsable === null ? s.k : s.r?.[responsable];
    if (!v) return;
    b.benches += v[0]; b.accr += v[1]; b.delayed += v[2]; b.sug += v[3];
  });
  return b;
}

export function bloquesFoto(foto: FotoSoloLectura, lunes: string, filtro: string, responsable: string | null): BloquesKpi {
  const l = iso(lunesDe(dia(lunes) ?? dia(foto.vista.hoy)!));
  const i = semanaDe(foto, l);
  semanaDe(foto, iso(sumarDias(dia(l)!, 7)));
  return { estaSemana: bloque(foto, i, filtro, responsable), siguiente: bloque(foto, i + 1, filtro, responsable) };
}

const pct = (busy: number, cap: number) => (cap ? Math.round((100 * busy) / cap) : 0);

/** Tabla de ocupación de un rango de semanas completas (desde = lunes, hasta = domingo). */
export function tablaFoto(foto: FotoSoloLectura, desde: string, hasta: string, filtro: string): TablaOcupacion {
  const primera = semanaDe(foto, desde);
  const finRango = dia(hasta);
  const ultima = semanaDe(foto, iso(lunesDe(finRango ?? dia(desde)!)));
  if (ultima < primera || !finRango || iso(sumarDias(dia(foto.indicadores.lunes[ultima])!, 6)) !== hasta) {
    throw new ErrorFoto(404, 'foto', NO_INCLUIDO);
  }
  const semanas = ultima - primera + 1;
  const porGrupo = new Map<string, FilaOcupacion>();
  bancosDelFiltro(foto, filtro).forEach(({ grupo, nombre }) => {
    let fila = porGrupo.get(grupo.clave);
    if (!fila) {
      fila = { clave: grupo.clave, nombre: grupo.nombre, color: grupo.color, bancos: 0, trabajando: 0, inicia: 0, acaba: 0, busy: 0, cap: 0, pct: 0 };
      porGrupo.set(grupo.clave, fila);
    }
    fila.bancos++;
    fila.cap += semanas * 7 * 24;
    let trabaja = false;
    for (let i = primera; i <= ultima; i++) {
      const o = foto.indicadores.bancos[nombre]?.[i]?.o;
      if (!o) continue;
      if (o[0]) trabaja = true;
      fila.inicia += o[1];
      fila.acaba += o[2];
      fila.busy += o[3];
    }
    if (trabaja) fila.trabajando++;
  });
  const grupos = [...porGrupo.values()];
  grupos.forEach((g) => { g.pct = pct(g.busy, g.cap); });
  const busy = grupos.reduce((n, g) => n + g.busy, 0);
  const cap = grupos.reduce((n, g) => n + g.cap, 0);
  return { grupos, busy, cap, pct: pct(busy, cap) };
}

/** Serie semanal del año del calendario, acotada como PanelDashboard.tendencia. */
export function tendenciaFoto(foto: FotoSoloLectura, cwDesde: number, cwHasta: number, filtro: string): Tendencia {
  const anio = foto.vista.calendario.anio;
  const inicio = lunesDe(new Date(anio, 0, 4));
  const semanasDelAnio = semanaIso(new Date(anio, 11, 28));
  const desde = Math.max(1, Math.min(cwDesde || 1, semanasDelAnio));
  const hasta = Math.max(desde, Math.min(cwHasta || semanasDelAnio, semanasDelAnio));
  const lunes: string[] = [];
  for (let i = desde - 1; i < hasta; i++) lunes.push(iso(sumarDias(inicio, 7 * i)));
  const indices = lunes.map((l) => semanaDe(foto, l));
  const porGrupo = new Map<string, SerieTendencia>();
  bancosDelFiltro(foto, filtro).forEach(({ grupo, nombre }) => {
    let serie = porGrupo.get(grupo.clave);
    if (!serie) {
      serie = { clave: grupo.clave, nombre: grupo.nombre, color: grupo.color, bancos: 0, busy: indices.map(() => 0), pct: [] };
      porGrupo.set(grupo.clave, serie);
    }
    serie.bancos++;
    indices.forEach((i, j) => { serie!.busy[j] += foto.indicadores.bancos[nombre]?.[i]?.o[3] ?? 0; });
  });
  const series = [...porGrupo.values()];
  series.forEach((s) => { s.pct = s.busy.map((b) => pct(b, s.bancos * 7 * 24)); });
  return { anio, semanasDelAnio, desde, hasta, lunes, cw: lunes.map((l) => semanaIso(dia(l)!)), series };
}

export function estadisticasFoto(foto: FotoSoloLectura, desde: string | null, hasta: string | null, filtro: string,
  responsable: string | null): FilaEstadistica[] {
  const anio = foto.vista.calendario.anio;
  const d = desde || `${anio}-01-01`;
  const h = hasta || `${anio}-12-31`;
  const bancos = new Set(bancosDelFiltro(foto, filtro).map((b) => b.nombre));
  return foto.indicadores.estadisticas.filter((f) => bancos.has(f.banco) && f.fecha >= d && f.fecha <= h
    && (!responsable || String(f.datos.resp ?? '') === responsable));
}

export function historicoFoto(foto: FotoSoloLectura, filtro: string): Historico {
  return foto.indicadores.historico[filtroFoto(filtro)] ?? { filas: 0, semanas: [] };
}

/** Copia para que nadie pueda modificar los datos incrustados. */
const copia = <T>(v: T): T => JSON.parse(JSON.stringify(v)) as T;

/**
 * Respuesta de la foto a una petición de la API (el mismo contrato que el
 * backend). Lo que no se exporta da 404 «no incluido»; escribir da 403.
 */
export function responderFoto(foto: FotoSoloLectura, metodo: string, ruta: string, cuerpo?: unknown): unknown {
  const url = new URL(ruta, 'http://foto.local');
  const p = url.pathname;
  const q = (n: string) => url.searchParams.get(n);
  if (metodo === 'GET') {
    switch (p) {
      case '/api/v1/sesion': return sesionFoto();
      case '/api/v1/version': return { backend: '', published: '' };
      case '/api/v1/planificacion': return copia(foto.vista);
      case '/api/v1/catalogos': return copia(foto.catalogos);
      case '/api/v1/usuarios': return [];
      case '/api/v1/recursos': return { resources: [], blocks: [] };
      case '/api/v1/aprobaciones': return [];
      case '/api/v1/indicadores/bloques':
        return bloquesFoto(foto, q('lunes') || foto.vista.hoy, q('org') || 'ALL', url.searchParams.has('responsable') ? q('responsable') ?? '' : null);
      case '/api/v1/indicadores/tabla-ocupacion': return tablaFoto(foto, q('desde') ?? '', q('hasta') ?? '', q('org') || 'ALL');
      case '/api/v1/indicadores/tendencia':
        return tendenciaFoto(foto, Number(q('cwDesde')) || 0, Number(q('cwHasta')) || 0, q('org') || 'ALL');
      case '/api/v1/indicadores/historico': return copia(historicoFoto(foto, q('org') || 'ALL'));
      case '/api/v1/indicadores/estado-fotos': return copia(foto.indicadores.estadoFotos?.[filtroFoto(q('org') || 'ALL')] ?? []);
      case '/api/v1/indicadores/estadisticas':
        return copia(estadisticasFoto(foto, q('desde'), q('hasta'), q('org') || 'ALL', q('responsable')));
      default: throw new ErrorFoto(404, 'foto', NO_INCLUIDO);
    }
  }
  if (metodo === 'POST' && p === '/api/v1/presencia') return { me: '', online: [] };
  if (metodo === 'POST' && p === '/api/v1/aprobaciones/estados') {
    const ids = (cuerpo as { ids?: string[] } | undefined)?.ids ?? [];
    return Object.fromEntries(ids.filter((id) => foto.estadosAprobacion[id]).map((id) => [id, foto.estadosAprobacion[id]]));
  }
  throw new ErrorFoto(403, 'solo-lectura', SOLO_LECTURA);
}

/* ===== Al exportar: recortar los datos a Thermal Benches ================= */

/** Vista con solo las categorías y los bancos de Thermal Benches. */
export function vistaSoloTermicos(vista: Vista): Vista {
  const grupos = vista.grupos.filter((g) => normalizarArea(g.area, vista) === AREA_TERMICOS);
  const nombres = new Set(grupos.flatMap((g) => g.bancos.map((b) => b.nombre)));
  return {
    ...vista,
    areas: (vista.areas ?? []).filter((a) => a.clave === AREA_TERMICOS),
    grupos,
    bancos: Object.fromEntries(Object.entries(vista.bancos).filter(([b]) => nombres.has(b))),
  };
}
