/*
 * Utilidades de exportación (bloque F): descargar un fichero y construir la
 * matriz del Gantt que se envía al backend para el Excel (buildGanttMatrix de
 * la 4.9.4). Todo lo calculado (inicio y fin de cada ensayo) viene del backend.
 */
import { api, peticion } from '../../api/cliente';
import { esFoto, vistaSoloTermicos, type FotoSoloLectura, type IndicadoresFoto } from '../../api/foto';
import type { Bloqueo, FilaEnsayo, Recurso, Vista } from '../../api/tipos';
import { dia, diasEntre, esFinDeSemana, iso, lunesDe, semanaIso, sumarDias } from '../../utilidades/fechas';
import { cumpleFiltro, nombresArea, normalizarArea } from '../../utilidades/areas';

/** Descarga un Blob con un nombre (como el enlace temporal de la 4.9.4). */
export function descargar(contenido: Blob, nombre: string) {
  const a = document.createElement('a');
  a.href = URL.createObjectURL(contenido);
  a.download = nombre;
  document.body.appendChild(a);
  a.click();
  setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 1500);
}

/** Un color por proyecto, siempre el mismo para el mismo nombre (projColor). */
const PALETA = ['#4C8DFF', '#3DD68C', '#E0A458', '#E86A92', '#B08BEA', '#F2A64B', '#6BB5FF', '#9AD14B', '#F5A623', '#23BCA3', '#FF8FA3', '#7BD389',
  '#C792EA', '#E5C07B', '#56B6C2', '#D19A66'];
export function colorProyecto(nombre: string): string {
  const s = (nombre || '').trim();
  if (!s) return '#8CA0AD';
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0;
  return PALETA[h % PALETA.length];
}

// dim: sin foco, la 4.9.4 envía «foc && …» = '' (cadena vacía); se envía igual.
interface Celda { c0: number; c1: number; text: string; color: string; dim: boolean | ''; proj: string }
interface FilaMatriz { label: string; cells: Celda[] }
export interface MatrizGantt {
  title: string;
  mode: 'daily';
  days: { day: number; month: string; cw: number; dow: string; we: boolean; hol: boolean; today: boolean }[];
  rows: FilaMatriz[];
  legend: { name: string; color: string }[];
  focus: string;
}

export type VistaGantt =
  | { tipo: 'banco'; banco: string; filas: FilaEnsayo[] }
  | { tipo: 'general'; responsable: boolean; organizacion: string }
  | { tipo: 'recursos'; recursos: Recurso[]; bloqueos: Bloqueo[] };

/** La matriz diaria del Gantt que se ve, con el foco de proyecto aplicado. */
export function matrizGantt(vista: Vista, que: VistaGantt, foco: string): MatrizGantt | null {
  const foc = (foco || '').trim();
  const usados = new Set<string>();
  const hoy = dia(vista.hoy)!;
  const anio = vista.calendario.anio;
  const festivos = new Set(vista.calendario.festivos.map((f) => f.fecha));
  let min: Date, max: Date, titulo: string;
  const filas: FilaMatriz[] = [];
  const celdaDe = (f: FilaEnsayo, texto: string): Celda => {
    const atenuada = !!foc && f.datos.project !== foc;
    const pj = String(f.datos.project || '').trim();
    if (pj) usados.add(pj);
    return { c0: diasEntre(min, dia(f.calculo.inicio)!), c1: diasEntre(min, dia(f.calculo.fin)!), text: texto,
      color: atenuada ? '#8CA0AD' : colorProyecto(String(f.datos.project || '')), dim: foc ? atenuada : '', proj: pj };
  };
  const conFechas = (f: FilaEnsayo) => !f.cerrado && !!f.calculo.inicio && !!f.calculo.fin;

  if (que.tipo === 'banco') {
    const activas = que.filas.filter(conFechas);
    if (!activas.length) return null;
    min = activas.map((f) => dia(f.calculo.inicio)!).reduce((a, b) => (b < a ? b : a));
    max = activas.map((f) => dia(f.calculo.fin)!).reduce((a, b) => (b > a ? b : a));
    min = lunesDe(min);
    max = sumarDias(max, 1);
    titulo = `Test bench: ${que.banco}`;
    activas.forEach((f, i) => filas.push({
      label: `P${i + 1} \u00b7 ${f.datos.project || f.datos.type || ''}`,
      cells: [celdaDe(f, [f.datos.project, f.datos.type, f.datos.comments].filter(Boolean).join(' \u00b7 '))],
    }));
  } else if (que.tipo === 'recursos') {
    min = lunesDe(new Date(anio, 0, 4));
    max = sumarDias(min, 53 * 7 - 1);
    titulo = 'Additional Equipment';
    // Equipos agrupados por tipo en el orden en que aparece cada tipo (resByType).
    const orden: string[] = [];
    const porTipo = new Map<string, Recurso[]>();
    que.recursos.forEach((r) => {
      const k = r.groupKey || 'otros';
      if (!porTipo.has(k)) { porTipo.set(k, []); orden.push(k); }
      porTipo.get(k)!.push(r);
    });
    orden.forEach((k) => porTipo.get(k)!.forEach((eq) => {
      const celdas: Celda[] = [];
      vista.grupos.forEach((g) => g.bancos.forEach((b) => (vista.bancos[b.nombre] ?? []).forEach((f) => {
        if (!conFechas(f) || !Array.isArray(f.datos.resources) || !f.datos.resources.includes(eq.name)) return;
        celdas.push(celdaDe(f, `${b.nombre} \u00b7 ${f.datos.project || f.datos.type || ''}`));
      })));
      que.bloqueos.forEach((bl) => {
        if (bl.resource !== eq.name || !bl.start || !bl.end) return;
        celdas.push({ c0: diasEntre(min, dia(bl.start)!), c1: diasEntre(min, dia(bl.end)!), text: `\u{1F512} ${bl.reason || 'Bloqueo'}`, color: '#c79a3a', dim: false, proj: '' });
      });
      filas.push({ label: eq.name + (eq.code ? ` [${eq.code}]` : ''), cells: celdas });
    }));
  } else {
    min = lunesDe(new Date(anio, 0, 4));
    max = sumarDias(min, 53 * 7 - 1);
    titulo = `${que.responsable ? 'Team plan' : 'Annual plan'} ${anio}`;
    // Como en la 4.9.4: todos los ensayos activos de los bancos de la organización elegida.
    // Con bancos de más de un área, una fila de título por área (vacía) antes de sus bancos.
    const nombres = nombresArea(vista);
    const visibles = vista.grupos.flatMap((g) => g.bancos.map((b) => ({ ...b, area: normalizarArea(g.area, vista) })))
      .filter((b) => cumpleFiltro(b, que.organizacion));
    const variasAreas = new Set(visibles.map((b) => b.area)).size > 1;
    let areaAnterior = '';
    visibles.forEach((b) => {
      if (variasAreas && b.area !== areaAnterior) {
        filas.push({ label: `\u25B8 ${nombres[b.area] ?? b.area}`, cells: [] });
        areaAnterior = b.area;
      }
      const celdas = (vista.bancos[b.nombre] ?? []).filter(conFechas).map((f) => celdaDe(f, String(f.datos.project || f.datos.type || '')));
      filas.push({ label: b.nombre, cells: celdas });
    });
  }

  const n = diasEntre(min, max) + 1;
  const days = Array.from({ length: n }, (_, i) => {
    const d = sumarDias(min, i);
    return { day: d.getDate(), month: d.toLocaleDateString('en-GB', { month: 'short', year: '2-digit' }), cw: semanaIso(d),
      dow: 'MTWTFSS'[(d.getDay() + 6) % 7], we: esFinDeSemana(d), hol: festivos.has(iso(d)), today: d.getTime() === hoy.getTime() };
  });
  filas.forEach((f) => f.cells.forEach((c) => {
    c.c0 = Math.max(0, Math.min(n - 1, c.c0));
    c.c1 = Math.max(c.c0, Math.min(n - 1, c.c1));
  }));
  const legend = [...usados].sort().map((p) => ({ name: p, color: colorProyecto(p) }));
  return { title: titulo + (foc ? `  \u00b7  Focus: ${foc}` : ''), mode: 'daily', days, rows: filas, legend, focus: foc };
}

/** 📊 Excel (exportGanttExcel): pide el .xlsx al backend y lo descarga. */
export async function exportarGanttExcel(m: MatrizGantt | null) {
  if (esFoto()) { window.alert('Excel export needs the TestBenchPlanner server: it is not available in this read-only snapshot.'); return; }
  if (!m || !m.rows.length) { window.alert('No hay datos que exportar en esta vista.'); return; }
  try {
    const r = await api.exportarGantt(m);
    if (!r.ok || !r.b64) { window.alert(`No se pudo generar el Excel: ${r.error || 'backend no disponible'}`); return; }
    const bytes = Uint8Array.from(atob(r.b64), (c) => c.charCodeAt(0));
    descargar(new Blob([bytes], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }), r.filename || 'gantt.xlsx');
  } catch (e) {
    window.alert(`Export Excel requiere el backend en marcha. ${e instanceof Error ? e.message : ''}`);
  }
}

/** ⬇️ Export (JSON backup): el estado completo con el formato de la 4.9.4. */
export async function exportarCopiaJson() {
  const estado = await api.copiaSeguridad();
  descargar(new Blob([JSON.stringify(estado, null, 2)], { type: 'application/json' }), `BW_TestBench_${new Date().toISOString().slice(0, 10)}.json`);
}

/**
 * 📤 Export read-only (exportReadOnly de la 4.9.4, con la interfaz nueva): un
 * único .html que se abre sin backend, en solo lectura, para enviarlo al
 * proveedor. Lleva esta misma aplicación (su JavaScript, sus estilos y sus
 * imágenes, todo dentro del fichero) y los datos de Thermal Benches del momento
 * de exportar: la planificación, los catálogos que se ven, el estado de los
 * Approval ID y los indicadores precalculados semana a semana (ver api/foto.ts).
 * El resto de áreas, la configuración, la facturación y Timing R&T no van.
 */
export async function exportarSoloLectura(version: string) {
  const texto = async (ruta: string) => {
    const r = await fetch(ruta, { cache: 'no-store' });
    if (!r.ok) throw new Error(`No se pudo leer ${ruta} (${r.status})`);
    return r.text();
  };
  const comoDatos = async (ruta: string) => {
    const r = await fetch(ruta);
    if (!r.ok) return ruta;
    const blob = await r.blob();
    return new Promise<string>((resolver) => {
      const lector = new FileReader();
      lector.onload = () => resolver(String(lector.result));
      lector.onerror = () => resolver(ruta);
      lector.readAsDataURL(blob);
    });
  };

  // La página compilada (dist/index.html): un módulo JS y una hoja de estilos.
  const pagina = await texto('/index.html');
  const rutaJs = /<script[^>]*type="module"[^>]*src="([^"]+)"/.exec(pagina)?.[1];
  const rutasCss = [...pagina.matchAll(/<link[^>]*rel="stylesheet"[^>]*href="([^"]+)"/g)].map((m) => m[1]);
  if (!rutaJs) throw new Error('No se encuentra la aplicación compilada (index.html sin módulo)');
  const [js, css, logo, ciudad, vistaCompleta, catalogos, indicadores] = await Promise.all([
    texto(rutaJs),
    Promise.all(rutasCss.map(texto)).then((l) => l.join('\n')),
    comoDatos('/logo.png'),
    comoDatos('/ciudad.jpg'),
    api.planificacion(),
    api.catalogos(),
    peticion<IndicadoresFoto>('GET', '/api/v1/indicadores/foto-solo-lectura?area=THERMAL'),
  ]);

  const vista = vistaSoloTermicos(vistaCompleta);
  const filas = Object.values(vista.bancos).flat();
  const usados = (campo: string) => new Set(filas.map((f) => String(f.datos[campo] ?? '').trim()).filter(Boolean));
  const proyectos = usados('project');
  const responsables = usados('resp');
  const ids = [...usados('approvalId')];
  const estadosAprobacion = ids.length ? await api.estadosAprobacion(ids).catch(() => ({})) : {};
  const ahora = new Date();
  const generada = `${iso(ahora)}T${String(ahora.getHours()).padStart(2, '0')}:${String(ahora.getMinutes()).padStart(2, '0')}`;
  const foto: FotoSoloLectura = {
    formato: 1,
    generada,
    version,
    vista,
    // Solo lo que aparece en Thermal Benches (sin usuarios, correos ni el resto de proyectos).
    catalogos: {
      projects: (catalogos.projects ?? []).filter((p) => proyectos.has(p)),
      responsibles: (catalogos.responsibles ?? []).filter((r) => responsables.has(r)),
    },
    estadosAprobacion,
    indicadores: { ...indicadores, estadisticas: indicadores.estadisticas.filter((f) => vista.bancos[f.banco]) },
  };

  // Dentro de <script>, «</script» cerraría la etiqueta: se escapa la barra (en JS «<\/» es lo mismo).
  const enScript = (codigo: string) => codigo.replace(/<\/script/gi, '<\\/script');
  const datos = JSON.stringify(foto).replace(/</g, '\\u003c').replace(/\u2028/g, '\\u2028').replace(/\u2029/g, '\\u2029');
  const codigo = enScript(js.split('"/logo.png"').join(JSON.stringify(logo)).split('"/ciudad.jpg"').join(JSON.stringify(ciudad)));
  const d = dia(vista.hoy)!;
  const cw = `CW${String(semanaIso(d)).padStart(2, '0')}`;
  const html = `<!DOCTYPE html>
<html lang="es">
<head>
<meta charset="UTF-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0" />
<title>${cw} BW Test Bench Planning \u00b7 Read-only</title>
<style>${css.replace(/<\/style/gi, '<\\/style')}</style>
<script>window.__TBP_FOTO__=${datos};</script>
<script type="module">${codigo}</script>
</head>
<body>
<div id="raiz"></div>
</body>
</html>
`;
  descargar(new Blob([html], { type: 'text/html' }), `${cw}_BW_TestBench_ReadOnly_${iso(d)}.html`);
}
